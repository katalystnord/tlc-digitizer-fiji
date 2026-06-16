package se.katalystnord.tlcdigitizer.ui;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.plugin.frame.RoiManager;
import se.katalystnord.tlcdigitizer.export.CsvExporter;
import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.model.Spot;
import se.katalystnord.tlcdigitizer.pipeline.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the 7-stage wizard pipeline.
 *
 * Each step presents a dialog or overlay-based interaction, runs the algorithm,
 * and populates the next fields of {@link AnalysisState}. A step returning false
 * means the user cancelled; the wizard stops.
 *
 * This class contains the UI logic only — all scientific computation is
 * delegated to the stateless pipeline classes.
 */
public class WizardController {

    private final AnalysisState state;

    public WizardController(AnalysisState state) {
        this.state = state;
    }

    /** Runs all 7 steps in sequence. Returns false if the user cancels at any step. */
    public boolean run() {
        return step1_prepare()
            && step2_perspectiveCorrection()
            && step3_backgroundCorrection()
            && step4_markOriginAndFront()
            && step5_spotDetection()
            && step6_calibration()
            && step7_export();
    }

    // -------------------------------------------------------------------------
    // Step 1: Image preparation
    // -------------------------------------------------------------------------

    boolean step1_prepare() {
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 1: Image");
        gd.addMessage("Convert the image to grayscale.\n" +
                      "For UV-fluorescence images, try 'Green channel'.");
        String[] methods = {"Luminance (Y = 0.2126R + 0.7152G + 0.0722B)", "Green channel only"};
        gd.addChoice("Conversion method:", methods, methods[0]);
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        boolean useGreen = gd.getNextChoiceIndex() == 1;
        if (useGreen) {
            state.grayscale = ImagePreparation.extractGreenChannel(state.originalImage);
        } else {
            state.grayscale = ImagePreparation.toLuminanceGrayscale(state.originalImage);
        }

        ImagePlus preview = new ImagePlus("Grayscale preview", state.grayscale.duplicate());
        preview.show();
        IJ.log("[Step 1] Grayscale conversion done. Size: " +
               state.grayscale.getWidth() + "×" + state.grayscale.getHeight());
        return true;
    }

    // -------------------------------------------------------------------------
    // Step 2: Perspective correction
    // -------------------------------------------------------------------------

    boolean step2_perspectiveCorrection() {
        // Auto-detect corners
        state.corners = PerspectiveCorrection.detectCorners(state.grayscale);

        // Show image with corner handles for user adjustment
        ImagePlus work = new ImagePlus("Plate corners — adjust if needed", state.grayscale.duplicate());
        Overlay ov = new Overlay();
        PointRoi corners = new PointRoi(
            new float[]{state.corners[0], state.corners[2], state.corners[4], state.corners[6]},
            new float[]{state.corners[1], state.corners[3], state.corners[5], state.corners[7]},
            4);
        corners.setName("corners");
        ov.add(corners);
        work.setOverlay(ov);
        work.show();
        IJ.setTool("multipoint");

        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 2: Perspective");
        gd.addMessage("Verify the four plate corners (TL, TR, BR, BL).\n" +
                      "Drag the yellow points to correct any misplacement.\n" +
                      "Click OK when the corners are correct.");
        // Allow manual override via coordinate fields
        gd.addNumericField("TL X:", state.corners[0], 1);
        gd.addNumericField("TL Y:", state.corners[1], 1);
        gd.addNumericField("TR X:", state.corners[2], 1);
        gd.addNumericField("TR Y:", state.corners[3], 1);
        gd.addNumericField("BR X:", state.corners[4], 1);
        gd.addNumericField("BR Y:", state.corners[5], 1);
        gd.addNumericField("BL X:", state.corners[6], 1);
        gd.addNumericField("BL Y:", state.corners[7], 1);
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        state.corners = new float[]{
            (float) gd.getNextNumber(), (float) gd.getNextNumber(),
            (float) gd.getNextNumber(), (float) gd.getNextNumber(),
            (float) gd.getNextNumber(), (float) gd.getNextNumber(),
            (float) gd.getNextNumber(), (float) gd.getNextNumber()
        };

        work.close();

        state.corrected = PerspectiveCorrection.warpImage(state.grayscale, state.corners);
        ImagePlus corrPreview = new ImagePlus("Corrected plate", state.corrected.duplicate());
        corrPreview.show();
        IJ.log("[Step 2] Perspective correction done. Output: " +
               state.corrected.getWidth() + "×" + state.corrected.getHeight());
        return true;
    }

    // -------------------------------------------------------------------------
    // Step 3: Background correction
    // -------------------------------------------------------------------------

    boolean step3_backgroundCorrection() {
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 3: Background");
        gd.addMessage("Select background correction method.\n" +
                      "Option A (quartic polynomial) is recommended for most plates.\n" +
                      "Option B (Savitzky-Golay) is better for non-uniform charring.");
        String[] methods = {"A — Quartic polynomial (recommended)", "B — Savitzky-Golay"};
        gd.addChoice("Method:", methods, methods[0]);
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        int method = gd.getNextChoiceIndex();
        state.usedPolynomialBackground = (method == 0);

        IJ.showStatus("Fitting background model…");
        if (state.usedPolynomialBackground) {
            state.corrected = BackgroundCorrection.fitAndSubtract(state.corrected);
        } else {
            // Option B (per-spot polynomial) runs after spot detection in Step 5.
            // No global image correction here — just log and continue.
            IJ.log("[Step 3] Per-spot polynomial (Option B) deferred to integration step.");
        }
        IJ.showStatus("");

        ImagePlus bgPreview = new ImagePlus("Background corrected", state.corrected.duplicate());
        bgPreview.show();
        IJ.log("[Step 3] Background correction done.");
        return true;
    }

    // -------------------------------------------------------------------------
    // Step 4: Mark origin and solvent front
    // -------------------------------------------------------------------------

    boolean step4_markOriginAndFront() {
        int height = state.corrected.getHeight();
        int width = state.corrected.getWidth();

        // Guess: origin at 90% of height, front at 10% of height
        float defaultOrigin = 0.90f;
        float defaultFront = 0.10f;

        ImagePlus work = new ImagePlus("Mark origin and solvent front", state.corrected.duplicate());
        Overlay ov = new Overlay();
        Line originLine = new Line(0, (int) (defaultOrigin * height), width, (int) (defaultOrigin * height));
        originLine.setName("origin");
        originLine.setStrokeColor(Color.RED);
        Line frontLine = new Line(0, (int) (defaultFront * height), width, (int) (defaultFront * height));
        frontLine.setName("front");
        frontLine.setStrokeColor(Color.BLUE);
        ov.add(originLine);
        ov.add(frontLine);
        work.setOverlay(ov);
        work.show();

        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 4: Rf Reference Lines");
        gd.addMessage("Enter the Y positions of the origin (application point) and solvent front\n" +
                      "as fractions of the image height (0.0 = top, 1.0 = bottom).\n" +
                      "Red line = origin, Blue line = solvent front.");
        gd.addNumericField("Origin Y fraction (0–1):", defaultOrigin, 3);
        gd.addNumericField("Solvent front Y fraction (0–1):", defaultFront, 3);
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        state.originYFraction = (float) gd.getNextNumber();
        state.frontYFraction = (float) gd.getNextNumber();

        if (state.originYFraction <= state.frontYFraction) {
            IJ.error("Origin must be below the solvent front (origin Y > front Y).");
            return false;
        }

        work.close();
        IJ.log("[Step 4] Origin=" + state.originYFraction + " Front=" + state.frontYFraction);
        return true;
    }

    // -------------------------------------------------------------------------
    // Step 5: Spot detection
    // -------------------------------------------------------------------------

    boolean step5_spotDetection() {
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 5: Spot Detection");
        gd.addMessage("Spots are detected automatically at the image mean threshold.\n" +
                      "Adjust the multiplier if too few or too many spots appear.");
        gd.addNumericField("Threshold multiplier:", 1.0, 2);
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        state.thresholdFactor = gd.getNextNumber();

        IJ.showStatus("Detecting spots…");
        state.spots = SpotDetector.detect(state.corrected);
        IJ.showStatus("");

        // Assign lane numbers (left-to-right, 1-indexed)
        LaneAssigner.assignLanes(state.spots, state.corrected.getWidth());

        // Assign Rf values
        RfCalculator.assignAll(state.spots, state.originYFraction, state.frontYFraction);

        // Integrate
        SpotIntegrator.integrateAll(state.corrected, state.spots);

        // Option B: apply per-spot polynomial background correction after integration
        if (!state.usedPolynomialBackground) {
            IJ.showStatus("Fitting per-spot background polynomial…");
            BackgroundCorrection.applyPerSpotPolynomial(
                state.spots, state.corrected, BackgroundCorrection.DEFAULT_SG_DEGREE);
            IJ.showStatus("");
            IJ.log("[Step 5] Per-spot polynomial background correction applied (degree " +
                   BackgroundCorrection.DEFAULT_SG_DEGREE + ").");
        }

        // Show detected spots as overlay
        ImagePlus work = new ImagePlus("Detected spots", state.corrected.duplicate());
        showSpotOverlay(work, state.spots);
        work.show();

        IJ.log("[Step 5] Detected " + state.spots.size() + " spots.");

        // Let user confirm or adjust
        GenericDialog confirm = new GenericDialog("TLC Digitizer — Step 5: Confirm Spots");
        confirm.addMessage(state.spots.size() + " spots detected.\n" +
                           "Review the overlay. If spots are wrong, cancel and re-run\n" +
                           "with a different threshold multiplier.");
        confirm.showDialog();
        work.close();
        return !confirm.wasCanceled();
    }

    // -------------------------------------------------------------------------
    // Step 6: Calibration
    // -------------------------------------------------------------------------

    boolean step6_calibration() {
        if (state.spots.isEmpty()) {
            IJ.error("No spots to calibrate.");
            return false;
        }

        // Build a numbered list of spots for the user to assign references
        StringBuilder sb = new StringBuilder("Spot ID  Rf      Integration\n");
        for (Spot s : state.spots) {
            sb.append(String.format("  %3d   %.3f   %.2f%n", s.id, s.rfValue, s.integrationValue));
        }

        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 6: Calibration");
        gd.addMessage(sb.toString());
        gd.addMessage("Enter reference spot IDs and known concentrations.\n" +
                      "Minimum 3 reference points (ICH Q2(R1)).\n" +
                      "Leave concentration as 0 to skip a row.");

        int rows = Math.min(8, state.spots.size());
        for (int i = 0; i < rows; i++) {
            gd.addNumericField("Spot ID for ref " + (i + 1) + ":", -1, 0);
            gd.addNumericField("Concentration (µg/mL):", 0.0, 4);
        }
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        // Mark reference spots
        for (int i = 0; i < rows; i++) {
            int id = (int) gd.getNextNumber();
            double conc = gd.getNextNumber();
            if (id >= 0 && conc > 0) {
                state.spots.stream()
                    .filter(s -> s.id == id)
                    .findFirst()
                    .ifPresent(s -> {
                        s.isReference = true;
                        s.referenceConcentration = conc;
                    });
            }
        }

        List<Spot> refs = state.spots.stream().filter(s -> s.isReference).collect(Collectors.toList());
        if (refs.size() < 3) {
            IJ.error("At least 3 reference spots are required. Only " + refs.size() + " assigned.");
            return false;
        }

        try {
            state.calibrationModel = CalibrationModel.fit(refs);
            state.calibrationModel.applyTo(state.spots);
            IJ.log("[Step 6] " + state.calibrationModel.toSummary());
        } catch (IllegalArgumentException e) {
            IJ.error("Calibration failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Step 7: Export
    // -------------------------------------------------------------------------

    boolean step7_export() {
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 7: Export");
        gd.addMessage("Save results to CSV.");
        gd.addStringField("Output file:", System.getProperty("user.home") + "/tlc_results.csv", 40);
        gd.showDialog();
        if (gd.wasCanceled()) return false;

        String path = gd.getNextString().trim();
        File out = new File(path);

        try {
            CsvExporter.export(state, out);
            IJ.log("[Step 7] Results saved to: " + out.getAbsolutePath());
            IJ.showMessage("TLC Digitizer", "Results saved to:\n" + out.getAbsolutePath());
        } catch (IOException e) {
            IJ.error("Export failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void showSpotOverlay(ImagePlus imp, List<Spot> spots) {
        Overlay ov = new Overlay();
        for (Spot s : spots) {
            ij.gui.OvalRoi oval = new ij.gui.OvalRoi(
                s.centroidX - s.radius, s.centroidY - s.radius,
                s.radius * 2, s.radius * 2);
            oval.setName("spot_" + s.id);
            oval.setStrokeColor(Color.YELLOW);
            ov.add(oval);
        }
        imp.setOverlay(ov);
    }
}
