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
 *    Y = 0.2126R + 0.7152G + 0.0722B  (linear RGB, per TLCyzer paper)
 *
 * B) Green channel extraction:
 *    For UV-fluorescence images where the green channel gives the best spot/background
 *    contrast (per Anton et al. 2023, Thin-layer chromatography quantification of
 *    ibuprofen using digital imaging).
 *
 * Output pixels are in [0.0, 255.0] for 8-bit input, [0.0, 65535.0] for 16-bit.
 */
public final class ImagePreparation {

    private ImagePreparation() {}

    /**
     * Converts {@code imp} to grayscale using the ITU-R BT.709 luminance formula.
     * Handles RGB, RGBA, 8-bit gray, and 16-bit gray input.
     */
    public static FloatProcessor toLuminanceGrayscale(ImagePlus imp) {
        ImageProcessor ip = resolveProcessor(imp);

        if (ip instanceof ColorProcessor) {
            return rgbToLuminance((ColorProcessor) ip);
        }

        // Already grayscale — just convert to float
        return ip.convertToFloatProcessor();
    }

    /**
     * Extracts the green channel from a colour image.
     * For grayscale input, returns a copy of the image.
     * Validated for UV-fluorescence TLC images (Anton et al. 2023).
     */
    public static FloatProcessor extractGreenChannel(ImagePlus imp) {
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
                out[y * width + x] = rgb[1]; // green channel
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

    private static FloatProcessor rgbToLuminance(ColorProcessor cp) {
        int width = cp.getWidth();
        int height = cp.getHeight();
        float[] out = new float[width * height];

        int[] rgb = new int[3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cp.getPixel(x, y, rgb);
                // ITU-R BT.709 coefficients
                out[y * width + x] = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
            }
        }

        return new FloatProcessor(width, height, out, null);
    }
}
