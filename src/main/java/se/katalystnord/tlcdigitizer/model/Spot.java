package se.katalystnord.tlcdigitizer.model;

/**
 * One detected spot on the TLC plate.
 * All coordinates are in the perspective-corrected image's pixel space
 * unless otherwise noted.
 */
public class Spot {

    public final int id;

    /** Intensity-weighted centroid in corrected-image pixels. */
    public final float centroidX;
    public final float centroidY;

    /** Radius in corrected-image pixels (min distance from centroid to bounding box corner). */
    public final float radius;

    /** Centroid position as fraction of image height, measured from top. */
    public final float centroidYFraction;

    /** Rf value — set after user marks origin and solvent front. NaN until then. */
    public float rfValue = Float.NaN;

    /** Raw integration value (sum of top-15% pixels in circle). Set after Stage 6. */
    public double integrationValue = Double.NaN;

    /** True if the user has designated this spot as a calibration reference. */
    public boolean isReference = false;

    /** Known concentration for reference spots (e.g. µg/mL). NaN for unknowns. */
    public double referenceConcentration = Double.NaN;

    /** Predicted concentration from calibration model. NaN until model is fitted. */
    public double assignedConcentration = Double.NaN;

    /** Lane number (1-indexed column in the plate, estimated from centroidX). */
    public int lane = 0;

    public Spot(int id, float centroidX, float centroidY, float radius, int imageHeight) {
        this.id = id;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.radius = radius;
        this.centroidYFraction = (imageHeight > 0) ? centroidY / imageHeight : 0f;
    }

    @Override
    public String toString() {
        return String.format("Spot{id=%d cx=%.1f cy=%.1f r=%.1f Rf=%.3f}", id, centroidX, centroidY, radius, rfValue);
    }
}
