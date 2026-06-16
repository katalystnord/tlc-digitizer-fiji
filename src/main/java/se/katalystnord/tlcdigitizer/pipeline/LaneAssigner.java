package se.katalystnord.tlcdigitizer.pipeline;

import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assigns 1-indexed lane numbers to spots by clustering on centroid X position.
 *
 * Algorithm: sort spots by centroidX, then walk through them in order.
 * When the gap between a spot and the previous one exceeds the lane-separation
 * threshold, start a new lane. Lanes are numbered left to right from 1.
 *
 * Threshold = max(2 × mean spot radius,  imageWidth × MIN_LANE_GAP_FRACTION).
 * Using the mean radius ensures the threshold scales with actual spot size on
 * the plate, while the image-fraction floor handles plates with few or no spots.
 */
public final class LaneAssigner {

    /** Minimum lane gap as a fraction of image width (floor on the threshold). */
    static final float MIN_LANE_GAP_FRACTION = 0.03f;

    private LaneAssigner() {}

    /**
     * Assigns {@link Spot#lane} (1-indexed) to every spot in {@code spots}.
     *
     * @param spots      list of detected spots; modified in place
     * @param imageWidth width of the perspective-corrected image in pixels
     */
    public static void assignLanes(List<Spot> spots, int imageWidth) {
        if (spots.isEmpty()) return;

        float threshold = computeThreshold(spots, imageWidth);

        // Work on a sorted copy so we can walk gaps without mutating the original order.
        List<Spot> byX = new ArrayList<>(spots);
        byX.sort(Comparator.comparingDouble(s -> s.centroidX));

        int lane = 1;
        float prevX = byX.get(0).centroidX;
        byX.get(0).lane = lane;

        for (int i = 1; i < byX.size(); i++) {
            float x = byX.get(i).centroidX;
            if (x - prevX > threshold) {
                lane++;
            }
            byX.get(i).lane = lane;
            prevX = x;
        }
    }

    /**
     * Returns the gap threshold in pixels.
     * Exposed package-private for testing.
     */
    static float computeThreshold(List<Spot> spots, int imageWidth) {
        double meanRadius = spots.stream().mapToDouble(s -> s.radius).average().orElse(1.0);
        float fromRadius = (float) (2.0 * meanRadius);
        float fromWidth  = imageWidth * MIN_LANE_GAP_FRACTION;
        return Math.max(fromRadius, fromWidth);
    }
}
