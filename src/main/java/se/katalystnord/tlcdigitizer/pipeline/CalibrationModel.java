package se.katalystnord.tlcdigitizer.pipeline;

import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.List;

/**
 * Stage 7: Calibration and quantification.
 *
 * Three model types are supported:
 *   LINEAR   — concentration = slope × signal + intercept
 *              Best for narrow concentration ranges. LOD/LOQ per ICH Q2(R1).
 *   LOG_LOG  — concentration = exp(b) × signal^a  (linear in log-log space)
 *              Best for wide dynamic ranges (e.g. 10–10 000 µg/mL).
 *   QUADRATIC — concentration = a2·signal² + a1·signal + a0
 *              Handles mild curve-bending at high concentrations.
 *
 * LOD/LOQ are only defined for LINEAR (ICH Q2(R1): 3.3σ/slope and 10σ/slope).
 * RMSE in concentration units is reported for all models.
 *
 * Reference standards must be on the same plate as unknowns (per qTLC paper).
 */
public final class CalibrationModel {

    // -----------------------------------------------------------------------
    // Model type
    // -----------------------------------------------------------------------

    public enum ModelType {
        LINEAR(
            "Linear",
            "y = slope×x + intercept  —  narrow concentration ranges, ICH Q2(R1) compliant"),
        LOG_LOG(
            "Log–log",
            "y = A×x^B  —  wide dynamic ranges where Beer–Lambert deviations occur"),
        QUADRATIC(
            "Quadratic",
            "y = a·x² + b·x + c  —  mild curve-bending at high concentrations");

        public final String label;
        public final String description;

        ModelType(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    public final ModelType modelType;

    /**
     * Model coefficients.
     * LINEAR/LOG_LOG: [0]=intercept (or log-intercept), [1]=slope (or exponent).
     * QUADRATIC:      [0]=a0 (constant), [1]=a1 (linear), [2]=a2 (quadratic).
     */
    public final double[] coefficients;

    /** Convenience accessor for LINEAR slope (NaN for other models). */
    public final double slope;
    /** Convenience accessor for LINEAR intercept (NaN for other models). */
    public final double intercept;

    public final double rSquared;

    /** ICH Q2(R1) LOD = 3.3σ/slope. NaN for non-LINEAR models. */
    public final double lod;
    /** ICH Q2(R1) LOQ = 10σ/slope. NaN for non-LINEAR models. */
    public final double loq;

    /** Root-mean-square error in concentration units. Available for all models. */
    public final double rmse;

    public final int nPoints;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private CalibrationModel(ModelType modelType, double[] coefficients,
                              double rSquared, double lod, double loq,
                              double rmse, int nPoints) {
        this.modelType    = modelType;
        this.coefficients = coefficients;
        this.rSquared     = rSquared;
        this.lod          = lod;
        this.loq          = loq;
        this.rmse         = rmse;
        this.nPoints      = nPoints;
        // Convenience fields
        this.slope     = (modelType == ModelType.LINEAR) ? coefficients[1] : Double.NaN;
        this.intercept = (modelType == ModelType.LINEAR) ? coefficients[0] : Double.NaN;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Fits a LINEAR model (backwards-compatible convenience overload). */
    public static CalibrationModel fit(List<Spot> referenceSpots) {
        return fit(referenceSpots, ModelType.LINEAR);
    }

    /**
     * Fits a calibration model of the specified type.
     *
     * @param referenceSpots spots with {@code isReference == true} and valid
     *                       {@code integrationValue} and {@code referenceConcentration}
     * @param type           which model to fit
     * @return fitted model
     * @throws IllegalArgumentException if too few reference spots are supplied
     */
    public static CalibrationModel fit(List<Spot> referenceSpots, ModelType type) {
        int minRequired = (type == ModelType.QUADRATIC) ? 3 : 2;
        long valid = referenceSpots.stream()
                .filter(s -> s.isReference
                        && !Double.isNaN(s.integrationValue)
                        && !Double.isNaN(s.referenceConcentration)
                        && (type != ModelType.LOG_LOG
                            || (s.integrationValue > 0 && s.referenceConcentration > 0)))
                .count();
        if (valid < minRequired) {
            throw new IllegalArgumentException(
                "At least " + minRequired + " reference spots are required for "
                + type.label + " calibration. Found: " + valid);
        }
        switch (type) {
            case LINEAR:    return fitLinear(referenceSpots);
            case LOG_LOG:   return fitLogLog(referenceSpots);
            case QUADRATIC: return fitQuadratic(referenceSpots);
            default: throw new IllegalStateException("Unknown model type: " + type);
        }
    }

    /**
     * Predicts concentration from a signal (integration) value using the fitted model.
     *
     * @return predicted concentration, or {@link Double#NaN} if the input is invalid for this model
     */
    public double predict(double integrationValue) {
        switch (modelType) {
            case LINEAR:
                return coefficients[0] + coefficients[1] * integrationValue;
            case LOG_LOG:
                if (integrationValue <= 0) return Double.NaN;
                return Math.exp(coefficients[0] + coefficients[1] * Math.log(integrationValue));
            case QUADRATIC: {
                double x = integrationValue;
                return coefficients[0] + coefficients[1] * x + coefficients[2] * x * x;
            }
            default:
                return Double.NaN;
        }
    }

    /** Populates {@code assignedConcentration} for all spots with a valid integration value. */
    public void applyTo(List<Spot> spots) {
        for (Spot s : spots) {
            if (!Double.isNaN(s.integrationValue)) {
                s.assignedConcentration = predict(s.integrationValue);
            }
        }
    }

    public String toSummary() {
        switch (modelType) {
            case LINEAR:
                return String.format(
                    "LINEAR n=%d slope=%.6g intercept=%.6g R²=%.4f LOD=%.4g LOQ=%.4g RMSE=%.4g",
                    nPoints, coefficients[1], coefficients[0], rSquared, lod, loq, rmse);
            case LOG_LOG:
                return String.format(
                    "LOG_LOG n=%d exponent=%.6g prefactor=%.6g R²=%.4f RMSE=%.4g",
                    nPoints, coefficients[1], Math.exp(coefficients[0]), rSquared, rmse);
            case QUADRATIC:
                return String.format(
                    "QUADRATIC n=%d a2=%.6g a1=%.6g a0=%.6g R²=%.4f RMSE=%.4g",
                    nPoints, coefficients[2], coefficients[1], coefficients[0], rSquared, rmse);
            default:
                return "CalibrationModel{unknown}";
        }
    }

    // -----------------------------------------------------------------------
    // Private fit methods
    // -----------------------------------------------------------------------

    private static CalibrationModel fitLinear(List<Spot> refs) {
        SimpleRegression reg = new SimpleRegression(true);
        for (Spot s : refs) {
            if (s.isReference && !Double.isNaN(s.integrationValue) && !Double.isNaN(s.referenceConcentration)) {
                reg.addData(s.integrationValue, s.referenceConcentration);
            }
        }
        double slope     = reg.getSlope();
        double intercept = reg.getIntercept();
        double r2        = reg.getRSquare();
        double sigma     = reg.getMeanSquareError() > 0 ? Math.sqrt(reg.getMeanSquareError()) : 0;
        double lod       = (slope != 0) ? 3.3  * sigma / Math.abs(slope) : Double.NaN;
        double loq       = (slope != 0) ? 10.0 * sigma / Math.abs(slope) : Double.NaN;
        double rmse      = computeRmse(refs, intercept, slope, 2, ModelType.LINEAR);
        return new CalibrationModel(ModelType.LINEAR, new double[]{intercept, slope},
                                    r2, lod, loq, rmse, (int) reg.getN());
    }

    private static CalibrationModel fitLogLog(List<Spot> refs) {
        SimpleRegression reg = new SimpleRegression(true);
        int n = 0;
        for (Spot s : refs) {
            if (s.isReference && !Double.isNaN(s.integrationValue) && !Double.isNaN(s.referenceConcentration)
                    && s.integrationValue > 0 && s.referenceConcentration > 0) {
                reg.addData(Math.log(s.integrationValue), Math.log(s.referenceConcentration));
                n++;
            }
        }
        double interceptLog = reg.getIntercept();
        double slopeLog     = reg.getSlope();
        double r2           = reg.getRSquare();

        double sumSqRes = 0;
        int count = 0;
        for (Spot s : refs) {
            if (s.isReference && !Double.isNaN(s.integrationValue) && !Double.isNaN(s.referenceConcentration)
                    && s.integrationValue > 0 && s.referenceConcentration > 0) {
                double pred = Math.exp(interceptLog + slopeLog * Math.log(s.integrationValue));
                double res  = s.referenceConcentration - pred;
                sumSqRes += res * res;
                count++;
            }
        }
        double rmse = (count >= 3) ? Math.sqrt(sumSqRes / (count - 2)) : 0;

        return new CalibrationModel(ModelType.LOG_LOG, new double[]{interceptLog, slopeLog},
                                    r2, Double.NaN, Double.NaN, rmse, n);
    }

    private static CalibrationModel fitQuadratic(List<Spot> refs) {
        WeightedObservedPoints obs = new WeightedObservedPoints();
        int n = 0;
        double sumConc = 0;
        for (Spot s : refs) {
            if (s.isReference && !Double.isNaN(s.integrationValue) && !Double.isNaN(s.referenceConcentration)) {
                obs.add(s.integrationValue, s.referenceConcentration);
                sumConc += s.referenceConcentration;
                n++;
            }
        }
        double[] c = PolynomialCurveFitter.create(2).fit(obs.toList());
        // c[0]=a0 (constant), c[1]=a1 (linear coeff), c[2]=a2 (quadratic coeff)

        double meanConc = (n > 0) ? sumConc / n : 0;
        double ssTot = 0, ssRes = 0;
        for (Spot s : refs) {
            if (s.isReference && !Double.isNaN(s.integrationValue) && !Double.isNaN(s.referenceConcentration)) {
                double x    = s.integrationValue;
                double pred = c[0] + c[1] * x + c[2] * x * x;
                double res  = s.referenceConcentration - pred;
                ssRes += res * res;
                ssTot += (s.referenceConcentration - meanConc) * (s.referenceConcentration - meanConc);
            }
        }
        double r2   = (ssTot > 0) ? 1.0 - ssRes / ssTot : 0;
        double rmse = (n >= 4) ? Math.sqrt(ssRes / (n - 3)) : 0;

        return new CalibrationModel(ModelType.QUADRATIC, c,
                                    r2, Double.NaN, Double.NaN, rmse, n);
    }

    private static double computeRmse(List<Spot> refs,
                                       double intercept, double slope,
                                       int degreesOfFreedom, ModelType type) {
        double sumSqRes = 0;
        int n = 0;
        for (Spot s : refs) {
            if (s.isReference && !Double.isNaN(s.integrationValue) && !Double.isNaN(s.referenceConcentration)) {
                double pred = intercept + slope * s.integrationValue;
                double res  = s.referenceConcentration - pred;
                sumSqRes += res * res;
                n++;
            }
        }
        return (n > degreesOfFreedom) ? Math.sqrt(sumSqRes / (n - degreesOfFreedom)) : 0;
    }
}
