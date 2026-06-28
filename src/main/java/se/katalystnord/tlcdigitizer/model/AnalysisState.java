package se.katalystnord.tlcdigitizer.model;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.pipeline.CalibrationModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable state threaded through the 7-stage wizard pipeline.
 * Each stage reads from this object, performs its computation,
 * and writes its output back here.
 */
public class AnalysisState {

    /** The original image as opened in Fiji. */
    public ImagePlus originalImage;

    /** Grayscale representation after Stage 1 (luminance or green channel). */
    public FloatProcessor grayscale;

    /**
     * Four corner points [tlX, tlY, trX, trY, brX, brY, blX, blY] in the
     * original grayscale image's pixel coordinates.
     * Set by Stage 2. Used to warp the image.
     */
    public float[] corners;

    /** Perspective-corrected, background-subtracted image after Stages 2–3. */
    public FloatProcessor corrected;

    /**
     * Origin line Y position as a fraction of correctedHeight (0 = top).
     * Set interactively before Stage 5.
     */
    public float originYFraction = Float.NaN;

    /**
     * Solvent front Y position as a fraction of correctedHeight (0 = top).
     * Set interactively before Stage 5.
     */
    public float frontYFraction = Float.NaN;

    /** All detected spots after Stage 4. Mutable — user can add/remove. */
    public List<Spot> spots = new ArrayList<>();

    /** Calibration model fitted in Stage 7. Null until then. */
    public CalibrationModel calibrationModel;

    /**
     * Perspective-corrected image BEFORE background subtraction.
     * Set in Stage 2, used as the source for Stage 3 so that background
     * correction can be re-run cleanly on Back navigation.
     */
    public FloatProcessor perspCorrected;

    /** Whether background correction used the polynomial (A) or Savitzky-Golay (B) method. */
    public boolean usedPolynomialBackground = true;

    /** Polynomial degree for per-spot Savitzky-Golay correction (1–8). */
    public int sgDegree = 5;

    /** Threshold multiplier used for spot detection (default: 1.0 = mean). */
    public double thresholdFactor = 1.0;
}
