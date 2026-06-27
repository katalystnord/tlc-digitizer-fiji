package se.katalystnord.tlcdigitizer.ui;

import se.katalystnord.tlcdigitizer.model.AnalysisState;

/**
 * Entry point for the TLC Digitizer wizard session.
 *
 * Creates the persistent {@link TlcDigitizerFrame} and delegates the
 * wizard loop to it.  All UI logic and pipeline orchestration live in
 * TlcDigitizerFrame; this class exists solely to satisfy the call site
 * in {@link se.katalystnord.tlcdigitizer.TlcDigitizerPlugin}.
 */
public class WizardController {

    private final AnalysisState state;

    public WizardController(AnalysisState state) {
        this.state = state;
    }

    /**
     * Opens the wizard window and blocks until the user completes or cancels.
     *
     * @return true if the analysis completed successfully, false if cancelled.
     */
    public boolean run() {
        TlcDigitizerFrame frame = new TlcDigitizerFrame(state);
        frame.setVisible(true);
        return frame.runWizard();
    }
}
