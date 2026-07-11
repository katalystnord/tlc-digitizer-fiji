package se.katalystnord.tlcdigitizer.pipeline;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.Wand;
import ij.plugin.filter.MaximumFinder;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.awt.Rectangle;
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
 *
 * <h2>Shape-aware detection (beta, opt-in)</h2>
 * The pipeline above assumes compact, roughly-circular spots. Real plates with
 * overloaded/tailing lanes produce elongated streaks whose midsection commonly dims
 * below the mean threshold, splitting one physical spot into two disconnected
 * components (a false negative for the streak's true extent, and a false spot count).
 * {@link #detect(FloatProcessor, float, boolean)} with {@code shapeAware = true} fixes
 * this in two stages:
 * <ol>
 *   <li><b>Candidate region via hysteresis linking:</b> each legacy-valid seed component
 *   is grown via a second, lower threshold (the same idea as Canny edge-linking) using
 *   {@link Wand}, producing the seed's true connected pixel mask instead of a
 *   fixed-radius circle. As a safety net against growing into an unrelated neighbouring
 *   spot, growth is rejected (falling back to the legacy circular result for that seed)
 *   if it grows implausibly large or reaches the image edge — see
 *   {@link #MAX_GROWTH_AREA_MULTIPLE} and {@link #MAX_GROWTH_DIM_FRACTION}.</li>
 *   <li><b>Peak separation via watershed:</b> real TLC lanes very commonly contain
 *   multiple co-eluting compounds (e.g. a reaction being monitored over time), not one
 *   spot per lane — so a candidate region merged by hysteresis linking is not
 *   necessarily one compound. {@link MaximumFinder} (ImageJ's classical, non-ML local-maxima
 *   + watershed algorithm — the same "Find Maxima → Segmented Particles" used for
 *   splitting touching cells in microscopy) is run within the candidate region: a single
 *   dominant peak stays one spot (correct for a genuinely smeared/tailing single
 *   compound); multiple peaks are split at the watershed ridge line between them (correct
 *   for resolved-but-touching co-eluting compounds), each becoming its own spot with its
 *   own mask, centroid, and integration. {@link #NOISE_TOLERANCE_FRACTION} controls how
 *   prominent a bump must be to count as a separate peak.</li>
 * </ol>
 * This is off by default; see CLAUDE.md's "Real-plate exploratory test" section for the
 * real-plate case that motivated it, including the three-threshold experiment that showed
 * hysteresis linking alone (without peak separation) cannot simultaneously do the right
 * thing for a genuinely single tailing compound and a mixture of close-eluting compounds.
 * The remaining hard case — peaks so close there is no watershed-detectable valley between
 * them at all — is a genuine limitation of any purely geometric/intensity-based approach;
 * that's the case the v1.5 roadmap's Trainable Weka Segmentation item is for.
 */
public final class SpotDetector {

    /** Aspect ratio must be in [1 - tolerance, 1 + tolerance]. Matches TLCyzer. */
    static final float ASPECT_RATIO_TOLERANCE = 0.75f;

    /** Minimum spot dimension as fraction of max(width, height). */
    static final float SIZE_MIN_FRACTION = 0.025f;

    /** Maximum spot dimension as fraction of max(width, height). Matches TLCyzer. */
    static final float SIZE_MAX_FRACTION = 0.25f;

    /**
     * Components whose bounding box reaches within this fraction of any image edge are
     * rejected as border artefacts (vignette corners, plate-edge reflections, etc.).
     * 1 % of min(width, height) ≈ 15 px on a 1500 px image.
     */
    static final float EDGE_MARGIN_FRACTION = 0.01f;

    /**
     * Shape-aware mode only: the hysteresis link threshold sits this fraction of the
     * way from the image mean up to the primary threshold (mean × multiplier). Lower
     * = more permissive linking (more likely to bridge a dim streak, more likely to
     * over-merge neighbours); 0.4 is a conservative starting point, not yet validated
     * against a labelled dataset.
     */
    static final float LINK_FRACTION = 0.4f;

    /**
     * Shape-aware mode only: reject a grown region (fall back to the legacy circular
     * spot for that seed) if its bounding-box area exceeds the original seed's
     * bounding-box area by more than this multiple. Guards against hysteresis linking
     * bridging into an unrelated neighbouring spot.
     */
    static final float MAX_GROWTH_AREA_MULTIPLE = 6f;

    /**
     * Shape-aware mode only: reject a grown region if its bounding box width or height
     * exceeds this fraction of the corresponding image dimension.
     */
    static final float MAX_GROWTH_DIM_FRACTION = 0.35f;

    /**
     * Shape-aware mode only: {@link MaximumFinder} watershed peak-separation tolerance,
     * as a fraction of the candidate region's own dynamic range above the link threshold
     * ({@code (regionMax - linkThreshold) × NOISE_TOLERANCE_FRACTION}). Lower = more
     * willing to call a small bump a separate peak (more sensitive to close-eluting
     * compounds, more prone to splitting noise); 0.3 is a starting point, not yet
     * validated against a labelled dataset.
     *
     * <p>This relative fraction alone is not sufficient: a small, faint candidate region
     * has a small dynamic range by construction, so a fixed fraction of it can be smaller
     * than the image's own pixel noise — see {@link #MIN_TOLERANCE_STDDEV_MULTIPLE}, which
     * is combined with this one via {@code max()} to fix exactly that failure mode (found
     * interactively on img_00451: a small faint spot was spuriously split into two on
     * noise, at every threshold multiplier tried).
     *
     * <p>An earlier version of the floor was based on {@code primaryThreshold - mean}
     * (i.e. {@code mean × (multiplier - 1)}) instead of pixel standard deviation. That
     * formula is degenerate — it is exactly zero at {@code multiplier = 1.0}, the default,
     * and stays weak at most other multipliers too, because a background-corrected
     * image's {@code mean} is inherently small (background ≈ 0 after correction). Don't
     * reintroduce a floor that scales with {@code mean × multiplier}.
     */
    static final float NOISE_TOLERANCE_FRACTION = 0.3f;

    /**
     * Shape-aware mode only: absolute floor for the watershed tolerance, as a multiple of
     * the whole image's pixel standard deviation — independent of the threshold multiplier
     * (see {@link #NOISE_TOLERANCE_FRACTION} for why that independence matters). Keeps
     * small/faint candidate regions from being split on grain-level bumps that a relative
     * fraction of their own (small) dynamic range wouldn't catch. 2.0 (two standard
     * deviations) is a starting point, not yet validated against a labelled dataset.
     */
    static final float MIN_TOLERANCE_STDDEV_MULTIPLE = 2.0f;

    /** Sentinel value written outside the candidate mask before watershed peak-finding,
     * far enough below any real (background-corrected) pixel value that it's never a
     * local maximum and never bridges two real peaks across the mask boundary. */
    private static final float WATERSHED_SENTINEL = -1e6f;

    /**
     * Minimum pixel count for a watershed-split sub-region to be kept as its own spot.
     * Raised from an initial 4 after interactive testing showed single-digit-pixel
     * fragments slipping through as spurious spots even with a reasonable tolerance.
     */
    private static final int MIN_PEAK_PIXELS = 16;

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
        return detect(fp, thresholdMultiplier, false);
    }

    /**
     * Runs the full detection pipeline using {@code mean × thresholdMultiplier} as the threshold,
     * optionally with shape-aware hysteresis linking (see class javadoc).
     *
     * @param fp                  background-corrected FloatProcessor
     * @param thresholdMultiplier multiplier applied to the image mean (must be > 0)
     * @param shapeAware          if true, grow each seed component into its true connected
     *                            shape via hysteresis linking instead of reporting a fixed
     *                            circle; see class javadoc for the failure mode this fixes
     * @return list of detected spots, sorted by Y centroid (top to bottom)
     */
    public static List<Spot> detect(FloatProcessor fp, float thresholdMultiplier, boolean shapeAware) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        // 1. Threshold at mean × multiplier
        float mean = computeMean(pixels);
        float primaryThreshold = mean * Math.max(0.01f, thresholdMultiplier);
        boolean[] binary = threshold(pixels, primaryThreshold);

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
        int edgeMargin = Math.max(2, (int)(Math.min(width, height) * EDGE_MARGIN_FRACTION));

        // Shape-aware mode: tracks pixels already absorbed into a finalised grown spot,
        // so that a streak's origin-dot and cap seeds (both legacy-valid on their own)
        // collapse into one spot instead of being reported twice.
        boolean[] claimed = shapeAware ? new boolean[width * height] : null;
        float linkThreshold = mean + (primaryThreshold - mean) * LINK_FRACTION;
        // Global pixel standard deviation — floors the watershed tolerance (see
        // growAndSplitSeed) independently of the threshold multiplier. Only needed in
        // shape-aware mode; computing it is an extra O(N) pass so skip it otherwise.
        float noiseScale = shapeAware ? computeStdDev(pixels, mean) : 0f;

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

            // Edge artefact filter: reject components whose bounding box reaches the image border.
            // Corner vignettes, plate-edge reflections, and printing artefacts all touch the edge;
            // real TLC spots are always fully inside the plate area.
            if (bbox[0] <= edgeMargin || bbox[1] <= edgeMargin
                    || bbox[2] >= width  - edgeMargin
                    || bbox[3] >= height - edgeMargin) {
                continue;
            }

            // 5. Intensity-weighted centroid (of the legacy seed component)
            double sumX = 0, sumY = 0, sumV = 0;
            int seedX = -1, seedY = -1;
            for (int y2 = bbox[1]; y2 <= bbox[3]; y2++) {
                for (int x2 = bbox[0]; x2 <= bbox[2]; x2++) {
                    if (labels[y2 * width + x2] == label) {
                        float v = pixels[y2 * width + x2];
                        sumX += x2 * v;
                        sumY += y2 * v;
                        sumV += v;
                        if (seedX < 0) { seedX = x2; seedY = y2; }
                    }
                }
            }

            if (sumV == 0) continue;
            float cx = (float) (sumX / sumV);
            float cy = (float) (sumY / sumV);

            // Radius = average half-dimension: (W + H) / 4.
            // NOTE: despite an earlier comment here, this is NOT what TLCyzer's own
            // Rust implementation does (rust/blob_detection/src/lib.rs uses the min
            // distance from centroid to a bbox corner, ~= the half-diagonal for a
            // symmetric spot — ~41% larger than this formula). Tested swapping to
            // TLCyzer's actual formula on 2026-07-10: it made RSD worse on all three
            // validation plates (MOESM2 4.71%->7.34%, MOESM3 16.29%->22.31%, MOESM4
            // 6.88%->8.36%), so (W+H)/4 is kept — it performs better in our specific
            // pipeline even though it isn't what TLCyzer does. Don't "fix" this again
            // without re-testing against the validation fixtures.
            float r = (bboxW + bboxH) / 4f;

            if (!shapeAware) {
                spots.add(new Spot(spotId++, cx, cy, r, height));
                continue;
            }

            if (claimed[seedY * width + seedX]) {
                // Already absorbed into a previously-grown spot from another seed.
                continue;
            }

            List<Spot> grown = growAndSplitSeed(fp, pixels, width, height, seedX, seedY,
                linkThreshold, bboxW * bboxH, edgeMargin, noiseScale);
            if (grown == null || grown.isEmpty()) {
                // Growth rejected (too large / touches edge / degenerate outline) — legacy fallback.
                spots.add(new Spot(spotId++, cx, cy, r, height));
                continue;
            }
            for (Spot sub : grown) {
                Spot finalSpot = sub.withId(spotId++);
                spots.add(finalSpot);
                for (int j = 0; j < finalSpot.maskH; j++) {
                    for (int i = 0; i < finalSpot.maskW; i++) {
                        if (finalSpot.mask[j * finalSpot.maskW + i]) {
                            claimed[(finalSpot.maskY + j) * width + (finalSpot.maskX + i)] = true;
                        }
                    }
                }
            }
        }

        // Sort top-to-bottom then left-to-right
        spots.sort(Comparator.comparingDouble((Spot s) -> s.centroidY)
                              .thenComparingDouble(s -> s.centroidX));

        return spots;
    }

    /**
     * Grows a single seed via hysteresis linking to find its candidate region, then
     * separates that region into one spot per intensity peak via watershed (see class
     * javadoc). Returns {@code null} if growth should be rejected (too large relative
     * to the seed, reaches the image edge, or the traced outline is degenerate) —
     * caller falls back to the legacy circular spot in that case. Returned spots have
     * placeholder id {@code -1}; caller assigns real ids.
     */
    private static List<Spot> growAndSplitSeed(FloatProcessor fp, float[] pixels, int width, int height,
                                                int seedX, int seedY, float linkThreshold, float seedArea,
                                                int edgeMargin, float noiseScale) {
        Wand wand = new Wand(fp);
        wand.autoOutline(seedX, seedY, linkThreshold, Double.MAX_VALUE, Wand.FOUR_CONNECTED);
        if (wand.npoints < 3) return null;

        PolygonRoi wandRoi = new PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.TRACED_ROI);
        Rectangle b = wandRoi.getBounds();
        if (b.width <= 0 || b.height <= 0) return null;

        float grownArea = (float) b.width * b.height;
        boolean tooBig = grownArea > seedArea * MAX_GROWTH_AREA_MULTIPLE
                || b.width  > width  * MAX_GROWTH_DIM_FRACTION
                || b.height > height * MAX_GROWTH_DIM_FRACTION;
        boolean touchesEdge = b.x <= edgeMargin || b.y <= edgeMargin
                || (b.x + b.width)  >= width  - edgeMargin
                || (b.y + b.height) >= height - edgeMargin;
        if (tooBig || touchesEdge) return null;

        ImageProcessor maskIp = wandRoi.getMask();
        boolean[] regionMask = new boolean[b.width * b.height];
        float regionMax = -Float.MAX_VALUE;
        int n = 0;
        for (int j = 0; j < b.height; j++) {
            for (int i = 0; i < b.width; i++) {
                if (maskIp.getPixel(i, j) != 0) {
                    regionMask[j * b.width + i] = true;
                    float v = pixels[(b.y + j) * width + (b.x + i)];
                    if (v > regionMax) regionMax = v;
                    n++;
                }
            }
        }
        if (n == 0) return null;

        // Peak separation: build a cropped copy with everything outside the candidate
        // region forced to a sentinel far below any real signal, so MaximumFinder never
        // treats background as a peak and watershed lines never leak outside the region.
        FloatProcessor region = new FloatProcessor(b.width, b.height);
        for (int j = 0; j < b.height; j++) {
            for (int i = 0; i < b.width; i++) {
                region.setf(i, j, regionMask[j * b.width + i]
                    ? pixels[(b.y + j) * width + (b.x + i)]
                    : WATERSHED_SENTINEL);
            }
        }
        double tolerance = Math.max(
            noiseScale * MIN_TOLERANCE_STDDEV_MULTIPLE,
            (regionMax - linkThreshold) * NOISE_TOLERANCE_FRACTION);
        tolerance = Math.max(1e-3, tolerance);
        ByteProcessor segmented = new MaximumFinder()
            .findMaxima(region, tolerance, MaximumFinder.SEGMENTED, false);

        boolean[] segBinary = new boolean[b.width * b.height];
        for (int idx = 0; idx < segBinary.length; idx++) {
            segBinary[idx] = regionMask[idx] && segmented.get(idx) != 0;
        }
        int[] peakLabels = labelComponents(segBinary, b.width, b.height);

        // Group pixels by peak label, keeping only sufficiently large peaks.
        Map<Integer, List<Integer>> peakPixels = new LinkedHashMap<>();
        for (int idx = 0; idx < peakLabels.length; idx++) {
            int label = peakLabels[idx];
            if (label == 0) continue;
            peakPixels.computeIfAbsent(label, k -> new ArrayList<>()).add(idx);
        }

        List<Spot> result = new ArrayList<>();
        for (List<Integer> idxs : peakPixels.values()) {
            if (idxs.size() < MIN_PEAK_PIXELS) continue;
            Spot s = buildSpotFromSubMask(pixels, width, b, idxs, height);
            if (s != null) result.add(s);
        }
        return result;
    }

    /**
     * Builds a {@link Spot} (mask, intensity-weighted centroid, best-fit ellipse) from a
     * subset of pixels within a candidate region's bounding box, given as flat indices
     * into a {@code b.width × b.height} local array. Extracts a tight bounding box for
     * the sub-mask rather than reusing the full candidate region's box.
     */
    private static Spot buildSpotFromSubMask(float[] pixels, int width, Rectangle b,
                                              List<Integer> localIdxs, int imageHeight) {
        int minI = b.width, minJ = b.height, maxI = -1, maxJ = -1;
        for (int idx : localIdxs) {
            int i = idx % b.width, j = idx / b.width;
            if (i < minI) minI = i;
            if (j < minJ) minJ = j;
            if (i > maxI) maxI = i;
            if (j > maxJ) maxJ = j;
        }
        int subW = maxI - minI + 1, subH = maxJ - minJ + 1;
        int subX = b.x + minI, subY = b.y + minJ;

        boolean[] subMask = new boolean[subW * subH];
        double sumX = 0, sumY = 0, sumV = 0;
        int n = 0;
        for (int idx : localIdxs) {
            int i = idx % b.width, j = idx / b.width;
            int si = i - minI, sj = j - minJ;
            subMask[sj * subW + si] = true;
            int gx = b.x + i, gy = b.y + j;
            float v = pixels[gy * width + gx];
            sumX += gx * v;
            sumY += gy * v;
            sumV += v;
            n++;
        }
        if (sumV == 0 || n == 0) return null;

        float gcx = (float) (sumX / sumV);
        float gcy = (float) (sumY / sumV);

        // Geometric (unweighted) second central moments of the mask, for the ellipse fit —
        // a shape descriptor, deliberately unweighted by intensity so a dim streak tail
        // isn't discounted relative to its bright cap.
        double m20 = 0, m02 = 0, m11 = 0;
        for (int idx : localIdxs) {
            int i = idx % b.width, j = idx / b.width;
            double dx = (b.x + i) - gcx;
            double dy = (b.y + j) - gcy;
            m20 += dx * dx;
            m02 += dy * dy;
            m11 += dx * dy;
        }
        m20 /= n;
        m02 /= n;
        m11 /= n;
        double common = Math.sqrt(Math.max(0, ((m20 - m02) * (m20 - m02)) / 4.0 + m11 * m11));
        double lambda1 = (m20 + m02) / 2.0 + common;
        double lambda2 = Math.max(0, (m20 + m02) / 2.0 - common);
        float ellipseMajor = (float) (2.0 * Math.sqrt(lambda1));
        float ellipseMinor = (float) (2.0 * Math.sqrt(lambda2));
        float ellipseAngleDeg = (float) Math.toDegrees(0.5 * Math.atan2(2 * m11, m20 - m02));
        float equivRadius = (float) Math.sqrt(n / Math.PI);

        return new Spot(-1, gcx, gcy, equivRadius, imageHeight,
            subMask, subX, subY, subW, subH,
            ellipseMajor, ellipseMinor, ellipseAngleDeg);
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

    /**
     * Refines a spot centroid using intensity-weighted centre-of-mass.
     *
     * <p>Searches within {@code searchRadius} pixels of the initial estimate
     * {@code (cx, cy)} on a background-corrected image where background ≈ 0
     * and spots are positive peaks. Pixels above the local mean of the disk
     * are weighted by intensity to compute the refined centre.
     *
     * <p>Useful both after auto-detection (sub-pixel accuracy) and for manual
     * fallback positions from validation fixtures (corrects rough estimates).
     *
     * @param fp           background-corrected image (background ≈ 0, spots bright)
     * @param cx           initial x estimate (image pixels)
     * @param cy           initial y estimate (image pixels)
     * @param searchRadius search disk radius (image pixels)
     * @return refined {@code {x, y}}, or {@code null} if no above-threshold
     *         pixels were found (region looks like pure background)
     */
    public static float[] refineCentroid(FloatProcessor fp,
                                         float cx, float cy, float searchRadius) {
        int w = fp.getWidth(), h = fp.getHeight();
        float r2 = searchRadius * searchRadius;

        int x0 = Math.max(0, (int)(cx - searchRadius));
        int y0 = Math.max(0, (int)(cy - searchRadius));
        int x1 = Math.min(w - 1, (int)(cx + searchRadius));
        int y1 = Math.min(h - 1, (int)(cy + searchRadius));

        // Mean intensity within the disk (threshold)
        float diskSum = 0;
        int   diskN   = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > r2) continue;
                diskSum += fp.getf(x, y);
                diskN++;
            }
        }
        if (diskN == 0) return null;
        float threshold = diskSum / diskN;

        // Intensity-weighted centroid of above-threshold pixels
        float wx = 0, wy = 0, wsum = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > r2) continue;
                float v = fp.getf(x, y);
                if (v > threshold) {
                    wx   += v * x;
                    wy   += v * y;
                    wsum += v;
                }
            }
        }
        if (wsum == 0) return null;  // pure-background disk: all values ~ 0
        return new float[]{wx / wsum, wy / wsum};
    }

    // -------------------------------------------------------------------------
    // Algorithm steps (package-private for unit testing)
    // -------------------------------------------------------------------------

    static float computeMean(float[] pixels) {
        double sum = 0;
        for (float v : pixels) sum += v;
        return (float) (sum / pixels.length);
    }

    static float computeStdDev(float[] pixels, float mean) {
        double sumSq = 0;
        for (float v : pixels) {
            double d = v - mean;
            sumSq += d * d;
        }
        return (float) Math.sqrt(sumSq / pixels.length);
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
