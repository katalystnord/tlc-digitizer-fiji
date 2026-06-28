package se.katalystnord.tlcdigitizer.export;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.OvalRoi;
import ij.gui.TextRoi;
import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.awt.*;
import java.io.File;

/**
 * Saves an annotated PNG of the processed plate image with spot circles and ID
 * labels burned in.  Intended to be saved alongside the CSV so the user can
 * correlate spot IDs to physical plate positions after the session ends.
 */
public final class AnnotatedImageExporter {

    private AnnotatedImageExporter() {}

    /**
     * Derives the PNG output path from a CSV path by replacing the {@code .csv}
     * extension with {@code .png}.  If the CSV path has no {@code .csv} suffix,
     * {@code .png} is appended.
     */
    public static File pngFileFor(File csvFile) {
        String path = csvFile.getAbsolutePath();
        if (path.toLowerCase().endsWith(".csv")) {
            path = path.substring(0, path.length() - 4);
        }
        return new File(path + ".png");
    }

    /**
     * Converts the background-corrected plate image to an annotated PNG:
     * <ul>
     *   <li>8-bit → RGB conversion with auto-contrast</li>
     *   <li>Yellow circle per spot (same geometry as the Step 5 overlay)</li>
     *   <li>Yellow spot ID label centred inside each circle</li>
     * </ul>
     * The overlay is flattened (burned into pixels) before saving so the file
     * is self-contained and readable in any viewer.
     */
    public static void export(AnalysisState state, File outputFile) {
        FloatProcessor fp = (FloatProcessor) state.corrected.duplicate();
        fp.resetMinAndMax();
        ImagePlus imp = new ImagePlus("annotated",
                fp.convertToByteProcessor().convertToRGB());

        Overlay ov = new Overlay();
        for (Spot s : state.spots) {
            OvalRoi oval = new OvalRoi(
                    s.centroidX - s.radius, s.centroidY - s.radius,
                    s.radius * 2, s.radius * 2);
            oval.setStrokeColor(Color.YELLOW);
            oval.setStrokeWidth(Math.max(1.5, s.radius * 0.06));
            ov.add(oval);

            int fontSize = Math.max(14, Math.min(60, (int) s.radius));
            String idStr = String.valueOf(s.id);
            double tx = s.centroidX - idStr.length() * fontSize * 0.30;
            double ty = s.centroidY - fontSize * 0.38;
            TextRoi label = new TextRoi(tx, ty, idStr,
                    new Font("SansSerif", Font.BOLD, fontSize));
            label.setStrokeColor(Color.YELLOW);
            ov.add(label);
        }
        imp.setOverlay(ov);

        ImagePlus flat = imp.flatten();
        IJ.saveAs(flat, "PNG", outputFile.getAbsolutePath());
    }
}
