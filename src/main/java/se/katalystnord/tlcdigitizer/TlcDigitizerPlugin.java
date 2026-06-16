package se.katalystnord.tlcdigitizer;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.ui.WizardController;

/**
 * TLC Digitizer — Fiji plugin entry point.
 *
 * Registered as:  Plugins > TLC > TLC Digitizer
 *
 * Opens the active image (or prompts to open one) and runs the 7-stage
 * quantitative TLC analysis wizard:
 *
 *   1. Grayscale conversion (luminance or green channel)
 *   2. Perspective correction (auto Hough + interactive corner adjustment)
 *   3. Background correction (2D quartic polynomial)
 *   4. Origin and solvent front marking
 *   5. Spot detection + Rf calculation + integration
 *   6. Calibration model (OLS linear, ≥3 reference standards)
 *   7. CSV export
 *
 * Algorithm validated against the TLCyzer paper:
 *   Hauk et al. Scientific Reports 12, 13433 (2022).
 */
public class TlcDigitizerPlugin implements PlugIn {

    @Override
    public void run(String arg) {
        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.error("TLC Digitizer", "Please open a TLC plate image first.");
            return;
        }

        AnalysisState state = new AnalysisState();
        state.originalImage = imp;

        WizardController wizard = new WizardController(state);
        boolean completed = wizard.run();

        if (completed) {
            IJ.log("TLC Digitizer: analysis complete.");
        } else {
            IJ.log("TLC Digitizer: analysis cancelled.");
        }
    }
}
