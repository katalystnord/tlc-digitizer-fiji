package se.katalystnord.tlcdigitizer.ui;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.WaitForUserDialog;
import ij.process.FloatPolygon;
import ij.plugin.frame.RoiManager;
import se.katalystnord.tlcdigitizer.export.CsvExporter;
import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.model.Spot;
import se.katalystnord.tlcdigitizer.pipeline.*;

import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
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
    /** The currently-visible step result window; closed automatically when the next step opens. */
    private ImagePlus currentDisplay = null;

    public WizardController(AnalysisState state) {
        this.state = state;
    }

    /**
     * Shows {@code imp} and closes the previous step's display window.
     * Keeps the screen tidy — at most one step-result window open at a time.
     */
    private void showDisplay(ImagePlus imp) {
        if (currentDisplay != null && currentDisplay.isVisible()) {
            currentDisplay.close();
        }
        currentDisplay = imp;
        imp.show();
    }

    /** Return value from each wizard step. */
    private enum StepResult { CONTINUE, BACK, CANCEL }

    /**
     * Runs the 7-step wizard with forward/back navigation.
     * Returns false if the user cancels at any step.
     */
    public boolean run() {
        int step = 1;
        while (step >= 1 && step <= 7) {
            StepResult result;
            switch (step) {
                case 1:  result = step1_prepare();              break;
                case 2:  result = step2_perspectiveCorrection(); break;
                case 3:  result = step3_backgroundCorrection(); break;
                case 4:  result = step4_markOriginAndFront();   break;
                case 5:  result = step5_spotDetection();        break;
                case 6:  result = step6_calibration();          break;
                case 7:  result = step7_export();               break;
                default: return false;
            }
            if (result == StepResult.CANCEL) return false;
            if (result == StepResult.BACK) {
                // Close the current step's display before re-entering the previous one
                if (currentDisplay != null && currentDisplay.isVisible()) {
                    currentDisplay.close();
                    currentDisplay = null;
                }
                step = Math.max(1, step - 1);
            } else {
                step++;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Step 1: Image preparation
    // -------------------------------------------------------------------------

    StepResult step1_prepare() {
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 1: Image");
        gd.addMessage("Convert the image to grayscale.\n" +
                      "For UV-fluorescence images, try 'Green channel'.");
        String[] methods = {"Luminance (Y = 0.2126R + 0.7152G + 0.0722B)", "Green channel only"};
        gd.addChoice("Conversion method:", methods, methods[0]);
        gd.showDialog();
        if (gd.wasCanceled()) return StepResult.CANCEL;

        boolean useGreen = gd.getNextChoiceIndex() == 1;
        if (useGreen) {
            state.grayscale = ImagePreparation.extractGreenChannel(state.originalImage);
        } else {
            state.grayscale = ImagePreparation.toLuminanceGrayscale(state.originalImage);
        }

        ImagePlus preview = new ImagePlus("Grayscale preview", state.grayscale.duplicate());
        showDisplay(preview);
        IJ.log("[Step 1] Grayscale conversion done. Size: " +
               state.grayscale.getWidth() + "×" + state.grayscale.getHeight());
        return StepResult.CONTINUE;
    }

    // -------------------------------------------------------------------------
    // Step 2: Perspective correction
    // -------------------------------------------------------------------------

    StepResult step2_perspectiveCorrection() {
        // Auto-detect corners
        state.corners = PerspectiveCorrection.detectCorners(state.grayscale);

        // Show image and set the 4-point ROI as the active (draggable) ROI.
        // Use showDisplay so this replaces the grayscale preview (no extra window).
        ImagePlus work = new ImagePlus("Plate corners — drag to correct", state.grayscale.duplicate());
        showDisplay(work); // closes grayscale preview
        IJ.setTool("multipoint");

        // Interactive corner dragging — retry loop handles accidental extra clicks.
        while (true) {
            setCornerRoi(work, state.corners);
            WaitForUserDialog wait = new WaitForUserDialog(
                "TLC Digitizer — Step 2: Perspective",
                "Drag the four yellow points to the plate corners.\n" +
                "Order: Top-Left, Top-Right, Bottom-Right, Bottom-Left.\n" +
                "Ctrl+Z undoes an accidental click.\n\n" +
                "Click OK when the corners are correct, or Cancel to abort.");
            wait.show();
            if (wait.escPressed()) {
                work.close();
                return StepResult.CANCEL;
            }

            ij.gui.Roi roi = work.getRoi();
            if (roi instanceof PointRoi) {
                FloatPolygon poly = roi.getFloatPolygon();
                if (poly.npoints == 4) {
                    readCornerRoi(work, state.corners);
                    break; // correct number of points
                }
                // Wrong count — tell user and let them try again
                IJ.showMessage("Step 2",
                    "Found " + poly.npoints + " corner points — need exactly 4.\n" +
                    "Use Ctrl+Z to undo any accidental clicks, then click OK again.");
            } else {
                break; // no ROI changed — use auto-detected corners
            }
        }

        // Numeric fine-tuning dialog
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 2: Fine-tune corners");
        gd.addMessage("Adjust corner coordinates if needed, then click OK.\n" +
                      "Tick 'Back' to return to Step 1 (grayscale conversion).");
        gd.addNumericField("TL X:", state.corners[0], 1);
        gd.addNumericField("TL Y:", state.corners[1], 1);
        gd.addNumericField("TR X:", state.corners[2], 1);
        gd.addNumericField("TR Y:", state.corners[3], 1);
        gd.addNumericField("BR X:", state.corners[4], 1);
        gd.addNumericField("BR Y:", state.corners[5], 1);
        gd.addNumericField("BL X:", state.corners[6], 1);
        gd.addNumericField("BL Y:", state.corners[7], 1);
        gd.addCheckbox("← Back to Step 1", false);
        gd.showDialog();
        if (gd.wasCanceled()) {
            work.close();
            return StepResult.CANCEL;
        }

        state.corners = new float[]{
            (float) gd.getNextNumber(), (float) gd.getNextNumber(),
            (float) gd.getNextNumber(), (float) gd.getNextNumber(),
            (float) gd.getNextNumber(), (float) gd.getNextNumber(),
            (float) gd.getNextNumber(), (float) gd.getNextNumber()
        };
        boolean backToStep1 = gd.getNextBoolean();
        work.close();
        if (backToStep1) return StepResult.BACK;

        state.corrected = PerspectiveCorrection.warpImage(state.grayscale, state.corners);
        ImagePlus corrPreview = new ImagePlus("Corrected plate", state.corrected.duplicate());
        showDisplay(corrPreview);
        IJ.log("[Step 2] Perspective correction done. Output: " +
               state.corrected.getWidth() + "×" + state.corrected.getHeight());
        return StepResult.CONTINUE;
    }

    /** Sets the four corner positions as the active draggable PointRoi on {@code imp}. */
    private static void setCornerRoi(ImagePlus imp, float[] corners) {
        PointRoi roi = new PointRoi(
            new float[]{corners[0], corners[2], corners[4], corners[6]},
            new float[]{corners[1], corners[3], corners[5], corners[7]},
            4);
        imp.setRoi(roi);
    }

    /**
     * Reads the current PointRoi from {@code imp} back into {@code corners}.
     * If the ROI is missing or has the wrong point count, {@code corners} is unchanged.
     */
    private static void readCornerRoi(ImagePlus imp, float[] corners) {
        ij.gui.Roi roi = imp.getRoi();
        if (!(roi instanceof PointRoi)) return;
        FloatPolygon poly = roi.getFloatPolygon();
        if (poly.npoints != 4) return;
        corners[0] = poly.xpoints[0]; corners[1] = poly.ypoints[0]; // TL
        corners[2] = poly.xpoints[1]; corners[3] = poly.ypoints[1]; // TR
        corners[4] = poly.xpoints[2]; corners[5] = poly.ypoints[2]; // BR
        corners[6] = poly.xpoints[3]; corners[7] = poly.ypoints[3]; // BL
    }

    // -------------------------------------------------------------------------
    // Step 3: Background correction
    // -------------------------------------------------------------------------

    StepResult step3_backgroundCorrection() {
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 3: Background");
        gd.addMessage("Select background correction method.\n" +
                      "Option A (quartic polynomial) is recommended for most plates.\n" +
                      "Option B (Savitzky-Golay) is better for non-uniform charring.");
        String[] methods = {"A — Quartic polynomial (recommended)", "B — Savitzky-Golay"};
        gd.addChoice("Method:", methods, methods[0]);
        gd.addCheckbox("← Back to Step 2 (perspective correction)", false);
        gd.showDialog();
        if (gd.wasCanceled()) return StepResult.CANCEL;

        int method = gd.getNextChoiceIndex();
        boolean backToStep2 = gd.getNextBoolean();
        if (backToStep2) return StepResult.BACK;

        state.usedPolynomialBackground = (method == 0);

        IJ.showStatus("Fitting background model…");
        if (state.usedPolynomialBackground) {
            state.corrected = BackgroundCorrection.fitAndSubtract(state.corrected);
        } else {
            IJ.log("[Step 3] Per-spot polynomial (Option B) deferred to integration step.");
        }
        IJ.showStatus("");

        ImagePlus bgPreview = new ImagePlus("Background corrected", state.corrected.duplicate());
        showDisplay(bgPreview);
        IJ.log("[Step 3] Background correction done.");
        return StepResult.CONTINUE;
    }

    // -------------------------------------------------------------------------
    // Step 4: Mark origin and solvent front
    // -------------------------------------------------------------------------

    StepResult step4_markOriginAndFront() {
        final int width  = state.corrected.getWidth();
        final int height = state.corrected.getHeight();

        // Use showDisplay so the background-corrected preview is replaced, not stacked.
        final ImagePlus work = new ImagePlus("Mark origin and solvent front", state.corrected.duplicate());
        final Overlay ov = new Overlay();
        work.setOverlay(ov);
        showDisplay(work); // closes background-corrected preview

        // Build dialog with two numeric fields + Back checkbox
        final GenericDialog gd = new GenericDialog("TLC Digitizer — Step 4: Rf Reference Lines");
        gd.addMessage(
            "Red line = origin (spotting point)     Blue line = solvent front\n" +
            "Y fraction: 0.0 = top of image, 1.0 = bottom.\n" +
            "Type a value or use ↑ / ↓ arrow keys to step by 0.005.");
        gd.addNumericField("Origin Y fraction (0–1):", 0.90, 3);
        gd.addNumericField("Solvent front Y fraction (0–1):", 0.10, 3);
        gd.addCheckbox("← Back to Step 3 (background correction)", false);

        @SuppressWarnings("unchecked")
        final Vector<TextField> fields = gd.getNumericFields();
        final TextField originField = fields.get(0);
        final TextField frontField  = fields.get(1);

        drawRfLines(ov, work, width, height, 0.90f, 0.10f);

        gd.addDialogListener(new DialogListener() {
            public boolean dialogItemChanged(GenericDialog d, AWTEvent e) {
                liveUpdateRfLines(originField, frontField, ov, work, width, height);
                return true;
            }
        });

        final double STEP = 0.005;
        addArrowStepping(originField, STEP, new Runnable() {
            public void run() { liveUpdateRfLines(originField, frontField, ov, work, width, height); }
        });
        addArrowStepping(frontField, STEP, new Runnable() {
            public void run() { liveUpdateRfLines(originField, frontField, ov, work, width, height); }
        });

        gd.showDialog();
        // work stays open as the currentDisplay; step 5's showDisplay will replace it.
        if (gd.wasCanceled()) return StepResult.CANCEL;

        // Read Back checkbox directly (GenericDialog.getNextBoolean() order not reliable here
        // because we skipped getNextNumber() for the live-preview fields).
        @SuppressWarnings("unchecked")
        Checkbox backCheck4 = ((Vector<Checkbox>) gd.getCheckboxes()).get(0);
        if (backCheck4.getState()) return StepResult.BACK;

        try {
            state.originYFraction = clamp01(Float.parseFloat(originField.getText().trim()));
            state.frontYFraction  = clamp01(Float.parseFloat(frontField.getText().trim()));
        } catch (NumberFormatException e) {
            IJ.error("Invalid Y fraction value — please enter a number between 0 and 1.");
            return StepResult.CANCEL;
        }

        if (state.originYFraction <= state.frontYFraction) {
            IJ.error("Origin must be below the solvent front (origin Y > front Y).");
            return StepResult.CANCEL;
        }

        IJ.log("[Step 4] Origin=" + state.originYFraction + " Front=" + state.frontYFraction);
        return StepResult.CONTINUE;
    }

    // -------------------------------------------------------------------------
    // Step 4 helpers
    // -------------------------------------------------------------------------

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Redraws both reference lines on the overlay and refreshes the image. */
    private static void drawRfLines(Overlay ov, ImagePlus work,
                                    int width, int height,
                                    float originFrac, float frontFrac) {
        ov.clear();

        int oy = Math.min(height - 1, (int) (originFrac * height));
        Line originLine = new Line(0, oy, width - 1, oy);
        originLine.setStrokeColor(Color.RED);
        originLine.setStrokeWidth(2.0);
        originLine.setName("origin");

        int fy = Math.min(height - 1, (int) (frontFrac * height));
        Line frontLine = new Line(0, fy, width - 1, fy);
        frontLine.setStrokeColor(Color.BLUE);
        frontLine.setStrokeWidth(2.0);
        frontLine.setName("front");

        ov.add(originLine);
        ov.add(frontLine);
        work.updateAndDraw();
    }

    /** Parses the two text fields and redraws lines; silently ignores partially-typed values. */
    private static void liveUpdateRfLines(TextField originField, TextField frontField,
                                          Overlay ov, ImagePlus work, int width, int height) {
        try {
            float o = clamp01(Float.parseFloat(originField.getText().trim()));
            float f = clamp01(Float.parseFloat(frontField.getText().trim()));
            drawRfLines(ov, work, width, height, o, f);
        } catch (NumberFormatException e) {
            // user is mid-type — don't update
        }
    }

    /**
     * Adds a KeyListener to {@code field} so that ↑ adds {@code step} and ↓
     * subtracts {@code step}, clamped to [0, 1], then calls {@code onChange}.
     */
    private static void addArrowStepping(final TextField field,
                                         final double step,
                                         final Runnable onChange) {
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code != KeyEvent.VK_UP && code != KeyEvent.VK_DOWN) return;
                try {
                    double val = Double.parseDouble(field.getText().trim());
                    val += (code == KeyEvent.VK_UP ? step : -step);
                    val = Math.max(0.0, Math.min(1.0, val));
                    field.setText(String.format("%.3f", val));
                    onChange.run();
                    e.consume();
                } catch (NumberFormatException ex) {
                    // ignore
                }
            }
        });
    }

    /**
     * Variant of {@link #addArrowStepping(TextField, double, Runnable)} with
     * explicit min/max bounds. Used for threshold multipliers and similar fields
     * that are not restricted to [0, 1].
     */
    private static void addArrowStepping(final TextField field,
                                         final double step,
                                         final double minVal,
                                         final double maxVal,
                                         final Runnable onChange) {
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code != KeyEvent.VK_UP && code != KeyEvent.VK_DOWN) return;
                try {
                    double val = Double.parseDouble(field.getText().trim());
                    val += (code == KeyEvent.VK_UP ? step : -step);
                    val = Math.max(minVal, Math.min(maxVal, val));
                    field.setText(String.format("%.2f", val));
                    onChange.run();
                    e.consume();
                } catch (NumberFormatException ex) {
                    // ignore
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Step 5: Spot detection
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    StepResult step5_spotDetection() {
        final int width  = state.corrected.getWidth();
        final int height = state.corrected.getHeight();

        // Open the spot detection window; closes the background-corrected preview
        final ImagePlus work = new ImagePlus("Spot detection", state.corrected.duplicate());
        showDisplay(work);
        final Overlay ov = new Overlay();
        work.setOverlay(ov);

        // Single-element array used as a mutable reference from anonymous inner classes
        final List<Spot>[] spotsHolder = new List[1];
        spotsHolder[0] = SpotDetector.detect(state.corrected, 1.0f);
        updateSpotOverlay(ov, work, spotsHolder[0]);

        // --- Part A: live threshold preview ---
        final GenericDialog gd = new GenericDialog("TLC Digitizer — Step 5: Spot Detection");
        gd.addMessage(
            "Spots above the threshold are circled in yellow.\n" +
            "Lower multiplier → more spots (dimmer included).\n" +
            "Higher multiplier → fewer spots (brighter only).\n" +
            "Use ↑ / ↓ to step by 0.1.");
        gd.addNumericField("Threshold multiplier:", 1.0, 2);
        gd.addCheckbox("← Back to Step 4 (re-mark origin and solvent front)", false);

        final Vector<TextField> threshFields = gd.getNumericFields();
        final TextField threshField = threshFields.get(0);

        final Runnable redetect = new Runnable() {
            public void run() {
                try {
                    float mult = Float.parseFloat(threshField.getText().trim());
                    if (mult > 0) {
                        spotsHolder[0] = SpotDetector.detect(state.corrected, mult);
                        updateSpotOverlay(ov, work, spotsHolder[0]);
                    }
                } catch (NumberFormatException ex) { /* mid-type, skip */ }
            }
        };

        gd.addDialogListener(new DialogListener() {
            public boolean dialogItemChanged(GenericDialog d, AWTEvent e) {
                redetect.run();
                return true;
            }
        });
        addArrowStepping(threshField, 0.1, 0.1, 10.0, redetect);

        gd.showDialog();
        if (gd.wasCanceled()) return StepResult.CANCEL;

        Checkbox backCheck5 = ((Vector<Checkbox>) gd.getCheckboxes()).get(0);
        if (backCheck5.getState()) return StepResult.BACK;

        try {
            state.thresholdFactor = Double.parseDouble(threshField.getText().trim());
        } catch (NumberFormatException ex) {
            state.thresholdFactor = 1.0;
        }

        int autoCount = spotsHolder[0].size();
        state.spots = new ArrayList<>(spotsHolder[0]);

        // --- Part B: manual addition of missed spots ---
        work.setRoi((ij.gui.Roi) null);
        IJ.setTool("multipoint");
        work.setTitle("Add missed spots — " + autoCount + " auto-detected");

        WaitForUserDialog addDlg = new WaitForUserDialog(
            "TLC Digitizer — Step 5: Add missed spots",
            autoCount + " spots auto-detected (yellow circles).\n\n" +
            "Click on any missed spots with the Multipoint tool.\n" +
            "Do NOT re-click already-circled spots.\n\n" +
            "Click OK when done.");
        addDlg.show();

        if (!addDlg.escPressed()) {
            ij.gui.Roi clickedRoi = work.getRoi();
            if (clickedRoi instanceof PointRoi) {
                FloatPolygon poly = clickedRoi.getFloatPolygon();
                float meanRadius = meanSpotRadius(state.spots);
                int nextId = state.spots.size();
                for (int i = 0; i < poly.npoints; i++) {
                    state.spots.add(new Spot(nextId++, poly.xpoints[i], poly.ypoints[i], meanRadius, height));
                }
            }
        }

        // --- Pipeline: assign lanes, Rf, integrate, Option B correction ---
        LaneAssigner.assignLanes(state.spots, width);
        RfCalculator.assignAll(state.spots, state.originYFraction, state.frontYFraction);
        SpotIntegrator.integrateAll(state.corrected, state.spots);

        if (!state.usedPolynomialBackground) {
            IJ.showStatus("Fitting per-spot background polynomial…");
            BackgroundCorrection.applyPerSpotPolynomial(
                state.spots, state.corrected, BackgroundCorrection.DEFAULT_SG_DEGREE);
            IJ.showStatus("");
            IJ.log("[Step 5] Per-spot polynomial background correction applied (degree " +
                   BackgroundCorrection.DEFAULT_SG_DEGREE + ").");
        }

        // Show final overlay with all spots (auto + manually added)
        updateSpotOverlay(ov, work, state.spots);
        work.setTitle("Detected spots (" + state.spots.size() + ")");
        IJ.log("[Step 5] " + state.spots.size() + " spots total (auto: " + autoCount +
               ", manual: " + (state.spots.size() - autoCount) + ").");
        return StepResult.CONTINUE;
    }

    // -------------------------------------------------------------------------
    // Step 6: Calibration
    // -------------------------------------------------------------------------

    StepResult step6_calibration() {
        if (state.spots.isEmpty()) {
            IJ.error("No spots detected. Going back to Step 5 to adjust the threshold.");
            return StepResult.BACK;
        }

        // Reset reference flags so re-entries don't accumulate
        for (Spot s : state.spots) { s.isReference = false; s.referenceConcentration = 0; }

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
        gd.addCheckbox("← Back to Step 5 (redo spot detection)", false);
        gd.showDialog();
        if (gd.wasCanceled()) return StepResult.CANCEL;

        // Read all numeric fields first, then check Back before mutating state
        int[] ids    = new int[rows];
        double[] concs = new double[rows];
        for (int i = 0; i < rows; i++) {
            ids[i]   = (int) gd.getNextNumber();
            concs[i] = gd.getNextNumber();
        }
        boolean backToStep5 = gd.getNextBoolean();
        if (backToStep5) return StepResult.BACK;

        // Mark reference spots
        for (int i = 0; i < rows; i++) {
            final int fid = ids[i];
            final double fconc = concs[i];
            if (fid >= 0 && fconc > 0) {
                state.spots.stream()
                    .filter(s -> s.id == fid)
                    .findFirst()
                    .ifPresent(s -> { s.isReference = true; s.referenceConcentration = fconc; });
            }
        }

        List<Spot> refs = state.spots.stream().filter(s -> s.isReference).collect(Collectors.toList());
        if (refs.size() < 3) {
            IJ.error("At least 3 reference spots are required. Only " + refs.size() + " assigned.");
            return StepResult.CANCEL;
        }

        try {
            state.calibrationModel = CalibrationModel.fit(refs);
            state.calibrationModel.applyTo(state.spots);
            IJ.log("[Step 6] " + state.calibrationModel.toSummary());
        } catch (IllegalArgumentException e) {
            IJ.error("Calibration failed: " + e.getMessage());
            return StepResult.CANCEL;
        }
        return StepResult.CONTINUE;
    }

    // -------------------------------------------------------------------------
    // Step 7: Export
    // -------------------------------------------------------------------------

    StepResult step7_export() {
        GenericDialog gd = new GenericDialog("TLC Digitizer — Step 7: Export");
        gd.addMessage("Save results to CSV.");
        gd.addStringField("Output file:", System.getProperty("user.home") + "/tlc_results.csv", 40);
        gd.addCheckbox("← Back to Step 6 (redo calibration)", false);
        gd.showDialog();
        if (gd.wasCanceled()) return StepResult.CANCEL;

        String path = gd.getNextString().trim();
        boolean backToStep6 = gd.getNextBoolean();
        if (backToStep6) return StepResult.BACK;

        File out = new File(path);
        try {
            CsvExporter.export(state, out);
            IJ.log("[Step 7] Results saved to: " + out.getAbsolutePath());
            IJ.showMessage("TLC Digitizer", "Results saved to:\n" + out.getAbsolutePath());
        } catch (IOException e) {
            IJ.error("Export failed: " + e.getMessage());
            return StepResult.CANCEL;
        }
        return StepResult.CONTINUE;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Clears {@code ov}, redraws all spots as yellow circles, and refreshes the image. */
    private static void updateSpotOverlay(Overlay ov, ImagePlus imp, List<Spot> spots) {
        ov.clear();
        for (Spot s : spots) {
            ij.gui.OvalRoi oval = new ij.gui.OvalRoi(
                s.centroidX - s.radius, s.centroidY - s.radius,
                s.radius * 2, s.radius * 2);
            oval.setName("spot_" + s.id);
            oval.setStrokeColor(Color.YELLOW);
            oval.setStrokeWidth(1.5);
            ov.add(oval);
        }
        IJ.showStatus(spots.size() + " spots detected");
        imp.updateAndDraw();
    }

    /** Returns the mean radius of all spots, or 20 px if the list is empty. */
    private static float meanSpotRadius(List<Spot> spots) {
        if (spots.isEmpty()) return 20f;
        double sum = 0;
        for (Spot s : spots) sum += s.radius;
        return (float) (sum / spots.size());
    }

    private void showSpotOverlay(ImagePlus imp, List<Spot> spots) {
        Overlay ov = new Overlay();
        updateSpotOverlay(ov, imp, spots);
        imp.setOverlay(ov);
    }
}
