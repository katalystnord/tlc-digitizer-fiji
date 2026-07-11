package se.katalystnord.tlcdigitizer.model;

/**
 * One detected sample lane's horizontal extent on a perspective-corrected plate image.
 * All coordinates are corrected-image pixel space (X axis), consistent with
 * {@link Spot#centroidX}.
 *
 * <p>Distinct from {@link Spot#lane} / {@code LaneAssigner}: this is a pre-spot-detection,
 * column-intensity-profile-based geometric primitive (see {@code LaneDetector}), not a
 * post-hoc grouping of already-detected spot centroids. A future, opt-in Step 5 workflow
 * may use detected {@code Lane}s to assign {@link Spot#lane} instead of
 * {@code LaneAssigner}'s centroid gap-clustering, but the two remain independent today.
 */
public class Lane {

    /** Left boundary (corrected-image pixels). */
    public final float left;

    /** Right boundary (corrected-image pixels). */
    public final float right;

    /** Lane center, i.e. {@code (left + right) / 2}. */
    public final float center;

    public Lane(float left, float right) {
        this.left = left;
        this.right = right;
        this.center = (left + right) / 2f;
    }

    @Override
    public String toString() {
        return String.format("Lane{left=%.1f right=%.1f center=%.1f}", left, right, center);
    }
}
