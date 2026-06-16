package se.katalystnord.tlcdigitizer.pipeline;

import org.junit.Test;

import static org.junit.Assert.*;

public class RfCalculatorTest {

    @Test
    public void spotAtOrigin_returnsZero() {
        float rf = RfCalculator.calculate(0.90f, 0.90f, 0.10f);
        assertEquals(0.0f, rf, 1e-5f);
    }

    @Test
    public void spotAtFront_returnsOne() {
        float rf = RfCalculator.calculate(0.10f, 0.90f, 0.10f);
        assertEquals(1.0f, rf, 1e-5f);
    }

    @Test
    public void spotAtMidpoint_returnsHalf() {
        float rf = RfCalculator.calculate(0.50f, 0.90f, 0.10f);
        assertEquals(0.5f, rf, 1e-5f);
    }

    @Test
    public void spotBeyondFront_clampsToOne() {
        float rf = RfCalculator.calculate(0.05f, 0.90f, 0.10f);
        assertEquals(1.0f, rf, 1e-5f);
    }

    @Test
    public void spotBelowOrigin_clampsToZero() {
        float rf = RfCalculator.calculate(0.95f, 0.90f, 0.10f);
        assertEquals(0.0f, rf, 1e-5f);
    }

    @Test
    public void coincidentOriginAndFront_returnsNaN() {
        float rf = RfCalculator.calculate(0.5f, 0.5f, 0.5f);
        assertTrue(Float.isNaN(rf));
    }

    @Test
    public void standardTlcGeometry_correctFormula() {
        // Origin at Y=0.85, front at Y=0.05, spot at Y=0.45
        // Rf = (0.85 - 0.45) / (0.85 - 0.05) = 0.40 / 0.80 = 0.50
        float rf = RfCalculator.calculate(0.45f, 0.85f, 0.05f);
        assertEquals(0.50f, rf, 1e-4f);
    }
}
