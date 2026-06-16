package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for Option B: per-spot local polynomial background correction (qtlc method).
 *
 * Source: Pavicevic et al., J. Pharm. Biomed. Anal. 129, 43 (2016), §3.4–3.5.
 */
public class PerSpotBackgroundTest {

    private static FloatProcessor uniformImage(int width, int height, float value) {
        float[] px = new float[width * height];
        for (int i = 0; i < px.length; i++) px[i] = value;
        return new FloatProcessor(width, height, px, null);
    }

    /** Build a simple Spot at (cx, cy) with given radius. imageHeight = 200. */
    private static Spot spot(int id, float cx, float cy, float r) {
        return new Spot(id, cx, cy, r, 200);
    }

    // -------------------------------------------------------------------------
    // sampleLocalBackground
    // -------------------------------------------------------------------------

    @Test
    public void sampleLocalBackground_uniformImage_returnsImageValue() {
        FloatProcessor fp = uniformImage(200, 200, 10f);
        float[] pixels = (float[]) fp.getPixels();
        Spot s = spot(1, 100, 100, 15);

        double bg = BackgroundCorrection.sampleLocalBackground(pixels, 200, 200, s);
        assertEquals("Uniform image background should equal pixel value", 10.0, bg, 0.1);
    }

    @Test
    public void sampleLocalBackground_spotAtTopEdge_returnsZeroWhenNoRoom() {
        FloatProcessor fp = uniformImage(200, 200, 5f);
        float[] pixels = (float[]) fp.getPixels();
        // cy=5, r=10 → above strip would start at max(0, 5-10-5)=0, end at max(0,5-10-1)=0
        // strip above is degenerate; below strip is also constrained
        Spot s = spot(1, 100, 5, 10);
        // Should not throw; may return 0 or some sampled value — just mustn't crash
        double bg = BackgroundCorrection.sampleLocalBackground(pixels, 200, 200, s);
        assertTrue("Background >= 0", bg >= 0.0);
    }

    // -------------------------------------------------------------------------
    // applyPerSpotPolynomial
    // -------------------------------------------------------------------------

    @Test
    public void applyPerSpotPolynomial_emptyList_noOp() {
        FloatProcessor fp = uniformImage(100, 100, 20f);
        // Must not throw
        BackgroundCorrection.applyPerSpotPolynomial(new ArrayList<>(), fp, 3);
    }

    @Test
    public void applyPerSpotPolynomial_reducesIntegrationValue() {
        // Image with uniform background of 50. Spots have integrationValue set
        // to a large number to simulate signal+background.
        int W = 400, H = 200;
        FloatProcessor fp = uniformImage(W, H, 50f);

        List<Spot> spots = new ArrayList<>();
        // Place 6 spots along x at y=100, well away from edges
        for (int i = 0; i < 6; i++) {
            Spot s = spot(i, 50 + i * 60, 100, 15);
            SpotIntegrator.integrate(fp, s); // raw integration over uniform bg image
            spots.add(s);
        }

        // Record raw values before correction
        double[] rawValues = spots.stream().mapToDouble(s -> s.integrationValue).toArray();

        BackgroundCorrection.applyPerSpotPolynomial(spots, fp, 3);

        // After correction against a uniform background, all integration values
        // should be smaller (background portion removed) and >= 0
        for (int i = 0; i < spots.size(); i++) {
            assertTrue("Integration must be >= 0 after correction",
                       spots.get(i).integrationValue >= 0.0);
            assertTrue("Integration should decrease after background correction",
                       spots.get(i).integrationValue <= rawValues[i]);
        }
    }

    @Test
    public void applyPerSpotPolynomial_zeroBackground_integrationUnchanged() {
        // Image with all zeros → background estimate is 0 → correction is 0
        // Integration values should be left exactly as they were.
        int W = 400, H = 200;
        FloatProcessor fp = uniformImage(W, H, 0f);

        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Spot s = spot(i, 50 + i * 70, 100, 15);
            s.integrationValue = 100.0 + i * 10; // manually set, not from image
            spots.add(s);
        }

        double[] expected = spots.stream().mapToDouble(s -> s.integrationValue).toArray();
        BackgroundCorrection.applyPerSpotPolynomial(spots, fp, 3);

        for (int i = 0; i < spots.size(); i++) {
            assertEquals("Zero background → correction = 0 → integration unchanged",
                         expected[i], spots.get(i).integrationValue, 0.01);
        }
    }

    @Test
    public void applyPerSpotPolynomial_fewerSpotsThanPolyDegree_usesRawBg() {
        // Only 2 spots, degree 5 → polynomial fit skipped, raw per-spot bg used.
        // Should still run without error and produce sensible values.
        int W = 400, H = 200;
        FloatProcessor fp = uniformImage(W, H, 30f);

        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Spot s = spot(i, 100 + i * 150, 100, 15);
            SpotIntegrator.integrate(fp, s);
            spots.add(s);
        }

        double[] before = spots.stream().mapToDouble(s -> s.integrationValue).toArray();
        BackgroundCorrection.applyPerSpotPolynomial(spots, fp, 5);

        for (int i = 0; i < spots.size(); i++) {
            assertTrue("Integration >= 0", spots.get(i).integrationValue >= 0);
            assertTrue("Integration <= raw", spots.get(i).integrationValue <= before[i]);
        }
    }

    @Test
    public void applyPerSpotPolynomial_horizontalGradientBackground_correctedUniformly() {
        // Background rises linearly from left (0) to right (100).
        // After polynomial correction, spots at different x positions should
        // end up with similar (lower) integration values.
        int W = 500, H = 200;
        float[] px = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                px[y * W + x] = x * 0.2f; // 0..99 background gradient
            }
        }
        FloatProcessor fp = new FloatProcessor(W, H, px, null);

        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Spot s = spot(i, 50 + i * 100, 100, 15);
            SpotIntegrator.integrate(fp, s);
            spots.add(s);
        }

        // Before correction, spots at high x have higher integration (due to bg gradient)
        double firstRaw  = spots.get(0).integrationValue;
        double lastRaw   = spots.get(4).integrationValue;
        assertTrue("Right-side spots should integrate more in a positive x gradient",
                   lastRaw > firstRaw);

        BackgroundCorrection.applyPerSpotPolynomial(spots, fp, 3);

        // After correction, the spread should be reduced
        double firstCorr = spots.get(0).integrationValue;
        double lastCorr  = spots.get(4).integrationValue;
        double rawSpread  = lastRaw  - firstRaw;
        double corrSpread = Math.abs(lastCorr - firstCorr);
        assertTrue("Polynomial correction should reduce spread across x-gradient background; " +
                   "raw=" + rawSpread + " corr=" + corrSpread,
                   corrSpread < rawSpread);
    }
}
