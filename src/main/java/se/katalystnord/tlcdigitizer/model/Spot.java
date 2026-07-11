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

    /**
     * Radius in corrected-image pixels. For circular (legacy) spots this is
     * (bbox width + bbox height) / 4; for shape-aware spots (see {@link #mask})
     * this is the equal-area equivalent radius, kept for backward compatibility
     * with code that hasn't been updated to consult the mask.
     */
    public final float radius;

    /** Centroid position as fraction of image height, measured from top. */
    public final float centroidYFraction;

    /**
     * Shape-aware detection's pixel mask, relative to ({@link #maskX}, {@link #maskY}),
     * row-major, size {@link #maskW} x {@link #maskH}. {@code null} for spots detected
     * with the legacy circular pipeline — callers must check {@link #hasMask()} before
     * using this and the accompanying bounds fields.
     */
    public final boolean[] mask;
    public final int maskX, maskY, maskW, maskH;

    /**
     * Best-fit ellipse describing the mask's shape (full axis lengths, not semi-axes;
     * angle in degrees from the x-axis). Zero when {@link #mask} is {@code null}.
     */
    public final float ellipseMajor, ellipseMinor, ellipseAngleDeg;

    /** Rf value — set after user marks origin and solvent front. NaN until then. */
    public float rfValue = Float.NaN;

    /** Raw integration value (sum of top-15% pixels in the spot's region). Set after Stage 6. */
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
        this(id, centroidX, centroidY, radius,
            (imageHeight > 0) ? centroidY / imageHeight : 0f,
            null, 0, 0, 0, 0, 0f, 0f, 0f);
    }

    /**
     * Full constructor including shape-aware mask/ellipse data.
     * Pass {@code mask = null} (and zero for the remaining shape fields) for a
     * plain circular spot.
     */
    public Spot(int id, float centroidX, float centroidY, float radius, int imageHeight,
                boolean[] mask, int maskX, int maskY, int maskW, int maskH,
                float ellipseMajor, float ellipseMinor, float ellipseAngleDeg) {
        this(id, centroidX, centroidY, radius,
            (imageHeight > 0) ? centroidY / imageHeight : 0f,
            mask, maskX, maskY, maskW, maskH, ellipseMajor, ellipseMinor, ellipseAngleDeg);
    }

    /** Internal: takes centroidYFraction directly so copy helpers don't need imageHeight. */
    private Spot(int id, float centroidX, float centroidY, float radius, float centroidYFraction,
                 boolean[] mask, int maskX, int maskY, int maskW, int maskH,
                 float ellipseMajor, float ellipseMinor, float ellipseAngleDeg) {
        this.id = id;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.radius = radius;
        this.centroidYFraction = centroidYFraction;
        this.mask = mask;
        this.maskX = maskX;
        this.maskY = maskY;
        this.maskW = maskW;
        this.maskH = maskH;
        this.ellipseMajor = ellipseMajor;
        this.ellipseMinor = ellipseMinor;
        this.ellipseAngleDeg = ellipseAngleDeg;
    }

    /** True for spots produced by shape-aware detection (i.e. {@link #mask} is not null). */
    public boolean hasMask() {
        return mask != null;
    }

    /**
     * Returns a copy of this spot with a new id, identical geometry (including
     * mask/ellipse, if present), and freshly-initialised analysis fields
     * (rfValue/integrationValue/etc. are NOT copied — callers that need those
     * carried over must copy them explicitly after calling this).
     */
    public Spot withId(int newId) {
        return new Spot(newId, centroidX, centroidY, radius, centroidYFraction,
            mask, maskX, maskY, maskW, maskH, ellipseMajor, ellipseMinor, ellipseAngleDeg);
    }

    @Override
    public String toString() {
        return String.format("Spot{id=%d cx=%.1f cy=%.1f r=%.1f Rf=%.3f%s}",
            id, centroidX, centroidY, radius, rfValue, hasMask() ? " mask" : "");
    }
}
