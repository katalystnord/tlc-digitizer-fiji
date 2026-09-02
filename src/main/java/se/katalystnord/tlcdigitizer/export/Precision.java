package se.katalystnord.tlcdigitizer.export;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Formats exported values at the resolution the measurement actually supports.
 *
 * <p>The principle: never pad a number with digits the method cannot resolve. A recovery
 * figure printed as {@code 110.162} from a calibration whose residual scatter is ±7 states
 * three digits of fiction, and a reader has no way to tell which two are real.
 *
 * <p>Different columns have genuinely different resolutions, so they are formatted
 * differently rather than through one blanket format string:
 *
 * <ul>
 *   <li><b>Concentrations</b> (predicted values, LOD, LOQ) are resolved by the calibration's
 *       own residual scatter. {@link #concentration} derives the decimal count from the
 *       model's RMSE, so a tight calibration prints more digits and a loose one prints
 *       fewer, automatically.</li>
 *   <li><b>Rf</b> is limited by where the user placed the origin and solvent-front lines by
 *       eye, not by pixel count. Three decimals is already generous.</li>
 *   <li><b>Geometry fractions</b> are limited by the pixel grid. Five decimals is roughly a
 *       third of a pixel on a 3000 px plate — sub-pixel, and not yet fictional. These are
 *       also what reproduce the analysis, so they stay on the generous side.</li>
 *   <li><b>Fit parameters</b> (slope, intercept, polynomial coefficients) are deliberately
 *       <em>not</em> rounded, by {@link #exact}. They are not measurements; they are the
 *       numbers needed to reproduce the model, and rounding them changes the result. This
 *       is also the full-precision escape hatch: the parameter block always carries every
 *       digit even though the measurement columns do not.</li>
 * </ul>
 */
final class Precision {

    /** Rf is set by hand-placed lines; more than three decimals is invented. */
    static final int RF_DECIMALS = 3;

    /** Fractions of image dimension: ~0.3 px on a 3000 px plate. */
    static final int GEOMETRY_DECIMALS = 5;

    /** Significant figures used when no calibration is available to set the resolution. */
    static final int DEFAULT_SIG_FIGS = 3;

    /** Never print more than this many decimals for a concentration, however tight the fit. */
    static final int MAX_CONCENTRATION_DECIMALS = 4;

    private Precision() {}

    /** {@code NA} for a missing value, so downstream readers can distinguish it from zero. */
    private static final String NA = "NA";

    /**
     * Formats a concentration at the resolution implied by {@code rmse}, the calibration's
     * residual standard error in concentration units.
     *
     * <p>The last printed digit is kept at roughly one tenth of the residual scatter: enough
     * to avoid discarding real information, not enough to imply precision the fit does not
     * have. An RMSE of 7 gives one decimal; an RMSE of 0.05 gives three.
     *
     * @param rmse residual scatter in concentration units; NaN or non-positive falls back to
     *             {@link #DEFAULT_SIG_FIGS} significant figures
     */
    static String concentration(double value, double rmse) {
        if (Double.isNaN(value)) return NA;
        if (Double.isNaN(rmse) || rmse <= 0) return significant(value, DEFAULT_SIG_FIGS);
        int decimals = (int) Math.ceil(-Math.log10(rmse / 10.0));
        decimals = Math.max(0, Math.min(MAX_CONCENTRATION_DECIMALS, decimals));
        return fixed(value, decimals);
    }

    /** Rf, at {@link #RF_DECIMALS}. */
    static String rf(double value) {
        return Double.isNaN(value) ? NA : fixed(value, RF_DECIMALS);
    }

    /** A fraction of an image dimension, at {@link #GEOMETRY_DECIMALS}. */
    static String geometry(double value) {
        return Double.isNaN(value) ? NA : fixed(value, GEOMETRY_DECIMALS);
    }

    /**
     * An integration value: a sum over thousands of pixels, so the integer part is the
     * measurement and any fractional digits are noise. Printed without an exponent, since
     * these routinely exceed a million and scientific notation is awkward in a spreadsheet.
     */
    static String integration(double value) {
        if (Double.isNaN(value)) return NA;
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** A user-entered value, echoed without inventing trailing zeros. */
    static String asEntered(double value) {
        if (Double.isNaN(value)) return NA;
        BigDecimal b = BigDecimal.valueOf(value).stripTrailingZeros();
        return (b.scale() < 0 ? b.setScale(0) : b).toPlainString();
    }

    /** A value rounded to {@code sigFigs} significant figures, without an exponent. */
    static String significant(double value, int sigFigs) {
        if (Double.isNaN(value)) return NA;
        if (value == 0) return "0";
        return BigDecimal.valueOf(value).round(new MathContext(sigFigs, RoundingMode.HALF_UP))
                         .stripTrailingZeros().toPlainString();
    }

    /**
     * Full precision, for numbers that reproduce the analysis rather than report a
     * measurement. Rounding these would change the result, so they are never shortened.
     */
    static String exact(double value) {
        return Double.isNaN(value) ? NA : Double.toString(value);
    }

    private static String fixed(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString();
    }
}
