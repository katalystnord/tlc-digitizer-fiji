package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;

import static org.junit.Assert.*;

public class SpotIntegratorTest {

    @Test
    public void integrateSpot_uniformPatch_correctSum() {
        // 50×50 image, uniform value of 100 in a circle of radius 10
        int w = 50, h = 50;
        float[] pixels = new float[w * h];
        float spotVal = 100f;
        float cx = 25, cy = 25, r = 10;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r * r) {
                    pixels[y * w + x] = spotVal;
                }
            }
        }

        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        double result = SpotIntegrator.integrateSpot(fp, cx, cy, r);

        // All pixels in the circle are uniform, so top 15% should be:
        // nPixels ≈ π*r² ≈ 314, top 15% ≈ 47, sum ≈ 47 * 100 = 4700
        double pixelCount = Math.PI * r * r;
        double expectedMin = pixelCount * SpotIntegrator.TOP_FRACTION * spotVal * 0.8;
        double expectedMax = pixelCount * SpotIntegrator.TOP_FRACTION * spotVal * 1.2;

        assertTrue("Integration should be in expected range [" + expectedMin + ", " + expectedMax + "]; got " + result,
                   result >= expectedMin && result <= expectedMax);
    }

    @Test
    public void integrateSpot_emptyRegion_returnsZero() {
        int w = 50, h = 50;
        float[] pixels = new float[w * h];
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        double result = SpotIntegrator.integrateSpot(fp, 25, 25, 10);
        assertEquals(0.0, result, 1e-9);
    }

    @Test
    public void integrateSpot_linearRelationship() {
        // Doubling the spot intensity should double the integration value
        int w = 60, h = 60;
        float cx = 30, cy = 30, r = 12;

        float[] px1 = new float[w * h];
        float[] px2 = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r * r) {
                    px1[y * w + x] = 50f;
                    px2[y * w + x] = 100f;
                }
            }
        }

        FloatProcessor fp1 = new FloatProcessor(w, h, px1, null);
        FloatProcessor fp2 = new FloatProcessor(w, h, px2, null);

        double v1 = SpotIntegrator.integrateSpot(fp1, cx, cy, r);
        double v2 = SpotIntegrator.integrateSpot(fp2, cx, cy, r);

        assertEquals("Doubling intensity should double integration", 2.0, v2 / v1, 0.01);
    }

    @Test
    public void integrate_updatesSpotField() {
        int w = 60, h = 60;
        float[] pixels = new float[w * h];
        for (int y = 15; y < 45; y++)
            for (int x = 15; x < 45; x++)
                pixels[y * w + x] = 100f;

        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        Spot spot = new Spot(0, 30, 30, 12, h);
        assertTrue(Double.isNaN(spot.integrationValue));

        SpotIntegrator.integrate(fp, spot);
        assertFalse(Double.isNaN(spot.integrationValue));
        assertTrue(spot.integrationValue > 0);
    }

    @Test
    public void integrate_maskedSpot_usesMaskNotCircle() {
        // 10x10 image; an L-shaped mask (not expressible as a circle) with a known
        // uniform value, positioned so the equal-area circular radius would clip a
        // corner — proves integration follows the mask, not spot.radius.
        int w = 10, h = 10;
        float[] pixels = new float[w * h]; // zero-initialised background
        // L-shape: row y=5 full width 0..5, plus column x=0 full height 0..5
        boolean[] mask = new boolean[5 * 5]; // 5x5 bounding box at (0,0)
        for (int i = 0; i < 5; i++) mask[4 * 5 + i] = true; // bottom row
        for (int j = 0; j < 5; j++) mask[j * 5 + 0] = true; // left column
        int maskPixelCount = 5 + 5 - 1; // corner counted once

        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 5; i++) {
                if (mask[j * 5 + i]) pixels[(0 + j) * w + (0 + i)] = 40f;
            }
        }
        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);

        Spot masked = new Spot(1, 2f, 2f, 3f, h, mask, 0, 0, 5, 5, 0f, 0f, 0f);
        SpotIntegrator.integrate(fp, masked);

        int cutoff = Math.max(1, (int) (maskPixelCount * SpotIntegrator.TOP_FRACTION));
        double expected = cutoff * 40.0;
        assertEquals("Masked integration should sum top-15% of the L-shape, not a circle",
            expected, masked.integrationValue, 1e-6);
    }
}
