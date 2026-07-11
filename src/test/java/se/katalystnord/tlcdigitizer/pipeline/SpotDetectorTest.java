package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.List;

import static org.junit.Assert.*;

public class SpotDetectorTest {

    @Test
    public void threshold_aboveMean_returnsTrue() {
        float[] pixels = {1f, 2f, 3f, 4f}; // mean = 2.5
        boolean[] result = SpotDetector.threshold(pixels, 2.5f);
        assertFalse(result[0]);
        assertFalse(result[1]);
        assertTrue(result[2]);
        assertTrue(result[3]);
    }

    @Test
    public void computeMean_correctlyAverages() {
        float[] pixels = {0f, 10f, 20f, 30f};
        assertEquals(15.0f, SpotDetector.computeMean(pixels), 1e-5f);
    }

    @Test
    public void labelComponents_singleBlob_getsLabelOne() {
        // 5×5 image with a 3×3 blob in the centre
        int w = 5, h = 5;
        boolean[] binary = new boolean[w * h];
        binary[1 * w + 1] = true;
        binary[1 * w + 2] = true;
        binary[1 * w + 3] = true;
        binary[2 * w + 1] = true;
        binary[2 * w + 2] = true;
        binary[2 * w + 3] = true;
        binary[3 * w + 1] = true;
        binary[3 * w + 2] = true;
        binary[3 * w + 3] = true;

        int[] labels = SpotDetector.labelComponents(binary, w, h);
        // All blob pixels should have the same non-zero label
        int label = labels[1 * w + 1];
        assertTrue(label > 0);
        assertEquals(label, labels[2 * w + 2]);
        assertEquals(label, labels[3 * w + 3]);
        // Background pixels should be 0
        assertEquals(0, labels[0]);
    }

    @Test
    public void labelComponents_twoBlobs_getDifferentLabels() {
        int w = 10, h = 5;
        boolean[] binary = new boolean[w * h];
        // Blob 1: column 1-2
        binary[2 * w + 1] = true;
        binary[2 * w + 2] = true;
        // Blob 2: column 7-8
        binary[2 * w + 7] = true;
        binary[2 * w + 8] = true;

        int[] labels = SpotDetector.labelComponents(binary, w, h);
        int l1 = labels[2 * w + 1];
        int l2 = labels[2 * w + 7];
        assertTrue(l1 > 0);
        assertTrue(l2 > 0);
        assertNotEquals(l1, l2);
    }

    private static FloatProcessor syntheticSpotImage(int w, int h,
                                                       float cx, float cy, float r,
                                                       float bgLevel, float spotLevel) {
        float[] pixels = new float[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = bgLevel * 0.5f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r * r) pixels[y * w + x] = spotLevel;
            }
        }
        return new FloatProcessor(w, h, pixels, null);
    }

    @Test
    public void detect_syntheticSpot_findsOneSpot() {
        FloatProcessor fp = syntheticSpotImage(200, 200, 100, 100, 20, 50f, 200f);
        List<Spot> spots = SpotDetector.detect(fp);

        assertEquals("Should detect exactly one spot", 1, spots.size());
        Spot s = spots.get(0);
        assertEquals("Centroid X near 100", 100f, s.centroidX, 5.0f);
        assertEquals("Centroid Y near 100", 100f, s.centroidY, 5.0f);
    }

    @Test
    public void detect_syntheticSpot_radiusMatchesInput() {
        // A circular spot of radius 20 should yield radius ≈ 20 with the TLCyzer
        // (W+H)/4 formula.  Previous minCornerDistance formula gave ~28 (41% too large).
        float inputRadius = 20f;
        FloatProcessor fp = syntheticSpotImage(200, 200, 100, 100, inputRadius, 50f, 200f);
        List<Spot> spots = SpotDetector.detect(fp);
        assertEquals(1, spots.size());
        assertEquals("Radius should approximate input radius",
                     inputRadius, spots.get(0).radius, 3.0f);
    }

    @Test
    public void detect_multiplierOverload_matchesDefaultAtOne() {
        // detect(fp, 1.0f) must produce the same spots as detect(fp)
        FloatProcessor fp = syntheticSpotImage(200, 200, 100, 100, 20, 50f, 200f);
        List<Spot> def  = SpotDetector.detect(fp);
        List<Spot> mult = SpotDetector.detect(fp, 1.0f);
        assertEquals("Same spot count", def.size(), mult.size());
        if (!def.isEmpty()) {
            assertEquals("Same centroid X", def.get(0).centroidX, mult.get(0).centroidX, 1e-3f);
            assertEquals("Same centroid Y", def.get(0).centroidY, mult.get(0).centroidY, 1e-3f);
        }
    }

    @Test
    public void detect_withVeryHighMultiplier_findsNothing() {
        // Threshold = 100 × mean → no pixel in any realistic image can exceed this
        FloatProcessor fp = syntheticSpotImage(200, 200, 100, 100, 20, 50f, 200f);
        List<Spot> spots = SpotDetector.detect(fp, 100f);
        assertTrue("Extreme threshold should eliminate all spots", spots.isEmpty());
    }

    /**
     * Builds a "dumbbell": two round blobs joined by a dim bridge whose intensity sits
     * strictly between the link threshold and the primary threshold at the given
     * multiplier — i.e. invisible to legacy detection (which sees two disconnected
     * round components, exactly like a real streaking spot's origin-dot + cap
     * fragments) but visible to shape-aware hysteresis linking.
     */
    private static FloatProcessor syntheticDumbbellImage(int w, int h,
            float cx, float capCy, float originCy, float blobR,
            float bgLevel, float bridgeLevel, float blobLevel, float bridgeHalfWidth) {
        float[] pixels = new float[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = bgLevel;
        // Bridge: a vertical strip between the two blob centres.
        int y0 = (int) (capCy), y1 = (int) (originCy);
        for (int y = y0; y <= y1; y++) {
            for (int x = (int) (cx - bridgeHalfWidth); x <= (int) (cx + bridgeHalfWidth); x++) {
                if (x >= 0 && x < w && y >= 0 && y < h) pixels[y * w + x] = bridgeLevel;
            }
        }
        // Two round blobs, drawn after the bridge so they aren't clipped by it.
        for (float blobCy : new float[]{capCy, originCy}) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float dx = x - cx, dy = y - blobCy;
                    if (dx * dx + dy * dy <= blobR * blobR) pixels[y * w + x] = blobLevel;
                }
            }
        }
        return new FloatProcessor(w, h, pixels, null);
    }

    @Test
    public void detect_legacyMode_splitsStreakIntoTwoSpots() {
        // Documents the real-world failure mode found on a lab plate (see CLAUDE.md,
        // "Real-plate exploratory test"): a tailing/streaked spot whose midsection dims
        // below the mean threshold gets reported as two disconnected spots.
        FloatProcessor fp = syntheticDumbbellImage(200, 500, 100, 150, 260, 18, 10f, 70f, 200f, 6.5f);
        List<Spot> spots = SpotDetector.detect(fp, 6.0f, false);
        assertEquals("Legacy detection should split the dumbbell into two components",
            2, spots.size());
        for (Spot s : spots) assertFalse("Legacy spots carry no mask", s.hasMask());
    }

    @Test
    public void detect_shapeAwareMode_separatesTwoPeaksInLinkedRegion() {
        // Two round blobs of EQUAL height joined by a dim bridge is what real co-eluting
        // compounds look like (see CLAUDE.md, "peak separation" discussion) — hysteresis
        // linking alone would wrongly fuse them into one spot (that's the pre-watershed
        // behaviour this replaced), but watershed peak-separation must recover two
        // spots, since there's a genuine valley (the bridge, well below both peaks)
        // between two local maxima.
        FloatProcessor fp = syntheticDumbbellImage(200, 500, 100, 150, 260, 18, 10f, 70f, 200f, 6.5f);
        List<Spot> spots = SpotDetector.detect(fp, 6.0f, true);
        assertEquals("Watershed should split the linked region back into its two real peaks",
            2, spots.size());
        for (Spot s : spots) {
            assertTrue("Split spots should carry a mask", s.hasMask());
        }
        // One spot centred near each original blob (150 and 260).
        assertEquals(150f, spots.get(0).centroidY, 20f);
        assertEquals(260f, spots.get(1).centroidY, 20f);
    }

    @Test
    public void detect_shapeAwareMode_keepsGenuineSingleStreakAsOneSpot() {
        // A genuinely single tailing compound: intensity ramps down monotonically from
        // a bright cap to a dim tail with no internal valley — the actual target case
        // for shape-aware detection (unlike the two-peak dumbbell above). Legacy
        // detection only catches the brighter cap; hysteresis linking recovers the full
        // tail; watershed must find exactly one peak (no local minimum to split at).
        int w = 200, h = 500;
        float cx = 100, topCy = 90, bottomCy = 190, halfWidth = 18;
        float bgLevel = 10f, topLevel = 200f, bottomLevel = 50f;
        float[] pixels = new float[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = bgLevel;
        for (int y = (int) topCy; y <= (int) bottomCy; y++) {
            float frac = (y - topCy) / (bottomCy - topCy);
            float v = topLevel + (bottomLevel - topLevel) * frac; // monotonic decreasing
            for (int x = (int) (cx - halfWidth); x <= (int) (cx + halfWidth); x++) {
                pixels[y * w + x] = v;
            }
        }
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);

        List<Spot> legacySpots = SpotDetector.detect(fp, 5.0f, false);
        assertEquals("Legacy should only catch the brighter cap, not the full tail",
            1, legacySpots.size());
        assertTrue("Legacy spot's radius should be smaller than the full streak length",
            legacySpots.get(0).radius < (bottomCy - topCy) / 2f);

        List<Spot> shapeAwareSpots = SpotDetector.detect(fp, 5.0f, true);
        assertEquals("A genuine single streak (no internal valley) must stay one spot",
            1, shapeAwareSpots.size());
        Spot s = shapeAwareSpots.get(0);
        assertTrue("Merged spot should carry a mask", s.hasMask());
        assertTrue("Mask should cover most of the streak's length", s.maskH > 80);
    }

    @Test
    public void detect_shapeAwareMode_faintSpotWithNoiseBumpStaysOneSpot() {
        // Regression for an interactively-found bug: a small/faint candidate region has
        // a small dynamic range by construction, so a tolerance that's only a *fraction*
        // of that range can be smaller than ordinary pixel noise, causing a spurious
        // split. A single small noise bump inside an otherwise-uniform faint blob must
        // NOT be reported as a second spot once the absolute tolerance floor
        // (MIN_TOLERANCE_STDDEV_MULTIPLE) is applied.
        int w = 200, h = 300;
        float cx = 100, cy = 150, r = 15, bg = 10f, blobLevel = 90f, bumpLevel = 110f;
        float[] pixels = new float[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = bg;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r * r) pixels[y * w + x] = blobLevel;
            }
        }
        // A tiny 2x2 noise bump off-centre within the blob.
        for (int y = 148; y <= 149; y++) {
            for (int x = 104; x <= 105; x++) {
                pixels[y * w + x] = bumpLevel;
            }
        }
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);

        List<Spot> spots = SpotDetector.detect(fp, 8.0f, true);
        assertEquals("A small noise bump inside a faint blob must not create a second spot",
            1, spots.size());
    }

    @Test
    public void detect_shapeAwareMode_faintSpotStaysOneSpotAtDefaultMultiplier() {
        // Regression for the specific bug found interactively (see CLAUDE.md,
        // "Real-plate exploratory test"): the tolerance floor was originally
        // primaryThreshold - mean = mean * (multiplier - 1), which is exactly ZERO at
        // multiplier = 1.0 — the default — so it provided no protection at all in the
        // most common case. Must not regress: a faint blob with a small noise bump must
        // stay one spot even at the default multiplier.
        int w = 200, h = 300;
        float cx = 100, cy = 150, r = 15, bg = 10f, blobLevel = 40f, bumpLevel = 55f;
        float[] pixels = new float[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = bg;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r * r) pixels[y * w + x] = blobLevel;
            }
        }
        for (int y = 148; y <= 149; y++) {
            for (int x = 104; x <= 105; x++) {
                pixels[y * w + x] = bumpLevel;
            }
        }
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);

        List<Spot> spots = SpotDetector.detect(fp, 1.0f, true);
        assertEquals("A faint spot with a small noise bump must not split at multiplier=1.0",
            1, spots.size());
    }

    @Test
    public void detect_shapeAwareMode_doesNotMergeGenuinelySeparateSpots() {
        // Safety-net regression: two round spots with a plain background gap (no
        // elevated bridge at all) must remain two spots under shape-aware mode too —
        // hysteresis linking must not over-merge unrelated neighbours.
        FloatProcessor fp = syntheticSpotImage(200, 500, 100, 150, 18, 50f, 200f);
        // Overlay a second, independent blob far away in the same image.
        float[] pixels = (float[]) fp.getPixels();
        int w = fp.getWidth(), h = fp.getHeight();
        float cx = 100, cy2 = 350, r = 18;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy2;
                if (dx * dx + dy * dy <= r * r) pixels[y * w + x] = 200f;
            }
        }
        List<Spot> spots = SpotDetector.detect(fp, 1.0f, true);
        assertEquals("Unconnected spots must stay distinct under shape-aware mode",
            2, spots.size());
    }

    @Test
    public void morphologicalOpen_removesSmallNoise() {
        int w = 10, h = 10;
        boolean[] binary = new boolean[w * h];
        // Large foreground region
        for (int y = 2; y < 8; y++)
            for (int x = 2; x < 8; x++)
                binary[y * w + x] = true;
        // Single isolated noise pixel
        binary[0] = true;

        boolean[] opened = SpotDetector.morphologicalOpen(binary, w, h, 2);

        // Noise pixel should be erased
        assertFalse("Isolated pixel should be removed by opening", opened[0]);
        // Core of large region should survive
        assertTrue("Centre of large region should survive", opened[5 * w + 5]);
    }
}
