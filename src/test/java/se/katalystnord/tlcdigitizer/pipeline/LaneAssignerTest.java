package se.katalystnord.tlcdigitizer.pipeline;

import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class LaneAssignerTest {

    private static Spot spot(float cx, float cy, float r) {
        return new Spot(0, cx, cy, r, 200);
    }

    // -------------------------------------------------------------------------

    @Test
    public void singleSpot_assignedLane1() {
        List<Spot> spots = List.of(spot(100, 100, 10));
        LaneAssigner.assignLanes(spots, 400);
        assertEquals(1, spots.get(0).lane);
    }

    @Test
    public void emptyList_noOp() {
        LaneAssigner.assignLanes(new ArrayList<>(), 400); // must not throw
    }

    @Test
    public void spotsCloseInX_singleLane() {
        // Three spots within 10px of each other — well within threshold for r=10 (threshold ≥ 20)
        List<Spot> spots = Arrays.asList(
            spot(50, 80, 10),
            spot(55, 100, 10),
            spot(58, 120, 10)
        );
        LaneAssigner.assignLanes(spots, 400);
        assertEquals(1, spots.get(0).lane);
        assertEquals(1, spots.get(1).lane);
        assertEquals(1, spots.get(2).lane);
    }

    @Test
    public void twoDistinctClusters_twoLanes() {
        // Two clusters 100px apart, spots within each cluster are ~5px apart
        List<Spot> spots = Arrays.asList(
            spot(50,  80, 10),
            spot(55, 100, 10),
            spot(150, 80, 10),
            spot(155, 100, 10)
        );
        LaneAssigner.assignLanes(spots, 400);

        int laneLeft  = spots.get(0).lane;
        int laneRight = spots.get(2).lane;

        assertEquals("Both left spots same lane",  laneLeft,  spots.get(1).lane);
        assertEquals("Both right spots same lane", laneRight, spots.get(3).lane);
        assertNotEquals("Left and right clusters are different lanes", laneLeft, laneRight);
        assertEquals("Lanes are 1 and 2", 1, Math.min(laneLeft, laneRight));
        assertEquals("Lanes are 1 and 2", 2, Math.max(laneLeft, laneRight));
    }

    @Test
    public void threeLanes_numberedLeftToRight() {
        List<Spot> spots = Arrays.asList(
            spot(300, 100, 10),  // rightmost → lane 3
            spot(50,  100, 10),  // leftmost  → lane 1
            spot(175, 100, 10)   // middle    → lane 2
        );
        LaneAssigner.assignLanes(spots, 500);

        // Find each by original index
        Spot left   = spots.get(1);
        Spot middle = spots.get(2);
        Spot right  = spots.get(0);

        assertEquals(1, left.lane);
        assertEquals(2, middle.lane);
        assertEquals(3, right.lane);
    }

    @Test
    public void allSpotsAtSameX_singleLane() {
        List<Spot> spots = Arrays.asList(
            spot(100, 50,  12),
            spot(100, 100, 12),
            spot(100, 150, 12)
        );
        LaneAssigner.assignLanes(spots, 400);
        assertEquals(1, spots.get(0).lane);
        assertEquals(1, spots.get(1).lane);
        assertEquals(1, spots.get(2).lane);
    }

    @Test
    public void computeThreshold_usesLargerOfRadiusAndWidthFloor() {
        // radius=5 → 2×5=10; width=400 → 400×0.03=12 → threshold=12
        List<Spot> spots = List.of(spot(100, 100, 5));
        float t = LaneAssigner.computeThreshold(spots, 400);
        assertEquals(12f, t, 0.01f);

        // radius=20 → 2×20=40; width=400 → 12 → threshold=40
        spots = List.of(spot(100, 100, 20));
        t = LaneAssigner.computeThreshold(spots, 400);
        assertEquals(40f, t, 0.01f);
    }

    @Test
    public void spotsJustBelowAndAboveThreshold_correctlySplit() {
        // threshold for r=10, width=400 → max(20, 12) = 20
        // spots at x=50 and x=71 → gap=21 > 20 → two lanes
        // spots at x=50 and x=69 → gap=19 < 20 → one lane
        List<Spot> twoLanes = Arrays.asList(spot(50, 100, 10), spot(71, 100, 10));
        LaneAssigner.assignLanes(twoLanes, 400);
        assertNotEquals(twoLanes.get(0).lane, twoLanes.get(1).lane);

        List<Spot> oneLane = Arrays.asList(spot(50, 100, 10), spot(69, 100, 10));
        LaneAssigner.assignLanes(oneLane, 400);
        assertEquals(oneLane.get(0).lane, oneLane.get(1).lane);
    }
}
