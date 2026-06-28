package se.katalystnord.tlcdigitizer.pipeline;

import ij.ImagePlus;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ImagePreparationTest {

    private static ImagePlus makeRgb(int r, int g, int b) {
        int packed = (r << 16) | (g << 8) | b;
        int[] pixels = new int[100];
        Arrays.fill(pixels, packed);
        return new ImagePlus("test", new ColorProcessor(10, 10, pixels));
    }

    // -------------------------------------------------------------------------
    // sRGB linearisation LUT
    // -------------------------------------------------------------------------

    @Test
    public void lut_blackAndWhiteEndpoints() {
        assertEquals(0.0f,   ImagePreparation.SRGB_TO_LINEAR[0],   0.01f);
        assertEquals(255.0f, ImagePreparation.SRGB_TO_LINEAR[255],  0.5f);
    }

    @Test
    public void lut_midGrayIsLowerThanLinear() {
        // sRGB 127 encodes a perceptual mid-tone; its linear value is ~54.6, not 127.
        float linear = ImagePreparation.SRGB_TO_LINEAR[127];
        assertTrue("sRGB 127 should linearise to ~54 (got " + linear + ")",
                   linear > 40f && linear < 70f);
    }

    @Test
    public void lut_isMonotonicallyIncreasing() {
        for (int i = 1; i < 256; i++) {
            assertTrue("LUT must be non-decreasing at index " + i,
                       ImagePreparation.SRGB_TO_LINEAR[i] >= ImagePreparation.SRGB_TO_LINEAR[i - 1]);
        }
    }

    // -------------------------------------------------------------------------
    // toLuminanceGrayscale
    // -------------------------------------------------------------------------

    @Test
    public void luminance_whiteInputReturns255() {
        FloatProcessor fp = ImagePreparation.toLuminanceGrayscale(makeRgb(255, 255, 255));
        assertEquals(255.0, fp.getf(5, 5), 0.5);
    }

    @Test
    public void luminance_blackInputReturnsZero() {
        FloatProcessor fp = ImagePreparation.toLuminanceGrayscale(makeRgb(0, 0, 0));
        assertEquals(0.0, fp.getf(5, 5), 0.01);
    }

    @Test
    public void luminance_neutralGrayIsLinearised() {
        // Before fix: output ≈ 127. After fix: output ≈ 54.6.
        FloatProcessor fp = ImagePreparation.toLuminanceGrayscale(makeRgb(127, 127, 127));
        float val = fp.getf(5, 5);
        assertTrue("Neutral sRGB 127 should linearise to ~54, not ~127; got " + val,
                   val > 40f && val < 70f);
    }

    @Test
    public void luminance_pureRedChannelWeightedCorrectly() {
        // Pure red sRGB 255 → linear 255 → luminance = 0.2126 * 255 ≈ 54.2
        FloatProcessor fp = ImagePreparation.toLuminanceGrayscale(makeRgb(255, 0, 0));
        assertEquals(0.2126f * 255f, fp.getf(5, 5), 1.0f);
    }

    // -------------------------------------------------------------------------
    // extractGreenChannel
    // -------------------------------------------------------------------------

    @Test
    public void greenChannel_pureGreenIsLinearised() {
        FloatProcessor fp = ImagePreparation.extractGreenChannel(makeRgb(0, 200, 0));
        float expected = ImagePreparation.SRGB_TO_LINEAR[200];
        assertEquals(expected, fp.getf(5, 5), 0.1f);
    }

    @Test
    public void greenChannel_zeroGreenReturnsZero() {
        FloatProcessor fp = ImagePreparation.extractGreenChannel(makeRgb(255, 0, 255));
        assertEquals(0.0f, fp.getf(5, 5), 0.01f);
    }
}
