package se.katalystnord.tlcdigitizer.pipeline;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.List;

/**
 * Stage 7: Calibration and quantification.
 *
 * Fits a linear model:
 *   concentration = slope × integrationValue + intercept
 *
 * Requires at least 3 reference spots (ICH Q2(R1) guideline).
 * Reports R², slope, intercept, LOD, and LOQ:
 *   LOD = 3.3 × σ / slope
 *   LOQ = 10  × σ / slope
 * where σ is the standard error of the regression.
 *
 * Reference standards must be on the same plate as unknowns to correct
 * for plate-to-plate illumination variability (per qTLC paper).
 */
public final class CalibrationModel {

    public final double slope;
    public final double intercept;
    public final double rSquared;
    public final double lod;
    public final double loq;
    public final int nPoints;

    private CalibrationModel(double slope, double intercept, double rSquared,
                              double lod, double loq, int nPoints) {
        this.slope = slope;
        this.intercept = intercept;
        this.rSquared = rSquared;
        this.lod = lod;
        this.loq = loq;
        this.nPoints = nPoints;
    }

    /**
     * Fits a calibration model from the designated reference spots.
     *
     * @param referenceSpots spots with {@code isReference == true} and
     *                       valid {@code integrationValue} and {@code referenceConcentration}
     * @return fitted model
     * @throws IllegalArgumentException if fewer than 3 reference spots are supplied
     */
    public static CalibrationModel fit(List<Spot> referenceSpots) {
        long valid = referenceSpots.stream()
                .filter(s -> s.isReference && !Double.isNaN(s.integrationValue)
                        && !Double.isNaN(s.referenceConcentration))
                .count();

        if (valid < 2) {
            throw new IllegalArgumentException(
                    "At least 2 reference spots are required for linear regression. Found: " + valid);
        }

        SimpleRegression reg = new SimpleRegression(true);
        for (Spot s : referenceSpots) {
            if (s.isReference && !Double.isNaN(s.integrationValue) && !Double.isNaN(s.referenceConcentration)) {
                reg.addData(s.integrationValue, s.referenceConcentration);
            }
        }

        double slope = reg.getSlope();
        double intercept = reg.getIntercept();
        double r2 = reg.getRSquare();

        // Standard error of the regression (σ)
        double sigma = reg.getMeanSquareError() > 0 ? Math.sqrt(reg.getMeanSquareError()) : 0;

        double lod = (slope != 0) ? 3.3 * sigma / Math.abs(slope) : Double.NaN;
        double loq = (slope != 0) ? 10.0 * sigma / Math.abs(slope) : Double.NaN;

        return new CalibrationModel(slope, intercept, r2, lod, loq, (int) reg.getN());
    }

    /**
     * Predicts concentration from an integration value using the fitted model.
     */
    public double predict(double integrationValue) {
        return slope * integrationValue + intercept;
    }

    /**
     * Applies predictions to all non-reference spots in {@code spots}.
     */
    public void applyTo(List<Spot> spots) {
        for (Spot s : spots) {
            if (!Double.isNaN(s.integrationValue)) {
                s.assignedConcentration = predict(s.integrationValue);
            }
        }
    }

    public String toSummary() {
        return String.format(
                "CalibrationModel{n=%d slope=%.6g intercept=%.6g R²=%.4f LOD=%.4g LOQ=%.4g}",
                nPoints, slope, intercept, rSquared, lod, loq);
    }
}
