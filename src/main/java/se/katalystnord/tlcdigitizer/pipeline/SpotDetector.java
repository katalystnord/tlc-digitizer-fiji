package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.*;

/**
 * Stage 4: Spot detection.
 *
 * Pipeline (matches TLCyzer algorithm exactly):
 *   1. Threshold at image mean
 *   2. Morphological opening with a square SE of radius ⌊max_dim × 0.0075⌋
 *   3. Connected component labelling (4-connectivity, BFS)
 *   4. Filter components by aspect ratio [0.25, 1.75] and size [2%, 25%] of max dimension
 *   5. Compute intensity-weighted centroid and radius for each accepted component
 *
 * Allows manual post-correction: add spot (click), remove spot (click),
 * adjust radius (drag) — handled separately in the UI layer.
 *
 * Source: Hauk et al. Scientific Reports 12, 13433 (2022).
 */
public final class SpotDetector {

    /** Aspect ratio must be in [1 - tolerance, 1 + tolerance]. Matches TLCyzer. */
    static final float ASPECT_RATIO_TOLERANCE = 0.75f;

    /** Minimum spot dimension as fraction of max(width, height). Matches TLCyzer. */
    static final float SIZE_MIN_FRACTION = 0.02f;

    /** Maximum spot dimension as fraction of max(width, height). Matches TLCyzer. */
    static final float SIZE_MAX_FRACTION = 0.25f;

    private SpotDetector() {}

    /**
     * Runs the full detection pipeline on the corrected image using mean threshold.
     *
     * @param fp background-corrected FloatProcessor
     * @return list of detected spots, sorted by Y centroid (top to bottom)
     */
    public static List<Spot> detect(FloatProcessor fp) {
        return detect(fp, 1.0f);
    }

    /**
     * Runs the full detection pipeline using {@code mean × thresholdMultiplier} as the threshold.
     * Values > 1 make detection stricter (only brighter regions); < 1 include dimmer spots.
     *
     * @param fp                  background-corrected FloatProcessor
     * @param thresholdMultiplier multiplier applied to the image mean (must be > 0)
     * @return list of detected spots, sorted by Y centroid (top to bottom)
     */
    public static List<Spot> detect(FloatProcessor fp, float thresholdMultiplier) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        // 1. Threshold at mean × multiplier
        float mean = computeMean(pixels);
        boolean[] binary = threshold(pixels, mean * Math.max(0.01f, thresholdMultiplier));

        // 2. Morphological opening (erosion then dilation with square SE)
        int maxDim = Math.max(width, height);
        int seRadius = Math.max(1, (int) (maxDim * 0.0075f));
        boolean[] opened = morphologicalOpen(binary, width, height, seRadius);

        // 3. Connected component labelling (4-connectivity BFS)
        int[] labels = labelComponents(opened, width, height);

        // 4. Extract component metadata and filter
        Map<Integer, int[]> boundingBoxes = computeBoundingBoxes(labels, width, height);
        List<Spot> spots = new ArrayList<>();
        int spotId = 0;

        float sizeMin = maxDim * SIZE_MIN_FRACTION;
        float sizeMax = maxDim * SIZE_MAX_FRACTION;

        for (Map.Entry<Integer, int[]> entry : boundingBoxes.entrySet()) {
            int label = entry.getKey();
            int[] bbox = entry.getValue(); // [minX, minY, maxX, maxY]

            float bboxW = bbox[2] - bbox[0];
            float bboxH = bbox[3] - bbox[1];

            // Aspect ratio filter
            if (bboxW < 1 || bboxH < 1) continue;
            float aspectRatio = bboxW / bboxH;
            if (aspectRatio < (1f - ASPECT_RATIO_TOLERANCE) || aspectRatio > (1f + ASPECT_RATIO_TOLERANCE)) {
                continue;
            }

            // Size filter
            if (bboxW < sizeMin || bboxW > sizeMax || bboxH < sizeMin || bboxH > sizeMax) {
                continue;
            }

            // 5. Intensity-weighted centroid
            double sumX = 0, sumY = 0, sumV = 0;
            for (int y2 = bbox[1]; y2 <= bbox[3]; y2++) {
                for (int x2 = bbox[0]; x2 <= bbox[2]; x2++) {
                    if (labels[y2 * width + x2] == label) {
                        float v = pixels[y2 * width + x2];
                        sumX += x2 * v;
                        sumY += y2 * v;
                        sumV += v;
                    }
                }
            }

            if (sumV == 0) continue;
            float cx = (float) (sumX / sumV);
            float cy = (float) (sumY / sumV);

            // Radius = average half-dimension (TLCyzer formula: (W + H) / 4).
            // This matches the physical spot for circular spots and averages the
            // two axes for ovals — unlike the half-diagonal formula previously
            // used, which overestimated by ~41% for round spots.
            float r = (bboxW + bboxH) / 4f;

            spots.add(new Spot(spotId++, cx, cy, r, height));
        }

        // Sort top-to-bottom then left-to-right
        spots.sort(Comparator.comparingDouble((Spot s) -> s.centroidY)
                              .thenComparingDouble(s -> s.centroidX));

        return spots;
    }

    /**
     * Detects a single spot at a specific image coordinate using local thresholding.
     * Used when the user manually adds a spot by clicking on the image canvas.
     * Thresholds a local patch centred on the click so that even a dim spot
     * against a bright background will be found correctly.
     *
     * @param fp          background-corrected image (same as passed to {@link #detect})
     * @param clickX      x coordinate of the click in image pixels
     * @param clickY      y coordinate of the click in image pixels
     * @param imageHeight full image height (used for {@link Spot} Rf normalisation)
     * @return a {@link Spot} with {@code id = -1}; caller must assign a real id; never null
     */
    public static Spot detectAtPoint(FloatProcessor fp, int clickX, int clickY, int imageHeight) {
        int w = fp.getWidth(), h = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();
        int maxDim = Math.max(w, h);

        // Clamp to image bounds
        clickX = Math.max(0, Math.min(w - 1, clickX));
        clickY = Math.max(0, Math.min(h - 1, clickY));

        // Local patch radius ≈ minimum spot size
        int searchR = Math.max(10, (int)(maxDim * SIZE_MIN_FRACTION));
        int x0 = Math.max(0, clickX - searchR), x1 = Math.min(w - 1, clickX + searchR);
        int y0 = Math.max(0, clickY - searchR), y1 = Math.min(h - 1, clickY + searchR);
        int lw = x1 - x0 + 1, lh = y1 - y0 + 1;

        // Extract patch pixels
        float[] local = new float[lw * lh];
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                local[(y - y0) * lw + (x - x0)] = pixels[y * w + x];

        // Threshold at local mean and label components within patch
        boolean[] binary = threshold(local, computeMean(local));
        int[] labels = labelComponents(binary, lw, lh);

        // Component containing the click
        int lx = Math.min(lw - 1, clickX - x0);
        int ly = Math.min(lh - 1, clickY - y0);
        int clickLabel = labels[ly * lw + lx];

        float defaultR = Math.max(5f, maxDim * SIZE_MIN_FRACTION * 0.5f);

        if (clickLabel == 0) {
            // Background click — return a minimum-size spot centred at the click
            return new Spot(-1, clickX, clickY, defaultR, imageHeight);
        }

        // Intensity-weighted centroid + bounding box in global image coordinates
        double sumX = 0, sumY = 0, sumV = 0;
        int minBX = w, minBY = h, maxBX = 0, maxBY = 0;
        for (int ly2 = 0; ly2 < lh; ly2++) {
            for (int lx2 = 0; lx2 < lw; lx2++) {
                if (labels[ly2 * lw + lx2] == clickLabel) {
                    int gx = x0 + lx2, gy = y0 + ly2;
                    float v = pixels[gy * w + gx];
                    sumX += gx * v;  sumY += gy * v;  sumV += v;
                    if (gx < minBX) minBX = gx;
                    if (gy < minBY) minBY = gy;
                    if (gx > maxBX) maxBX = gx;
                    if (gy > maxBY) maxBY = gy;
                }
            }
        }
        if (sumV == 0) return new Spot(-1, clickX, clickY, defaultR, imageHeight);

        float cx = (float)(sumX / sumV), cy = (float)(sumY / sumV);
        float r = Math.max(defaultR, (float)(maxBX - minBX + maxBY - minBY) / 4f);
        return new Spot(-1, cx, cy, r, imageHeight);
    }

    // -------------------------------------------------------------------------
    // Algorithm steps (package-private for unit testing)
    // -------------------------------------------------------------------------

    static float computeMean(float[] pixels) {
        double sum = 0;
        for (float v : pixels) sum += v;
        return (float) (sum / pixels.length);
    }

    static boolean[] threshold(float[] pixels, float threshold) {
        boolean[] result = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            result[i] = pixels[i] >= threshold;
        }
        return result;
    }

    /**
     * Morphological opening = erosion followed by dilation with a square SE of the given radius.
     * Uses separable min/max for efficiency: O(N * radius) rather than O(N * radius²).
     */
    static boolean[] morphologicalOpen(boolean[] binary, int width, int height, int radius) {
        boolean[] eroded = separableMinFilter(binary, width, height, radius);
        return separableMaxFilter(eroded, width, height, radius);
    }

    /** Separable box min-filter (erosion with square SE). */
    private static boolean[] separableMinFilter(boolean[] src, int width, int height, int radius) {
        boolean[] hPass = new boolean[src.length];
        // Horizontal pass
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean val = true;
                for (int dx = -radius; dx <= radius && val; dx++) {
                    int nx = x + dx;
                    if (nx < 0 || nx >= width) { val = false; }
                    else if (!src[y * width + nx]) { val = false; }
                }
                hPass[y * width + x] = val;
            }
        }
        // Vertical pass
        boolean[] result = new boolean[src.length];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                boolean val = true;
                for (int dy = -radius; dy <= radius && val; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) { val = false; }
                    else if (!hPass[ny * width + x]) { val = false; }
                }
                result[y * width + x] = val;
            }
        }
        return result;
    }

    /** Separable box max-filter (dilation with square SE). */
    private static boolean[] separableMaxFilter(boolean[] src, int width, int height, int radius) {
        boolean[] hPass = new boolean[src.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean val = false;
                for (int dx = -radius; dx <= radius && !val; dx++) {
                    int nx = x + dx;
                    if (nx >= 0 && nx < width && src[y * width + nx]) { val = true; }
                }
                hPass[y * width + x] = val;
            }
        }
        boolean[] result = new boolean[src.length];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                boolean val = false;
                for (int dy = -radius; dy <= radius && !val; dy++) {
                    int ny = y + dy;
                    if (ny >= 0 && ny < height && hPass[ny * width + x]) { val = true; }
                }
                result[y * width + x] = val;
            }
        }
        return result;
    }

    /**
     * BFS connected component labelling with 4-connectivity.
     *
     * @return int[] where 0 = background, positive integers = component labels
     */
    static int[] labelComponents(boolean[] binary, int width, int height) {
        int[] labels = new int[binary.length];
        int nextLabel = 1;
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        Queue<Integer> queue = new ArrayDeque<>();

        for (int i = 0; i < binary.length; i++) {
            if (binary[i] && labels[i] == 0) {
                int label = nextLabel++;
                labels[i] = label;
                queue.add(i);
                while (!queue.isEmpty()) {
                    int pos = queue.poll();
                    int px = pos % width;
                    int py = pos / width;
                    for (int d = 0; d < 4; d++) {
                        int nx = px + dx[d];
                        int ny = py + dy[d];
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            int npos = ny * width + nx;
                            if (binary[npos] && labels[npos] == 0) {
                                labels[npos] = label;
                                queue.add(npos);
                            }
                        }
                    }
                }
            }
        }
        return labels;
    }

    private static Map<Integer, int[]> computeBoundingBoxes(int[] labels, int width, int height) {
        Map<Integer, int[]> boxes = new HashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label = labels[y * width + x];
                if (label == 0) continue;
                int[] bbox = boxes.get(label);
                if (bbox == null) {
                    boxes.put(label, new int[]{x, y, x, y});
                } else {
                    if (x < bbox[0]) bbox[0] = x;
                    if (y < bbox[1]) bbox[1] = y;
                    if (x > bbox[2]) bbox[2] = x;
                    if (y > bbox[3]) bbox[3] = y;
                }
            }
        }
        return boxes;
    }

}
