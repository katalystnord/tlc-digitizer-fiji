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
     * Returns the full analysis results as a UTF-8 CSV string.
     * Used both for writing to disk and for embedding in TIFF metadata.
     */
    public static String toCsvString(AnalysisState state) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        writeMetadata(pw, state);
        writeCalibration(pw, state.calibrationModel);
        writeSpots(pw, state);
        pw.flush();
        return sw.toString();
    }

    /**
     * Writes analysis results to {@code outputFile} in UTF-8 CSV format.
     */
    public static void export(AnalysisState state, File outputFile) throws IOException {
        try (OutputStreamWriter ow = new OutputStreamWriter(
                new FileOutputStream(outputFile), "UTF-8")) {
            ow.write(toCsvString(state));
        }
    }

    private static void writeMetadata(PrintWriter pw, AnalysisState state) {
        pw.println("# TLC Digitizer analysis export");
        pw.println("# Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        if (state.originalImage != null) {
            pw.println("# Source image: " + state.originalImage.getTitle());
        }
        pw.println("# Image inverted: " + state.invertImage);
        pw.println("# Background method: " + (state.usedPolynomialBackground ? "polynomial (Option A)" : "Savitzky-Golay (Option B, degree " + state.sgDegree + ")"));
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
            pw.println("# Calibration model: " + cal.modelType.name());
            pw.println("# Calibration summary: " + cal.toSummary());
            pw.println("# R_squared: " + cal.rSquared);
            pw.println("# RMSE_concentration: " + cal.rmse);
            pw.println("# n_calibration_points: " + cal.nPoints);
            switch (cal.modelType) {
                case LINEAR:
                    pw.println("# slope: " + cal.slope);
                    pw.println("# intercept: " + cal.intercept);
                    pw.println("# LOD: " + cal.lod);
                    pw.println("# LOQ: " + cal.loq);
                    pw.println("# LOD_LOQ_method: ICH_Q2R1_regression (3.3sigma/slope, 10sigma/slope)");
                    break;
                case LOG_LOG:
                    pw.println("# exponent: " + cal.coefficients[1]);
                    pw.println("# prefactor: " + Math.exp(cal.coefficients[0]));
                    pw.println("# LOD: NA (log-log model — compute from residuals manually)");
                    pw.println("# LOQ: NA (log-log model — compute from residuals manually)");
                    break;
                case QUADRATIC:
                    pw.println("# a2: " + cal.coefficients[2]);
                    pw.println("# a1: " + cal.coefficients[1]);
                    pw.println("# a0: " + cal.coefficients[0]);
                    pw.println("# LOD: NA (quadratic model — compute from residuals manually)");
                    pw.println("# LOQ: NA (quadratic model — compute from residuals manually)");
                    break;
            }
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
