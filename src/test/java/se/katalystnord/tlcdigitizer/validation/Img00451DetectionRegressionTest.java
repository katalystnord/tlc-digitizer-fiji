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
 * <p>Gated behind the real JPEG's presence on disk (same pattern as {@link ValidationTest}'s
 * {@code -Dvalidation.data.dir} gate) since the source photo lives outside the repository.
 */
public class Img00451DetectionRegressionTest {

    private static final Path IMAGE_PATH =
            Paths.get("/home/david/code/img_00451-e1488277851441.jpg");

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

    /** Match tolerance as a fraction of image width/height -- generous (3%) since this is a
     * regression guard against gross pipeline changes, not a precision check. */
    private static final float MATCH_TOLERANCE_FRACTION = 0.03f;

    @BeforeClass
    public static void configureHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void legacyDetection_stillFindsAllEightKnownSpots() throws Exception {
        Assume.assumeTrue("Skipping: img_00451 source photo not present at " + IMAGE_PATH,
                Files.isReadable(IMAGE_PATH));

        BufferedImage bi = ImageIO.read(IMAGE_PATH.toFile());
        assertNotNull("ImageIO could not read " + IMAGE_PATH, bi);
        ImagePlus imp = new ImagePlus("img_00451", bi);

        FloatProcessor gray = ImagePreparation.extractGreenChannel(imp);
        if (INVERT_IMAGE) {
            float[] px = (float[]) gray.getPixels();
            for (int i = 0; i < px.length; i++) px[i] = 255.0f - px[i];
        }
        FloatProcessor warped = PerspectiveCorrection.warpImage(gray, CORNERS);
        FloatProcessor corrected = BackgroundCorrection.topHat(warped, TOP_HAT_SE_RADIUS);

        float[] cpx = (float[]) corrected.getPixels();
        float cmax = 0;
        for (float v : cpx) if (v > cmax) cmax = v;
        float[] norm = new float[cpx.length];
        float scale = cmax > 0 ? 255f / cmax : 1f;
        for (int i = 0; i < cpx.length; i++) norm[i] = cpx[i] * scale;
        FloatProcessor detectionImage =
                new FloatProcessor(corrected.getWidth(), corrected.getHeight(), norm, null);

        List<Spot> detected = SpotDetector.detect(detectionImage, THRESHOLD_FACTOR, false,
                ORIGIN_Y_FRACTION, FRONT_Y_FRACTION);

        int width = corrected.getWidth(), height = corrected.getHeight();
        float tolPx = MATCH_TOLERANCE_FRACTION * Math.max(width, height);

        List<String> misses = new ArrayList<>();
        boolean[] usedDetected = new boolean[detected.size()];
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
            } else {
                misses.add(String.format("expected (%.3f, %.3f) -- nearest unused detection at %.1fpx away",
                        expected[0], expected[1], bestD));
            }
        }

        assertTrue("Legacy detection should still find all 8 previously-confirmed spots on "
                + "img_00451 within " + (MATCH_TOLERANCE_FRACTION * 100) + "% tolerance. Misses:\n"
                + String.join("\n", misses) + "\nDetected " + detected.size() + " spots total.",
                misses.isEmpty());
    }
}
