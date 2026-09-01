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
 *   LINEAR    — concentration = slope × signal + intercept
 *               Best for narrow concentration ranges.
 *   LOG_LOG   — concentration = exp(b) × signal^a  (linear in log-log space)
 *               Best for wide dynamic ranges (e.g. 10–10 000 µg/mL).
 *   QUADRATIC — concentration = a2·signal² + a1·signal + a0
 *               Handles mild curve-bending at high concentrations.
 *
 * LOD/LOQ are computed for LINEAR only. Three conventions are selectable
 * (see {@link LodLoqConvention}); the default is ICH Q2(R1) regression.
 * RMSE in concentration units is reported for all models.
 *
 * Reference standards must be on the same plate as unknowns (per qTLC paper).
 */
public final class CalibrationModel {

    // -----------------------------------------------------------------------
    // Enums
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

    /**
     * Convention used to compute LOD and LOQ. Only meaningful for LINEAR models;
     * LOD/LOQ are NaN for LOG_LOG and QUADRATIC regardless of convention.
     */
    public enum LodLoqConvention {
        REGRESSION_ICH(
            "Regression (ICH Q2(R1))",
            "σ from calibration line residuals — standard pharma method (3.3σ/S, 10σ/S)"),
        SIGNAL_NOISE(
            "Signal / noise  (S/N = 3, 10)",
            "σ from image background pixels — suitable when blank lane areas are present"),
        MANUAL(
            "Manual entry",
            "Enter LOD and LOQ values directly, e.g. from a separate dilution experiment");

        public final String label;
        public final String description;

        LodLoqConvention(String label, String description) {
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

    /** ICH Q2(R1) LOD, in concentration units. NaN for non-LINEAR models. */
    public final double lod;
    /** ICH Q2(R1) LOQ, in concentration units. NaN for non-LINEAR models. */
    public final double loq;

    /** Root-mean-square error in concentration units. Available for all models. */
    public final double rmse;

    public final int nPoints;

    /**
     * Residual standard error from the LINEAR regression (σ in ICH formula).
     * NaN for non-LINEAR models. Stored so LOD/LOQ can be recomputed later
     * when the user switches the LOD/LOQ convention in the UI.
     */
    public final double sigmaRegression;

    /** Convention used to derive {@link #lod} and {@link #loq}. */
    public final LodLoqConvention lodLoqConvention;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private CalibrationModel(ModelType modelType, double[] coefficients,
                              double rSquared, double lod, double loq,
                              double rmse, int nPoints,
                              double sigmaRegression, LodLoqConvention lodLoqConvention) {
        this.modelType        = modelType;
        this.coefficients     = coefficients;
        this.rSquared         = rSquared;
        this.lod              = lod;
        this.loq              = loq;
        this.rmse             = rmse;
        this.nPoints          = nPoints;
        this.sigmaRegression  = sigmaRegression;
        this.lodLoqConvention = lodLoqConvention;
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

    /**
     * Returns a new CalibrationModel identical to this one but with LOD/LOQ
     * recomputed using the specified convention. For non-LINEAR models returns
     * {@code this} unchanged (LOD/LOQ are not defined for LOG_LOG/QUADRATIC).
     *
     * @param convention  which LOD/LOQ method to apply
     * @param bgSigma     background image sigma (used by SIGNAL_NOISE; ignored otherwise)
     * @param manualLod   user-supplied LOD in concentration units (used by MANUAL only)
     * @param manualLoq   user-supplied LOQ in concentration units (used by MANUAL only)
     */
    public CalibrationModel withLodLoqConvention(
            LodLoqConvention convention, double bgSigma,
            double manualLod, double manualLoq) {
        if (modelType != ModelType.LINEAR) return this;
        double newLod, newLoq;
        switch (convention) {
            case REGRESSION_ICH:
                // ICH Q2(R1) states LOD = 3.3σ/S for a calibration fitted as
                // response = S × concentration. This model is fitted the other way round
                // (concentration = slope × signal), so σ is ALREADY in concentration units
                // and the slope conversion is already built in. Dividing by the slope again
                // would return the answer in signal units — see the unit tests.
                newLod = 3.3  * sigmaRegression;
                newLoq = 10.0 * sigmaRegression;
                break;
            case SIGNAL_NOISE:
                if (Double.isNaN(bgSigma)) {
                    newLod = Double.NaN;
                    newLoq = Double.NaN;
                } else {
                    // bgSigma is a background sigma in SIGNAL units, so converting it to a
                    // concentration means MULTIPLYING by slope (concentration per signal).
                    newLod = 3.0  * bgSigma * Math.abs(slope);
                    newLoq = 10.0 * bgSigma * Math.abs(slope);
                }
                break;
            case MANUAL:
                newLod = manualLod;
                newLoq = manualLoq;
                break;
            default: return this;
        }
        return new CalibrationModel(modelType, coefficients, rSquared,
                                    newLod, newLoq, rmse, nPoints,
                                    sigmaRegression, convention);
    }

    /**
     * Estimates background noise σ from the corrected image by computing the
     * standard deviation of pixels that lie outside all spot circles.
     * Returns {@link Double#NaN} if fewer than 100 background pixels are found.
     *
     * @param fp    background-corrected FloatProcessor
     * @param spots detected spots (centroids + radii in image pixels)
     */
    public static double estimateBackgroundSigma(ij.process.FloatProcessor fp, List<Spot> spots) {
        int w = fp.getWidth(), h = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        // Build spot list as arrays for fast inner-loop access
        int n = spots.size();
        float[] cx = new float[n], cy = new float[n], r2 = new float[n];
        for (int i = 0; i < n; i++) {
            Spot s = spots.get(i);
            cx[i] = s.centroidX;
            cy[i] = s.centroidY;
            float margin = s.radius * 1.5f;   // exclude a halo around each spot
            r2[i] = margin * margin;
        }

        double sum = 0, sumSq = 0;
        long count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inSpot = false;
                for (int i = 0; i < n && !inSpot; i++) {
                    float dx = x - cx[i], dy = y - cy[i];
                    if (dx * dx + dy * dy <= r2[i]) inSpot = true;
                }
                if (!inSpot) {
                    double v = pixels[y * w + x];
                    sum   += v;
                    sumSq += v * v;
                    count++;
                }
            }
        }
        if (count < 100) return Double.NaN;
        double mean = sum / count;
        return Math.sqrt(sumSq / count - mean * mean);
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
                    "LINEAR n=%d slope=%.6g intercept=%.6g R²=%.4f LOD=%.4g LOQ=%.4g RMSE=%.4g [%s]",
                    nPoints, coefficients[1], coefficients[0], rSquared, lod, loq, rmse,
                    lodLoqConvention.label);
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
        // σ is in concentration units because the regression is fitted as
        // concentration = slope × signal; see withLodLoqConvention for the full note.
        double lod       = 3.3  * sigma;
        double loq       = 10.0 * sigma;
        double rmse      = computeRmse(refs, intercept, slope, 2, ModelType.LINEAR);
        return new CalibrationModel(ModelType.LINEAR, new double[]{intercept, slope},
                                    r2, lod, loq, rmse, (int) reg.getN(),
                                    sigma, LodLoqConvention.REGRESSION_ICH);
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
                                    r2, Double.NaN, Double.NaN, rmse, n,
                                    Double.NaN, LodLoqConvention.REGRESSION_ICH);
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
                                    r2, Double.NaN, Double.NaN, rmse, n,
                                    Double.NaN, LodLoqConvention.REGRESSION_ICH);
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
