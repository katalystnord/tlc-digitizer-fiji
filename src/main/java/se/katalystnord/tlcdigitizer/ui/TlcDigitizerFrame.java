package se.katalystnord.tlcdigitizer.ui;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.EllipseRoi;
import ij.gui.ImageCanvas;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.process.FloatPolygon;
import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.export.AnnotatedImageExporter;
import se.katalystnord.tlcdigitizer.export.CsvExporter;
import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.model.Lane;
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
    private Ui.StepChip[] stepIndicators;

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
        // CardLayout reports the MAXIMUM preferred size across every card, so all seven steps
        // were sized to the tallest one (Step 6's calibration table) and the short steps carried
        // a large block of dead space -- Step 5 was roughly 40% empty. Re-pack()ing per step
        // could not shrink it, because the layout kept reporting that same maximum.
        //
        // Width still comes from the maximum, deliberately: a window whose width changed per
        // step would jitter as the user advances. Only the height follows the visible card.
        contentPanel = new JPanel(cardLayout) {
            @Override
            public Dimension getPreferredSize() {
                Dimension max = super.getPreferredSize();
                Insets in = getInsets();
                for (Component c : getComponents()) {
                    if (c.isVisible()) {
                        return new Dimension(max.width,
                            c.getPreferredSize().height + in.top + in.bottom);
                    }
                }
                return max;
            }
        };
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

        packToCurrentStep();
        setLocationRelativeTo(null);
    }

    /**
     * Sizes the window to the <em>current</em> step's own content.
     *
     * <p>This previously added a fixed 120px of slack after every {@code pack()}, because Step 5
     * kept ending up inside its own scroll pane. That was treating a symptom: the real cause was
     * {@code CardLayout} reporting the tallest card's height for every card (see
     * {@link #buildUI()}), so the slack compounded the problem it was meant to hide — every step
     * paid for the tallest one, plus 120px. With the content panel now reporting the visible
     * card's height, {@code pack()} alone is correct and each step is as tall as it needs to be.
     *
     * <p>Still clamped to the usable screen height so a tall step cannot run off-screen.
     */
    private void packToCurrentStep() {
        pack();
        setMinimumSize(new Dimension(460, 0));
        int maxHeight = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds().height;
        Dimension d = getSize();
        if (d.height > maxHeight) setSize(d.width, maxHeight);
    }

    /**
     * Re-lays out and re-sizes the window after a step reveals or hides part of its own content
     * (Step 3's top-hat / Savitzky-Golay option rows, Step 5's Labkit sub-panel).
     *
     * <p>Without this the window keeps whatever height it had when the step was entered, so
     * newly-revealed controls are simply cut off the bottom — Step 3's Savitzky-Golay option
     * disappeared entirely once the top-hat SE row appeared. This was previously masked by a
     * fixed block of spare height that {@link #packToCurrentStep()} used to add to every step.
     */
    void relayoutStep() {
        contentPanel.revalidate();
        packToCurrentStep();
    }

    private JPanel buildIndicatorBar() {
        stepIndicators = new Ui.StepChip[STEP_NAMES.length];
        JPanel bar = new JPanel(new GridLayout(1, STEP_NAMES.length, 0, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.line()),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        for (int i = 0; i < STEP_NAMES.length; i++) {
            Ui.StepChip chip = new Ui.StepChip(STEP_NAMES[i]);
            stepIndicators[i] = chip;
            bar.add(chip);
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

        // Three states, not two: a completed step now looks different from one not yet reached,
        // which the old flat bar (active = solid blue block, everything else identical) could
        // not express.
        for (int i = 0; i < stepIndicators.length; i++) {
            stepIndicators[i].setState(
                i == step - 1 ? Ui.StepState.ACTIVE
              : i <  step - 1 ? Ui.StepState.DONE
              :                 Ui.StepState.UPCOMING);
        }

        backBtn.setEnabled(step > 1);
        nextBtn.setText(step == STEP_NAMES.length ? "Export" : "Next →");

        cardLayout.show(contentPanel, STEP_NAMES[step - 1]);
        stepPanels[step - 1].onEnter();

        packToCurrentStep();
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

        /**
         * The instruction block at the top of a step.
         *
         * <p>Must be LEFT-aligned explicitly. Swing defaults a {@code JPanel} to {@code CENTER}
         * alignment, and a {@code BoxLayout.Y_AXIS} that mixes alignment values lays the
         * LEFT-aligned children out relative to the centred child rather than the container —
         * which indented every control on the step and squeezed wrapped labels into a narrower
         * width than their reserved height allowed for, clipping their last line.
         *
         * <p>Also clamped in height: without a maximum, BoxLayout will stretch this block into
         * any spare vertical space instead of leaving it at the top where it belongs.
         */
        JPanel instrPanel(String html) {
            JLabel lbl = new JLabel(Ui.wrap(html));
            lbl.setFont(Ui.body());
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.line()),
                BorderFactory.createEmptyBorder(0, 0, 10, 0)));
            p.add(lbl, BorderLayout.WEST);
            p.setAlignmentX(LEFT_ALIGNMENT);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
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
            // Two colour spaces, one source: raw sRGB drives detection and background
            // correction (the quartic fits the lamp gradient there), linear light is the
            // integration base (pixel value proportional to intensity). See
            // ImagePreparation.toLuminanceGrayscale(ImagePlus, boolean).
            FloatProcessor fp = greenBtn.isSelected()
                ? ImagePreparation.extractGreenChannel(state.originalImage, false)
                : ImagePreparation.toLuminanceGrayscale(state.originalImage, false);
            FloatProcessor fpLinear = greenBtn.isSelected()
                ? ImagePreparation.extractGreenChannel(state.originalImage, true)
                : ImagePreparation.toLuminanceGrayscale(state.originalImage, true);
            state.invertImage = invertBox.isSelected();
            if (state.invertImage) { fp.invert(); fpLinear.invert(); }
            state.grayscale = fp;
            state.grayscaleLinear = fpLinear;
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
                ensureNonDegenerate();
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
            ensureNonDegenerate();
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
            roi.setPointType(PointRoi.CIRCLE);
            roi.setSize(4); // "Extra Large" — default marker is too small to see at typical zoom
            roi.setStrokeColor(Color.RED);
            imp.setRoi(roi);

            Overlay ov = new Overlay();
            int imgW = (state.grayscale != null) ? state.grayscale.getWidth() : 1000;
            int cornerFontSize = Math.max(24, Math.min(120, imgW / 40));
            Font labelFont = new Font("SansSerif", Font.BOLD, cornerFontSize);

            // Connect the four corners with edge lines so the crop quadrilateral is
            // visually obvious at a glance, not just four independent handles.
            float edgeWidth = Math.max(2f, imgW / 400f);
            int[] order = {0, 1, 2, 3, 0}; // TL, TR, BR, BL, TL — closes the loop
            for (int i = 0; i < 4; i++) {
                int a = order[i], b = order[i + 1];
                Line edge = new Line(
                    state.corners[a * 2], state.corners[a * 2 + 1],
                    state.corners[b * 2], state.corners[b * 2 + 1]);
                edge.setStrokeColor(Color.RED);
                edge.setStrokeWidth(edgeWidth);
                ov.add(edge);
            }

            String[] labels = {"TL", "TR", "BR", "BL"};
            int lx = (int)(cornerFontSize * 1.4);  // left-side offset (approx 2-char width)
            int ly = (int)(cornerFontSize * 1.1);  // vertical offset (approx 1 line height)
            int[] dx = {-lx, 5, 5, -lx};
            int[] dy = {-ly, -ly, 5, 5};
            for (int i = 0; i < 4; i++) {
                TextRoi tr = new TextRoi(
                    state.corners[i * 2] + dx[i],
                    state.corners[i * 2 + 1] + dy[i],
                    labels[i], labelFont);
                tr.setStrokeColor(Color.YELLOW);
                ov.add(tr);
            }
            imp.setOverlay(ov);
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
            updateRoi();
        }

        private void ensureNonDegenerate() {
            if (state.corners == null || state.grayscale == null) return;
            float[] c = state.corners;
            // Shoelace area of the quadrilateral
            float area = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                area += c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1];
            }
            area = Math.abs(area) / 2f;
            float imgArea = (float) state.grayscale.getWidth() * state.grayscale.getHeight();
            if (area < 0.01f * imgArea) {
                int w = state.grayscale.getWidth();
                int h = state.grayscale.getHeight();
                int inX = w / 10, inY = h / 10;
                state.corners = new float[]{
                    inX,     inY,         // TL
                    w - inX, inY,         // TR
                    w - inX, h - inY,     // BR
                    inX,     h - inY      // BL
                };
                IJ.log("[Step 2] Degenerate corner detection — reset to 10% inset defaults.");
            }
        }

        @Override
        boolean commit() {
            spinnersToCorners();
            state.corrected     = PerspectiveCorrection.warpImage(state.grayscale, state.corners);
            state.perspCorrected = state.corrected; // snapshot pre-background for step 3 re-entry
            state.perspCorrectedLinear = (state.grayscaleLinear != null)
                ? PerspectiveCorrection.warpImage(state.grayscaleLinear, state.corners)
                : null;
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
        private JRadioButton topHatBtn;
        private JRadioButton sgBtn;
        private JSpinner seRadiusSp;
        private JSpinner sgDegreeSp;

        Step3Panel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            add(instrPanel("Correct for uneven plate illumination.<br>" +
                "<b>Quartic polynomial</b> fits a 2-D surface to the whole plate (TLCyzer method).<br>" +
                "<b>White top-hat</b> removes background by morphological opening — best for " +
                "UV-fluorescence plates.<br>" +
                "<b>Savitzky-Golay</b> estimates background locally per spot — better for " +
                "non-uniform charring or sulphuric-acid stained plates."));
            add(Box.createVerticalStrut(14));

            polynomialBtn = new JRadioButton(
                "<html><b>Quartic polynomial</b></html>", true);
            topHatBtn = new JRadioButton(
                "<html><b>White top-hat</b> &nbsp;<small style='color:gray'>(recommended for UV-fluorescence)</small></html>");
            sgBtn = new JRadioButton("<html><b>Savitzky-Golay</b> per-spot polynomial</html>");
            ButtonGroup grp = new ButtonGroup();
            grp.add(polynomialBtn);
            grp.add(topHatBtn);
            grp.add(sgBtn);
            polynomialBtn.setAlignmentX(LEFT_ALIGNMENT);
            topHatBtn.setAlignmentX(LEFT_ALIGNMENT);
            sgBtn.setAlignmentX(LEFT_ALIGNMENT);
            add(polynomialBtn);
            add(Box.createVerticalStrut(6));
            add(topHatBtn);

            // Top-hat options panel — SE radius
            JPanel topHatOptions = new JPanel();
            topHatOptions.setLayout(new BoxLayout(topHatOptions, BoxLayout.Y_AXIS));
            topHatOptions.setAlignmentX(LEFT_ALIGNMENT);
            topHatOptions.setBorder(BorderFactory.createEmptyBorder(4, 22, 0, 0));

            seRadiusSp = new JSpinner(new SpinnerNumberModel(0, 0, 500, 5));
            ((JSpinner.DefaultEditor) seRadiusSp.getEditor()).getTextField().setColumns(4);
            JPanel seRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            seRow.add(new JLabel("SE radius (px):"));
            seRow.add(seRadiusSp);
            seRow.add(new JLabel(
                "<html><small style='color:gray'>(0 = auto: 1.5× median spot radius)</small></html>"));
            seRow.setAlignmentX(LEFT_ALIGNMENT);
            topHatOptions.add(seRow);
            topHatOptions.setVisible(false);
            add(topHatOptions);

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

            // Re-pack on an actual visibility change only: a ChangeListener also fires for
            // armed/pressed/rollover transitions, and re-packing the window on every one of
            // those would make the frame flicker as the pointer crosses a radio button.
            topHatBtn.addChangeListener(e -> {
                if (topHatOptions.isVisible() != topHatBtn.isSelected()) {
                    topHatOptions.setVisible(topHatBtn.isSelected());
                    relayoutStep();
                }
            });
            sgBtn.addChangeListener(e -> {
                if (sgOptions.isVisible() != sgBtn.isSelected()) {
                    sgOptions.setVisible(sgBtn.isSelected());
                    relayoutStep();
                }
            });
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
            state.usedTopHatBackground     = topHatBtn.isSelected();
            state.sgDegree = (Integer) sgDegreeSp.getValue();
            FloatProcessor src = (state.perspCorrected != null) ? state.perspCorrected : state.corrected;

            if (state.usedPolynomialBackground) {
                IJ.showStatus("Fitting quartic background polynomial…");
                state.corrected = BackgroundCorrection.fitAndSubtract(src);
                IJ.showStatus("");
                setDisplay(state.corrected.duplicate(),
                    "TLC Digitizer — Step 3 · Background corrected");
                IJ.log("[Step 3] Background method: quartic polynomial");

            } else if (state.usedTopHatBackground) {
                int seInput = (Integer) seRadiusSp.getValue();
                float seRadius;
                if (seInput == 0) {
                    // Auto: estimate SE radius from a quick polynomial detection pass
                    IJ.showStatus("Estimating SE radius…");
                    FloatProcessor polyEst = BackgroundCorrection.fitAndSubtract(src);
                    float[] epx = (float[]) polyEst.getPixels();
                    float emax = 0;
                    for (float v : epx) if (v > emax) emax = v;
                    if (emax > 0) {
                        float sc = 255f / emax;
                        for (int i = 0; i < epx.length; i++) epx[i] *= sc;
                    }
                    List<Spot> prelim = SpotDetector.detect(polyEst, 1.0f);
                    float mR = prelim.isEmpty() ? 20f : medianRadiusOf(prelim);
                    seRadius = Math.max(10f, 1.5f * mR);
                    IJ.log("[Step 3] Top-hat SE radius (auto): " + seRadius + " px");
                } else {
                    seRadius = seInput;
                }
                state.topHatSeRadius = seRadius;
                IJ.showStatus("Applying white top-hat…");
                state.corrected = BackgroundCorrection.topHat(src, seRadius);
                IJ.showStatus("");
                setDisplay(state.corrected.duplicate(),
                    "TLC Digitizer — Step 3 · Background (top-hat, SE=" + (int) seRadius + " px)");
                IJ.log("[Step 3] Background method: white top-hat (SE radius " + seRadius + " px)");

            } else {
                state.corrected = src;
                setDisplay(state.corrected.duplicate(),
                    "TLC Digitizer — Step 3 · Background (SG deferred to Step 5)");
                IJ.log("[Step 3] Background method: Savitzky-Golay (degree " + state.sgDegree + ")");
            }
            return true;
        }

        private float medianRadiusOf(List<Spot> spots) {
            float[] radii = new float[spots.size()];
            for (int i = 0; i < spots.size(); i++) radii[i] = spots.get(i).radius;
            java.util.Arrays.sort(radii);
            return radii[spots.size() / 2];
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
        /** Detection method is one mutually-exclusive choice (radio group), not independent
         * toggles: these are alternative strategies for the same job, and letting them combine
         * freely made the previous checkbox-based UI show no clear default and no clear
         * relationship between the threshold slider and whichever method(s) happened to be
         * checked (feedback from interactive testing, 2026-07-12). */
        private JRadioButton legacyRadio;
        private JRadioButton shapeAwareRadio;
        private JRadioButton laneDetectionRadio;
        private JRadioButton labkitRadio;
        private JPanel labkitPanel;
        private JLabel labkitStatusLabel;
        private JButton labkitTrainBtn;
        private JButton labkitMarkSpotBtn;
        private JButton labkitMarkBgBtn;
        private JLabel countLabel;

        private List<Spot> spots = new ArrayList<Spot>();
        private final List<List<Spot>> spotHistory = new ArrayList<List<Spot>>();
        private Overlay overlay;
        private MouseAdapter canvasListener;
        private java.awt.event.AWTEventListener awtUndoListener;
        /** Pre-flattened image used for detection when Savitzky-Golay is selected. */
        private FloatProcessor flattenedForDetection;

        /** User-marked example regions for the Labkit trainable-classifier mode (image-pixel
         * coordinates on {@code state.corrected}), and the resulting trained probability map —
         * null until "Train & Detect" is clicked. See {@code TrainableClassifier}. */
        private final List<Rectangle> labkitSpotRegions = new ArrayList<>();
        private final List<Rectangle> labkitBackgroundRegions = new ArrayList<>();
        private FloatProcessor labkitProbabilityMap;

        Step5Panel() {
            setLayout(new BorderLayout(0, 8));

            add(instrPanel("Pick a detection method below, then drag the slider to auto-detect " +
                "spots. <b>Click</b> to add a missed spot, <b>Ctrl+click</b> a spot to remove " +
                "it, <b>Ctrl+Z</b> to undo " +
                "<span style='color:gray'>(unavailable while Advanced detection/Labkit is " +
                "selected — see below)</span>."), BorderLayout.NORTH);

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

            // GridBagLayout, not BorderLayout: the multiplier spinner is taller than a plain
            // label, and BorderLayout's default vertical alignment for WEST/EAST vs. a taller
            // CENTER component split this into three visually separate lines instead of one row
            // (confirmed happening in practice) -- GridBagLayout keeps every cell in the same
            // row reliably regardless of each component's own preferred height.
            JPanel sliderRow = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridy = 0;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(0, 0, 0, 6);

            gbc.gridx = 0;
            sliderRow.add(new JLabel("Multiplier:"), gbc);
            gbc.gridx = 1;
            sliderRow.add(multSp, gbc);
            gbc.gridx = 2;
            sliderRow.add(new JLabel("0.1×"), gbc);
            gbc.gridx = 3;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            sliderRow.add(slider, gbc);
            gbc.gridx = 4;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            gbc.insets = new Insets(0, 0, 0, 0);
            sliderRow.add(new JLabel("5×"), gbc);

            // Title on the radio, one line of description beneath it, validation status as a
            // badge. Previously each option was a single wrapped paragraph ending in a grey
            // parenthetical, and "not yet validated against the reference plates" appeared three
            // times in a row -- accurate, but it made the panel's largest block of text a
            // disclaimer, and buried what each method actually does.
            legacyRadio        = new JRadioButton("Legacy (mean threshold)");
            shapeAwareRadio    = new JRadioButton("Shape-aware detection");
            laneDetectionRadio = new JRadioButton("Lane detection");
            labkitRadio        = new JRadioButton("Advanced detection (Labkit)");
            for (JRadioButton rb : new JRadioButton[]{
                    legacyRadio, shapeAwareRadio, laneDetectionRadio, labkitRadio}) {
                rb.setFont(Ui.bodyBold());
            }

            ButtonGroup methodGroup = new ButtonGroup();
            methodGroup.add(legacyRadio);
            methodGroup.add(shapeAwareRadio);
            methodGroup.add(laneDetectionRadio);
            methodGroup.add(labkitRadio);
            legacyRadio.setSelected(true);

            for (JRadioButton rb : new JRadioButton[]{legacyRadio, shapeAwareRadio, laneDetectionRadio, labkitRadio}) {
                rb.setAlignmentX(LEFT_ALIGNMENT);
                rb.addActionListener(e -> {
                    labkitPanel.setVisible(labkitRadio.isSelected());
                    relayoutStep();
                    if (labkitRadio.isSelected()) {
                        // Hide the previous detection overlay while marking regions -- those
                        // old numbered circles don't reflect anything about the Labkit workflow
                        // and just clutter the image the user is trying to draw rectangles on.
                        clearOverlayForLabkitMarking();
                    } else {
                        detect();
                    }
                });
            }

            labkitPanel = buildLabkitPanel();
            labkitPanel.setAlignmentX(LEFT_ALIGNMENT);
            labkitPanel.setVisible(false);

            JLabel methodHeader = new JLabel("Detection method:");
            methodHeader.setFont(methodHeader.getFont().deriveFont(Font.BOLD, 13f));
            methodHeader.setAlignmentX(LEFT_ALIGNMENT);

            JLabel sliderCaption = new JLabel(
                "<html><body style='color:gray'><i>Threshold multiplier — applies to whichever " +
                "method is selected above</i></body></html>");
            sliderCaption.setAlignmentX(LEFT_ALIGNMENT);

            JPanel controls = new JPanel();
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            controls.add(methodHeader);
            controls.add(Box.createVerticalStrut(Ui.GAP_TIGHT));
            controls.add(methodOption(legacyRadio, Ui.validatedBadge(),
                "Threshold at image-mean intensity, integrate within a fixed circular radius. "
                + "Recommended: the only method validated against the reference plates."));
            controls.add(Box.createVerticalStrut(Ui.GAP));
            controls.add(methodOption(shapeAwareRadio, Ui.betaBadge(),
                "Hysteresis-link and integrate each spot's true connected shape instead of a "
                + "fixed circle. Better for streaking or tailing spots."));
            controls.add(Box.createVerticalStrut(Ui.GAP));
            controls.add(methodOption(laneDetectionRadio, Ui.betaBadge(),
                "Assign spots to lanes by CWT-based lane-boundary detection rather than by gaps "
                + "between detected spots. Can represent a genuinely empty lane."));
            controls.add(Box.createVerticalStrut(Ui.GAP));
            controls.add(methodOption(labkitRadio, Ui.betaBadge(),
                "Train a pixel classifier from a few marked example regions instead of using a "
                + "single intensity threshold. Better for faint spots and tailing lanes."));
            controls.add(labkitPanel);

            // The method-selection area (not the threshold section below -- see the SOUTH
            // footer) is wrapped in a scroll pane: the wizard window's size is fixed at first
            // pack() (see the Step 5 checkbox-width lesson elsewhere in this file's history) and
            // won't grow to fit taller content. A single, full-height scroll pane here is a
            // standard, immediately visible pattern if that's ever needed -- unlike a small
            // scroll region wrapping just one sub-panel, which hid actual action buttons behind
            // a scrollbar that wasn't obviously there (confirmed as a real problem).
            JScrollPane controlsScroll = new JScrollPane(controls,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            controlsScroll.setBorder(BorderFactory.createEmptyBorder());
            add(controlsScroll, BorderLayout.CENTER);

            // Fixed SOUTH footer, never affected by scrolling above: the threshold slider
            // applies to every detection method (it shouldn't be something a scrollbar could
            // ever hide), and the spot count is always relevant regardless of which method's
            // extra controls are currently showing.
            JPanel footer = new JPanel();
            footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
            footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(10, 0, 0, 0)));
            sliderCaption.setAlignmentX(LEFT_ALIGNMENT);
            sliderRow.setAlignmentX(LEFT_ALIGNMENT);
            footer.add(sliderCaption);
            footer.add(sliderRow);
            footer.add(Box.createVerticalStrut(6));
            countLabel.setAlignmentX(LEFT_ALIGNMENT);
            footer.add(countLabel);
            add(footer, BorderLayout.SOUTH);
        }

        /**
         * One detection method: radio title, status badge on the same row, description beneath.
         * The description is indented to line up under the radio's text rather than its button,
         * so the block reads as one option instead of two unrelated lines.
         */
        private JPanel methodOption(JRadioButton rb, JLabel badge, String description) {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.add(rb);
            row.add(Box.createHorizontalStrut(Ui.GAP_TIGHT));
            row.add(badge);
            row.add(Box.createHorizontalGlue());

            JPanel block = new JPanel();
            block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
            block.setAlignmentX(LEFT_ALIGNMENT);
            block.add(row);
            block.add(Ui.caption(description, 22));
            return block;
        }

        @Override
        void onEnter() {
            spotHistory.clear();
            labkitSpotRegions.clear();
            labkitBackgroundRegions.clear();
            labkitProbabilityMap = null;
            if (labkitStatusLabel != null) updateLabkitStatus();
            setDisplay(state.corrected.duplicate(), "TLC Digitizer — Step 5 · Spots");
            overlay = new Overlay();
            displayWindow.setOverlay(overlay);

            // S-G mode: no global background was applied, so pre-flatten a detection-only
            // copy using the quartic polynomial. Integration uses raw state.corrected.
            // Top-hat mode: state.corrected is already the top-hat result (spots as
            // positive residuals) — use it directly for detection without re-flattening.
            if (!state.usedPolynomialBackground && !state.usedTopHatBackground) {
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
                        // While Advanced detection (Labkit) is active, clicks draw/adjust the
                        // rectangle selection used for region marking instead -- manual
                        // add/remove-spot would silently do nothing useful here (spots aren't
                        // shown until Train & Detect runs) and was confusing when both were live
                        // at once.
                        if (labkitRadio.isSelected()) return;
                        ImageCanvas c = displayWindow.getCanvas();
                        if (c == null) return;
                        int ix = c.offScreenX(e.getX());
                        int iy = c.offScreenY(e.getY());
                        FloatProcessor src = source();
                        if (SwingUtilities.isLeftMouseButton(e) && e.isControlDown()) {
                            // Ctrl+click: remove nearest spot
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
                        } else if (SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                            // Plain left-click: add spot
                            pushSpotHistory();
                            Spot found = SpotDetector.detectAtPoint(src, ix, iy, src.getHeight());
                            spots.add(new Spot(0, found.centroidX, found.centroidY,
                                found.radius, src.getHeight()));
                        } else {
                            return;
                        }
                        refreshOverlay();
                    }
                };
                canvas.addMouseListener(canvasListener);
            }

            // Intercept Ctrl+Z system-wide while Step 5 is active.
            // ImageCanvas lives in a separate JFrame so WHEN_IN_FOCUSED_WINDOW bindings
            // on TlcDigitizerFrame don't fire when the canvas has focus.  An AWTEventListener
            // runs before any window processes the key, so we can consume it here.
            int undoMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
            awtUndoListener = event -> {
                if (!(event instanceof KeyEvent)) return;
                KeyEvent ke = (KeyEvent) event;
                if (ke.getID() == KeyEvent.KEY_PRESSED
                        && ke.getKeyCode() == KeyEvent.VK_Z
                        && (ke.getModifiers() & undoMask) != 0) {
                    ke.consume();
                    SwingUtilities.invokeLater(Step5Panel.this::undoSpotEdit);
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    awtUndoListener, AWTEvent.KEY_EVENT_MASK);

            // The method radios persist across Back/Next (this panel isn't recreated each
            // visit), but labkitProbabilityMap was just reset above -- if Labkit was left
            // selected from a previous visit, detect() would silently fall back to legacy
            // thresholding while the UI still shows Labkit selected. Show the (now-empty)
            // marking overlay instead, consistent with a fresh start.
            if (labkitRadio.isSelected()) {
                clearOverlayForLabkitMarking();
            } else {
                detect();
            }
        }

        private FloatProcessor source() {
            return (flattenedForDetection != null) ? flattenedForDetection : state.corrected;
        }

        private void detect() {
            float mult = slider.getValue() / 100.0f;
            spotHistory.clear();
            // Labkit's probability map, once trained, replaces the source purely for the
            // threshold+connected-component step below -- integration in commit() always
            // integrates state.corrected, so this can't corrupt integration values.
            //
            // 2026-07-19: tried scaling Labkit-detected radii up 1.3x here (on the theory that
            // probability-map-derived circles undersize the real signal, so SpotIntegrator's
            // top-15%-by-intensity sum ends up averaged over too few pixels -- see
            // Spot#integrationPixelCount). Tested via a real interactive Train & Detect + strict
            // LOO recompute on MOESM4: it made things WORSE, not better (RSD 13.99% -> 19.10%,
            // spot-1 recovery 80.4% -> 66.2%) -- widening pulled in more background/edge pixels
            // rather than more real peak, hurting the faintest spot most. Reverted. Don't
            // reintroduce a blanket radius multiplier here without a real controlled re-test;
            // `integration_pixel_count` in the CSV export remains, and is what surfaced this.
            FloatProcessor detectionSource = (labkitRadio.isSelected() && labkitProbabilityMap != null)
                    ? labkitProbabilityMap : source();
            spots = SpotDetector.detect(detectionSource, mult, shapeAwareRadio.isSelected(),
                    state.originYFraction, state.frontYFraction);
            refreshOverlay();
        }

        /** Below this many marked regions per class, the status feedback nudges the user to
         * mark more — not a hard requirement (training only requires 1+ of each), but a
         * practical minimum for the classifier to generalize reasonably. */
        private static final int LABKIT_RECOMMENDED_MIN_REGIONS = 3;

        /** Every marked Labkit training region is normalized to a square of this size
         * (fraction of {@code min(width, height)}), centered on wherever the user actually
         * dragged, regardless of how large or small that drag was. Fixes a real failure mode
         * found via interactive testing (2026-07-13, MOESM2 — the cleanest plate in the
         * whole corpus): background boxes noticeably larger than the spot boxes (still well
         * under the {@link TrainableClassifier#imbalanceRatio}'s 20:1 warning threshold)
         * fully suppressed spot recall to zero, confirmed not a thresholding issue (0 real
         * spots detected even at the minimum 0.1x multiplier) — i.e. below-the-warning-
         * threshold imbalance can still break training completely. Rather than rely on users
         * to eyeball comparably-sized boxes, this removes size as a variable entirely: every
         * region (spot or background) has identical area, so class balance depends only on
         * region *count*, which the existing per-button counters already make visible. This
         * also matches the methodology of the original successful feasibility spike
         * (~13 small, similarly-sized 16px boxes) rather than inventing a new convention.
         */
        private static final float LABKIT_REGION_SIZE_FRACTION = 0.02f;

        /** Builds the region-marking + train UI shown only when {@link #labkitRadio} is
         * selected, added directly under it in the same flow as every other radio's own
         * description (no separate scroll region — see {@link #Step5Panel()} for why a small
         * nested scroll pane around just this panel was tried and abandoned: it hid the actual
         * action buttons behind a scrollbar that wasn't obvious was there, which is a worse
         * problem than the panel being a bit tall). */
        private JPanel buildLabkitPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 0));

            labkitMarkSpotBtn = new JButton("Mark as spot");
            labkitMarkSpotBtn.addActionListener(e -> markSelection(labkitSpotRegions));
            labkitMarkBgBtn = new JButton("Mark as background");
            labkitMarkBgBtn.addActionListener(e -> markSelection(labkitBackgroundRegions));
            JButton clearBtn = new JButton("Clear");
            clearBtn.addActionListener(e -> {
                labkitSpotRegions.clear();
                labkitBackgroundRegions.clear();
                labkitProbabilityMap = null;
                updateLabkitStatus();
                refreshLabkitMarkingOverlay();
            });
            labkitTrainBtn = new JButton("Train & Detect");
            labkitTrainBtn.setAlignmentX(LEFT_ALIGNMENT);
            labkitTrainBtn.addActionListener(e -> trainLabkitClassifier());

            // Row 1: the two "mark" actions (each button's own live counter, per David's
            // "small counter next to each button" feedback, replaces the old separate red
            // status-count text). Row 2: Clear + Train & Detect together, since they're both
            // "do something with the accumulated regions" actions rather than "add a region."
            JPanel markRow = new JPanel();
            markRow.setLayout(new BoxLayout(markRow, BoxLayout.X_AXIS));
            labkitMarkSpotBtn.setAlignmentX(LEFT_ALIGNMENT);
            labkitMarkBgBtn.setAlignmentX(LEFT_ALIGNMENT);
            markRow.add(labkitMarkSpotBtn);
            markRow.add(Box.createHorizontalStrut(6));
            markRow.add(labkitMarkBgBtn);
            markRow.setAlignmentX(LEFT_ALIGNMENT);

            JPanel actionRow = new JPanel();
            actionRow.setLayout(new BoxLayout(actionRow, BoxLayout.X_AXIS));
            clearBtn.setAlignmentX(LEFT_ALIGNMENT);
            actionRow.add(clearBtn);
            actionRow.add(Box.createHorizontalStrut(6));
            actionRow.add(labkitTrainBtn);
            actionRow.setAlignmentX(LEFT_ALIGNMENT);

            JPanel buttonCol = new JPanel();
            buttonCol.setLayout(new BoxLayout(buttonCol, BoxLayout.Y_AXIS));
            buttonCol.add(markRow);
            buttonCol.add(Box.createVerticalStrut(4));
            buttonCol.add(actionRow);

            // A plain wrapping JTextArea, not an HTML JLabel: this project has already hit the
            // "HTML width:Npx isn't reliably honored" class of bug once (see the Step 5
            // checkbox-width lesson elsewhere in this file's history), and it recurred here too.
            // A JTextArea's wrapping is a real layout computation, not CSS Swing's HTML renderer
            // may or may not honor.
            JTextArea instr = new JTextArea(
                "Click roughly on a spot or on background (Fiji's rectangle tool -- any drag "
                    + "size is fine, it's normalized to a fixed size automatically), then "
                    + "click the matching button (" + LABKIT_RECOMMENDED_MIN_REGIONS
                    + "+ of each recommended). Spot and background counts must match exactly "
                    + "before training is enabled.");
            instr.setLineWrap(true);
            instr.setWrapStyleWord(true);
            instr.setEditable(false);
            instr.setFocusable(false);
            instr.setOpaque(false);
            instr.setFont(instr.getFont().deriveFont(Font.ITALIC));
            instr.setForeground(Color.GRAY);
            instr.setAlignmentY(Component.TOP_ALIGNMENT);
            // Computed, not guessed: a hardcoded pixel height clipped this text once already
            // today when a sentence was added (60/80px), and the "generous" replacement
            // (100/130px) still clipped the last line -- two guesses, two misses. Instead,
            // size the JTextArea at its fixed width and read back how tall Swing's own line-
            // wrapping computation says it needs to be, so this is correct for whatever the
            // text happens to be, now or after a future edit.
            int wrapWidth = 280;
            instr.setSize(wrapWidth, Short.MAX_VALUE);
            int wrappedHeight = instr.getPreferredSize().height;
            instr.setPreferredSize(new Dimension(wrapWidth, wrappedHeight));
            instr.setMaximumSize(new Dimension(wrapWidth, wrappedHeight));

            JPanel topRow = new JPanel();
            topRow.setLayout(new BoxLayout(topRow, BoxLayout.X_AXIS));
            buttonCol.setAlignmentY(Component.TOP_ALIGNMENT);
            topRow.add(buttonCol);
            topRow.add(Box.createHorizontalStrut(12));
            topRow.add(instr);
            topRow.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(topRow);

            labkitStatusLabel = new JLabel(" ");
            labkitStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(Box.createVerticalStrut(4));
            panel.add(labkitStatusLabel);
            updateLabkitStatus();

            return panel;
        }

        /** Reads the current rectangular ROI on the display canvas (image-pixel coordinates),
         * normalizes it to a fixed-size square centered on the user's drag (see
         * {@link #LABKIT_REGION_SIZE_FRACTION} for why), and records it under the given
         * region list. */
        private void markSelection(List<Rectangle> regions) {
            ImagePlus imp = getDisplayWindow();
            if (imp == null || imp.getRoi() == null) {
                IJ.error("Step 5 — Advanced detection",
                    "Click roughly on a spot or background area first (Fiji's rectangle "
                        + "selection tool, top-left square icon in the main Fiji toolbar "
                        + "window) — any drag size is fine, it's normalized automatically.");
                return;
            }
            Rectangle dragged = imp.getRoi().getBounds();
            int size = Math.max(10, Math.round(
                LABKIT_REGION_SIZE_FRACTION * Math.min(imp.getWidth(), imp.getHeight())));
            int cx = dragged.x + dragged.width / 2;
            int cy = dragged.y + dragged.height / 2;
            int x = Math.max(0, Math.min(imp.getWidth() - size, cx - size / 2));
            int y = Math.max(0, Math.min(imp.getHeight() - size, cy - size / 2));
            regions.add(new Rectangle(x, y, size, size));
            imp.deleteRoi();
            labkitProbabilityMap = null;  // stale once labels change
            updateLabkitStatus();
            refreshLabkitMarkingOverlay();
        }

        /** Updates each mark button's own live region count and the (only-when-relevant)
         * mismatch/trained note below them, and enables {@link #labkitTrainBtn} only once
         * counts match. The counts used to be a separate always-visible red/orange/green
         * status line, which read as alarming before the user had done anything at all -- a
         * small counter directly on each button is calmer and more compact, and only
         * genuinely noteworthy conditions (count mismatch, trained) get a separate line.
         *
         * <p>Every marked region is now a fixed size (see {@link #LABKIT_REGION_SIZE_FRACTION}),
         * so class balance depends purely on region <em>count</em> -- an area-ratio warning
         * doesn't add anything beyond a plain count comparison anymore, and equal counts are
         * enforced as a hard requirement (button disabled, not just a warning), not a
         * recommendation: the 2026-07-13 MOESM2 interactive test found that even moderate
         * imbalance (well under the old 20:1 area-ratio warning threshold) can silently zero
         * out spot recall entirely on an otherwise clean plate. */
        private void updateLabkitStatus() {
            int nSpot = labkitSpotRegions.size(), nBg = labkitBackgroundRegions.size();
            if (labkitMarkSpotBtn != null) labkitMarkSpotBtn.setText("Mark as spot (" + nSpot + ")");
            if (labkitMarkBgBtn != null) labkitMarkBgBtn.setText("Mark as background (" + nBg + ")");

            boolean countsMatch = nSpot == nBg;
            if (labkitTrainBtn != null) labkitTrainBtn.setEnabled(countsMatch && nSpot > 0);
            if (labkitStatusLabel == null) return;

            StringBuilder sb = new StringBuilder("<html><body>");
            boolean any = false;
            if (!countsMatch && (nSpot > 0 || nBg > 0)) {
                sb.append("<span style='color:#b8860b'>⚠ spot/background counts must match "
                    + "exactly before training (currently " + nSpot + " vs " + nBg
                    + ") — mark more of whichever class is behind</span>");
                any = true;
            }
            if (labkitProbabilityMap != null) {
                if (any) sb.append("<br>");
                sb.append("<span style='color:green'>✓ trained — showing classifier-based " +
                    "detection below</span>");
                any = true;
            }
            sb.append("</body></html>");
            labkitStatusLabel.setText(any ? sb.toString() : " ");
        }

        private void trainLabkitClassifier() {
            if (labkitSpotRegions.isEmpty() || labkitBackgroundRegions.isEmpty()) {
                IJ.error("Step 5 — Advanced detection",
                    "Mark at least one spot region and one background region before training.");
                return;
            }
            // Defense in depth: updateLabkitStatus() already disables this button until
            // counts match, but re-check here too in case that ever falls out of sync (see
            // its javadoc for why exact counts are required, not just recommended).
            if (labkitSpotRegions.size() != labkitBackgroundRegions.size()) {
                IJ.error("Step 5 — Advanced detection",
                    "Spot and background region counts must match exactly (currently "
                        + labkitSpotRegions.size() + " spot vs " + labkitBackgroundRegions.size()
                        + " background) -- mark or remove regions until they're equal, "
                        + "then train again.");
                return;
            }
            // Training/prediction take several seconds and there's no way to show real
            // progress (Labkit doesn't report incremental progress), so this runs off the EDT
            // in a SwingWorker -- setting a wait cursor/status text and then blocking
            // synchronously on the EDT (an earlier version of this method did that) never
            // actually renders those changes, since Swing repaints are just queued events that
            // can't run while the EDT is busy: the whole UI looks frozen with zero feedback for
            // the entire duration, easy to mistake for "did nothing."
            labkitTrainBtn.setEnabled(false);
            labkitStatusLabel.setText("<html><i>Training… this can take several seconds.</i></html>");
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            ImageCanvas canvas = getDisplayWindow() == null ? null : getDisplayWindow().getCanvas();
            if (canvas != null) canvas.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            IJ.showStatus("Training classifier…");

            new SwingWorker<FloatProcessor, Void>() {
                private Throwable failure;

                @Override
                protected FloatProcessor doInBackground() {
                    try {
                        TrainableClassifier classifier = TrainableClassifier.train(
                            state.corrected, labkitSpotRegions, labkitBackgroundRegions);
                        return classifier.predictSpotProbability(state.corrected);
                    } catch (Throwable ex) {
                        // Catches Throwable, not just Exception: a missing/incompatible
                        // dependency at runtime (e.g. a class-version mismatch between this
                        // plugin and Fiji's own bundled jars) surfaces as an Error
                        // (NoSuchMethodError, NoClassDefFoundError, ...), not an Exception -- an
                        // earlier version of this catch clause only caught Exception and let
                        // such failures propagate silently (no dialog, easy to mistake for
                        // "nothing happened").
                        failure = ex;
                        return null;
                    }
                }

                @Override
                protected void done() {
                    IJ.showStatus("");
                    setCursor(Cursor.getDefaultCursor());
                    if (canvas != null) canvas.setCursor(Cursor.getDefaultCursor());
                    labkitTrainBtn.setEnabled(true);

                    if (failure != null) {
                        IJ.error("Step 5 — Advanced detection",
                            "Training failed: " + failure.getClass().getSimpleName()
                                + (failure.getMessage() != null ? (": " + failure.getMessage()) : ""));
                        labkitProbabilityMap = null;
                        updateLabkitStatus();
                        return;
                    }
                    try {
                        labkitProbabilityMap = get();
                    } catch (Exception ex) {
                        // get() only rethrows if doInBackground threw AND failure wasn't set,
                        // which can't happen given the catch above -- kept for correctness.
                        labkitProbabilityMap = null;
                    }
                    updateLabkitStatus();
                    detect();
                }
            }.execute();
        }

        /** Clears the spot-detection overlay when switching into Labkit marking mode, so old
         * numbered circles from the previous (threshold-based) detection don't clutter the
         * image, then draws whatever regions are already marked (see
         * {@link #refreshLabkitMarkingOverlay}). Detection resumes, and the overlay is
         * repopulated with detected spots, once Train & Detect runs (via {@link #detect}) or a
         * different method is selected. */
        private void clearOverlayForLabkitMarking() {
            spots = new ArrayList<>();
            // countLabel isn't updated by refreshOverlay() here (that only runs from detect()) --
            // without this, it kept showing the stale count from whichever method was selected
            // before switching to Labkit, which reads as "8 spots detected" even though nothing
            // has been detected yet and no training has happened.
            countLabel.setText(labkitProbabilityMap == null
                ? "No spots detected yet — mark regions and train below"
                : "0 spots detected");
            refreshLabkitMarkingOverlay();
        }

        /** Draws every currently-marked Labkit training region as a persistent, labeled
         * rectangle on the canvas (green for spot regions, orange for background) — without
         * this, a marked rectangle disappeared the instant it was marked (the selection itself
         * is cleared right after recording it, see {@link #markSelection}), leaving nothing for
         * the user to visually remember what had already been marked. */
        private void refreshLabkitMarkingOverlay() {
            ImagePlus imp = getDisplayWindow();
            if (imp == null) return;
            overlay = new Overlay();
            addLabeledRegionRois(overlay, labkitSpotRegions, "S", new Color(40, 180, 40));
            addLabeledRegionRois(overlay, labkitBackgroundRegions, "B", new Color(230, 140, 30));
            imp.setOverlay(overlay);
        }

        private void addLabeledRegionRois(Overlay ov, List<Rectangle> regions, String prefix, Color color) {
            for (int i = 0; i < regions.size(); i++) {
                Rectangle r = regions.get(i);
                Roi roi = new Roi(r.x, r.y, r.width, r.height);
                roi.setStrokeColor(color);
                roi.setStrokeWidth(2f);
                roi.setName(prefix + (i + 1));
                ov.add(roi);

                String label = prefix + (i + 1);
                TextRoi text = new TextRoi(r.x + 2, r.y - 16, label,
                    new Font("SansSerif", Font.BOLD, 14));
                text.setStrokeColor(color);
                ov.add(text);
            }
        }

        private void refreshOverlay() {
            // Always assign final IDs before drawing so the overlay matches Step 6.
            renumberXFirst(spots, source().getHeight());
            ImagePlus imp = getDisplayWindow();
            if (imp == null) return;
            // Create a fresh Overlay every time — reusing and clearing the old one
            // leaves stale TextRoi ghosts in ImageJ's render cache.
            overlay = new Overlay();
            updateSpotOverlay(overlay, imp, spots);
            int n = spots.size();
            countLabel.setText(n + " spot" + (n == 1 ? "" : "s") + " detected");
        }

        private void renumberXFirst(List<Spot> list, int imageHeight) {
            list.sort(Comparator.comparingDouble((Spot s) -> (double) s.centroidX)
                .thenComparingDouble(s -> (double) s.centroidY));
            for (int i = 0; i < list.size(); i++) {
                // withId() preserves mask/ellipse data — a plain 5-arg reconstruction here
                // would silently discard shape-aware spots' mask on every overlay refresh.
                list.set(i, list.get(i).withId(i + 1));
            }
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
            // Detach listeners before finalising
            ImagePlus imp = getDisplayWindow();
            if (imp != null && canvasListener != null && imp.getCanvas() != null) {
                imp.getCanvas().removeMouseListener(canvasListener);
            }
            canvasListener = null;
            if (awtUndoListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtUndoListener);
                awtUndoListener = null;
            }

            if (spots.isEmpty()) {
                IJ.error("Step 5 — No Spots",
                    "No spots detected. Adjust the threshold slider and try again.");
                return false;
            }

            state.thresholdFactor = slider.getValue() / 100.0;
            state.shapeAwareDetection = shapeAwareRadio.isSelected();
            state.laneDetectionEnabled = laneDetectionRadio.isSelected();
            state.labkitDetectionEnabled = labkitRadio.isSelected() && labkitProbabilityMap != null;

            // Spots are already sorted X-first and renumbered by refreshOverlay();
            // just copy the final list into state.
            state.spots = new ArrayList<Spot>(spots);

            if (state.laneDetectionEnabled) {
                List<Lane> lanes = LaneDetector.detect(state.corrected, state.originYFraction);
                LaneAssigner.assignLanesFromBoundaries(state.spots, lanes);
            } else {
                LaneAssigner.assignLanes(state.spots, state.corrected.getWidth());
            }
            RfCalculator.assignAll(state.spots, state.originYFraction, state.frontYFraction);
            // Integration base mirrors ValidationRunner: top-hat integrates the corrected
            // image (background already removed); every other mode integrates the
            // linear-light warped image plus a per-spot local background correction, so
            // that the global quartic only ever influences detection, never the values.
            FloatProcessor integrationBase = state.integrationBase();
            SpotIntegrator.integrateAll(integrationBase, state.spots);

            if (!state.usedTopHatBackground) {
                IJ.showStatus("Applying per-spot polynomial background…");
                BackgroundCorrection.applyPerSpotPolynomial(
                    state.spots, integrationBase, state.sgDegree);
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

    // Spot colour palette — index by (spot.id - 1) % SPOT_COLORS.length.
    // Bright, distinct colours readable on both light and dark grayscale backgrounds.
    static final Color[] SPOT_COLORS = {
        new Color(255, 220,   0),  // gold
        new Color(  0, 210, 255),  // cyan
        new Color(255,  80,  80),  // coral
        new Color( 80, 235,  80),  // lime
        new Color(255, 140, 255),  // pink
        new Color(255, 155,  30),  // orange
        new Color(160, 255, 255),  // sky
        new Color(210, 210, 255),  // lavender
        new Color(255, 255, 120),  // pale yellow
        new Color(160, 255, 140),  // pale green
    };

    class Step6Panel extends AbstractStepPanel {
        private JPanel rows;
        private JScrollPane scroll;
        private final List<JCheckBox> refBoxes    = new ArrayList<JCheckBox>();
        private final List<JSpinner>  concSpinners = new ArrayList<JSpinner>();
        private final ButtonGroup modelGroup = new ButtonGroup();
        private final JRadioButton[] modelButtons =
                new JRadioButton[CalibrationModel.ModelType.values().length];
        private JLabel modelDesc;

        // LOD/LOQ convention selector
        private final ButtonGroup lodLoqGroup = new ButtonGroup();
        private final JRadioButton[] lodLoqButtons =
                new JRadioButton[CalibrationModel.LodLoqConvention.values().length];
        private JLabel lodLoqDesc;
        private JPanel lodLoqSection;       // entire LOD/LOQ block (hidden for non-LINEAR)
        private JLabel snSigmaLabel;        // shown for SIGNAL_NOISE
        private JPanel manualLodLoqPanel;   // shown for MANUAL
        private JSpinner manualLodSpinner;
        private JSpinner manualLoqSpinner;
        private double cachedBgSigma = Double.NaN;

        // Live R² preview + optimize (SOUTH, above calResultsLabel)
        private JLabel liveR2Label;
        private JButton optimizeBtn;

        // Calibration results strip (SOUTH) — populated after fitting
        private JLabel calResultsLabel;

        Step6Panel() {
            setLayout(new BorderLayout(0, 8));

            // --- instruction + model selector (stacked in NORTH) ---
            JPanel north = new JPanel();
            north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
            north.add(instrPanel("Tick the <b>Reference</b> checkbox for each calibration standard spot " +
                "and enter its known concentration. At least 3 reference spots are required " +
                "(ICH&nbsp;Q2(R1))."));

            // Calibration model
            JPanel modelPanel = new JPanel(new BorderLayout(0, 2));
            modelPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(6, 0, 8, 0)));
            JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            CalibrationModel.ModelType[] types = CalibrationModel.ModelType.values();
            for (int i = 0; i < types.length; i++) {
                modelButtons[i] = new JRadioButton(types[i].label);
                modelButtons[i].setFont(modelButtons[i].getFont().deriveFont(12f));
                final CalibrationModel.ModelType t = types[i];
                modelButtons[i].addActionListener(e -> {
                    modelDesc.setText(t.description);
                    lodLoqSection.setVisible(t == CalibrationModel.ModelType.LINEAR);
                    north.revalidate();
                    north.repaint();
                    liveCalibUpdate();
                });
                modelGroup.add(modelButtons[i]);
                radios.add(modelButtons[i]);
            }
            modelButtons[0].setSelected(true);  // LINEAR default
            modelDesc = new JLabel(types[0].description);
            modelDesc.setFont(modelDesc.getFont().deriveFont(Font.ITALIC, 11f));
            modelDesc.setForeground(Color.DARK_GRAY);
            modelPanel.add(radios, BorderLayout.CENTER);
            modelPanel.add(modelDesc, BorderLayout.SOUTH);
            north.add(modelPanel);

            // LOD/LOQ convention (visible only for LINEAR)
            lodLoqSection = new JPanel();
            lodLoqSection.setLayout(new BoxLayout(lodLoqSection, BoxLayout.Y_AXIS));
            lodLoqSection.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 0, 8, 0)));

            JLabel lodLoqTitle = new JLabel("LOD / LOQ convention:");
            lodLoqTitle.setFont(lodLoqTitle.getFont().deriveFont(Font.BOLD, 12f));
            JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            titleRow.add(lodLoqTitle);
            lodLoqSection.add(titleRow);

            JPanel lodLoqRadios = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            CalibrationModel.LodLoqConvention[] convs = CalibrationModel.LodLoqConvention.values();
            for (int i = 0; i < convs.length; i++) {
                lodLoqButtons[i] = new JRadioButton(convs[i].label);
                lodLoqButtons[i].setFont(lodLoqButtons[i].getFont().deriveFont(12f));
                final CalibrationModel.LodLoqConvention c = convs[i];
                lodLoqButtons[i].addActionListener(e -> updateLodLoqConditionalPanel(c));
                lodLoqGroup.add(lodLoqButtons[i]);
                lodLoqRadios.add(lodLoqButtons[i]);
            }
            lodLoqButtons[0].setSelected(true);  // REGRESSION_ICH default
            lodLoqSection.add(lodLoqRadios);

            lodLoqDesc = new JLabel(convs[0].description);
            lodLoqDesc.setFont(lodLoqDesc.getFont().deriveFont(Font.ITALIC, 11f));
            lodLoqDesc.setForeground(Color.DARK_GRAY);
            JPanel descRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            descRow.add(lodLoqDesc);
            lodLoqSection.add(descRow);

            // S/N sigma label (shown when SIGNAL_NOISE is selected)
            snSigmaLabel = new JLabel("σ_bg = computing…");
            snSigmaLabel.setFont(snSigmaLabel.getFont().deriveFont(12f));
            JPanel snRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            snRow.add(snSigmaLabel);
            snRow.setVisible(false);
            lodLoqSection.add(snRow);

            // Manual LOD / LOQ spinners (shown when MANUAL is selected)
            manualLodSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 1e9, 0.001));
            manualLodSpinner.setEditor(new JSpinner.NumberEditor(manualLodSpinner, "0.000"));
            ((JSpinner.NumberEditor) manualLodSpinner.getEditor()).getTextField().setColumns(8);
            manualLoqSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 1e9, 0.001));
            manualLoqSpinner.setEditor(new JSpinner.NumberEditor(manualLoqSpinner, "0.000"));
            ((JSpinner.NumberEditor) manualLoqSpinner.getEditor()).getTextField().setColumns(8);
            manualLodLoqPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            manualLodLoqPanel.add(new JLabel("LOD:"));
            manualLodLoqPanel.add(manualLodSpinner);
            manualLodLoqPanel.add(new JLabel("LOQ:"));
            manualLodLoqPanel.add(manualLoqSpinner);
            manualLodLoqPanel.setVisible(false);
            lodLoqSection.add(manualLodLoqPanel);

            // Store references to the conditional rows (snRow, manualLodLoqPanel) so
            // updateLodLoqConditionalPanel can show/hide them
            north.add(lodLoqSection);

            add(north, BorderLayout.NORTH);

            rows = new JPanel(new GridLayout(0, 4, 6, 3));
            scroll = new JScrollPane(rows);
            scroll.setPreferredSize(new Dimension(420, 240));
            add(scroll, BorderLayout.CENTER);

            // Live R² bar
            liveR2Label = new JLabel(
                "<html><body style='color:gray;font-style:italic'>" +
                "Assign ≥3 references to preview R²</body></html>");
            liveR2Label.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

            optimizeBtn = new JButton("Optimize radius");
            optimizeBtn.setEnabled(false);
            optimizeBtn.setToolTipText(
                "Search for the integration radius scale that maximises calibration R²");
            optimizeBtn.addActionListener(e -> optimizeIntegrationRadius());

            JPanel liveRow = new JPanel(new BorderLayout(6, 0));
            liveRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(2, 0, 2, 4)));
            liveRow.add(liveR2Label, BorderLayout.CENTER);
            liveRow.add(optimizeBtn, BorderLayout.EAST);

            // Empty and hidden until there is an actual result. A permanent
            // "results will appear here after clicking Next" placeholder occupied real estate
            // to say nothing, and read as an unfinished panel in every screenshot of this step.
            calResultsLabel = new JLabel();
            calResultsLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(5, 4, 5, 4)));

            JPanel southStack = new JPanel();
            southStack.setLayout(new BoxLayout(southStack, BoxLayout.Y_AXIS));
            southStack.add(liveRow);
            southStack.add(calResultsLabel);
            add(southStack, BorderLayout.SOUTH);
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
                JLabel idLbl = new JLabel(String.valueOf(s.id));
                idLbl.setForeground(SPOT_COLORS[(s.id - 1) % SPOT_COLORS.length]);
                idLbl.setFont(idLbl.getFont().deriveFont(Font.BOLD));
                rows.add(idLbl);
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

            // Wire up live R² — re-evaluate whenever the user ticks or types
            for (JCheckBox cb : refBoxes)       cb.addItemListener(e  -> liveCalibUpdate());
            for (JSpinner   sp : concSpinners)  sp.addChangeListener(e -> liveCalibUpdate());

            // Pre-compute background sigma for the S/N convention
            if (state.corrected != null) {
                cachedBgSigma = CalibrationModel.estimateBackgroundSigma(
                        state.corrected, state.spots);
                snSigmaLabel.setText(Double.isNaN(cachedBgSigma)
                    ? "σ_bg = unavailable (too few background pixels)"
                    : String.format("σ_bg = %.4g  (background pixel SD)", cachedBgSigma));
            }

            // Restore results strip if model was already fitted (back-navigation)
            showCalibrationResults(state.calibrationModel);

            setDisplay(state.corrected.duplicate(), "TLC Digitizer — Step 6 · Calibrate");
            Overlay ov = new Overlay();
            displayWindow.setOverlay(ov);
            updateSpotOverlay(ov, displayWindow, state.spots);

            liveCalibUpdate();
        }

        private void updateLodLoqConditionalPanel(CalibrationModel.LodLoqConvention conv) {
            lodLoqDesc.setText(conv.description);
            // Find the snRow and manualLodLoqPanel by their position in lodLoqSection
            Component[] comps = lodLoqSection.getComponents();
            for (Component c : comps) {
                if (c == manualLodLoqPanel) c.setVisible(conv == CalibrationModel.LodLoqConvention.MANUAL);
            }
            // snSigmaLabel is inside a JPanel (snRow) — update that panel's visibility
            if (snSigmaLabel.getParent() != null) {
                snSigmaLabel.getParent().setVisible(conv == CalibrationModel.LodLoqConvention.SIGNAL_NOISE);
            }
            lodLoqSection.revalidate();
            lodLoqSection.repaint();
        }

        private void liveCalibUpdate() {
            List<Spot> refs = currentRefSpots();
            if (refs.size() < 3) {
                liveR2Label.setText(
                    "<html><body style='color:gray;font-style:italic'>" +
                    "Assign ≥3 references to preview R²</body></html>");
                optimizeBtn.setEnabled(false);
                return;
            }
            try {
                CalibrationModel m = CalibrationModel.fit(refs, selectedModelType());
                String col = m.rSquared >= 0.99 ? "green" :
                             m.rSquared >= 0.90 ? "darkorange" : "red";
                liveR2Label.setText(String.format(
                    "<html><b style='color:%s'>R² = %.4f</b>" +
                    "&nbsp;&nbsp;<span style='color:gray;font-size:10px'>live preview</span></html>",
                    col, m.rSquared));
                optimizeBtn.setEnabled(true);
            } catch (Exception ex) {
                liveR2Label.setText(
                    "<html><i style='color:red'>Calibration error</i></html>");
                optimizeBtn.setEnabled(false);
            }
        }

        private List<Spot> currentRefSpots() {
            List<Spot> refs = new ArrayList<>();
            for (int i = 0; i < state.spots.size() && i < refBoxes.size(); i++) {
                if (!refBoxes.get(i).isSelected()) continue;
                double conc = ((Number) concSpinners.get(i).getValue()).doubleValue();
                if (conc <= 0) continue;
                Spot s = state.spots.get(i);
                Spot ref = s.withId(s.id); // preserves mask/ellipse if s is shape-aware
                ref.integrationValue = s.integrationValue;
                ref.isReference = true;
                ref.referenceConcentration = conc;
                refs.add(ref);
            }
            return refs;
        }

        private CalibrationModel.ModelType selectedModelType() {
            CalibrationModel.ModelType[] types = CalibrationModel.ModelType.values();
            for (int i = 0; i < modelButtons.length; i++) {
                if (modelButtons[i].isSelected()) return types[i];
            }
            return CalibrationModel.ModelType.LINEAR;
        }

        /**
         * R² tolerance for picking a winning radius scale out of the grid search below:
         * among all scales within this much of the true maximum R², the smallest is
         * chosen, rather than whichever single grid point has the strictly highest R².
         * Same constant, same rationale, as {@code ValidationRunner.RADIUS_SCALE_R2_TOLERANCE}
         * (MOESM3 investigation, 2026-07-19) — a naive single-pass {@code >} selection
         * picks whichever side of a noise-level near-tie the floating-point arithmetic
         * happens to favour, which can flip for a tiny change elsewhere in the pipeline,
         * multiplying every reference spot's integration radius by a different amount all
         * at once. Also reused below as the "not meaningfully better than baseline"
         * threshold for the info-dialog fallback.
         */
        private static final double RADIUS_SCALE_R2_TOLERANCE = 0.005;

        private void optimizeIntegrationRadius() {
            List<Spot> curRefs = currentRefSpots();
            if (curRefs.size() < 3 || state.corrected == null) return;
            CalibrationModel.ModelType mt = selectedModelType();
            double baseR2 = CalibrationModel.fit(curRefs, mt).rSquared;
            int imgH = state.corrected.getHeight();

            // Cap scale so no two spot circles overlap (90% of half inter-centroid distance).
            double maxSafeScale = 2.5;
            for (Spot a : state.spots) {
                for (Spot b : state.spots) {
                    if (a == b || a.radius <= 0) continue;
                    double dx = a.centroidX - b.centroidX;
                    double dy = a.centroidY - b.centroidY;
                    double halfGap = Math.sqrt(dx * dx + dy * dy) * 0.9 / 2.0;
                    maxSafeScale = Math.min(maxSafeScale, halfGap / a.radius);
                }
            }
            maxSafeScale = Math.max(maxSafeScale, 0.5); // never shrink below minimum

            // Grid search: radius scale 0.5× to 2.5× in steps of 0.1 (capped by overlap limit).
            // Two-pass selection (see RADIUS_SCALE_R2_TOLERANCE javadoc): find the true best
            // R² first, then pick the smallest scale within tolerance of it.
            double maxR2 = baseR2;
            Map<Double, Double> r2ByScale = new LinkedHashMap<>();
            for (int step = 5; step <= 25; step++) {
                double scale = step / 10.0;
                if (scale > maxSafeScale) break;
                List<Spot> tempRefs = new ArrayList<>();
                for (int i = 0; i < state.spots.size() && i < refBoxes.size(); i++) {
                    if (!refBoxes.get(i).isSelected()) continue;
                    double conc = ((Number) concSpinners.get(i).getValue()).doubleValue();
                    if (conc <= 0) continue;
                    Spot orig = state.spots.get(i);
                    // Radius scaling is meaningless for shape-aware spots — integration
                    // sums the mask, not a circle — so leave those untouched by the search.
                    Spot temp = orig.hasMask()
                        ? orig.withId(orig.id)
                        : new Spot(orig.id, orig.centroidX, orig.centroidY,
                            (float)(orig.radius * scale), imgH);
                    temp.isReference = true;
                    temp.referenceConcentration = conc;
                    SpotIntegrator.integrate(state.integrationBase(), temp);
                    tempRefs.add(temp);
                }
                if (tempRefs.size() < 3) continue;
                // The integration base carries its own background for every non-top-hat
                // mode, so the per-spot local correction must run here too or the R2 is
                // computed on values inflated by a background offset. Mirrors
                // ValidationRunner's grid search.
                if (!state.usedTopHatBackground) {
                    BackgroundCorrection.applyPerSpotPolynomial(
                        tempRefs, state.integrationBase(), state.sgDegree);
                }
                try {
                    double r2 = CalibrationModel.fit(tempRefs, mt).rSquared;
                    r2ByScale.put(scale, r2);
                    if (r2 > maxR2) maxR2 = r2;
                } catch (Exception ignored) {}
            }
            double bestScale = 1.0;
            double bestR2 = baseR2;
            for (Map.Entry<Double, Double> e : r2ByScale.entrySet()) {
                if (e.getValue() >= maxR2 - RADIUS_SCALE_R2_TOLERANCE) {
                    bestScale = e.getKey();
                    bestR2 = e.getValue();
                    break;
                }
            }

            if (bestScale == 1.0 || bestR2 <= baseR2 + RADIUS_SCALE_R2_TOLERANCE) {
                JOptionPane.showMessageDialog(TlcDigitizerFrame.this,
                    String.format("<html>Current radius is already optimal.<br>R² = %.4f</html>",
                        baseR2),
                    "Optimize Integration Radius", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            int choice = JOptionPane.showConfirmDialog(TlcDigitizerFrame.this,
                String.format(
                    "<html>Optimal integration radius: <b>%.1f×</b> the detected radius<br>" +
                    "R²: %.4f &rarr; <b>%.4f</b><br><br>" +
                    "Apply? All spot integration values will be recalculated.</html>",
                    bestScale, baseR2, bestR2),
                "Optimize Integration Radius", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) return;

            // Rebuild all spots with the optimal radius scale and re-integrate
            final double scale = bestScale;
            List<Spot> newSpots = new ArrayList<>();
            for (Spot s : state.spots) {
                Spot scaled = s.hasMask()
                    ? s.withId(s.id)
                    : new Spot(s.id, s.centroidX, s.centroidY, (float)(s.radius * scale), imgH);
                scaled.rfValue              = s.rfValue;
                scaled.lane                 = s.lane;
                scaled.isReference          = s.isReference;
                scaled.referenceConcentration = s.referenceConcentration;
                SpotIntegrator.integrate(state.integrationBase(), scaled);
                newSpots.add(scaled);
            }
            if (!state.usedTopHatBackground) {
                BackgroundCorrection.applyPerSpotPolynomial(
                    newSpots, state.integrationBase(), state.sgDegree);
            }
            state.spots = newSpots;

            // Refresh overlay to reflect new radii
            ImagePlus dispImp = getDisplayWindow();
            if (dispImp != null) {
                Overlay ov = dispImp.getOverlay();
                if (ov == null) { ov = new Overlay(); dispImp.setOverlay(ov); }
                updateSpotOverlay(ov, dispImp, state.spots);
            }

            IJ.log(String.format("[Step 6] Integration radius optimized: %.1f× — R² %.4f → %.4f",
                scale, baseR2, bestR2));
            liveCalibUpdate();
        }

        private JLabel bold(String text) {
            JLabel lbl = new JLabel("<html><b>" + text + "</b></html>");
            return lbl;
        }

        private CalibrationModel.LodLoqConvention selectedConvention() {
            CalibrationModel.LodLoqConvention[] convs = CalibrationModel.LodLoqConvention.values();
            for (int i = 0; i < lodLoqButtons.length; i++) {
                if (lodLoqButtons[i].isSelected()) return convs[i];
            }
            return CalibrationModel.LodLoqConvention.REGRESSION_ICH;
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

            CalibrationModel.ModelType modelType = CalibrationModel.ModelType.LINEAR;
            CalibrationModel.ModelType[] types = CalibrationModel.ModelType.values();
            for (int i = 0; i < modelButtons.length; i++) {
                if (modelButtons[i].isSelected()) { modelType = types[i]; break; }
            }
            try {
                state.calibrationModel = CalibrationModel.fit(refs, modelType);

                // Apply LOD/LOQ convention (only meaningful for LINEAR)
                if (modelType == CalibrationModel.ModelType.LINEAR) {
                    CalibrationModel.LodLoqConvention conv = selectedConvention();
                    double manualLod = ((Number) manualLodSpinner.getValue()).doubleValue();
                    double manualLoq = ((Number) manualLoqSpinner.getValue()).doubleValue();
                    state.calibrationModel = state.calibrationModel.withLodLoqConvention(
                            conv, cachedBgSigma, manualLod, manualLoq);
                }

                state.calibrationModel.applyTo(state.spots);
                IJ.log("[Step 6] " + state.calibrationModel.toSummary());

                // Show calibration quality summary before advancing to Step 7
                showCalibrationResults(state.calibrationModel);
                JOptionPane.showMessageDialog(
                    TlcDigitizerFrame.this,
                    buildCalibResultsHtml(state.calibrationModel),
                    "Step 6 — Calibration Results",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IllegalArgumentException e) {
                IJ.error("Step 6 — Calibration Error", e.getMessage());
                return false;
            }
            return true;
        }

        /** Shows the fitted-model summary, or nothing at all when there is no model yet. */
        private void showCalibrationResults(CalibrationModel m) {
            calResultsLabel.setText(m != null ? buildCalibResultsHtml(m) : "");
            calResultsLabel.setVisible(m != null);
        }

        private String buildCalibResultsHtml(CalibrationModel m) {
            StringBuilder sb = new StringBuilder();
            sb.append("<html><body style='width:480px'>");
            sb.append("<b>").append(m.modelType.label).append("</b>");
            sb.append("&nbsp;&nbsp;n&nbsp;=&nbsp;").append(m.nPoints);
            sb.append("&nbsp;&nbsp;R²&nbsp;=&nbsp;").append(String.format("%.4f", m.rSquared));
            sb.append("&nbsp;&nbsp;RMSE&nbsp;=&nbsp;").append(String.format("%.4g", m.rmse));
            if (m.modelType == CalibrationModel.ModelType.LINEAR) {
                sb.append("<br>slope&nbsp;=&nbsp;").append(String.format("%.4g", m.slope));
                sb.append("&nbsp;&nbsp;intercept&nbsp;=&nbsp;").append(String.format("%.4g", m.intercept));
                if (!Double.isNaN(m.lod) && !Double.isNaN(m.loq)) {
                    sb.append("<br>LOD&nbsp;=&nbsp;<b>").append(String.format("%.4g", m.lod)).append("</b>");
                    sb.append("&nbsp;&nbsp;LOQ&nbsp;=&nbsp;<b>").append(String.format("%.4g", m.loq)).append("</b>");
                    sb.append("&nbsp;&nbsp;<i>(").append(m.lodLoqConvention.label).append(")</i>");
                } else {
                    sb.append("<br><i>LOD/LOQ: not computed (zero slope)</i>");
                }
            } else if (m.modelType == CalibrationModel.ModelType.LOG_LOG) {
                sb.append("<br>exponent&nbsp;=&nbsp;").append(String.format("%.4g", m.coefficients[1]));
                sb.append("&nbsp;&nbsp;prefactor&nbsp;=&nbsp;").append(
                    String.format("%.4g", Math.exp(m.coefficients[0])));
            } else if (m.modelType == CalibrationModel.ModelType.QUADRATIC) {
                sb.append("<br>a₂&nbsp;=&nbsp;").append(String.format("%.4g", m.coefficients[2]));
                sb.append("&nbsp;&nbsp;a₁&nbsp;=&nbsp;").append(String.format("%.4g", m.coefficients[1]));
                sb.append("&nbsp;&nbsp;a₀&nbsp;=&nbsp;").append(String.format("%.4g", m.coefficients[0]));
            }
            sb.append("</body></html>");
            return sb.toString();
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
                "<b>.csv</b> — numerical results + analysis parameters<br>" +
                "<b>.png</b> — annotated plate image for spot identification<br>" +
                "<b>.tif</b> — same image with full CSV embedded in metadata " +
                "(Fiji › Image › Show Info… to retrieve)<br>" +
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
            String current = pathField.getText().trim();
            if (current.isEmpty() || current.endsWith("tlc_results.csv")) {
                pathField.setText(deriveDefaultPath());
            }
        }

        private String deriveDefaultPath() {
            String stem = "tlc_results";
            String dir  = System.getProperty("user.home") + File.separator;
            if (state.originalImage != null) {
                ij.io.FileInfo fi = state.originalImage.getOriginalFileInfo();
                if (fi != null && fi.fileName != null && !fi.fileName.isEmpty()) {
                    stem = fi.fileName.replaceAll("\\.[^.]+$", "");
                    if (fi.directory != null && !fi.directory.isEmpty()) {
                        dir = fi.directory.endsWith(File.separator)
                            ? fi.directory : fi.directory + File.separator;
                    }
                } else {
                    String title = state.originalImage.getTitle();
                    if (title != null && !title.isEmpty()) {
                        stem = title.replaceAll("\\.[^.]+$", "");
                    }
                }
            }
            return dir + stem + "-digitized.csv";
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
            Color c = SPOT_COLORS[(s.id - 1) % SPOT_COLORS.length];

            Roi shapeRoi;
            if (s.hasMask() && s.ellipseMajor > 0) {
                // Shape-aware spot: draw the fitted ellipse (true orientation/elongation)
                // rather than a plain circle, so the overlay honestly reflects what
                // SpotIntegrator actually integrates (the mask, not a fixed radius).
                double half = s.ellipseMajor / 2.0;
                double rad = Math.toRadians(s.ellipseAngleDeg);
                double x1 = s.centroidX - half * Math.cos(rad);
                double y1 = s.centroidY - half * Math.sin(rad);
                double x2 = s.centroidX + half * Math.cos(rad);
                double y2 = s.centroidY + half * Math.sin(rad);
                double aspect = s.ellipseMajor > 0 ? s.ellipseMinor / s.ellipseMajor : 1.0;
                shapeRoi = new EllipseRoi(x1, y1, x2, y2, aspect);
            } else {
                shapeRoi = new OvalRoi(
                    s.centroidX - s.radius, s.centroidY - s.radius,
                    s.radius * 2, s.radius * 2);
            }
            shapeRoi.setStrokeColor(c);
            shapeRoi.setStrokeWidth(Math.max(1.5, s.radius * 0.06));
            shapeRoi.setName("spot_" + s.id);
            ov.add(shapeRoi);

            // Font size scales with the spot so labels stay legible at any zoom / resolution.
            // Clamp: minimum readable at small zoom, maximum that fits inside large spots.
            int fontSize = Math.max(14, Math.min(60, (int) s.radius));
            Font labelFont = new Font("SansSerif", Font.BOLD, fontSize);
            String idStr = String.valueOf(s.id);
            // Approximate text-centre offset: ~0.55 × fontSize per char wide, ~0.7 × fontSize tall
            double tx = s.centroidX - idStr.length() * fontSize * 0.30;
            double ty = s.centroidY - fontSize * 0.38;
            TextRoi label = new TextRoi(tx, ty, idStr, labelFont);
            label.setStrokeColor(c);
            label.setName("label_" + s.id);
            ov.add(label);
        }
        imp.killRoi();         // clear any ROI ImageJ selected from the overlay on click
        imp.setOverlay(ov);
        IJ.showStatus(spots.size() + " spots");
        imp.updateAndDraw();
    }
}
