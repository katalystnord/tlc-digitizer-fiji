package se.katalystnord.tlcdigitizer.pipeline;

import ij.ImagePlus;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Stage 1: Import and prepare the image.
 *
 * Converts colour or grayscale input to a float-valued grayscale FloatProcessor
 * using one of two validated methods:
 *
 * A) Luminance-preserving weighted sum:
 *    Y = 0.2126R + 0.7152G + 0.0722B  (ITU-R BT.709, applied to linear light)
 *    Smartphone JPEGs and most camera images are sRGB-encoded; the sRGB inverse
 *    transfer function is applied per channel before the luminance sum so that the
 *    output values are proportional to physical light energy (required for
 *    Beer-Lambert linearity in quantitative TLC).
 *
 * B) Green channel extraction:
 *    For UV-fluorescence images where the green channel gives the best spot/background
 *    contrast (per Anton et al. 2023). The channel is linearised from sRGB before
 *    returning, for the same reason as (A).
 *
 * Output pixels are in [0.0, 255.0] linear-light units for 8-bit input.
 * Grayscale (non-RGB) input is returned as-is via convertToFloatProcessor().
 */
public final class ImagePreparation {

    private ImagePreparation() {}

    /**
     * Lookup table: maps an 8-bit sRGB component value (0–255) to its linearised
     * equivalent on the same [0, 255] scale.  Built once at class-load time.
     *
     * <p>sRGB inverse transfer function (IEC 61966-2-1):
     * <pre>
     *   C_linear = C_srgb / 12.92                         if C_srgb ≤ 0.04045
     *   C_linear = ((C_srgb + 0.055) / 1.055) ^ 2.4      otherwise
     * </pre>
     */
    static final float[] SRGB_TO_LINEAR = buildSrgbLut();

    private static float[] buildSrgbLut() {
        float[] lut = new float[256];
        for (int i = 0; i < 256; i++) {
            float v = i / 255.0f;
            float linear = (v <= 0.04045f)
                    ? v / 12.92f
                    : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
            lut[i] = linear * 255.0f;
        }
        return lut;
    }

    /**
     * Converts {@code imp} to grayscale using the ITU-R BT.709 luminance formula,
     * linearising sRGB first. Equivalent to {@code toLuminanceGrayscale(imp, true)}.
     *
     * @see #toLuminanceGrayscale(ImagePlus, boolean) for why the choice matters
     */
    public static FloatProcessor toLuminanceGrayscale(ImagePlus imp) {
        return toLuminanceGrayscale(imp, true);
    }

    /**
     * Converts {@code imp} to grayscale using the ITU-R BT.709 luminance formula.
     * Handles RGB, RGBA, 8-bit gray, and 16-bit gray input.
     *
     * <p><b>Why {@code linearise} is a parameter and not a constant.</b> Photometric
     * linearity and background-model fidelity want opposite things, and the pipeline
     * needs both images:
     * <ul>
     *   <li><b>{@code true} (linear light)</b> is physically correct for
     *       <i>quantification</i>: an integration value is only proportional to the
     *       amount of analyte if the pixel values are proportional to light intensity.
     *       Summing gamma-encoded values measures the wrong quantity, and the error
     *       grows across a wide concentration range.</li>
     *   <li><b>{@code false} (raw sRGB)</b> is what Stage 3's quartic background model
     *       needs. The illumination gradient of a UV lamp is well approximated by a
     *       15-coefficient quartic in the encoded space; after the sRGB inverse
     *       transfer function (a 2.4 power) it is no longer quartic-shaped, and the
     *       fit leaves a residual gradient across the plate. That residual raises the
     *       detection threshold on the dim side of the plate and shrinks the spot
     *       regions there — and because {@link SpotIntegrator} sums the top 15% of
     *       pixels <i>within</i> each region, region area then carries the
     *       concentration signal backwards.</li>
     * </ul>
     *
     * <p>Measured on the three TLCyzer reference plates (2026-09-01): linearising
     * everywhere gives pooled LOO recovery 97.56% / RSD 12.67% and needs a hand-picked
     * threshold per plate; detecting on raw sRGB while integrating on linear light
     * gives 100.77% / 6.33% with a single threshold of 1.0 for every plate and 5/5
     * spots auto-detected on all three. See CLAUDE.md, "Colour space".
     *
     * @param linearise whether to apply the sRGB inverse transfer function
     */
    public static FloatProcessor toLuminanceGrayscale(ImagePlus imp, boolean linearise) {
        ImageProcessor ip = resolveProcessor(imp);

        if (ip instanceof ColorProcessor) {
            return rgbToLuminance((ColorProcessor) ip, linearise);
        }

        // Already grayscale — just convert to float
        return ip.convertToFloatProcessor();
    }

    /**
     * Extracts the green channel from a colour image, linearising sRGB first.
     * Equivalent to {@code extractGreenChannel(imp, true)}.
     */
    public static FloatProcessor extractGreenChannel(ImagePlus imp) {
        return extractGreenChannel(imp, true);
    }

    /**
     * Extracts the green channel from a colour image.
     * For grayscale input, returns a copy of the image.
     * Validated for UV-fluorescence TLC images (Anton et al. 2023).
     *
     * @param linearise whether to apply the sRGB inverse transfer function; see
     *                  {@link #toLuminanceGrayscale(ImagePlus, boolean)} for why the
     *                  detection and integration paths want different answers
     */
    public static FloatProcessor extractGreenChannel(ImagePlus imp, boolean linearise) {
        ImageProcessor ip = resolveProcessor(imp);

        if (!(ip instanceof ColorProcessor)) {
            // Grayscale input — green channel extraction degrades to a copy
            return ip.convertToFloatProcessor();
        }

        ColorProcessor cp = (ColorProcessor) ip;
        int width = cp.getWidth();
        int height = cp.getHeight();
        float[] out = new float[width * height];

        int[] rgb = new int[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cp.getPixel(x, y, rgb);
                out[y * width + x] = linearise ? SRGB_TO_LINEAR[rgb[1]] : rgb[1];
            }
        }

        return new FloatProcessor(width, height, out, null);
    }

    // -------------------------------------------------------------------------

    /**
     * Returns the active {@link ImageProcessor} for {@code imp}, forcing
     * processor construction for SCIFIO-opened or hyperstack images where
     * {@link ImagePlus#getProcessor()} may return null before the image is
     * explicitly rendered.
     *
     * @throws IllegalStateException if no processor can be obtained
     */
    private static ImageProcessor resolveProcessor(ImagePlus imp) {
        ImageProcessor ip = imp.getProcessor();
        if (ip == null && imp.getStackSize() >= 1) {
            // SCIFIO / lazy-loaded images: fetch via the stack directly
            ip = imp.getStack().getProcessor(Math.max(1, imp.getCurrentSlice()));
        }
        if (ip == null) {
            throw new IllegalStateException(
                "Cannot read image data from '" + imp.getTitle() + "'.\n" +
                "Try Image ▶ Type ▶ RGB Color in Fiji to convert the image first.");
        }
        return ip;
    }

    private static FloatProcessor rgbToLuminance(ColorProcessor cp, boolean linearise) {
        int width = cp.getWidth();
        int height = cp.getHeight();
        float[] out = new float[width * height];

        int[] rgb = new int[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cp.getPixel(x, y, rgb);
                float r = linearise ? SRGB_TO_LINEAR[rgb[0]] : rgb[0];
                float g = linearise ? SRGB_TO_LINEAR[rgb[1]] : rgb[1];
                float b = linearise ? SRGB_TO_LINEAR[rgb[2]] : rgb[2];
                out[y * width + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            }
        }

        return new FloatProcessor(width, height, out, null);
    }
}
