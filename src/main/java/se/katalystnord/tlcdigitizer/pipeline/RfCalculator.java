package se.katalystnord.tlcdigitizer.pipeline;

import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.List;

/**
 * Stage 5: Rf value calculation.
 *
 * Rf = (originY - spotY) / (originY - frontY)
 *
 * All Y coordinates are expressed as fractions of image height, measured from
 * the top of the corrected image (0.0 = top, 1.0 = bottom).
 * On a TLC plate the origin is below the spots, so originY > frontY.
 * Result is clamped to [0, 1].
 *
 * Source: consistent across all validated references (TLCyzer, qTLC, qtlc, quanTLC).
 */
public final class RfCalculator {

    private RfCalculator() {}

    /**
     * Calculates Rf for a single spot centroid position.
     *
     * @param spotYFraction    centroid Y as fraction of image height
     * @param originYFraction  application point Y as fraction of image height
     * @param frontYFraction   solvent front Y as fraction of image height
     * @return Rf value in [0, 1], or NaN if the origin and front coincide
     */
    public static float calculate(float spotYFraction, float originYFraction, float frontYFraction) {
        float denominator = originYFraction - frontYFraction;
        if (Math.abs(denominator) < 1e-6f) {
            return Float.NaN;
        }
        float rf = (originYFraction - spotYFraction) / denominator;
        return Math.max(0f, Math.min(1f, rf));
    }

    /**
     * Updates the rfValue field of every spot in {@code spots} in place.
     */
    public static void assignAll(List<Spot> spots, float originYFraction, float frontYFraction) {
        for (Spot s : spots) {
            s.rfValue = calculate(s.centroidYFraction, originYFraction, frontYFraction);
        }
    }
}
