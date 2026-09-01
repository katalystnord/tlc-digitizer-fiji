/*
 * TLC Digitizer — Fiji/ImageJ plugin
 * Copyright (C) 2025 David Sandquist, Katalyst Nord AB
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package se.katalystnord.tlcdigitizer.validation;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;
import se.katalystnord.tlcdigitizer.pipeline.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Detection-only ground-truth regression test for {@code img_00451} — a real, hard,
 * off-axis UV254 plate with two tailing/streaking lanes (see CLAUDE.md's "Real-plate
 * exploratory test" and the shape-aware/lane-detection/Labkit writeups; this specific
 * plate has been the primary real-world testbed for all three beta detection features).
 *
 * <p>Unlike {@link ValidationFixture}/{@link ValidationRunner} (MOESM2-4), this plate has
 * no known concentrations — there was never a calibration/recovery ground truth available
 * for it, only visual judgement each time a new feature was tried against it. This test
 * instead uses the exact corner/background/threshold parameters and the 8 confirmed spot
 * positions from a real prior interactive session's CSV export
 * ({@code img_00451-e1488277851441-digitized.csv}, legacy mean-threshold detection, David's
 * own manually-corrected result) as a fixed, numeric geometry target — so any future change
 * to the legacy detection pipeline that regresses on this specific hard photo gets caught
 * automatically, instead of requiring a fresh interactive re-test and screenshot judgement.
 *
 * <p><b>Caveat, stated plainly:</b> this is legacy detection's own output from an
 * interactive session (refined by whatever manual add/remove clicks were made that
 * session) — the best human-confirmed record available for this plate, not an
 * independently re-verified pixel-by-pixel annotation. Treat it as a strong regression
 * baseline for the legacy path, not as absolute ground truth for judging the beta
 * detection methods' correctness.
 *
 * <p><b>One unlogged assumption, flagged explicitly:</b> the source CSV records
 * {@code invertImage=true} but does not log which grayscale channel was used (green vs.
 * luminance) — {@link se.katalystnord.tlcdigitizer.export.CsvExporter} doesn't export this
 * field (a real, separate gap, same class of issue as Labkit's own unlogged training-region
 * coordinates). Green channel is used here per CLAUDE.md's own stains-guide convention for
 * UV254 quenching plates (this plate's stated imaging mode) — if this test's positions
 * don't line up well, that assumption is the first thing to revisit.
 *
 * <p>Unlike the MOESM plates, this photo is our own (not third-party copyrighted journal
 * supplementary material), so it's small enough and safe to commit directly at
 * {@code validation/img_00451/} rather than live outside the repository — still gated behind
 * a file-presence {@code Assume} (same defensive pattern as {@link ValidationTest}'s
 * {@code -Dvalidation.data.dir} gate) in case it's ever missing from a given checkout.
 */
public class Img00451DetectionRegressionTest {

    private static final Path IMAGE_PATH =
            Paths.get("validation/img_00451/img_00451-e1488277851441.jpg");

    // Exact parameters from img_00451-e1488277851441-digitized.csv (2026-07-12 interactive session).
    private static final float[] CORNERS = {
            168.6307f, 244.35832f,   // top-left
            1859.1796f, 337.08978f,  // top-right
            1594.2563f, 2661.0747f,  // bottom-right
            169.44188f, 2575.4824f  // bottom-left
    };
    private static final boolean INVERT_IMAGE = true;
    private static final float TOP_HAT_SE_RADIUS = 126.0f;
    private static final float THRESHOLD_FACTOR = 1.0f;
    private static final float ORIGIN_Y_FRACTION = 0.9f;
    private static final float FRONT_Y_FRACTION = 0.1f;

    /** {centroid_x_fraction, centroid_y_fraction} per spot, from the CSV, in detection order. */
    private static final float[][] EXPECTED_SPOTS = {
            {0.191451f, 0.745271f},
            {0.207874f, 0.558415f},
            {0.288192f, 0.739061f},
            {0.407367f, 0.372156f},
            {0.525995f, 0.452129f},
            {0.631842f, 0.449851f},
            {0.638693f, 0.740603f},
            {0.729817f, 0.545044f},
    };

    /**
     * {@code radius_fraction} per spot, from the same CSV, in the same order as
     * {@link #EXPECTED_SPOTS}.
     *
     * <p><b>Why this exists.</b> Position matching alone passes even when the fitted circles are
     * visibly wrong -- during a 2026-08-11 screenshot session the detector produced grossly
     * oversized, mutually overlapping circles at these exact parameters and every position
     * assertion still passed, because radius was never checked. These values were then measured
     * from a fresh run and found to be bit-identical to the CSV's, confirming the recorded
     * fixture is the detector's own untouched output rather than a hand-corrected one, and that
     * nothing had drifted.
     *
     * <p>So this is a characterisation assertion: it pins what the detector currently does, not
     * what it ideally should do. Spots 4 and 8 (r ≈ 0.058) genuinely do overlap their
     * neighbours -- that is a real, open weakness of fixed-circle legacy detection on streaking
     * lanes, not something this test endorses. If a future change improves those radii, this
     * test SHOULD fail, and the fixture should be re-recorded deliberately.
     */
    private static final float[] EXPECTED_RADII = {
            0.021697f, 0.054083f, 0.025759f, 0.018491f,
            0.034096f, 0.035165f, 0.028752f, 0.055472f,
    };

    /** Radius agreement, relative. Tight (2%) on purpose: radii were verified to reproduce
     * exactly, so any real movement here is a genuine behaviour change worth failing on.
     *
     * <p>Re-recorded 2026-09-01 when detection moved to raw sRGB (see CLAUDE.md, "Colour
     * space -- two images, not one"). Every radius shrank -- 0.0245/0.0583/0.0288/0.0242/
     * 0.0409/0.0404/0.0432/0.0584 previously -- because the quartic background fit no
     * longer leaves a residual gradient inflating the thresholded regions. All 8 spot
     * POSITIONS were unaffected by the change, and this is still a characterisation test:
     * it pins what the detector does, not what it should do. */
    private static final float RADIUS_TOLERANCE_RELATIVE = 0.02f;

    /** Match tolerance as a fraction of image width/height -- generous (3%) since this is a
     * regression guard against gross pipeline changes, not a precision check. */
    private static final float MATCH_TOLERANCE_FRACTION = 0.03f;

    /** Shared corrected/detection image, computed once (loading + top-hat on the full-size
     * real photo takes ~90s) rather than per test method. Null if the source photo isn't
     * present -- every {code @}Test method must {@code Assume.assumeTrue} on this before use. */
    private static FloatProcessor corrected; // Stage 3 output, un-normalised -- what LaneDetector consumes
    private static FloatProcessor detectionImage; // corrected, normalised to [0,255] -- what SpotDetector consumes
    private static int width, height;

    @BeforeClass
    public static void setUp() throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (!Files.isReadable(IMAGE_PATH)) return; // tests below Assume-skip in this case

        BufferedImage bi = ImageIO.read(IMAGE_PATH.toFile());
        ImagePlus imp = new ImagePlus("img_00451", bi);

        // Raw sRGB, matching the shipping detection path. The 1-arg overload linearises
        // and would pin a colour space the product no longer detects in.
        FloatProcessor gray = ImagePreparation.extractGreenChannel(imp, false);
        if (INVERT_IMAGE) {
            float[] px = (float[]) gray.getPixels();
            for (int i = 0; i < px.length; i++) px[i] = 255.0f - px[i];
        }
        FloatProcessor warped = PerspectiveCorrection.warpImage(gray, CORNERS);
        corrected = BackgroundCorrection.topHat(warped, TOP_HAT_SE_RADIUS);
        width = corrected.getWidth();
        height = corrected.getHeight();

        float[] cpx = (float[]) corrected.getPixels();
        float cmax = 0;
        for (float v : cpx) if (v > cmax) cmax = v;
        float[] norm = new float[cpx.length];
        float scale = cmax > 0 ? 255f / cmax : 1f;
        for (int i = 0; i < cpx.length; i++) norm[i] = cpx[i] * scale;
        detectionImage = new FloatProcessor(width, height, norm, null);
    }

    /** Result of matching detected spots against {@link #EXPECTED_SPOTS}: nearest-neighbour,
     * one-to-one, within {@link #MATCH_TOLERANCE_FRACTION}. */
    private static final class MatchResult {
        final int matched;
        final List<String> misses;
        final int falsePositives; // detected spots not claimed by any expected position

        MatchResult(int matched, List<String> misses, int falsePositives) {
            this.matched = matched;
            this.misses = misses;
            this.falsePositives = falsePositives;
        }
    }

    private static MatchResult match(List<Spot> detected) {
        float tolPx = MATCH_TOLERANCE_FRACTION * Math.max(width, height);
        boolean[] usedDetected = new boolean[detected.size()];
        List<String> misses = new ArrayList<>();
        int matched = 0;

        for (float[] expected : EXPECTED_SPOTS) {
            float tx = expected[0] * width, ty = expected[1] * height;
            int bestIdx = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < detected.size(); i++) {
                if (usedDetected[i]) continue;
                float dx = detected.get(i).centroidX - tx;
                float dy = detected.get(i).centroidY - ty;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < bestD) { bestD = d; bestIdx = i; }
            }
            if (bestIdx >= 0 && bestD <= tolPx) {
                usedDetected[bestIdx] = true;
                matched++;
            } else {
                misses.add(String.format("expected (%.3f, %.3f) -- nearest unused detection at %.1fpx away",
                        expected[0], expected[1], bestD));
            }
        }

        int falsePositives = 0;
        for (boolean used : usedDetected) if (!used) falsePositives++;
        return new MatchResult(matched, misses, falsePositives);
    }

    @Test
    public void legacyDetection_stillFindsAllEightKnownSpots() {
        Assume.assumeTrue("Skipping: img_00451 source photo not present at " + IMAGE_PATH,
                detectionImage != null);

        List<Spot> detected = SpotDetector.detect(detectionImage, THRESHOLD_FACTOR, false,
                ORIGIN_Y_FRACTION, FRONT_Y_FRACTION);
        MatchResult result = match(detected);

        assertTrue("Legacy detection should still find all 8 previously-confirmed spots on "
                + "img_00451 within " + (MATCH_TOLERANCE_FRACTION * 100) + "% tolerance. Misses:\n"
                + String.join("\n", result.misses) + "\nDetected " + detected.size() + " spots total.",
                result.misses.isEmpty());
    }

    /**
     * Pins the fitted circle radii, not just the centroids.
     *
     * <p>Positions alone are not enough: the same detection run can place all 8 centroids
     * correctly while sizing their circles badly enough to swallow neighbouring spots, and the
     * position-only assertions above pass regardless. See {@link #EXPECTED_RADII} for how that
     * gap was found and why these particular numbers are trustworthy as a baseline.
     */
    @Test
    public void legacyDetection_radiiMatchTheRecordedFixture() {
        Assume.assumeTrue("Skipping: img_00451 source photo not present at " + IMAGE_PATH,
                detectionImage != null);

        List<Spot> detected = SpotDetector.detect(detectionImage, THRESHOLD_FACTOR, false,
                ORIGIN_Y_FRACTION, FRONT_Y_FRACTION);
        float maxDim = Math.max(width, height);
        float tolPx = MATCH_TOLERANCE_FRACTION * maxDim;

        for (int i = 0; i < EXPECTED_SPOTS.length; i++) {
            float tx = EXPECTED_SPOTS[i][0] * width, ty = EXPECTED_SPOTS[i][1] * height;
            Spot nearest = null;
            float bestD = Float.MAX_VALUE;
            for (Spot s : detected) {
                float dx = s.centroidX - tx, dy = s.centroidY - ty;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < bestD) { bestD = d; nearest = s; }
            }
            assertTrue(String.format(
                    "No detection near expected spot %d at (%.3f, %.3f); nearest was %.1fpx away",
                    i + 1, EXPECTED_SPOTS[i][0], EXPECTED_SPOTS[i][1], bestD),
                    nearest != null && bestD <= tolPx);

            float actual = nearest.radius / maxDim;
            float expected = EXPECTED_RADII[i];
            assertEquals(String.format(
                    "Spot %d radius drifted from the recorded fixture (expected %.6f, got %.6f "
                    + "as a fraction of the image's longest side). If this change is intended, "
                    + "re-record EXPECTED_RADII deliberately rather than widening the tolerance.",
                    i + 1, expected, actual),
                    expected, actual, expected * RADIUS_TOLERANCE_RELATIVE);
        }
    }

    /**
     * Scores shape-aware detection against the same 8 known positions. Empirically, at
     * these exact parameters shape-aware matches all 8 with zero false positives -- a
     * genuinely good result, consistent with CLAUDE.md's own Follow-up 3 account of this
     * plate ("the floating spot no longer splits... lane 4's clean single-streak capture...
     * lane-6 oval stays a single well-isolated spot"). This test's value isn't proving that
     * again -- it's turning it into an exact, versioned number future shape-aware changes
     * get checked against, replacing a fresh screenshot judgement call. If a future change
     * regresses this count, that's a real signal to investigate, not necessarily grounds to
     * just update the expected number.
     */
    @Test
    public void shapeAwareDetection_matchesAllKnownSpotsWithNoFalsePositives() {
        Assume.assumeTrue("Skipping: img_00451 source photo not present at " + IMAGE_PATH,
                detectionImage != null);

        List<Spot> detected = SpotDetector.detect(detectionImage, THRESHOLD_FACTOR, true,
                ORIGIN_Y_FRACTION, FRONT_Y_FRACTION);
        MatchResult result = match(detected);

        assertEquals("Shape-aware should match all 8 known img_00451 positions. Misses:\n"
                + String.join("\n", result.misses), 8, result.matched);
        assertEquals("Shape-aware should produce no false-positive spots at these parameters",
                0, result.falsePositives);
    }

    /**
     * Turns CLAUDE.md's already-documented qualitative finding into an exact, versioned
     * regression/characterization number -- and, in doing so, corrects it. CLAUDE.md's
     * "Known remaining limitation — irregular real lane layouts" section recalled "still
     * only 2 lanes detected, not 6" from an earlier interactive/visual pass; precisely
     * re-running the exact CSV-recorded parameters headlessly gives <b>4</b>, not 2 --
     * exactly the kind of fuzzy-recollection-vs-exact-number gap this whole synthetic/
     * regression-fixture effort was built to catch (see the "Synthetic-plate integration
     * tests" section of CLAUDE.md). CLAUDE.md has been corrected to say 4.
     *
     * <p>This plate's true layout is genuinely non-periodic (two dominant tailing streaks
     * plus several smaller, irregularly-occupied lanes) -- no periodicity-based method,
     * including this one, is expected to recover the visual 6-lane count from it; that's a
     * real, currently-unsolved limitation (deliberately deferred pending a
     * non-periodicity-based strategy), NOT a bug this test is asserting away. Its value is
     * tracking the number exactly, so a future fix attempt has an automatic before/after
     * instead of a fresh visual re-inspection.
     */
    @Test
    public void laneDetection_currentCountOnIrregularRealLayout() {
        Assume.assumeTrue("Skipping: img_00451 source photo not present at " + IMAGE_PATH,
                corrected != null);

        List<se.katalystnord.tlcdigitizer.model.Lane> lanes =
                LaneDetector.detect(corrected, ORIGIN_Y_FRACTION);

        assertEquals("Current lane count on img_00451's genuinely non-periodic real layout "
                + "-- see this test's javadoc before assuming a change here is a regression "
                + "(it may be a genuine fix, since 6 is the true visual count)",
                4, lanes.size());
    }
}
