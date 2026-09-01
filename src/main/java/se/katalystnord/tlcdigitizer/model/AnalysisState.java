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

    /** Grayscale representation after Stage 1 (luminance or green channel), in RAW sRGB.
     *  This is the detection/background-correction image. */
    public FloatProcessor grayscale;

    /**
     * The same Stage 1 grayscale in LINEAR light (sRGB inverse transfer function applied).
     * Used only as the integration base, so that integration values are proportional to
     * light intensity while the quartic background model still sees the encoded gradient
     * it can actually fit. See {@code ImagePreparation.toLuminanceGrayscale(ImagePlus,
     * boolean)} for the rationale and measured effect.
     */
    public FloatProcessor grayscaleLinear;

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

    /**
     * Perspective-corrected LINEAR-light image, before background subtraction.
     * The integration base for polynomial / Savitzky-Golay modes, mirroring
     * {@code ValidationRunner}'s {@code integrationBase}.
     */
    public FloatProcessor perspCorrectedLinear;

    /**
     * The image spot integration should read, mirroring {@code ValidationRunner}.
     *
     * <p>Top-hat mode integrates {@link #corrected} directly: its background is already
     * removed and there is no global surface to over-subtract. Every other mode
     * integrates the linear-light perspective-corrected image ({@link
     * #perspCorrectedLinear}), so integration values are proportional to light intensity
     * and the global quartic influences detection only. Falls back to {@link #corrected}
     * if the linear image is unavailable (e.g. grayscale input).
     */
    public FloatProcessor integrationBase() {
        if (usedTopHatBackground || perspCorrectedLinear == null) return corrected;
        return perspCorrectedLinear;
    }

    /** True when the plate has dark spots on a bright background (staining, UV 254 nm). */
    public boolean invertImage = false;

    /** Whether background correction used the polynomial (A) or Savitzky-Golay (B) method. */
    public boolean usedPolynomialBackground = true;

    /** Whether background correction used the white top-hat transform (C). */
    public boolean usedTopHatBackground = false;

    /** Structuring element radius used for top-hat correction (pixels; 0 = auto). */
    public float topHatSeRadius = 0f;

    /** Polynomial degree for per-spot Savitzky-Golay correction (1–8). */
    public int sgDegree = 5;

    /** Threshold multiplier used for spot detection (default: 1.0 = mean). */
    public double thresholdFactor = 1.0;

    /**
     * Whether spot detection used shape-aware hysteresis linking (beta, opt-in) instead
     * of the legacy fixed-radius circle. See {@code SpotDetector} class javadoc.
     */
    public boolean shapeAwareDetection = false;

    /**
     * Whether spot-to-lane assignment used CWT-based lane-boundary detection (beta,
     * opt-in) instead of {@code LaneAssigner}'s legacy centroid-gap clustering.
     * See {@code LaneDetector} class javadoc.
     */
    public boolean laneDetectionEnabled = false;

    /**
     * Whether spot detection thresholded a trained Labkit pixel-classification probability
     * map (beta, opt-in) instead of raw corrected-image intensity. See
     * {@code TrainableClassifier} class javadoc. The user-marked training regions themselves
     * are not currently logged (see CLAUDE.md's Phase B write-up for this known gap).
     */
    public boolean labkitDetectionEnabled = false;
}
