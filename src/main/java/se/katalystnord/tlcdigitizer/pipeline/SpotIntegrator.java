package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 6: Spot integration.
 *
 * For each spot, collects pixel values within the spot's circular region,
 * sorts them in descending order, and sums the top 15%.
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
     * Computes the integration value for a single spot and stores it in
     * {@link Spot#integrationValue}.
     */
    public static void integrate(FloatProcessor fp, Spot spot) {
        spot.integrationValue = integrateSpot(fp, spot.centroidX, spot.centroidY, spot.radius);
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

        if (values.isEmpty()) {
            return 0.0;
        }

        // Sort descending
        Collections.sort(values, Collections.reverseOrder());

        int cutoff = Math.max(1, (int) (values.size() * TOP_FRACTION));
        double sum = 0.0;
        for (int i = 0; i < cutoff; i++) {
            sum += values.get(i);
        }
        return sum;
    }
}
