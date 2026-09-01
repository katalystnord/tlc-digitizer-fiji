package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CalibrationModelTest {

    private Spot makeRef(int id, double integration, double concentration) {
        Spot s = new Spot(id, 10, 10, 5, 100);
        s.integrationValue = integration;
        s.referenceConcentration = concentration;
        s.isReference = true;
        return s;
    }

    @Test
    public void fit_perfectLinearData_rSquaredNearOne() {
        // Concentration = 2 × integration + 0
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 100, 200));
        refs.add(makeRef(1, 200, 400));
        refs.add(makeRef(2, 300, 600));
        refs.add(makeRef(3, 400, 800));

        CalibrationModel model = CalibrationModel.fit(refs);
        assertEquals("R² should be ~1 for perfect linear data", 1.0, model.rSquared, 0.001);
        assertEquals("Slope should be 2", 2.0, model.slope, 0.01);
        assertEquals("Intercept should be ~0", 0.0, model.intercept, 1.0);
    }

    @Test
    public void fit_tooFewReferences_throwsException() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 100, 200));
        try {
            CalibrationModel.fit(refs);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("2"));
        }
    }

    @Test
    public void fitLogLog_perfectPowerLaw_rSquaredNearOne() {
        // concentration = 0.01 × signal^2  →  log(conc) = log(0.01) + 2×log(signal)
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 100,   100));
        refs.add(makeRef(1, 200,   400));
        refs.add(makeRef(2, 400,  1600));
        refs.add(makeRef(3, 800,  6400));

        CalibrationModel m = CalibrationModel.fit(refs, CalibrationModel.ModelType.LOG_LOG);
        assertEquals("R² should be ~1", 1.0, m.rSquared, 0.001);
        assertEquals("Exponent should be ~2", 2.0, m.coefficients[1], 0.01);
        assertTrue("LOD should be NaN for log-log", Double.isNaN(m.lod));
    }

    @Test
    public void fitLogLog_predict_recoversKnownValue() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 100,   100));
        refs.add(makeRef(1, 200,   400));
        refs.add(makeRef(2, 400,  1600));

        CalibrationModel m = CalibrationModel.fit(refs, CalibrationModel.ModelType.LOG_LOG);
        assertEquals("Predict at reference point", 400.0, m.predict(200), 1.0);
    }

    @Test
    public void fitQuadratic_perfectQuadraticData_rSquaredNearOne() {
        // concentration = 0.5×signal² + 0×signal + 0
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 10,    50));
        refs.add(makeRef(1, 20,   200));
        refs.add(makeRef(2, 30,   450));
        refs.add(makeRef(3, 40,   800));

        CalibrationModel m = CalibrationModel.fit(refs, CalibrationModel.ModelType.QUADRATIC);
        assertEquals("R² should be ~1", 1.0, m.rSquared, 0.001);
        assertEquals("Quadratic coeff a2 should be ~0.5", 0.5, m.coefficients[2], 0.01);
        assertTrue("LOD should be NaN for quadratic", Double.isNaN(m.lod));
    }

    @Test
    public void fitQuadratic_tooFewPoints_throwsException() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 100, 200));
        refs.add(makeRef(1, 200, 400));
        try {
            CalibrationModel.fit(refs, CalibrationModel.ModelType.QUADRATIC);
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("3"));
        }
    }

    @Test
    public void predict_invertsCalibration() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 1000, 10));
        refs.add(makeRef(1, 2000, 20));
        refs.add(makeRef(2, 3000, 30));

        CalibrationModel model = CalibrationModel.fit(refs);

        // Predict at a known reference point — should recover the known concentration
        double predicted = model.predict(2000);
        assertEquals("Prediction at reference point should equal known concentration", 20.0, predicted, 0.5);
    }

    @Test
    public void fit_reportsLodAndLoq() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 500, 5));
        refs.add(makeRef(1, 1000, 10));
        refs.add(makeRef(2, 1500, 15));
        refs.add(makeRef(3, 2000, 20));

        CalibrationModel model = CalibrationModel.fit(refs);

        // LOD = 3.3σ/slope, LOQ = 10σ/slope — both should be positive
        assertTrue("LOD should be positive or zero", model.lod >= 0);
        assertTrue("LOQ should be positive or zero", model.loq >= 0);
        assertTrue("LOQ should be >= LOD", model.loq >= model.lod);
    }

    @Test
    public void withLodLoqConvention_signalNoise_scaledByBgSigma() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 500,  5));
        refs.add(makeRef(1, 1000, 10));
        refs.add(makeRef(2, 1500, 15));
        refs.add(makeRef(3, 2000, 20));
        CalibrationModel m = CalibrationModel.fit(refs);

        // slope ≈ 0.01 (10µg/mL per 1000 units), bgSigma = 50
        double bgSigma = 50.0;
        CalibrationModel sn = m.withLodLoqConvention(
                CalibrationModel.LodLoqConvention.SIGNAL_NOISE, bgSigma, 0, 0);

        assertEquals(CalibrationModel.LodLoqConvention.SIGNAL_NOISE, sn.lodLoqConvention);
        // bgSigma is a background sigma in SIGNAL units; converting it to a concentration
        // means multiplying by the slope (concentration per signal), not dividing.
        // Corrected 2026-09-01 — the previous expectation divided, which returned a value
        // in signal²/concentration units.
        assertEquals("LOD from S/N", 3.0 * bgSigma * Math.abs(m.slope), sn.lod, 0.01);
        assertEquals("LOQ from S/N", 10.0 * bgSigma * Math.abs(m.slope), sn.loq, 0.01);
        assertTrue("LOQ >= LOD", sn.loq >= sn.lod);
    }

    // -------------------------------------------------------------------------
    // LOD/LOQ units. The model is fitted as concentration = slope × signal, the
    // inverse of ICH Q2(R1)'s response = S × concentration, so σ is already in
    // concentration units and must NOT be divided by the slope again.
    // -------------------------------------------------------------------------

    @Test
    public void lod_isThreePointThreeSigmaInConcentrationUnits() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(1, 1000, 10));
        refs.add(makeRef(2, 1500, 15));
        refs.add(makeRef(3, 2000, 21));   // slight scatter so sigma > 0
        CalibrationModel m = CalibrationModel.fit(refs);

        assertEquals("LOD must be 3.3σ in concentration units",
                     3.3 * m.sigmaRegression, m.lod, 1e-9);
        assertEquals("LOQ must be 10σ in concentration units",
                     10.0 * m.sigmaRegression, m.loq, 1e-9);
    }

    @Test
    public void lodLoq_areInvariantToTheSignalScale() {
        // The decisive dimensional check. Same concentrations, integration values scaled
        // by 1000: LOD expressed in concentration units cannot change. Under the previous
        // `3.3σ/slope` formula it scaled with the signal, off by exactly this factor —
        // which is how LOD came out as 4.776e4 against a 60–100 calibration.
        List<Spot> small = new ArrayList<>();
        small.add(makeRef(1, 1000, 10));
        small.add(makeRef(2, 1500, 15));
        small.add(makeRef(3, 2000, 21));

        List<Spot> large = new ArrayList<>();
        large.add(makeRef(1, 1_000_000, 10));
        large.add(makeRef(2, 1_500_000, 15));
        large.add(makeRef(3, 2_000_000, 21));

        CalibrationModel a = CalibrationModel.fit(small);
        CalibrationModel b = CalibrationModel.fit(large);

        assertEquals("LOD must not depend on the units of the integration value",
                     a.lod, b.lod, 1e-6);
        assertEquals("LOQ must not depend on the units of the integration value",
                     a.loq, b.loq, 1e-6);
    }

    @Test
    public void lod_staysOnTheSameScaleAsTheCalibrationRange() {
        // Sanity a chemist would apply: an LOD for a 10–20 µg/mL calibration belongs
        // somewhere near that range, not orders of magnitude above it.
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(1, 1000, 10));
        refs.add(makeRef(2, 1500, 15));
        refs.add(makeRef(3, 2000, 21));
        CalibrationModel m = CalibrationModel.fit(refs);

        assertTrue("LOD (" + m.lod + ") should be within an order of magnitude of the "
                   + "calibration range, not in signal units", m.lod < 200.0);
    }

    @Test
    public void withLodLoqConvention_manual_storesExactValues() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 500,  5));
        refs.add(makeRef(1, 1000, 10));
        refs.add(makeRef(2, 1500, 15));
        CalibrationModel m = CalibrationModel.fit(refs);

        CalibrationModel manual = m.withLodLoqConvention(
                CalibrationModel.LodLoqConvention.MANUAL, 0, 1.23, 4.56);
        assertEquals(1.23, manual.lod, 1e-9);
        assertEquals(4.56, manual.loq, 1e-9);
        assertEquals(CalibrationModel.LodLoqConvention.MANUAL, manual.lodLoqConvention);
    }

    @Test
    public void withLodLoqConvention_nonLinearModel_returnsUnchanged() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 100,  100));
        refs.add(makeRef(1, 200,  400));
        refs.add(makeRef(2, 400, 1600));
        CalibrationModel ll = CalibrationModel.fit(refs, CalibrationModel.ModelType.LOG_LOG);
        CalibrationModel same = ll.withLodLoqConvention(
                CalibrationModel.LodLoqConvention.MANUAL, 0, 0.1, 0.3);
        assertSame("Non-linear model should be returned unchanged", ll, same);
    }

    @Test
    public void estimateBackgroundSigma_uniformBackground_nearZeroSigma() {
        // 100×100 image, all pixels = 1.0f (uniform background, no spots)
        FloatProcessor fp = new FloatProcessor(100, 100);
        float[] px = (float[]) fp.getPixels();
        java.util.Arrays.fill(px, 1.0f);
        List<Spot> noSpots = new ArrayList<>();

        double sigma = CalibrationModel.estimateBackgroundSigma(fp, noSpots);
        assertEquals("Uniform background should give σ ≈ 0", 0.0, sigma, 1e-6);
    }

    @Test
    public void estimateBackgroundSigma_spotsExcluded_measuresBgOnly() {
        // 200×200 image: background = 1.0, bright spot in center at (100,100) r=20
        int w = 200, h = 200;
        FloatProcessor fp = new FloatProcessor(w, h);
        float[] px = (float[]) fp.getPixels();
        java.util.Arrays.fill(px, 1.0f);
        // bright spot
        for (int y = 80; y < 120; y++)
            for (int x = 80; x < 120; x++)
                px[y * w + x] = 10.0f;

        List<Spot> spots = new ArrayList<>();
        spots.add(new Spot(1, 100, 100, 20, h));

        double sigma = CalibrationModel.estimateBackgroundSigma(fp, spots);
        // background pixels are all 1.0 (halo 1.5× radius = 30 excluded) → σ ≈ 0
        assertEquals("Background sigma should be ~0 with uniform background", 0.0, sigma, 0.01);
    }

    @Test
    public void applyTo_populatesAssignedConcentrations() {
        List<Spot> refs = new ArrayList<>();
        refs.add(makeRef(0, 100, 10));
        refs.add(makeRef(1, 200, 20));
        refs.add(makeRef(2, 300, 30));

        CalibrationModel model = CalibrationModel.fit(refs);

        Spot unknown = new Spot(99, 50, 50, 5, 100);
        unknown.integrationValue = 150;

        List<Spot> all = new ArrayList<>(refs);
        all.add(unknown);

        model.applyTo(all);

        assertFalse("Unknown spot should have an assigned concentration",
                    Double.isNaN(unknown.assignedConcentration));
    }
}
