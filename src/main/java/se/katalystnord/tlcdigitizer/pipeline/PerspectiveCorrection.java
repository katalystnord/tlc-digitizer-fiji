package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2: Perspective correction.
 *
 * Auto-detection pipeline (matches TLCyzer):
 *   1. Downscale to ≤ 256px on the shorter side (powers of 2, per TLCyzer)
 *   2. Adaptive threshold (local threshold with window = min_dim / 3)
 *   3. Detect boundary pixels (Sobel gradient magnitude threshold)
 *   4. Hough transform for lines (r, theta accumulator)
 *   5. Separate horizontal and vertical line candidates
 *   6. Find all H×V line intersections
 *   7. Assign each intersection to the nearest image corner
 *   8. Upscale corner coordinates back to full resolution
 *
 * If fewer than 4 distinct corners are found, falls back to a default quad
 * at 10% inset from each edge (same fallback as TLCyzer).
 *
 * The user can manually override any corner by dragging handles in the UI.
 *
 * Perspective warp uses mpicbg HomographyModel2D (8-DOF homography).
 * Output dimensions are max(topEdge, bottomEdge) × max(leftEdge, rightEdge).
 *
 * Source: Hauk et al. Scientific Reports 12, 13433 (2022).
 */
public final class PerspectiveCorrection {

    /** Vote threshold for Hough line detection (analogous to TLCyzer's 40). */
    private static final int HOUGH_VOTE_THRESHOLD = 40;

    /** Non-maximum suppression radius in Hough space (angle bins). */
    private static final int HOUGH_SUPPRESSION_RADIUS = 8;

    /** Angle tolerance for classifying lines as horizontal or vertical (degrees). */
    private static final int ANGLE_TOLERANCE = 2;

    private PerspectiveCorrection() {}

    /**
     * Attempts automatic corner detection on the grayscale image.
     *
     * @param fp full-resolution grayscale image
     * @return [tlX, tlY, trX, trY, brX, brY, blX, blY] in full-resolution pixels,
     *         or a default 10%-inset quad if detection fails
     */
    public static float[] detectCorners(FloatProcessor fp) {
        int width = fp.getWidth();
        int height = fp.getHeight();

        // 1. Compute downscale factor to bring shorter side to ≤ 256px
        int minDim = Math.min(width, height);
        int factor = 1;
        while (minDim / (factor * 2) >= 128) factor *= 2;

        int sw = width / factor;
        int sh = height / factor;
        float[] small = downsample(fp, sw, sh, factor);

        // 2. Adaptive threshold on the small image
        boolean[] binary = adaptiveThreshold(small, sw, sh, Math.max(3, sw / 3));

        // 3. Boundary / edge pixels
        boolean[] edges = extractBoundaryPixels(binary, sw, sh);

        // 4. Hough transform
        float[][] accumulator = houghTransform(edges, sw, sh);
        List<float[]> lines = extractLines(accumulator, sw, sh); // each: [r, thetaDeg]

        // 5. Split into horizontal and vertical
        List<float[]> horizontal = new ArrayList<>();
        List<float[]> vertical = new ArrayList<>();
        for (float[] line : lines) {
            float angle = line[1];
            if (Math.abs(angle - 90) <= ANGLE_TOLERANCE) horizontal.add(line);
            if (angle <= ANGLE_TOLERANCE || angle >= 180 - ANGLE_TOLERANCE) vertical.add(line);
        }

        // 6. Find intersections and assign to corners
        float[] corners = findCorners(horizontal, vertical, sw, sh);

        if (corners != null) {
            // Upscale back to full resolution
            for (int i = 0; i < 8; i++) {
                corners[i] *= factor;
            }
            return corners;
        }

        // Fallback: 10% inset
        return defaultCorners(width, height);
    }

    /**
     * Warps {@code fp} to a rectangle using a perspective homography defined by
     * the four corners [tlX, tlY, trX, trY, brX, brY, blX, blY].
     *
     * Output dimensions are computed as the max of the two opposing edge lengths,
     * matching TLCyzer's propose_destination logic.
     *
     * @throws IllegalStateException if the homography cannot be computed
     */
    public static FloatProcessor warpImage(FloatProcessor fp, float[] corners) {
        float tlX = corners[0], tlY = corners[1];
        float trX = corners[2], trY = corners[3];
        float brX = corners[4], brY = corners[5];
        float blX = corners[6], blY = corners[7];

        // Output size = max of opposing edges
        int outW = (int) Math.max(
                edgeLength(tlX, tlY, trX, trY),
                edgeLength(blX, blY, brX, brY));
        int outH = (int) Math.max(
                edgeLength(tlX, tlY, blX, blY),
                edgeLength(trX, trY, brX, brY));

        // Destination corners (unit rectangle scaled to outW × outH)
        float[] src = {tlX, tlY, trX, trY, brX, brY, blX, blY};
        float[] dst = {0, 0, outW - 1, 0, outW - 1, outH - 1, 0, outH - 1};

        HomographyModel2D model = buildHomography(src, dst);
        return inverseWarp(fp, model, outW, outH);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static float[] downsample(FloatProcessor fp, int sw, int sh, int factor) {
        float[] pixels = (float[]) fp.getPixels();
        int origWidth = fp.getWidth();
        float[] small = new float[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                small[y * sw + x] = pixels[(y * factor) * origWidth + (x * factor)];
            }
        }
        return small;
    }

    /** Local mean threshold: pixel >= local mean in window → foreground. */
    static boolean[] adaptiveThreshold(float[] img, int width, int height, int windowRadius) {
        boolean[] result = new boolean[img.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - windowRadius);
                int x1 = Math.min(width - 1, x + windowRadius);
                int y0 = Math.max(0, y - windowRadius);
                int y1 = Math.min(height - 1, y + windowRadius);
                double sum = 0;
                int cnt = 0;
                for (int yy = y0; yy <= y1; yy++) {
                    for (int xx = x0; xx <= x1; xx++) {
                        sum += img[yy * width + xx];
                        cnt++;
                    }
                }
                result[y * width + x] = img[y * width + x] >= (float) (sum / cnt);
            }
        }
        return result;
    }

    /** Extract boundary pixels: foreground pixels adjacent to background (4-connectivity). */
    static boolean[] extractBoundaryPixels(boolean[] binary, int width, int height) {
        boolean[] edges = new boolean[binary.length];
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!binary[y * width + x]) continue;
                for (int d = 0; d < 4; d++) {
                    int nx = x + dx[d];
                    int ny = y + dy[d];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height || !binary[ny * width + nx]) {
                        edges[y * width + x] = true;
                        break;
                    }
                }
            }
        }
        return edges;
    }

    /**
     * Standard sinusoidal Hough transform.
     *
     * @return accumulator[thetaIdx][rIdx], theta in [0, 180), r in [-diag, +diag]
     */
    static float[][] houghTransform(boolean[] edges, int width, int height) {
        int diag = (int) Math.ceil(Math.sqrt(width * width + height * height));
        int nTheta = 180;
        int nR = 2 * diag + 1;
        float[][] acc = new float[nTheta][nR];

        double[] cosTable = new double[nTheta];
        double[] sinTable = new double[nTheta];
        for (int t = 0; t < nTheta; t++) {
            double rad = t * Math.PI / nTheta;
            cosTable[t] = Math.cos(rad);
            sinTable[t] = Math.sin(rad);
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!edges[y * width + x]) continue;
                for (int t = 0; t < nTheta; t++) {
                    int r = (int) Math.round(x * cosTable[t] + y * sinTable[t]) + diag;
                    if (r >= 0 && r < nR) acc[t][r]++;
                }
            }
        }
        return acc;
    }

    /** Extract lines from Hough accumulator above threshold with NMS in theta dimension. */
    private static List<float[]> extractLines(float[][] acc, int width, int height) {
        int diag = (int) Math.ceil(Math.sqrt(width * width + height * height));
        int nTheta = acc.length;
        int nR = acc[0].length;
        List<float[]> lines = new ArrayList<>();

        for (int t = 0; t < nTheta; t++) {
            for (int r = 0; r < nR; r++) {
                if (acc[t][r] < HOUGH_VOTE_THRESHOLD) continue;
                // Check local maximum in theta dimension within suppression radius
                boolean isMax = true;
                for (int dt = -HOUGH_SUPPRESSION_RADIUS; dt <= HOUGH_SUPPRESSION_RADIUS && isMax; dt++) {
                    if (dt == 0) continue;
                    int nt = (t + dt + nTheta) % nTheta;
                    if (acc[nt][r] > acc[t][r]) isMax = false;
                }
                if (isMax) {
                    lines.add(new float[]{r - diag, t}); // [r_actual, thetaDeg]
                }
            }
        }
        return lines;
    }

    /**
     * Finds the 4 plate corners from sets of horizontal and vertical lines.
     * Returns [tlX, tlY, trX, trY, brX, brY, blX, blY] or null if not found.
     */
    private static float[] findCorners(List<float[]> horizontal, List<float[]> vertical, int width, int height) {
        if (horizontal.isEmpty() || vertical.isEmpty()) return null;

        // Compute all H×V intersections
        List<float[]> intersections = new ArrayList<>();
        for (float[] h : horizontal) {
            for (float[] v : vertical) {
                float[] pt = intersect(h, v, width, height);
                if (pt != null) intersections.add(pt);
            }
        }
        if (intersections.isEmpty()) return null;

        // Assign each intersection to the nearest image corner
        float[][] imageCorners = {{0, 0}, {width, 0}, {width, height}, {0, height}}; // TL,TR,BR,BL
        float[] bestX = new float[4];
        float[] bestY = new float[4];
        float[] bestDist = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        boolean[] found = new boolean[4];

        for (float[] pt : intersections) {
            for (int c = 0; c < 4; c++) {
                float dx = pt[0] - imageCorners[c][0];
                float dy = pt[1] - imageCorners[c][1];
                float dist = dx * dx + dy * dy;
                if (dist < bestDist[c]) {
                    bestDist[c] = dist;
                    bestX[c] = pt[0];
                    bestY[c] = pt[1];
                    found[c] = true;
                }
            }
        }

        for (boolean f : found) if (!f) return null;

        return new float[]{
            bestX[0], bestY[0], // TL
            bestX[1], bestY[1], // TR
            bestX[2], bestY[2], // BR
            bestX[3], bestY[3]  // BL
        };
    }

    /**
     * Computes the intersection point of two Hough lines in image space.
     * Returns null if lines are parallel or intersection is outside the image.
     */
    private static float[] intersect(float[] h, float[] v, int width, int height) {
        // h = [r, thetaDeg], line equation: x*cos(theta) + y*sin(theta) = r
        double thetaH = h[1] * Math.PI / 180.0;
        double thetaV = v[1] * Math.PI / 180.0;
        double rH = h[0];
        double rV = v[0];

        double cosH = Math.cos(thetaH), sinH = Math.sin(thetaH);
        double cosV = Math.cos(thetaV), sinV = Math.sin(thetaV);

        double det = cosH * sinV - sinH * cosV;
        if (Math.abs(det) < 1e-8) return null;

        float x = (float) ((rH * sinV - rV * sinH) / det);
        float y = (float) ((cosH * rV - cosV * rH) / det);

        if (x < 0 || x >= width || y < 0 || y >= height) return null;
        return new float[]{x, y};
    }

    private static float[] defaultCorners(int width, int height) {
        float l = width / 10f;
        float r = width - l;
        float t = height / 10f;
        float b = height - t;
        return new float[]{l, t, r, t, r, b, l, b};
    }

    private static float edgeLength(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static HomographyModel2D buildHomography(float[] src, float[] dst) {
        List<PointMatch> matches = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Point p = new Point(new double[]{src[i * 2], src[i * 2 + 1]});
            Point q = new Point(new double[]{dst[i * 2], dst[i * 2 + 1]});
            matches.add(new PointMatch(p, q));
        }
        HomographyModel2D model = new HomographyModel2D();
        try {
            model.fit(matches);
        } catch (NotEnoughDataPointsException | mpicbg.models.IllDefinedDataPointsException e) {
            throw new IllegalStateException("Cannot compute perspective homography: " + e.getMessage(), e);
        }
        return model;
    }

    /**
     * Inverse-maps the output image through the homography to sample the source image.
     * For each destination pixel (px, py), computes the source (sx, sy) and
     * bilinearly interpolates the source FloatProcessor.
     */
    private static FloatProcessor inverseWarp(FloatProcessor src, HomographyModel2D model, int outW, int outH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        float[] srcPx = (float[]) src.getPixels();
        float[] out = new float[outW * outH];

        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                double[] pt = {x, y};
                try {
                    model.applyInverseInPlace(pt);
                } catch (Exception e) {
                    out[y * outW + x] = 0;
                    continue;
                }
                out[y * outW + x] = bilinearSample(srcPx, srcW, srcH, (float) pt[0], (float) pt[1]);
            }
        }
        return new FloatProcessor(outW, outH, out, null);
    }

    private static float bilinearSample(float[] pixels, int width, int height, float x, float y) {
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = x0 + 1;
        int y1 = y0 + 1;

        if (x0 < 0 || x1 >= width || y0 < 0 || y1 >= height) return 0;

        float fx = x - x0;
        float fy = y - y0;

        float v00 = pixels[y0 * width + x0];
        float v10 = pixels[y0 * width + x1];
        float v01 = pixels[y1 * width + x0];
        float v11 = pixels[y1 * width + x1];

        return (1 - fx) * (1 - fy) * v00
             + fx       * (1 - fy) * v10
             + (1 - fx) * fy       * v01
             + fx       * fy       * v11;
    }
}
