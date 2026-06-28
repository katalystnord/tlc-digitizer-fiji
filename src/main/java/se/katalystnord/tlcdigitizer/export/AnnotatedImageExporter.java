package se.katalystnord.tlcdigitizer.export;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.OvalRoi;
import ij.gui.TextRoi;
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.awt.*;
import java.io.File;

/**
 * Saves the processed plate image with spot circles and ID labels burned in.
 *
 * <p>Two output formats:
 * <ul>
 *   <li>{@link #exportPng} — flattened RGB PNG, for easy viewing alongside the CSV.</li>
 *   <li>{@link #exportTiff} — same image saved as TIFF with the full CSV analysis
 *       results embedded in the ImageDescription tag (TIFF tag 270).  A single
 *       {@code .tif} file thus contains both the visual record and all numerical
 *       results, suitable for long-term archiving.</li>
 * </ul>
 */
public final class AnnotatedImageExporter {

    private AnnotatedImageExporter() {}

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /** Derives the PNG output path from a CSV path (replaces {@code .csv} with {@code .png}). */
    public static File pngFileFor(File csvFile) {
        return siblingWithExtension(csvFile, ".png");
    }

    /** Derives the TIFF output path from a CSV path (replaces {@code .csv} with {@code .tif}). */
    public static File tiffFileFor(File csvFile) {
        return siblingWithExtension(csvFile, ".tif");
    }

    private static File siblingWithExtension(File base, String ext) {
        String path = base.getAbsolutePath();
        if (path.toLowerCase().endsWith(".csv")) {
            path = path.substring(0, path.length() - 4);
        }
        return new File(path + ext);
    }

    // -------------------------------------------------------------------------
    // Export methods
    // -------------------------------------------------------------------------

    /** Saves the annotated plate image as a PNG. */
    public static void exportPng(AnalysisState state, File outputFile) {
        ImagePlus flat = buildAnnotatedImage(state).flatten();
        IJ.saveAs(flat, "PNG", outputFile.getAbsolutePath());
    }

    /**
     * Saves the annotated plate image as a TIFF with the full CSV analysis results
     * embedded in the ImageDescription tag (TIFF tag 270).
     *
     * <p>The embedded text can be retrieved in Fiji via Image › Show Info…,
     * with {@code exiftool -ImageDescription file.tif}, or programmatically
     * via any TIFF library (e.g. Python {@code tifffile}).
     *
     * @param csvContent the CSV string as returned by {@link CsvExporter#toCsvString}
     */
    public static void exportTiff(AnalysisState state, String csvContent, File outputFile) {
        ImagePlus flat = buildAnnotatedImage(state).flatten();
        flat.setProperty("Info", csvContent);
        new FileSaver(flat).saveAsTiff(outputFile.getAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Shared image construction
    // -------------------------------------------------------------------------

    private static ImagePlus buildAnnotatedImage(AnalysisState state) {
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
        return imp;
    }
}
