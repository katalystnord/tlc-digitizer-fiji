package se.katalystnord.tlcdigitizer.pipeline;

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
        // Minimum is now 2 (mathematical minimum for linear regression);
        // the UI warns for < 3 but CalibrationModel.fit itself requires >= 2.
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
