package se.katalystnord.tlcdigitizer.ui;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.TextRoi;
import ij.process.FloatPolygon;
import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.export.AnnotatedImageExporter;
import se.katalystnord.tlcdigitizer.export.CsvExporter;
import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.model.Spot;
import se.katalystnord.tlcdigitizer.pipeline.*;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Persistent single-window wizard UI for TLC Digitizer.
 *
 * A fixed step-indicator bar at the top shows progress; a swappable centre
 * panel (CardLayout) renders each step's controls; Back/Next buttons at the
 * bottom drive navigation.  The Fiji image window stays non-modal alongside
 * the control panel throughout the session.
 *
 * UI logic only — all scientific computation is delegated to the stateless
 * pipeline classes.  {@link AnalysisState} threads mutable state through.
 */
public class TlcDigitizerFrame extends JFrame {

    private static final String[] STEP_NAMES = {
        "1 · Image", "2 · Perspective", "3 · Background",
        "4 · Rf Lines", "5 · Spots", "6 · Calibrate", "7 · Export"
    };

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final AnalysisState state;
    private ImagePlus displayWindow;
    private int currentStep = 1;

    // Saved state of the original image window — restored on wizard close
    private ij.process.ImageProcessor savedProcessor;
    private String savedTitle;
    private Overlay savedOverlay;

    // Navigation controls
    private JButton backBtn;
    private JButton nextBtn;
    private JLabel[] stepIndicators;

    // Content
    private JPanel contentPanel;
    private CardLayout cardLayout;
    AbstractStepPanel[] stepPanels;   // package-private for inner-class access

    // Blocking mechanism (plugin thread ↔ EDT)
    private volatile StepResult pendingResult;
    private volatile WaitHandle currentWaitHandle;

    // -----------------------------------------------------------------------
    // Supporting types
    // -----------------------------------------------------------------------

    enum StepResult { CONTINUE, BACK, CANCEL }

    interface WaitHandle {
        void waitFor();
        void release();
    }

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public TlcDigitizerFrame(AnalysisState state) {
        super("TLC Digitizer");
        this.state = state;
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { signalResult(StepResult.CANCEL); }
        });
        buildUI();
    }

    private void buildUI() {
        setLayout(new BorderLayout());

        add(buildIndicatorBar(), BorderLayout.NORTH);

        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

        stepPanels = new AbstractStepPanel[]{
            new Step1Panel(), new Step2Panel(), new Step3Panel(),
            new Step4Panel(), new Step5Panel(), new Step6Panel(),
            new Step7Panel()
        };
        for (int i = 0; i < STEP_NAMES.length; i++) {
            contentPanel.add(stepPanels[i], STEP_NAMES[i]);
        }
        add(contentPanel, BorderLayout.CENTER);
        add(buildNavBar(), BorderLayout.SOUTH);

        // Ctrl+Z: delegate to current step panel (step 5 uses local spot undo;
        // all other steps treat Ctrl+Z as Back navigation).
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
        KeyStroke ctrlZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlZ, "globalUndo");
        getRootPane().getActionMap().put("globalUndo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (currentStep == 5) {
                    ((Step5Panel) stepPanels[4]).undoSpotEdit();
                } else {
                    signalResult(StepResult.BACK);
                }
            }
        });

        pack();
        setMinimumSize(new Dimension(460, 0));
        setLocationRelativeTo(null);
    }

    private JPanel buildIndicatorBar() {
        stepIndicators = new JLabel[STEP_NAMES.length];
        JPanel bar = new JPanel(new GridLayout(1, STEP_NAMES.length, 0, 0));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        for (int i = 0; i < STEP_NAMES.length; i++) {
            JLabel lbl = new JLabel(STEP_NAMES[i], SwingConstants.CENTER);
            lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
            lbl.setOpaque(true);
            lbl.setBorder(BorderFactory.createEmptyBorder(5, 2, 5, 2));
            stepIndicators[i] = lbl;
            bar.add(lbl);
        }
        return bar;
    }

    private JPanel buildNavBar() {
        backBtn = new JButton("← Back");
        nextBtn = new JButton("Next →");
        nextBtn.setFont(nextBtn.getFont().deriveFont(Font.BOLD));
        backBtn.addActionListener(e -> signalResult(StepResult.BACK));
        nextBtn.addActionListener(e -> signalResult(StepResult.CONTINUE));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        bar.add(backBtn);
        bar.add(nextBtn);
        return bar;
    }

    // -----------------------------------------------------------------------
    // Wizard loop  (called from the Fiji plugin thread, not from EDT)
    // -----------------------------------------------------------------------

    public boolean runWizard() {
        // Claim the original image window as our display for the whole session
        try {
            SwingUtilities.invokeAndWait(this::initDisplay);
        } catch (Exception e) { return false; }

        int step = 1;
        while (step >= 1 && step <= STEP_NAMES.length) {
            final int s = step;
            currentWaitHandle = makeWaitHandle();

            // Activate step on EDT; plugin thread blocks on the wait handle.
            if (SwingUtilities.isEventDispatchThread()) {
                activateStep(s);
            } else {
                try {
                    SwingUtilities.invokeAndWait(() -> activateStep(s));
                } catch (Exception e) {
                    cleanup();
                    return false;
                }
            }

            currentWaitHandle.waitFor();

            StepResult result = pendingResult;
            if (result == StepResult.CANCEL) {
                cleanup();
                return false;
            }

            if (result == StepResult.BACK) {
                step = Math.max(1, step - 1);
            } else {
                // commit() runs here on the plugin thread — safe for CPU-heavy work.
                if (!stepPanels[step - 1].commit()) {
                    continue; // validation failed; stay on this step
                }
                step++;
            }
        }
        cleanup();
        return true;
    }

    // -----------------------------------------------------------------------
    // Internal navigation helpers (EDT only)
    // -----------------------------------------------------------------------

    private void activateStep(int step) {
        currentStep = step;

        // Update step indicator colours
        for (int i = 0; i < stepIndicators.length; i++) {
            boolean active = (i == step - 1);
            stepIndicators[i].setBackground(active
                ? new Color(0x1E5FAE)
                : UIManager.getColor("Panel.background"));
            stepIndicators[i].setForeground(active
                ? Color.WHITE
                : UIManager.getColor("Label.foreground"));
            stepIndicators[i].setFont(stepIndicators[i].getFont()
                .deriveFont(active ? Font.BOLD : Font.PLAIN));
        }

        backBtn.setEnabled(step > 1);
        nextBtn.setText(step == STEP_NAMES.length ? "Export" : "Next →");

        cardLayout.show(contentPanel, STEP_NAMES[step - 1]);
        stepPanels[step - 1].onEnter();

        pack();
        setMinimumSize(new Dimension(460, 0));
    }

    void signalResult(StepResult result) {
        pendingResult = result;
        WaitHandle wh = currentWaitHandle;
        if (wh != null) wh.release();
    }

    private void initDisplay() {
        // Save original image state so we can restore it when the wizard closes
        savedProcessor = state.originalImage.getProcessor().duplicate();
        savedTitle     = state.originalImage.getTitle();
        savedOverlay   = state.originalImage.getOverlay();

        displayWindow  = state.originalImage;
        displayWindow.setOverlay(null);

        // Position: image left, control panel right
        if (displayWindow.getWindow() != null) {
            positionWindows();
        }
    }

    private void cleanup() {
        // Restore original image to the state before the wizard ran
        if (displayWindow != null && savedProcessor != null) {
            displayWindow.setProcessor(savedProcessor);
            displayWindow.setTitle(savedTitle);
            displayWindow.setOverlay(savedOverlay);
            displayWindow.setRoi((ij.gui.Roi) null);
            displayWindow.updateAndDraw();
        }
        dispose();
    }

    // -----------------------------------------------------------------------
    // Display window management
    // -----------------------------------------------------------------------

    /**
     * Swap the processor shown in the persistent display window.
     * Safe to call from any thread; ImageJ dispatches the repaint internally.
     */
    void setDisplay(ij.process.ImageProcessor ip, String title) {
        if (displayWindow == null) return;
        displayWindow.setProcessor(ip);
        displayWindow.setTitle(title);
        displayWindow.setOverlay(null);
        displayWindow.setRoi((ij.gui.Roi) null);
        displayWindow.updateAndDraw();
    }

    ImagePlus getDisplayWindow() { return displayWindow; }

    private void positionWindows() {
        Window imgWin = (displayWindow != null) ? displayWindow.getWindow() : null;
        if (imgWin == null) return;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        imgWin.setLocation(8, 50);
        int ctrlX = imgWin.getX() + imgWin.getWidth() + 10;
        ctrlX = Math.min(ctrlX, screen.width - getWidth() - 8);
        setLocation(Math.max(0, ctrlX), 50);
    }

    // -----------------------------------------------------------------------
    // WaitHandle  (blocks plugin thread while EDT continues handling events)
    // -----------------------------------------------------------------------

    private static WaitHandle makeWaitHandle() {
        if (EventQueue.isDispatchThread()) {
            SecondaryLoop loop = Toolkit.getDefaultToolkit()
                .getSystemEventQueue().createSecondaryLoop();
            return new WaitHandle() {
                public void waitFor() { loop.enter(); }
                public void release() { loop.exit(); }
            };
        }
        CountDownLatch latch = new CountDownLatch(1);
        return new WaitHandle() {
            public void waitFor() {
                try { latch.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            public void release() { latch.countDown(); }
        };
    }

    // -----------------------------------------------------------------------
    // Abstract step panel base class
    // -----------------------------------------------------------------------

    abstract class AbstractStepPanel extends JPanel {
        AbstractStepPanel() {
            setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        }

        /** Called on EDT when this step becomes active. Set up the image display and controls. */
        void onEnter() {}

        /**
         * Called on the plugin thread when the user clicks Next.
         * Run pipeline computation, update state, return false to stay on this step.
         */
        boolean commit() { return true; }

        // --- Layout helpers ---

        JPanel instrPanel(String html) {
            JLabel lbl = new JLabel("<html>" + html + "</html>");
            lbl.setFont(lbl.getFont().deriveFont(12f));
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(0, 0, 10, 0)));
            p.add(lbl, BorderLayout.WEST);
            return p;
        }

        JPanel labeledRow(String label, JComponent field) {
            JPanel p = new JPanel(new BorderLayout(8, 0));
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height + 4));
            JLabel lbl = new JLabel(label);
            lbl.setPreferredSize(new Dimension(190, 0));
            p.add(lbl, BorderLayout.WEST);
            p.add(field, BorderLayout.CENTER);
            return p;
        }
    }

    // -----------------------------------------------------------------------
    // Step 1 — Image preparation
    // -----------------------------------------------------------------------

    class Step1Panel extends AbstractStepPanel {
        private JRadioButton luminanceBtn;
        private JRadioButton greenBtn;
        private JCheckBox invertBox;

        Step1Panel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            add(instrPanel("Convert the raw image to greyscale.<br>" +
                "For UV-fluorescence plates (dark background, bright spots), " +
                "try <b>Green channel</b> for better spot contrast.<br>" +
                "Check <b>Invert</b> for stained or UV&nbsp;254&nbsp;nm plates " +
                "where spots are <i>darker</i> than the background."));
            add(Box.createVerticalStrut(14));

            luminanceBtn = new JRadioButton(
                "<html>Luminance &nbsp;<small style='color:gray'>" +
                "(Y = 0.2126·R + 0.7152·G + 0.0722·B)</small></html>", true);
            greenBtn = new JRadioButton("Green channel only");
            ButtonGroup grp = new ButtonGroup();
            grp.add(luminanceBtn);
            grp.add(greenBtn);
            luminanceBtn.setAlignmentX(LEFT_ALIGNMENT);
            greenBtn.setAlignmentX(LEFT_ALIGNMENT);
            add(luminanceBtn);
            add(Box.createVerticalStrut(6));
            add(greenBtn);
            add(Box.createVerticalStrut(12));

            invertBox = new JCheckBox(
                "<html><b>Invert</b> — dark spots on bright background " +
                "<small style='color:gray'>(stained plates, UV 254 nm)</small></html>");
            invertBox.setAlignmentX(LEFT_ALIGNMENT);
            add(invertBox);
        }

        @Override
        void onEnter() {
            setDisplay(state.originalImage.getProcessor().duplicate(),
                "TLC Digitizer — Step 1 · Image");
        }

        @Override
        boolean commit() {
            FloatProcessor fp = greenBtn.isSelected()
                ? ImagePreparation.extractGreenChannel(state.originalImage)
                : ImagePreparation.toLuminanceGrayscale(state.originalImage);
            state.invertImage = invertBox.isSelected();
            if (state.invertImage) fp.invert();
            state.grayscale = fp;
            IJ.log("[Step 1] Greyscale: " + (greenBtn.isSelected() ? "green channel" : "luminance")
                + (state.invertImage ? " (inverted)" : ""));
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Step 2 — Perspective correction
    // -----------------------------------------------------------------------

    class Step2Panel extends AbstractStepPanel {
        private final JSpinner[] sp = new JSpinner[8]; // [tlX, tlY, trX, trY, brX, brY, blX, blY]
        private boolean suppressRoi = false;

        Step2Panel() {
            setLayout(new BorderLayout(0, 10));

            add(instrPanel("The four plate corners are highlighted on the image.<br>" +
                "<b>Drag</b> the corner handles to adjust them. " +
                "Clicking elsewhere is ignored — extra points are not added.<br>" +
                "Or type pixel coordinates directly below. " +
                "Order: Top-Left → Top-Right → Bottom-Right → Bottom-Left."),
                BorderLayout.NORTH);

            String[] names = {"Top-Left", "Top-Right", "Bottom-Right", "Bottom-Left"};
            JPanel grid = new JPanel(new GridLayout(4, 3, 8, 5));
            for (int i = 0; i < 4; i++) {
                grid.add(new JLabel(names[i]));
                sp[i * 2]     = coordSpinner();
                sp[i * 2 + 1] = coordSpinner();
                JPanel xp = new JPanel(new BorderLayout(3, 0));
                xp.add(new JLabel("X"), BorderLayout.WEST);
                xp.add(sp[i * 2], BorderLayout.CENTER);
                JPanel yp = new JPanel(new BorderLayout(3, 0));
                yp.add(new JLabel("Y"), BorderLayout.WEST);
                yp.add(sp[i * 2 + 1], BorderLayout.CENTER);
                grid.add(xp);
                grid.add(yp);
            }
            add(grid, BorderLayout.CENTER);

            JButton redetect = new JButton("Re-detect corners automatically");
            redetect.addActionListener(e -> {
                state.corners = PerspectiveCorrection.detectCorners(state.grayscale);
                cornersToSpinners();
                updateRoi();
            });
            JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            south.add(redetect);
            add(south, BorderLayout.SOUTH);
        }

        private JSpinner coordSpinner() {
            JSpinner s = new JSpinner(new SpinnerNumberModel(0.0, -9999.0, 99999.0, 1.0));
            ((JSpinner.NumberEditor) s.getEditor()).getTextField().setColumns(5);
            s.addChangeListener(e -> {
                if (!suppressRoi) { spinnersToCorners(); updateRoi(); }
            });
            return s;
        }

        @Override
        void onEnter() {
            if (state.corners == null) {
                state.corners = PerspectiveCorrection.detectCorners(state.grayscale);
            }
            setDisplay(state.grayscale.duplicate(), "TLC Digitizer — Step 2 · Perspective");
            IJ.setTool("multipoint");
            cornersToSpinners();
            updateRoi();

            IJ.wait(80);  // let ImageJ create the canvas before attaching the listener
            ImageCanvas canvas = displayWindow.getCanvas();
            if (canvas != null) {
                canvas.addMouseListener(new MouseAdapter() {
                    public void mouseReleased(MouseEvent e) {
                        ij.gui.Roi roi = displayWindow.getRoi();
                        if (roi instanceof PointRoi
                                && roi.getFloatPolygon().npoints > 4) {
                            updateRoi();
                            return;
                        }
                        roiToSpinners(displayWindow);
                    }
                });
            }
        }

        private void cornersToSpinners() {
            suppressRoi = true;
            if (state.corners != null) {
                for (int i = 0; i < 8; i++) sp[i].setValue((double) state.corners[i]);
            }
            suppressRoi = false;
        }

        private void spinnersToCorners() {
            if (state.corners == null) state.corners = new float[8];
            for (int i = 0; i < 8; i++) state.corners[i] = ((Number) sp[i].getValue()).floatValue();
        }

        private void updateRoi() {
            ImagePlus imp = getDisplayWindow();
            if (imp == null || state.corners == null) return;
            PointRoi roi = new PointRoi(
                new float[]{state.corners[0], state.corners[2], state.corners[4], state.corners[6]},
                new float[]{state.corners[1], state.corners[3], state.corners[5], state.corners[7]}, 4);
            imp.setRoi(roi);
            imp.updateAndDraw();
        }

        private void roiToSpinners(ImagePlus imp) {
            ij.gui.Roi roi = imp.getRoi();
            if (!(roi instanceof PointRoi)) return;
            FloatPolygon poly = roi.getFloatPolygon();
            if (poly.npoints != 4) return;
            for (int i = 0; i < 4; i++) {
                state.corners[i * 2]     = poly.xpoints[i];
                state.corners[i * 2 + 1] = poly.ypoints[i];
            }
            cornersToSpinners();
        }

        @Override
        boolean commit() {
            spinnersToCorners();
            state.corrected     = PerspectiveCorrection.warpImage(state.grayscale, state.corners);
            state.perspCorrected = state.corrected; // snapshot pre-background for step 3 re-entry
            setDisplay(state.corrected.duplicate(), "TLC Digitizer — Step 2 · Perspective corrected");
            IJ.log("[Step 2] Perspective warp: "
                + state.corrected.getWidth() + "×" + state.corrected.getHeight());
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Step 3 — Background correction
    // -----------------------------------------------------------------------

    class Step3Panel extends AbstractStepPanel {
        private JRadioButton polynomialBtn;
        private JRadioButton sgBtn;
        private JSpinner sgDegreeSp;

        Step3Panel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            add(instrPanel("Correct for uneven plate illumination.<br>" +
                "<b>Quartic polynomial</b> fits a 2-D surface to the whole plate — " +
                "best for most plates (TLCyzer method).<br>" +
                "<b>Savitzky-Golay</b> estimates background locally per spot — better for " +
                "non-uniform charring or sulphuric-acid stained plates."));
            add(Box.createVerticalStrut(14));

            polynomialBtn = new JRadioButton(
                "<html><b>Quartic polynomial</b> &nbsp;<small style='color:gray'>(recommended)</small></html>",
                true);
            sgBtn = new JRadioButton("<html><b>Savitzky-Golay</b> per-spot polynomial</html>");
            ButtonGroup grp = new ButtonGroup();
            grp.add(polynomialBtn);
            grp.add(sgBtn);
            polynomialBtn.setAlignmentX(LEFT_ALIGNMENT);
            sgBtn.setAlignmentX(LEFT_ALIGNMENT);
            add(polynomialBtn);
            add(Box.createVerticalStrut(6));
            add(sgBtn);

            // S-G options panel — shown only when Savitzky-Golay is selected
            JPanel sgOptions = new JPanel();
            sgOptions.setLayout(new BoxLayout(sgOptions, BoxLayout.Y_AXIS));
            sgOptions.setAlignmentX(LEFT_ALIGNMENT);
            sgOptions.setBorder(BorderFactory.createEmptyBorder(4, 22, 0, 0));

            sgDegreeSp = new JSpinner(new SpinnerNumberModel(
                    BackgroundCorrection.DEFAULT_SG_DEGREE, 1, 8, 1));
            ((JSpinner.DefaultEditor) sgDegreeSp.getEditor()).getTextField().setColumns(2);
            JPanel degreeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            degreeRow.add(new JLabel("Polynomial degree:"));
            degreeRow.add(sgDegreeSp);
            degreeRow.add(new JLabel(
                "<html><small style='color:gray'>(1–8, default 5)</small></html>"));
            degreeRow.setAlignmentX(LEFT_ALIGNMENT);

            JLabel deferredNote = new JLabel(
                "<html><small><i>Correction is applied in Step 5 after spot detection.</i></small></html>");
            deferredNote.setAlignmentX(LEFT_ALIGNMENT);

            sgOptions.add(degreeRow);
            sgOptions.add(Box.createVerticalStrut(4));
            sgOptions.add(deferredNote);
            sgOptions.setVisible(false);
            add(sgOptions);

            sgBtn.addChangeListener(e -> sgOptions.setVisible(sgBtn.isSelected()));
        }

        @Override
        void onEnter() {
            // Always show the perspective-corrected image; if the user goes Back
            // from step 4 and changes method, commit() re-applies to perspCorrected.
            FloatProcessor src = (state.perspCorrected != null) ? state.perspCorrected : state.corrected;
            setDisplay(src.duplicate(), "TLC Digitizer — Step 3 · Background");
        }

        @Override
        boolean commit() {
            state.usedPolynomialBackground = polynomialBtn.isSelected();
            state.sgDegree = (Integer) sgDegreeSp.getValue();
            FloatProcessor src = (state.perspCorrected != null) ? state.perspCorrected : state.corrected;
            if (state.usedPolynomialBackground) {
                IJ.showStatus("Fitting quartic background polynomial…");
                state.corrected = BackgroundCorrection.fitAndSubtract(src);
                IJ.showStatus("");
                setDisplay(state.corrected.duplicate(),
                    "TLC Digitizer — Step 3 · Background corrected");
            } else {
                state.corrected = src;
                setDisplay(state.corrected.duplicate(),
                    "TLC Digitizer — Step 3 · Background (SG deferred to Step 5)");
            }
            IJ.log("[Step 3] Background method: "
                + (state.usedPolynomialBackground
                    ? "quartic polynomial"
                    : "Savitzky-Golay (degree " + state.sgDegree + ")"));
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Step 4 — Rf reference lines
    // -----------------------------------------------------------------------

    class Step4Panel extends AbstractStepPanel {
        private JSpinner originSp;
        private JSpinner frontSp;
        private Overlay overlay;
        private MouseAdapter dragListener;
        private int draggingLine = -1;   // -1=none, 0=origin, 1=front
        private static final int SNAP_PX = 12;

        Step4Panel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            add(instrPanel(
                "Set the <font color='red'><b>origin</b></font> (red) and " +
                "<font color='blue'><b>solvent front</b></font> (blue) Y positions.<br>" +
                "<b>Drag</b> either line directly on the image, or use ↑&nbsp;/&nbsp;↓ " +
                "arrow keys (0.005 steps) for fine adjustment."));
            add(Box.createVerticalStrut(14));

            float initO = Float.isNaN(state.originYFraction) ? 0.90f : state.originYFraction;
            float initF = Float.isNaN(state.frontYFraction)  ? 0.10f : state.frontYFraction;

            originSp = rfSpinner(initO);
            frontSp  = rfSpinner(initF);

            add(labeledRow("Origin Y fraction  (red):", originSp));
            add(Box.createVerticalStrut(8));
            add(labeledRow("Solvent front Y fraction  (blue):", frontSp));
        }

        private JSpinner rfSpinner(float init) {
            JSpinner s = new JSpinner(new SpinnerNumberModel((double) init, 0.0, 1.0, 0.005));
            s.setEditor(new JSpinner.NumberEditor(s, "0.000"));
            ((JSpinner.NumberEditor) s.getEditor()).getTextField().setColumns(5);
            s.addChangeListener(e -> updateLines());
            return s;
        }

        @Override
        void onEnter() {
            // Show the perspective-corrected (pre-background) image so the plate
            // structure is clearly visible for line placement.
            FloatProcessor display4 = (state.perspCorrected != null)
                    ? state.perspCorrected : state.corrected;
            setDisplay(display4.duplicate(), "TLC Digitizer — Step 4 · Rf Lines");
            overlay = new Overlay();
            displayWindow.setOverlay(overlay);
            updateLines();

            IJ.setTool("hand");  // prevents ROI creation while dragging lines
            IJ.wait(80);
            ImageCanvas canvas = displayWindow.getCanvas();
            if (canvas != null) {
                dragListener = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        draggingLine = hitTest(canvas, e.getY());
                    }
                    @Override public void mouseDragged(MouseEvent e) {
                        if (draggingLine < 0) return;
                        int imgH = displayWindow.getHeight();
                        if (imgH == 0) return;
                        double frac = (double) canvas.offScreenY(e.getY()) / imgH;
                        frac = Math.max(0.0, Math.min(1.0, frac));
                        if (draggingLine == 0) originSp.setValue(frac);
                        else                   frontSp.setValue(frac);
                    }
                    @Override public void mouseReleased(MouseEvent e) {
                        if (draggingLine >= 0) {
                            canvas.setCursor(Cursor.getDefaultCursor());
                            draggingLine = -1;
                        }
                    }
                    @Override public void mouseMoved(MouseEvent e) {
                        canvas.setCursor(hitTest(canvas, e.getY()) >= 0
                            ? Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                            : Cursor.getDefaultCursor());
                    }
                };
                canvas.addMouseListener(dragListener);
                canvas.addMouseMotionListener(dragListener);
            }
        }

        private int hitTest(ImageCanvas canvas, int screenY) {
            ImagePlus imp = getDisplayWindow();
            if (imp == null) return -1;
            int imgH = imp.getHeight();
            float o = ((Number) originSp.getValue()).floatValue();
            float f = ((Number) frontSp.getValue()).floatValue();
            int dO = Math.abs(screenY - canvas.screenY((int)(o * imgH)));
            int dF = Math.abs(screenY - canvas.screenY((int)(f * imgH)));
            if (dO <= SNAP_PX && dO <= dF) return 0;
            if (dF <= SNAP_PX)             return 1;
            return -1;
        }

        private void updateLines() {
            ImagePlus imp = getDisplayWindow();
            if (overlay == null || imp == null) return;
            int w = imp.getWidth(), h = imp.getHeight();
            float o = ((Number) originSp.getValue()).floatValue();
            float f = ((Number) frontSp.getValue()).floatValue();
            overlay.clear();
            Line ol = new Line(0, clamp(o * h, h), w - 1, clamp(o * h, h));
            ol.setStrokeColor(Color.RED);  ol.setStrokeWidth(2.0); ol.setName("origin");
            Line fl = new Line(0, clamp(f * h, h), w - 1, clamp(f * h, h));
            fl.setStrokeColor(Color.BLUE); fl.setStrokeWidth(2.0); fl.setName("front");
            overlay.add(ol);
            overlay.add(fl);
            imp.updateAndDraw();
        }

        private int clamp(float v, int max) {
            return Math.min(max - 1, Math.max(0, (int) v));
        }

        @Override
        boolean commit() {
            ImagePlus imp = getDisplayWindow();
            if (imp != null && dragListener != null && imp.getCanvas() != null) {
                imp.getCanvas().removeMouseListener(dragListener);
                imp.getCanvas().removeMouseMotionListener(dragListener);
            }
            dragListener = null;
            draggingLine = -1;

            float o = ((Number) originSp.getValue()).floatValue();
            float f = ((Number) frontSp.getValue()).floatValue();
            if (o <= f) {
                IJ.error("Step 4 — Input Error",
                    "Origin must be below the solvent front (origin Y > front Y).\n" +
                    "Current origin: " + String.format("%.3f", o) +
                    "   front: " + String.format("%.3f", f));
                return false;
            }
            state.originYFraction = o;
            state.frontYFraction  = f;
            IJ.log("[Step 4] Origin=" + String.format("%.3f", o)
                + "  Front=" + String.format("%.3f", f));
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Step 5 — Spot detection
    // -----------------------------------------------------------------------

    class Step5Panel extends AbstractStepPanel {
        private JSlider slider;
        private JSpinner multSp;
        private JLabel countLabel;

        private List<Spot> spots = new ArrayList<Spot>();
        private final List<List<Spot>> spotHistory = new ArrayList<List<Spot>>();
        private Overlay overlay;
        private MouseAdapter canvasListener;
        /** Pre-flattened image used for detection when Savitzky-Golay is selected. */
        private FloatProcessor flattenedForDetection;

        Step5Panel() {
            setLayout(new BorderLayout(0, 8));

            add(instrPanel("<b>Drag the slider</b> to auto-detect spots (numbered yellow circles).<br>" +
                "<b>Left-click</b> the image to add a missed spot; " +
                "<b>right-click</b> to remove a false positive.<br>" +
                "Ctrl+Z undoes the last manual add/remove. " +
                "Moving the slider resets manual edits."), BorderLayout.NORTH);

            // Slider 10–500 → multiplier 0.10–5.00
            slider = new JSlider(10, 500, 100);
            slider.setMajorTickSpacing(100);
            slider.setMinorTickSpacing(50);
            slider.setPaintTicks(true);

            multSp = new JSpinner(new SpinnerNumberModel(1.00, 0.10, 5.00, 0.05));
            multSp.setEditor(new JSpinner.NumberEditor(multSp, "0.00"));
            ((JSpinner.NumberEditor) multSp.getEditor()).getTextField().setColumns(4);

            countLabel = new JLabel(" ");
            countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD, 13f));

            // Slider: update text live; re-detect on release
            slider.addChangeListener(e -> {
                double v = slider.getValue() / 100.0;
                multSp.setValue(v);
                if (!slider.getValueIsAdjusting()) detect();
            });
            // Spinner: push value to slider, re-detect
            multSp.addChangeListener(e -> {
                int sv = (int) Math.round(((Number) multSp.getValue()).doubleValue() * 100);
                if (slider.getValue() != sv) slider.setValue(sv);
                detect();
            });

            JPanel sliderRow = new JPanel(new BorderLayout(4, 0));
            sliderRow.add(new JLabel("0.1×"), BorderLayout.WEST);
            sliderRow.add(slider, BorderLayout.CENTER);
            sliderRow.add(new JLabel("5×"),   BorderLayout.EAST);

            JPanel spinRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            spinRow.add(new JLabel("Multiplier:"));
            spinRow.add(multSp);
            spinRow.add(new JLabel("  (or drag slider)"));

            JPanel controls = new JPanel();
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            controls.add(sliderRow);
            controls.add(Box.createVerticalStrut(4));
            controls.add(spinRow);
            controls.add(Box.createVerticalStrut(6));
            controls.add(countLabel);
            add(controls, BorderLayout.CENTER);
        }

        @Override
        void onEnter() {
            spotHistory.clear();
            setDisplay(state.corrected.duplicate(), "TLC Digitizer — Step 5 · Spots");
            overlay = new Overlay();
            displayWindow.setOverlay(overlay);

            // When Savitzky-Golay is selected the image reaching Step 5 has no global background
            // correction. Spot detection on an uneven-background image degrades quality, so we
            // pre-flatten a detection-only copy here using the quartic polynomial. Integration
            // still uses the raw state.corrected; only detection uses the flattened copy.
            if (!state.usedPolynomialBackground) {
                IJ.showStatus("Pre-flattening for spot detection…");
                flattenedForDetection = BackgroundCorrection.fitAndSubtract(state.corrected);
                IJ.showStatus("");
            } else {
                flattenedForDetection = null;
            }

            IJ.wait(120);  // let ImageJ finish binding the canvas
            ImageCanvas canvas = displayWindow.getCanvas();
            if (canvas != null) {
                canvasListener = new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        ImageCanvas c = displayWindow.getCanvas();
                        if (c == null) return;
                        int ix = c.offScreenX(e.getX());
                        int iy = c.offScreenY(e.getY());
                        FloatProcessor src = source();
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            pushSpotHistory();
                            Spot found = SpotDetector.detectAtPoint(src, ix, iy, src.getHeight());
                            int nextId = spots.isEmpty() ? 0
                                : spots.stream().mapToInt(s -> s.id).max().getAsInt() + 1;
                            spots.add(new Spot(nextId, found.centroidX, found.centroidY,
                                found.radius, src.getHeight()));
                        } else if (SwingUtilities.isRightMouseButton(e)) {
                            Spot nearest = null;
                            float bestDist = Float.MAX_VALUE;
                            for (Spot s : spots) {
                                float dx = ix - s.centroidX, dy = iy - s.centroidY;
                                float d = (float) Math.sqrt(dx * dx + dy * dy);
                                if (d < bestDist && d < s.radius * 3f) { bestDist = d; nearest = s; }
                            }
                            if (nearest == null) return;
                            pushSpotHistory();
                            spots.remove(nearest);
                        } else {
                            return;
                        }
                        refreshOverlay();
                    }
                };
                canvas.addMouseListener(canvasListener);
            }

            detect();
        }

        private FloatProcessor source() {
            return (flattenedForDetection != null) ? flattenedForDetection : state.corrected;
        }

        private void detect() {
            float mult = slider.getValue() / 100.0f;
            spotHistory.clear();
            spots = SpotDetector.detect(source(), mult);
            refreshOverlay();
        }

        private void refreshOverlay() {
            ImagePlus imp = getDisplayWindow();
            if (imp == null || overlay == null) return;
            updateSpotOverlay(overlay, imp, spots);
            int n = spots.size();
            countLabel.setText(n + " spot" + (n == 1 ? "" : "s") + " detected");
        }

        void undoSpotEdit() {
            if (spotHistory.isEmpty()) return;
            spots = spotHistory.remove(spotHistory.size() - 1);
            refreshOverlay();
        }

        private void pushSpotHistory() {
            spotHistory.add(new ArrayList<Spot>(spots));
            if (spotHistory.size() > 10) spotHistory.remove(0);
        }

        @Override
        boolean commit() {
            // Detach mouse listener before finalising
            ImagePlus imp = getDisplayWindow();
            if (imp != null && canvasListener != null && imp.getCanvas() != null) {
                imp.getCanvas().removeMouseListener(canvasListener);
            }
            canvasListener = null;

            if (spots.isEmpty()) {
                IJ.error("Step 5 — No Spots",
                    "No spots detected. Adjust the threshold slider and try again.");
                return false;
            }

            state.thresholdFactor = slider.getValue() / 100.0;

            // Re-sort left-to-right (lane), then top-to-bottom within each lane,
            // so spots belonging to the same lane get adjacent IDs.
            List<Spot> sorted = new ArrayList<Spot>(spots);
            sorted.sort(Comparator.comparingDouble((Spot s) -> (double) s.centroidX)
                .thenComparingDouble(s -> (double) s.centroidY));
            int h = state.corrected.getHeight();
            state.spots = new ArrayList<Spot>();
            for (int i = 0; i < sorted.size(); i++) {
                Spot old = sorted.get(i);
                state.spots.add(new Spot(i, old.centroidX, old.centroidY, old.radius, h));
            }

            LaneAssigner.assignLanes(state.spots, state.corrected.getWidth());
            RfCalculator.assignAll(state.spots, state.originYFraction, state.frontYFraction);
            SpotIntegrator.integrateAll(state.corrected, state.spots);

            if (!state.usedPolynomialBackground) {
                IJ.showStatus("Applying per-spot polynomial background…");
                BackgroundCorrection.applyPerSpotPolynomial(
                    state.spots, state.corrected, state.sgDegree);
                IJ.showStatus("");
            }

            if (imp != null && overlay != null) {
                updateSpotOverlay(overlay, imp, state.spots);
                imp.setTitle("Spots (" + state.spots.size() + ")");
            }
            IJ.log("[Step 5] " + state.spots.size() + " spots finalised.");
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Step 6 — Calibration
    // -----------------------------------------------------------------------

    class Step6Panel extends AbstractStepPanel {
        private JPanel rows;
        private JScrollPane scroll;
        private final List<JCheckBox> refBoxes    = new ArrayList<JCheckBox>();
        private final List<JSpinner>  concSpinners = new ArrayList<JSpinner>();

        Step6Panel() {
            setLayout(new BorderLayout(0, 8));
            add(instrPanel("Tick the <b>Reference</b> checkbox for each calibration standard spot " +
                "and enter its known concentration. At least 3 reference spots are required " +
                "(ICH&nbsp;Q2(R1))."), BorderLayout.NORTH);

            rows = new JPanel(new GridLayout(0, 4, 6, 3));
            scroll = new JScrollPane(rows);
            scroll.setPreferredSize(new Dimension(420, 240));
            add(scroll, BorderLayout.CENTER);
        }

        @Override
        void onEnter() {
            rows.removeAll();
            refBoxes.clear();
            concSpinners.clear();

            // Header row
            rows.add(bold("ID"));
            rows.add(bold("Rf"));
            rows.add(bold("Reference?"));
            rows.add(bold("Conc. (µg/mL)"));

            for (Spot s : state.spots) {
                rows.add(new JLabel(String.valueOf(s.id)));
                rows.add(new JLabel(String.format("%.3f", s.rfValue)));

                JCheckBox cb = new JCheckBox();
                cb.setSelected(s.isReference);
                refBoxes.add(cb);
                rows.add(cb);

                double initConc = s.isReference ? s.referenceConcentration : 0.0;
                JSpinner conc = new JSpinner(
                    new SpinnerNumberModel(initConc, 0.0, 1_000_000.0, 0.001));
                conc.setEditor(new JSpinner.NumberEditor(conc, "0.000"));
                ((JSpinner.NumberEditor) conc.getEditor()).getTextField().setColumns(7);
                concSpinners.add(conc);
                rows.add(conc);
            }

            rows.revalidate();
            rows.repaint();
            scroll.revalidate();

            setDisplay(state.corrected.duplicate(), "TLC Digitizer — Step 6 · Calibrate");
            Overlay ov = new Overlay();
            displayWindow.setOverlay(ov);
            updateSpotOverlay(ov, displayWindow, state.spots);
        }

        private JLabel bold(String text) {
            JLabel lbl = new JLabel("<html><b>" + text + "</b></html>");
            return lbl;
        }

        @Override
        boolean commit() {
            for (Spot s : state.spots) { s.isReference = false; s.referenceConcentration = 0; }

            for (int i = 0; i < state.spots.size(); i++) {
                if (refBoxes.get(i).isSelected()) {
                    double conc = ((Number) concSpinners.get(i).getValue()).doubleValue();
                    if (conc > 0) {
                        state.spots.get(i).isReference             = true;
                        state.spots.get(i).referenceConcentration  = conc;
                    }
                }
            }

            List<Spot> refs = state.spots.stream()
                .filter(s -> s.isReference).collect(Collectors.toList());
            if (refs.size() == 0) {
                // No references at all — skip calibration and export Rf + integration only
                IJ.log("[Step 6] No reference spots — calibration skipped; Rf and integration values only.");
                return true;
            }
            if (refs.size() < 3) {
                // Warn but allow: useful for quick qualitative runs
                int choice = JOptionPane.showConfirmDialog(TlcDigitizerFrame.this,
                    "Only " + refs.size() + " reference spot(s) marked.\n" +
                    "ICH Q2(R1) recommends at least 3 for a valid calibration curve.\n\n" +
                    "Continue anyway? (Concentrations will be calculated but may be unreliable.)",
                    "Step 6 — Calibration Warning",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) return false;
            }

            try {
                state.calibrationModel = CalibrationModel.fit(refs);
                state.calibrationModel.applyTo(state.spots);
                IJ.log("[Step 6] " + state.calibrationModel.toSummary());
            } catch (IllegalArgumentException e) {
                IJ.error("Step 6 — Calibration Error", e.getMessage());
                return false;
            }
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Step 7 — Export
    // -----------------------------------------------------------------------

    class Step7Panel extends AbstractStepPanel {
        private JTextField pathField;

        Step7Panel() {
            setLayout(new BorderLayout(0, 12));
            add(instrPanel("Saves three files from a single base path:<br>" +
                "<b>.csv</b> — numerical results + analysis parameters &nbsp;|&nbsp; " +
                "<b>.png</b> — annotated plate image for spot identification &nbsp;|&nbsp; " +
                "<b>.tif</b> — same image with the full CSV embedded in the metadata " +
                "(open in Fiji › Image › Show Info… to retrieve). " +
                "Spot IDs run left-to-right by lane, top-to-bottom within each lane."),
                BorderLayout.NORTH);

            String defaultPath = System.getProperty("user.home")
                + File.separator + "tlc_results.csv";
            pathField = new JTextField(defaultPath, 32);

            JButton browse = new JButton("Browse…");
            browse.addActionListener(e -> {
                File current = new File(pathField.getText().trim());
                JFileChooser fc = new JFileChooser(current.getParentFile());
                fc.setSelectedFile(current);
                fc.setDialogTitle("Save TLC results CSV");
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "CSV files (*.csv)", "csv"));
                if (fc.showSaveDialog(TlcDigitizerFrame.this) == JFileChooser.APPROVE_OPTION) {
                    String p = fc.getSelectedFile().getAbsolutePath();
                    if (!p.endsWith(".csv")) p += ".csv";
                    pathField.setText(p);
                }
            });

            JPanel pathRow = new JPanel(new BorderLayout(6, 0));
            pathRow.add(new JLabel("Output file:"), BorderLayout.WEST);
            pathRow.add(pathField, BorderLayout.CENTER);
            pathRow.add(browse, BorderLayout.EAST);

            // GridBagLayout with no weighty centres the pathRow vertically
            // and stretches it horizontally — keeps the text field one line tall.
            JPanel centre = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            centre.add(pathRow, gbc);
            add(centre, BorderLayout.CENTER);
        }

        @Override
        void onEnter() {
            nextBtn.setText("Export");
        }

        @Override
        boolean commit() {
            File csvOut  = new File(pathField.getText().trim());
            File pngOut  = AnnotatedImageExporter.pngFileFor(csvOut);
            File tiffOut = AnnotatedImageExporter.tiffFileFor(csvOut);
            try {
                String csv = CsvExporter.toCsvString(state);
                CsvExporter.export(state, csvOut);
                AnnotatedImageExporter.exportPng(state, pngOut);
                AnnotatedImageExporter.exportTiff(state, csv, tiffOut);
                IJ.showMessage("TLC Digitizer — Export Complete",
                    "CSV:  " + csvOut.getAbsolutePath()  + "\n" +
                    "PNG:  " + pngOut.getAbsolutePath()  + "\n" +
                    "TIFF: " + tiffOut.getAbsolutePath());
                IJ.log("[Step 7] CSV  saved: " + csvOut.getAbsolutePath());
                IJ.log("[Step 7] PNG  saved: " + pngOut.getAbsolutePath());
                IJ.log("[Step 7] TIFF saved: " + tiffOut.getAbsolutePath());
            } catch (IOException e) {
                IJ.error("Export Failed", e.getMessage());
                return false;
            }
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private static void updateSpotOverlay(Overlay ov, ImagePlus imp, List<Spot> spots) {
        ov.clear();
        for (Spot s : spots) {
            OvalRoi oval = new OvalRoi(
                s.centroidX - s.radius, s.centroidY - s.radius,
                s.radius * 2, s.radius * 2);
            oval.setStrokeColor(Color.YELLOW);
            oval.setStrokeWidth(Math.max(1.5, s.radius * 0.06));
            oval.setName("spot_" + s.id);
            ov.add(oval);

            // Font size scales with the spot so labels stay legible at any zoom / resolution.
            // Clamp: minimum readable at small zoom, maximum that fits inside large spots.
            int fontSize = Math.max(14, Math.min(60, (int) s.radius));
            Font labelFont = new Font("SansSerif", Font.BOLD, fontSize);
            String idStr = String.valueOf(s.id);
            // Approximate text-centre offset: ~0.55 × fontSize per char wide, ~0.7 × fontSize tall
            double tx = s.centroidX - idStr.length() * fontSize * 0.30;
            double ty = s.centroidY - fontSize * 0.38;
            TextRoi label = new TextRoi(tx, ty, idStr, labelFont);
            label.setStrokeColor(Color.YELLOW);
            label.setName("label_" + s.id);
            ov.add(label);
        }
        IJ.showStatus(spots.size() + " spots");
        imp.updateAndDraw();
    }
}
