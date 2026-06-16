package se.katalystnord.tlcdigitizer.export;

import se.katalystnord.tlcdigitizer.model.AnalysisState;
import se.katalystnord.tlcdigitizer.model.Spot;
import se.katalystnord.tlcdigitizer.pipeline.CalibrationModel;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Stage 8: CSV export.
 *
 * Output columns (per CLAUDE.md specification):
 *   spot_id, lane, rf_value, centroid_x_fraction, centroid_y_fraction,
 *   radius_fraction, integration_value, assigned_concentration,
 *   is_reference, reference_concentration
 *
 * The header section also records analysis parameters so results are
 * fully reproducible from the saved file.
 */
public final class CsvExporter {

    private CsvExporter() {}

    /**
     * Writes analysis results to {@code outputFile} in UTF-8 CSV format.
     */
    public static void export(AnalysisState state, File outputFile) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile), "UTF-8"))) {

            writeMetadata(pw, state);
            writeCalibration(pw, state.calibrationModel);
            writeSpots(pw, state);
        }
    }

    private static void writeMetadata(PrintWriter pw, AnalysisState state) {
        pw.println("# TLC Digitizer analysis export");
        pw.println("# Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        if (state.originalImage != null) {
            pw.println("# Source image: " + state.originalImage.getTitle());
        }
        pw.println("# Background method: " + (state.usedPolynomialBackground ? "polynomial (Option A)" : "Savitzky-Golay (Option B)"));
        pw.println("# Threshold factor: " + state.thresholdFactor);
        pw.println("# Origin Y fraction: " + state.originYFraction);
        pw.println("# Front Y fraction: " + state.frontYFraction);
        if (state.corrected != null) {
            pw.println("# Corrected image size: " + state.corrected.getWidth() + "x" + state.corrected.getHeight());
        }
        pw.println("#");
    }

    private static void writeCalibration(PrintWriter pw, CalibrationModel cal) {
        if (cal == null) {
            pw.println("# Calibration: not performed");
        } else {
            pw.println("# Calibration: " + cal.toSummary());
            pw.println("# slope: " + cal.slope);
            pw.println("# intercept: " + cal.intercept);
            pw.println("# R_squared: " + cal.rSquared);
            pw.println("# LOD: " + cal.lod);
            pw.println("# LOQ: " + cal.loq);
            pw.println("# n_calibration_points: " + cal.nPoints);
        }
        pw.println("#");
    }

    private static void writeSpots(PrintWriter pw, AnalysisState state) {
        List<Spot> spots = state.spots;
        int imageWidth = (state.corrected != null) ? state.corrected.getWidth() : 1;
        int imageHeight = (state.corrected != null) ? state.corrected.getHeight() : 1;

        pw.println("spot_id,lane,rf_value,centroid_x_fraction,centroid_y_fraction," +
                   "radius_fraction,integration_value,assigned_concentration," +
                   "is_reference,reference_concentration");

        for (Spot s : spots) {
            pw.printf("%d,%d,%s,%.6f,%.6f,%.6f,%s,%s,%b,%s%n",
                s.id,
                s.lane,
                formatDouble(s.rfValue),
                s.centroidX / imageWidth,
                s.centroidY / imageHeight,
                s.radius / Math.max(imageWidth, imageHeight),
                formatDouble(s.integrationValue),
                formatDouble(s.assignedConcentration),
                s.isReference,
                formatDouble(s.referenceConcentration));
        }
    }

    private static String formatDouble(double v) {
        return Double.isNaN(v) ? "NA" : String.format("%.6g", v);
    }

    private static String formatDouble(float v) {
        return Float.isNaN(v) ? "NA" : String.format("%.6f", v);
    }
}
