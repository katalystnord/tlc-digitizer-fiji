package se.katalystnord.tlcdigitizer.pipeline;

import ij.plugin.filter.RankFilters;
import ij.process.FloatProcessor;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.List;

/**
 * Stage 3 / post-integration: Background / illumination correction.
 *
 * <p><b>Method A (default) — 2D quartic polynomial fitting (TLCyzer):</b><br>
 * Fits the 15-coefficient model f(x,y) to the image at 1/16 density then
 * evaluates and subtracts at full resolution. Applied to the whole image before
 * spot detection.
 * Source: Hauk et al. Scientific Reports 12, 13433 (2022).
 *
 * <p><b>Method B — per-spot local polynomial (qtlc / Savitzky-Golay):</b><br>
 * After spot detection and integration, estimates background per spot from
 * rectangular strips above and below each spot. Fits a polynomial in x to
 * model horizontal background variation (e.g. uneven charring or staining),
 * then subtracts the predicted background from each spot's integration value.
 * Source: Pavicevic et al., J. Pharm. Biomed. Anal. 129, 43 (2016), §3.4–3.5.
 */
public final class BackgroundCorrection {

    /** Sample stride: every 4th pixel in x and y (factor of 16 overall, per TLCyzer). */
    private static final int STRIDE = 4;

    private BackgroundCorrection() {}

    /**
     * Fits a quartic polynomial background to {@code fp} and returns a new
     * FloatProcessor with the background subtracted.
     *
     * Pixel values in the result are shifted so the minimum is 0.
     * Negative values (where background model exceeds actual pixel) are clamped to 0.
     */
    public static FloatProcessor fitAndSubtract(FloatProcessor fp) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        float[] pixels = (float[]) fp.getPixelsCopy();

        double[] coefficients = fitCoefficients(pixels, width, height);
        float[] background = evaluateBackground(coefficients, width, height);

        float[] result = new float[width * height];
        for (int i = 0; i < result.length; i++) {
            result[i] = Math.max(0f, pixels[i] - background[i]);
        }

        return new FloatProcessor(width, height, result, null);
    }

    /**
     * Fits the quartic polynomial coefficients using OLS on a subsampled grid.
     *
     * @return double[15]: [intercept, b_x, b_y, b_x2, b_xy, b_y2,
     *                      b_x3, b_x2y, b_xy2, b_y3, b_x4, b_x3y, b_x2y2, b_xy3, b_y4]
     */
    static double[] fitCoefficients(float[] pixels, int width, int height) {
        // Count samples
        int count = 0;
        for (int y = 0; y < height; y += STRIDE) {
            for (int x = 0; x < width; x += STRIDE) {
                count++;
            }
        }

        double[][] X = new double[count][14]; // 14 polynomial terms (intercept added by OLS)
        double[] Y = new double[count];

        int idx = 0;
        for (int y = 0; y < height; y += STRIDE) {
            for (int x = 0; x < width; x += STRIDE) {
                X[idx] = polyTerms(x, y);
                Y[idx] = pixels[y * width + x];
                idx++;
            }
        }

        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.newSampleData(Y, X); // intercept is included by default
        return ols.estimateRegressionParameters(); // [intercept, b1..b14]
    }

    /**
     * Evaluates the fitted polynomial at every pixel in the full-resolution image.
     */
    static float[] evaluateBackground(double[] coefficients, int width, int height) {
        double intercept = coefficients[0];
        float[] bg = new float[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] terms = polyTerms(x, y);
                double val = intercept;
                for (int k = 0; k < terms.length; k++) {
                    val += coefficients[k + 1] * terms[k];
                }
                bg[y * width + x] = (float) val;
            }
        }

        return bg;
    }

    /**
     * Returns the 14 polynomial basis terms for coordinate (x, y).
     * Order matches TLCyzer's coord_to_poly:
     *   [x, y, x², xy, y², x³, x²y, xy², y³, x⁴, x³y, x²y², xy³, y⁴]
     */
    static double[] polyTerms(double x, double y) {
        double x2 = x * x;
        double y2 = y * y;
        double x3 = x2 * x;
        double y3 = y2 * y;
        double x4 = x3 * x;
        double y4 = y3 * y;

        return new double[]{
            x, y,           // D1
            x2, x * y, y2, // D2
            x3, x2 * y, x * y2, y3, // D3
            x4, x3 * y, x2 * y2, x * y3, y4  // D4
        };
    }

    // -------------------------------------------------------------------------
    // Method C: white top-hat morphological transform
    // -------------------------------------------------------------------------

    /**
     * White top-hat transform: {@code original − morphological_opening(original, seRadius)}.
     *
     * <p>Opening with a disk SE of radius {@code seRadius} removes structures
     * (spots) wider than the SE from the image; subtracting the opening from the
     * original isolates bright blobs as positive residuals with near-zero background.
     * Standard operator for "bright blobs on smooth background" — equivalent to
     * fluorescence microscopy spot enhancement.
     *
     * <p>SE radius rule of thumb: 1.5× the median spot radius. Spots narrower than
     * the SE are preserved; the background estimate is the morphological opening.
     *
     * @param fp       source image (not modified)
     * @param seRadius disk structuring element radius in pixels (≥ 1)
     * @return top-hat image: spots as positive residuals, background ≈ 0
     */
    public static FloatProcessor topHat(FloatProcessor fp, float seRadius) {
        FloatProcessor opening = (FloatProcessor) fp.duplicate();
        RankFilters rf = new RankFilters();
        rf.rank(opening, seRadius, RankFilters.MIN); // erosion
        rf.rank(opening, seRadius, RankFilters.MAX); // dilation → opening

        float[] srcPx = (float[]) fp.getPixels();
        float[] opnPx = (float[]) opening.getPixels();
        float[] result = new float[srcPx.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = Math.max(0f, srcPx[i] - opnPx[i]);
        }
        return new FloatProcessor(fp.getWidth(), fp.getHeight(), result, null);
    }

    // -------------------------------------------------------------------------
    // Method B: per-spot local polynomial (qtlc / Savitzky-Golay)
    // -------------------------------------------------------------------------

    /** Default polynomial degree for per-spot horizontal background fit. */
    public static final int DEFAULT_SG_DEGREE = 5;

    /**
     * Applies per-spot polynomial background correction (Option B) to a list of
     * already-integrated spots.
     *
     * <p>Algorithm (faithful to qtlc {@code noisepoly2D}):
     * <ol>
     *   <li>For each spot, sample mean pixel intensity from strips immediately
     *       above and below the spot bounding circle (strip height = max(3, r/2)).
     *   <li>Fit a polynomial of degree {@code polyDegree} to the sampled
     *       background values as a function of spot x-position (normalised to [0,1]).
     *       This captures horizontal illumination/charring gradients across the plate.
     *   <li>Subtract predicted background × estimated top-15% pixel count from
     *       each spot's {@link Spot#integrationValue}.
     * </ol>
     *
     * <p>If there are fewer than {@code polyDegree + 2} spots the polynomial fit
     * is skipped and raw per-spot background estimates are used instead.
     *
     * @param spots      spots with {@code integrationValue} already set by {@link SpotIntegrator}
     * @param fp         the perspective-corrected image (before or after global correction)
     * @param polyDegree degree of the horizontal background polynomial (≥ 1)
     */
    public static void applyPerSpotPolynomial(List<Spot> spots, FloatProcessor fp, int polyDegree) {
        if (spots.isEmpty()) return;

        int width  = fp.getWidth();
        int height = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        // Step 1: sample local background per spot
        double[] bgPerSpot = new double[spots.size()];
        for (int i = 0; i < spots.size(); i++) {
            bgPerSpot[i] = sampleLocalBackground(pixels, width, height, spots.get(i));
        }

        // Step 2: fit horizontal polynomial (or fall through to raw per-spot values)
        double[] predictedBg;
        if (spots.size() >= polyDegree + 2) {
            predictedBg = fitHorizontalPolynomial(spots, bgPerSpot, polyDegree);
        } else {
            predictedBg = bgPerSpot;
        }

        // Step 3: subtract predicted background from each spot's integration value.
        // integrationValue = sum of top-15% pixels ≈ bgMean * (π r² * 0.15) + signal
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            if (Double.isNaN(s.integrationValue)) continue;
            double approxPixels = Math.PI * s.radius * s.radius * SpotIntegrator.TOP_FRACTION;
            double correction = predictedBg[i] * Math.max(1.0, approxPixels);
            s.integrationValue = Math.max(0.0, s.integrationValue - correction);
        }
    }

    /**
     * Samples mean pixel intensity from strips immediately above and below the
     * spot's circular bounding box. Strip height = max(3, radius/2).
     * Returns 0 if no valid pixels exist (spot at image boundary).
     */
    static double sampleLocalBackground(float[] pixels, int width, int height, Spot spot) {
        int cx     = Math.round(spot.centroidX);
        int cy     = Math.round(spot.centroidY);
        int r      = (int) Math.ceil(spot.radius);
        int stripH = Math.max(3, r / 2);
        int xLo    = Math.max(0, cx - r);
        int xHi    = Math.min(width - 1, cx + r);

        double sum = 0;
        int count = 0;

        // above the spot
        int aboveTop = Math.max(0, cy - r - stripH);
        int aboveBot = Math.min(height - 1, cy - r - 1);
        for (int y = aboveTop; y <= aboveBot; y++) {
            for (int x = xLo; x <= xHi; x++) {
                sum += pixels[y * width + x];
                count++;
            }
        }

        // below the spot
        int belowTop = Math.max(0, cy + r + 1);
        int belowBot = Math.min(height - 1, cy + r + stripH);
        for (int y = belowTop; y <= belowBot; y++) {
            for (int x = xLo; x <= xHi; x++) {
                sum += pixels[y * width + x];
                count++;
            }
        }

        return count > 0 ? sum / count : 0.0;
    }

    /**
     * Fits a 1D polynomial of degree {@code degree} to {normalised-x → bgValue}
     * pairs and returns the predicted background for each spot's x position.
     */
    private static double[] fitHorizontalPolynomial(List<Spot> spots, double[] bgValues, int degree) {
        int n = spots.size();
        double maxX = 1.0;
        for (Spot s : spots) {
            if (s.centroidX > maxX) maxX = s.centroidX;
        }

        double[][] X = new double[n][degree];
        for (int i = 0; i < n; i++) {
            X[i] = poly1DTerms(spots.get(i).centroidX / maxX, degree);
        }

        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.newSampleData(bgValues.clone(), X);
        double[] coef = ols.estimateRegressionParameters(); // [intercept, b1..b_degree]

        double[] predicted = new double[n];
        for (int i = 0; i < n; i++) {
            double t = spots.get(i).centroidX / maxX;
            double[] terms = poly1DTerms(t, degree);
            double val = coef[0];
            for (int k = 0; k < degree; k++) {
                val += coef[k + 1] * terms[k];
            }
            predicted[i] = val;
        }

        return predicted;
    }

    /** Returns [t, t², t³, ..., t^degree] — the 1D polynomial basis for normalised x. */
    private static double[] poly1DTerms(double t, int degree) {
        double[] terms = new double[degree];
        double power = t;
        for (int i = 0; i < degree; i++) {
            terms[i] = power;
            power *= t;
        }
        return terms;
    }
}
