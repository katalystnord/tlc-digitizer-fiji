package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration-level regression tests: build a synthetic post-warp plate ({@link SyntheticPlate},
 * smooth Gaussian features, no hard edges) with known ground truth, run it through the REAL
 * {@link BackgroundCorrection} → {@link SpotDetector} pipeline (not isolated unit-level helpers),
 * and assert against that known truth.
 *
 * <p>Motivation: existing unit tests use idealized flat-background hard-edged circles and test
 * {@code SpotDetector} in isolation. Several real bugs this project found (streak-splitting,
 * ruler-band false positives, watershed tolerance floors) only surfaced via interactive testing
 * against real photos, which have no fixed numeric ground truth to regress against. These tests
 * are a fixed, versioned middle ground: realistic defects, exact known truth, full pipeline.
 */
public class SyntheticPlateIntegrationTest {

    /** Same [0,255] max-normalisation ValidationRunner applies before detection: background
     * correction leaves background≈0 and mean×multiplier would threshold almost everything
     * without rescaling the sparse positive residuals first. */
    private static FloatProcessor normalize(FloatProcessor corrected) {
        float[] px = (float[]) corrected.getPixels();
        float max = 0;
        for (float v : px) if (v > max) max = v;
        if (max <= 0) return corrected;
        float scale = 255f / max;
        float[] out = new float[px.length];
        for (int i = 0; i < px.length; i++) out[i] = px[i] * scale;
        return new FloatProcessor(corrected.getWidth(), corrected.getHeight(), out, null);
    }

    @Test
    public void rulerBandExcluded_nearOriginSpotKept() {
        int w = 300, h = 600;
        float originYFraction = 0.85f, frontYFraction = 0.15f;
        float spotX = 150, spotY = 480; // fraction 0.80 -- strictly inside (front, origin)
        float rulerY = 565; // fraction ~0.942 -- beyond the origin line, like a real plate's ruler margin

        FloatProcessor warped = new SyntheticPlate(w, h, 20f)
                .addSpot(spotX, spotY, 10f, 150f)
                .addRulerBand(rulerY, 6f, 150f, 5, 0.08f)
                .addNoise(2f, 1)
                .build();

        FloatProcessor corrected = BackgroundCorrection.fitAndSubtract(warped);
        FloatProcessor detectionImage = normalize(corrected);

        // Self-check: without the origin/front exclusion, the ruler ticks must actually be
        // "spot-like" enough to register as their own components -- otherwise this test would
        // pass for the wrong reason (ticks filtered out by size/aspect, not by the exclusion).
        List<Spot> withoutExclusion = SpotDetector.detect(detectionImage, 1.0f, false, Float.NaN, Float.NaN);
        assertTrue("Ruler ticks must register as detectable components without the exclusion "
                + "filter, or this test doesn't actually exercise it (found "
                + withoutExclusion.size() + ")", withoutExclusion.size() >= 2);

        List<Spot> withExclusion = SpotDetector.detect(detectionImage, 1.0f, false,
                originYFraction, frontYFraction);
        assertEquals("Only the real near-origin spot should survive the developed-region filter",
                1, withExclusion.size());
        assertEquals(spotX, withExclusion.get(0).centroidX, 15f);
        assertEquals(spotY, withExclusion.get(0).centroidY, 15f);
    }

    @Test
    public void singleTailingStreak_shapeAwareCapturesMoreTailThanLegacy() {
        // A genuinely single tailing compound (smooth monotonic taper, no internal valley --
        // the real dumbbell-shaped "two blobs + dim bridge" bug is a structurally different
        // case, covered by twoRealPeaksInOneLane_shapeAwareFindsBoth below and by the existing
        // SpotDetectorTest unit tests). The real value shape-aware adds here isn't a different
        // *count* -- both modes correctly report one spot -- it's capturing more of the dim
        // tail than legacy's fixed-radius circle can, which is the actual under-integration
        // problem that motivated this feature (see CLAUDE.md's img_00451 writeup).
        int w = 200, h = 500;
        float cx = 100;
        float originYFraction = 0.9f, frontYFraction = 0.1f;

        FloatProcessor warped = new SyntheticPlate(w, h, 10f)
                .addStreak(cx, 90, 410, 18f, 200f, 50f)
                .addNoise(2f, 2)
                .build();

        FloatProcessor corrected = BackgroundCorrection.fitAndSubtract(warped);
        FloatProcessor detectionImage = normalize(corrected);

        List<Spot> legacy = SpotDetector.detect(detectionImage, 1.0f, false,
                originYFraction, frontYFraction);
        assertEquals("A smooth monotonic taper should not spuriously fragment under legacy "
                + "detection either", 1, legacy.size());

        List<Spot> shapeAware = SpotDetector.detect(detectionImage, 1.0f, true,
                originYFraction, frontYFraction);
        assertEquals("Shape-aware hysteresis+watershed must recognise this as one genuinely "
                + "tailing compound", 1, shapeAware.size());
        assertTrue(shapeAware.get(0).hasMask());
        assertTrue("Shape-aware's mask should capture meaningfully more of the tail than "
                + "legacy's fixed circle (2x legacy's diameter as a floor)",
                shapeAware.get(0).maskH > legacy.get(0).radius * 2 * 2);
    }

    @Test
    public void twoRealPeaksInOneLane_shapeAwareFindsBoth() {
        // Two fully-resolved co-eluting compounds with a genuine valley between them --
        // shape-aware must not merge them into one via hysteresis linking, at a threshold
        // clean of background-correction edge artifacts (see class javadoc note below).
        int w = 200, h = 500;
        float cx = 100;
        float originYFraction = 0.9f, frontYFraction = 0.1f;
        float y1 = 150, y2 = 220;

        FloatProcessor warped = new SyntheticPlate(w, h, 10f)
                .addSpot(cx, y1, 18f, 200f)
                .addSpot(cx, y2, 18f, 200f)
                .addNoise(2f, 3)
                .build();

        FloatProcessor corrected = BackgroundCorrection.fitAndSubtract(warped);
        FloatProcessor detectionImage = normalize(corrected);

        // NOTE: at intermediate multipliers (~1.2-1.8) on this specific sparse synthetic
        // image, the quartic polynomial background fit leaves a spurious residual bump far
        // from either real spot (around y~390-415, well past both true peaks) that briefly
        // registers as its own component or two -- an independent, minimal reproduction of
        // the already-documented "polynomial background correction has real edge/residual
        // weaknesses" finding (see validation-runner-architecture memory / CLAUDE.md's MOESM3
        // writeup). 2.0 sits above that artifact band; both real peaks remain well-separated
        // and correctly detected there.
        List<Spot> shapeAware = SpotDetector.detect(detectionImage, 2.0f, true,
                originYFraction, frontYFraction);
        assertEquals("Two resolved co-eluting compounds must be reported as two spots",
                2, shapeAware.size());
        float minCy = Math.min(shapeAware.get(0).centroidY, shapeAware.get(1).centroidY);
        float maxCy = Math.max(shapeAware.get(0).centroidY, shapeAware.get(1).centroidY);
        assertEquals(y1, minCy, 20f);
        assertEquals(y2, maxCy, 20f);

        List<Spot> legacy = SpotDetector.detect(detectionImage, 2.0f, false,
                originYFraction, frontYFraction);
        assertEquals("At this multiplier legacy also already sees both peaks as separate -- "
                + "they don't happen to diverge on this specific geometry; the merge-then-"
                + "split mechanism itself is covered by SpotDetectorTest's isolated unit tests",
                2, legacy.size());
    }
}
