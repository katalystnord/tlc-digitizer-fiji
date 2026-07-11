package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Lane;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class LaneDetectorTest {

    // -------------------------------------------------------------------------
    // computeProfile — origin-band exclusion
    // -------------------------------------------------------------------------

    @Test
    public void computeProfile_originNearBottom_excludesBottomBand() {
        // 4 columns x 10 rows. Rows 0-4 = 10, rows 5-9 = 1000 (would dominate the mean
        // if included). originYFraction = 0.51 -> rounds to originY=5, near bottom half
        // (> 0.5) -> exclude rows 5-9 exactly.
        int w = 4, h = 10;
        float[] pixels = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = (y < 5) ? 10f : 1000f;
            }
        }
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        float[] profile = LaneDetector.computeProfile(fp, 0.51f);
        for (float v : profile) {
            assertEquals("Profile should average only the non-excluded (top) rows", 10f, v, 1e-5f);
        }
    }

    @Test
    public void computeProfile_originNearTop_excludesTopBand() {
        int w = 4, h = 10;
        float[] pixels = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = (y < 5) ? 1000f : 10f;
            }
        }
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        float[] profile = LaneDetector.computeProfile(fp, 0.4f); // <= 0.5 -> near top
        for (float v : profile) {
            assertEquals("Profile should average only the non-excluded (bottom) rows", 10f, v, 1e-5f);
        }
    }

    // -------------------------------------------------------------------------
    // Morphological reconstruction
    // -------------------------------------------------------------------------

    @Test
    public void reconstructByDilation_seedWithinFlatPlateau_fillsWholePlateau() {
        // Classic reconstruction-by-dilation use: a single seed pixel at the mask's own
        // height, inside a wide flat plateau, should propagate across the whole
        // connected plateau (capped by, but reaching, the mask everywhere on it).
        float[] mask   = {0, 0, 5, 5, 5, 5, 5, 0, 0};
        float[] marker = {0, 0, 5, 0, 0, 0, 0, 0, 0}; // only index 2 seeded
        float[] result = LaneDetector.reconstructByDilation(marker, mask);
        assertArrayEquals(mask, result, 1e-5f);
    }

    @Test
    public void reconstructByDilation_isolatedPeak_fallsShortOfMaskByAboutH() {
        // Reconstructing an isolated (non-flat-topped) peak from marker = mask - h does
        // NOT recover the peak's full original height: dilation only propagates existing
        // marker values, and there is no taller neighbour to propagate from at an isolated
        // peak, so the result saturates at roughly (peak height - h), not the peak height
        // itself. This is the mechanism documented in LaneDetector's candidateLaneRegions
        // javadoc for why regional maxima must be extracted from the suppressed image's
        // own local structure, not by comparing back to the original mask.
        float[] mask   = {0, 0, 5, 5, 5, 0, 0};
        float h = 2f;
        float[] marker = new float[mask.length];
        for (int i = 0; i < mask.length; i++) marker[i] = mask[i] - h;
        float[] result = LaneDetector.reconstructByDilation(marker, mask);
        assertTrue("Isolated peak should fall short of the mask's own height",
                result[3] < mask[3] - 1e-3f);
    }

    @Test
    public void reconstructByDilation_bumpBelowThreshold_getsSuppressed() {
        // A small bump (height 2 above baseline) with marker = mask-3 everywhere: at the
        // bump the marker starts at -1 (below baseline), so it cannot climb back up to the
        // bump's own height via dilation from its own neighbourhood alone — it gets pulled
        // down toward the surrounding baseline instead.
        float[] mask   = {0, 0, 2, 0, 0};
        float[] marker = {-3, -3, -1, -3, -3}; // mask - 3
        float[] result = LaneDetector.reconstructByDilation(marker, mask);
        // The bump should NOT survive at its full height of 2 (baseline is stuck at -3
        // everywhere else, and the bump's neighbours are also -3, so it can only rise as
        // high as its immediate marker value allows via propagation from flat -3 regions).
        assertTrue("Suppressed bump should not reach the mask's full peak height",
                result[2] < 2f - 1e-3f);
    }

    @Test
    public void reconstructByErosion_isDualOfDilation() {
        // -reconstructByDilation(-marker, -mask) should equal reconstructByErosion(marker, mask).
        float[] mask   = {5, 5, 0, 0, 0, 5, 5};
        float[] marker = {7, 7, 2, 2, 2, 7, 7}; // mask + 2
        float[] direct = LaneDetector.reconstructByErosion(marker, mask);

        float[] negMask = new float[mask.length];
        float[] negMarker = new float[marker.length];
        for (int i = 0; i < mask.length; i++) { negMask[i] = -mask[i]; negMarker[i] = -marker[i]; }
        float[] viaDual = LaneDetector.reconstructByDilation(negMarker, negMask);
        for (int i = 0; i < direct.length; i++) {
            assertEquals(direct[i], -viaDual[i], 1e-4f);
        }
    }

    // -------------------------------------------------------------------------
    // h-maxima / h-minima / candidate regions
    // -------------------------------------------------------------------------

    @Test
    public void candidateLaneRegions_twoProminentPeaks_bothDetected() {
        // Two flat-topped peaks of height 10 separated by a deep valley (height 0),
        // baseline 0. With h = 3, both peaks should survive as maxima regions and the
        // valley should not be part of either.
        float[] profile = {0, 0, 10, 10, 0, 0, 0, 10, 10, 0, 0};
        float[] hmax = LaneDetector.hMaxima(profile, 3f);
        float[] hmin = LaneDetector.hMinima(profile, 3f);
        List<LaneDetector.Region> regions = LaneDetector.candidateLaneRegions(profile, hmax, hmin);

        assertEquals("Should find exactly two candidate regions", 2, regions.size());
        assertTrue(regions.get(0).left <= 3 && regions.get(0).right >= 2);
        assertTrue(regions.get(1).left <= 8 && regions.get(1).right >= 7);
    }

    @Test
    public void candidateLaneRegions_smallBumpBelowH_notDetected() {
        // A small bump (height 2) alongside a tall one (height 10), h = 5: only the tall
        // one should survive as a candidate region.
        float[] profile = {0, 0, 10, 10, 0, 0, 2, 2, 0, 0};
        float[] hmax = LaneDetector.hMaxima(profile, 5f);
        float[] hmin = LaneDetector.hMinima(profile, 5f);
        List<LaneDetector.Region> regions = LaneDetector.candidateLaneRegions(profile, hmax, hmin);

        assertEquals("Only the tall peak should survive h-maxima suppression", 1, regions.size());
        assertTrue(regions.get(0).left <= 3 && regions.get(0).right >= 2);
    }

    // -------------------------------------------------------------------------
    // selectCutoffs
    // -------------------------------------------------------------------------

    @Test
    public void selectCutoffs_singlePeak_maxIsWindowCeiling() {
        // Rises to a single peak then falls, no secondary peak -> cutoff-max should be
        // the last index (window ceiling), cutoff-min should be the local min before the peak.
        float[] amp = {1, 1, 2, 5, 8, 5, 3, 2, 1, 1};
        // local min before peak (index 4): scanning back from 4, index 1 or 0 is a plateau,
        // first strict local min going backward is index... let's use a clear dip: adjust array
        float[] amp2 = {5, 3, 1, 2, 8, 6, 4, 3, 2, 1};
        int[] cutoffs = LaneDetector.selectCutoffs(amp2);
        assertEquals("cutoff-min should be the dip before the peak", 2, cutoffs[0]);
        assertEquals("cutoff-max should default to the window ceiling (no secondary peak)",
                amp2.length - 1, cutoffs[1]);
    }

    @Test
    public void selectCutoffs_secondaryPeak_maxIsLocalMinAfterIt() {
        // Primary peak at index 3, dips at index 6, secondary (smaller) peak at index 8,
        // dips again (strictly, on both sides) at index 10 -> cutoff-max should land at
        // index 10.
        float[] amp = {1, 3, 6, 9, 6, 4, 2, 3, 5, 3, 1, 2};
        int[] cutoffs = LaneDetector.selectCutoffs(amp);
        assertEquals(10, cutoffs[1]);
    }

    // -------------------------------------------------------------------------
    // removeFalseLanes
    // -------------------------------------------------------------------------

    @Test
    public void removeFalseLanes_dimNarrowRegionNearNeighbour_isRemoved() {
        // Three normal-width, well-spaced, high-intensity regions plus a dim, oddly-narrow
        // spurious region jammed close to one of them.
        float[] profile = new float[200];
        for (int x = 0; x < 200; x++) profile[x] = 0f;
        for (int x = 20; x <= 39; x++) profile[x] = 100f;   // real lane, width 20
        for (int x = 45; x <= 47; x++) profile[x] = 8f;     // spurious: narrow + dim + close
        for (int x = 90; x <= 109; x++) profile[x] = 100f;  // real lane
        for (int x = 160; x <= 179; x++) profile[x] = 100f; // real lane

        List<LaneDetector.Region> candidates = new ArrayList<>();
        candidates.add(new LaneDetector.Region(20, 39));
        candidates.add(new LaneDetector.Region(45, 47));
        candidates.add(new LaneDetector.Region(90, 109));
        candidates.add(new LaneDetector.Region(160, 179));

        List<LaneDetector.Region> kept = LaneDetector.removeFalseLanes(candidates, profile, 200);
        assertEquals("The dim, narrow, close spurious region should be removed", 3, kept.size());
        for (LaneDetector.Region r : kept) {
            assertTrue("Every kept region should be one of the real lanes",
                    (r.left == 20 && r.right == 39)
                            || (r.left == 90 && r.right == 109)
                            || (r.left == 160 && r.right == 179));
        }
    }

    @Test
    public void removeFalseLanes_fewerThanTwoCandidates_returnsUnchanged() {
        List<LaneDetector.Region> candidates = new ArrayList<>();
        candidates.add(new LaneDetector.Region(10, 20));
        float[] profile = new float[50];
        List<LaneDetector.Region> kept = LaneDetector.removeFalseLanes(candidates, profile, 50);
        assertEquals(1, kept.size());
    }

    // -------------------------------------------------------------------------
    // recoverSubtleLanes / findRecoverableLanesInGap
    // -------------------------------------------------------------------------

    @Test
    public void findRecoverableLanesInGap_plausibleBump_isRecovered() {
        // Derivative has a rising local max at index 10 and a falling local min at index
        // 20, separation 10 (within [0.6, 1.4] x mlw=12), amplitude diff 6 (> 0.3 x mla=10 -> 3).
        float[] derivative = new float[40];
        derivative[10] = 5f;
        derivative[9] = 2f; derivative[11] = 2f;
        derivative[20] = -1f;
        derivative[19] = 2f; derivative[21] = 2f;

        List<LaneDetector.Region> found =
                LaneDetector.findRecoverableLanesInGap(derivative, 0, 39, 12f, 10f);
        assertEquals(1, found.size());
        assertEquals(10, found.get(0).left);
        assertEquals(20, found.get(0).right);
    }

    @Test
    public void findRecoverableLanesInGap_separationOutOfRange_isRejected() {
        // Same amplitude swing as above but the max/min are far too close together
        // relative to mlw=12 (separation 2, below 0.6 x 12 = 7.2).
        float[] derivative = new float[40];
        derivative[10] = 5f;
        derivative[9] = 2f; derivative[11] = 2f;
        derivative[12] = -1f;
        derivative[11] = 2f; derivative[13] = 2f;

        List<LaneDetector.Region> found =
                LaneDetector.findRecoverableLanesInGap(derivative, 0, 39, 12f, 10f);
        assertTrue("Too-close a pair should not be recovered as a lane", found.isEmpty());
    }

    @Test
    public void recoverSubtleLanes_noValidatedLanes_returnsEmpty() {
        List<LaneDetector.Region> result =
                LaneDetector.recoverSubtleLanes(new ArrayList<>(), new float[50], 50);
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // finalizeBoundaries
    // -------------------------------------------------------------------------

    @Test
    public void finalizeBoundaries_midpointsBetweenRegions_andEdgeClamping() {
        List<LaneDetector.Region> regions = new ArrayList<>();
        regions.add(new LaneDetector.Region(10, 20));  // center 15
        regions.add(new LaneDetector.Region(40, 60));  // center 50
        List<Lane> lanes = LaneDetector.finalizeBoundaries(regions, 100);

        assertEquals(2, lanes.size());
        assertEquals(0f, lanes.get(0).left, 1e-5f);
        assertEquals(30f, lanes.get(0).right, 1e-5f); // midpoint of (20, 40)
        assertEquals(30f, lanes.get(1).left, 1e-5f);
        assertEquals(100f, lanes.get(1).right, 1e-5f);
    }

    // -------------------------------------------------------------------------
    // Full pipeline (synthetic multi-lane images) — integration smoke tests
    // -------------------------------------------------------------------------

    /** Builds an image where every row is identical to the given column profile. */
    private static FloatProcessor bandedImage(int width, int height, float[] profile) {
        float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            System.arraycopy(profile, 0, pixels, y * width, width);
        }
        return new FloatProcessor(width, height, pixels, null);
    }

    /** Sum of Gaussian bumps on a flat baseline. */
    private static float[] gaussianBumpsProfile(int width, float baseline,
                                                 float[] centers, float[] amplitudes, float sigma) {
        float[] p = new float[width];
        for (int x = 0; x < width; x++) {
            double v = baseline;
            for (int i = 0; i < centers.length; i++) {
                double dx = x - centers[i];
                v += amplitudes[i] * Math.exp(-0.5 * dx * dx / (sigma * sigma));
            }
            p[x] = (float) v;
        }
        return p;
    }

    @Test
    public void detect_regularlySpacedLanes_findsCorrectCount() {
        int width = 420, height = 120;
        float[] centers = {40, 110, 180, 250, 320, 390};
        float[] amps = new float[centers.length];
        java.util.Arrays.fill(amps, 100f);
        float[] profile = gaussianBumpsProfile(width, 0f, centers, amps, 10f);

        FloatProcessor fp = bandedImage(width, height, profile);
        List<Lane> lanes = LaneDetector.detect(fp, 0.9f); // origin near bottom, harmless here

        assertEquals("Should detect all six regularly-spaced lanes", 6, lanes.size());
        for (int i = 0; i < centers.length; i++) {
            assertEquals("Lane " + i + " center should be near its true position",
                    centers[i], lanes.get(i).center, 25f);
        }
    }

    @Test
    public void detect_irregularSpacingAndWidth_findsAllLanes() {
        // Gaps (110-120px) are all comfortably larger than removeFalseLanes' distance
        // check would ever flag at this sigma (10px) — irregular in spacing/amplitude,
        // but not marginal, so this test isolates "does detection handle non-uniform
        // lanes at all" from the separate, real question of exactly where
        // removeFalseLanes' distance threshold should sit for closely-spaced lanes
        // (unvalidated against real plates yet, like H_MAXIMA_FRACTION).
        int width = 620, height = 120;
        float[] centers = {30, 140, 260, 360, 460, 580};
        float[] amps = {90f, 110f, 100f, 95f, 105f, 100f};
        float[] profile = gaussianBumpsProfile(width, 0f, centers, amps, 10f);

        FloatProcessor fp = bandedImage(width, height, profile);
        List<Lane> lanes = LaneDetector.detect(fp, 0.9f);

        assertEquals("Should detect all six irregularly-spaced lanes", 6, lanes.size());
    }
}
