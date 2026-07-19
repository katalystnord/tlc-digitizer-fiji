package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 6: Spot integration.
 *
 * For each spot, collects pixel values within the spot's region — a circular
 * region for legacy spots, or the shape-aware mask (see {@link Spot#hasMask()})
 * for spots produced by {@link SpotDetector}'s shape-aware mode — sorts them in
 * descending order, and sums the top 15%.
 *
 * Integrating only the top 15% of pixels improves robustness to spot edge
 * noise and partial overlaps, as validated by the TLCyzer algorithm.
 *
 * Source: Hauk et al. Scientific Reports 12, 13433 (2022).
 */
public final class SpotIntegrator {

    /** Fraction of pixels (by intensity, descending) to include in the sum. */
    static final float TOP_FRACTION = 0.15f;

    private SpotIntegrator() {}

    /**
     * Computes the integration value for a single spot and stores it, along with the
     * number of pixels actually summed ({@link Spot#integrationPixelCount} — a small count
     * means the value is averaged over few samples and is more sensitive to per-pixel
     * noise), in the spot.
     */
    public static void integrate(FloatProcessor fp, Spot spot) {
        List<Float> values = spot.hasMask() ? collectMaskValues(fp, spot)
                                             : collectSpotValues(fp, spot.centroidX, spot.centroidY, spot.radius);
        spot.integrationValue = sumTopFraction(values);
        spot.integrationPixelCount = topFractionCount(values);
    }

    /**
     * Integrates all spots in the list.
     */
    public static void integrateAll(FloatProcessor fp, List<Spot> spots) {
        for (Spot s : spots) {
            integrate(fp, s);
        }
    }

    /**
     * Returns the integration value for a circular region.
     * Collects all pixels inside radius {@code r} of (cx, cy), sorts descending,
     * and sums the top {@value #TOP_FRACTION} fraction.
     */
    static double integrateSpot(FloatProcessor fp, float cx, float cy, float radius) {
        return sumTopFraction(collectSpotValues(fp, cx, cy, radius));
    }

    /**
     * Returns the integration value for a shape-aware spot: collects pixel values
     * within {@link Spot#mask}, sorts descending, and sums the top {@value #TOP_FRACTION}
     * fraction — same robustness rule as {@link #integrateSpot}, just over the spot's
     * true connected shape instead of a fixed circle.
     */
    static double integrateMask(FloatProcessor fp, Spot spot) {
        return sumTopFraction(collectMaskValues(fp, spot));
    }

    private static List<Float> collectSpotValues(FloatProcessor fp, float cx, float cy, float radius) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        int xMin = Math.max(0, (int) (cx - radius));
        int xMax = Math.min(width - 1, (int) (cx + radius));
        int yMin = Math.max(0, (int) (cy - radius));
        int yMax = Math.min(height - 1, (int) (cy + radius));

        float r2 = radius * radius;
        List<Float> values = new ArrayList<>();

        for (int y = yMin; y <= yMax; y++) {
            for (int x = xMin; x <= xMax; x++) {
                float dx = x - cx;
                float dy = y - cy;
                if (dx * dx + dy * dy <= r2) {
                    values.add(pixels[y * width + x]);
                }
            }
        }
        return values;
    }

    private static List<Float> collectMaskValues(FloatProcessor fp, Spot spot) {
        int width = fp.getWidth();
        float[] pixels = (float[]) fp.getPixels();

        List<Float> values = new ArrayList<>();
        for (int j = 0; j < spot.maskH; j++) {
            for (int i = 0; i < spot.maskW; i++) {
                if (spot.mask[j * spot.maskW + i]) {
                    int gx = spot.maskX + i, gy = spot.maskY + j;
                    values.add(pixels[gy * width + gx]);
                }
            }
        }
        return values;
    }

    private static double sumTopFraction(List<Float> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted, Collections.reverseOrder());
        int cutoff = Math.max(1, (int) (sorted.size() * TOP_FRACTION));
        double sum = 0.0;
        for (int i = 0; i < cutoff; i++) {
            sum += sorted.get(i);
        }
        return sum;
    }

    private static int topFractionCount(List<Float> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) (values.size() * TOP_FRACTION));
    }
}
