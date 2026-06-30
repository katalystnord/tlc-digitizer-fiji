package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the quartic polynomial background fitting.
 *
 * Ground truth: the polyTerms function must match TLCyzer's coord_to_poly exactly.
 * Ref test case from TLCyzer: coord_to_poly(2, 3) = [2,3,4,6,9,8,12,18,27,16,24,36,54,81]
 */
public class BackgroundCorrectionTest {

    @Test
    public void polyTerms_matchesTlcyzerReferenceCase() {
        double[] terms = BackgroundCorrection.polyTerms(2.0, 3.0);
        double[] expected = {2, 3, 4, 6, 9, 8, 12, 18, 27, 16, 24, 36, 54, 81};

        assertEquals("Should produce 14 terms", 14, terms.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Term " + i, expected[i], terms[i], 1e-9);
        }
    }

    @Test
    public void polyTerms_atOrigin_allZero() {
        double[] terms = BackgroundCorrection.polyTerms(0.0, 0.0);
        for (double t : terms) {
            assertEquals(0.0, t, 1e-9);
        }
    }

    @Test
    public void fitAndSubtract_linearGradient_becomesFlat() {
        // Create a 40×40 image with a horizontal linear gradient (background-like)
        int w = 40, h = 40;
        float[] pixels = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = x * 2.0f; // pure horizontal ramp
            }
        }
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        FloatProcessor corrected = BackgroundCorrection.fitAndSubtract(fp);

        float[] out = (float[]) corrected.getPixels();

        // After subtracting a fitted linear background, residuals should be near zero
        double maxResidual = 0;
        for (float v : out) maxResidual = Math.max(maxResidual, Math.abs(v));

        assertTrue("Residuals should be < 5 after background subtraction of a linear gradient; got " + maxResidual,
                   maxResidual < 5.0);
    }

    @Test
    public void topHat_removesBackground_preservesSpot() {
        // Image: smooth ramp background (0–100) with a single bright spot (+80) in the centre.
        int w = 60, h = 60;
        float[] pixels = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = x * (100f / w); // horizontal gradient background
            }
        }
        // Inject a 5×5 bright spot at centre (30, 30)
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                pixels[(30 + dy) * w + (30 + dx)] += 80f;
            }
        }

        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        FloatProcessor result = BackgroundCorrection.topHat(fp, 12f); // SE > spot, < image

        float[] out = (float[]) result.getPixels();

        // Background pixels far from the spot should be near zero
        double bgMax = 0;
        for (int x = 0; x < 10; x++) {
            bgMax = Math.max(bgMax, out[5 * w + x]);
        }
        assertTrue("Background should be near zero after top-hat; got " + bgMax, bgMax < 10.0);

        // Spot centre should have a positive residual
        float spotPeak = out[30 * w + 30];
        assertTrue("Spot should remain positive after top-hat; got " + spotPeak, spotPeak > 40f);
    }

    @Test
    public void fitCoefficients_returnsFifteenParameters() {
        int w = 20, h = 20;
        float[] pixels = new float[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = i * 0.1f;

        double[] coeffs = BackgroundCorrection.fitCoefficients(pixels, w, h);
        assertEquals("OLS with intercept returns 15 parameters (1 intercept + 14 poly terms)", 15, coeffs.length);
    }
}
