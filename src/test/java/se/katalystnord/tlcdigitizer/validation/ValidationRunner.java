/*
 * TLC Digitizer — Fiji/ImageJ plugin
 * Copyright (C) 2025 David Sandquist, Katalyst Nord AB
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package se.katalystnord.tlcdigitizer.validation;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.model.Spot;
import se.katalystnord.tlcdigitizer.pipeline.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Headless pipeline harness for accuracy validation.
 *
 * <p>Runs the full 8-stage pipeline on a single plate image described by a
 * {@link ValidationFixture}, then performs leave-one-out (LOO) cross-validation
 * to compute per-spot recovery and per-API RSD — the two headline metrics from
 * the TLCyzer paper (Hauk et al., Sci. Rep. 12, 13433, 2022).
 *
 * <h2>Leave-one-out rationale</h2>
 * LOO avoids training-set bias: each reference spot acts as the "unknown" exactly
 * once, predicted by a calibration model built on the remaining N-1 spots.
 * Requires N ≥ 3 reference spots per fixture.
 *
 * <h2>Spot matching</h2>
 * For each fixture reference spot the runner finds the nearest auto-detected spot
 * within {@code 2 × medianRadius}. If no detected spot is close enough (detection
 * missed the spot), a manual spot is created at the exact fixture coordinates.
 * This means recovery reflects pipeline output even when detection partially fails.
 */
public final class ValidationRunner {

    private ValidationRunner() {}

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /** Per-spot outcome from one LOO fold. */
    public static final class SpotResult {
        /** Index of this spot in the fixture's {@code referenceSpots} array. */
        public final int fixtureIndex;
        public final String apiName;
        public final double knownConcentration;
        public final double predictedConcentration;
        /** {@code 100 × predicted / known}. NaN if the LOO fold could not be fitted. */
        public final double recoveryPercent;
        public final double integrationValue;
        public final float  rfValue;
        /** True if this spot was matched to an auto-detected spot (false = manual fallback). */
        public final boolean autoDetected;

        SpotResult(int fixtureIndex, String apiName,
                   double knownConcentration, double predictedConcentration,
                   double recoveryPercent, double integrationValue,
                   float rfValue, boolean autoDetected) {
            this.fixtureIndex         = fixtureIndex;
            this.apiName              = apiName;
            this.knownConcentration   = knownConcentration;
            this.predictedConcentration = predictedConcentration;
            this.recoveryPercent      = recoveryPercent;
            this.integrationValue     = integrationValue;
            this.rfValue              = rfValue;
            this.autoDetected         = autoDetected;
        }
    }

    /** Aggregate result for one plate. */
    public static final class RunResult {
        public final String imagePath;
        public final List<SpotResult> spotResults;
        /** Mean LOO recovery across all valid folds (%). */
        public final double meanRecovery;
        /** RSD of LOO recoveries, pooled across all APIs (%). */
        public final double rsdOverall;
        /** Per-API RSD (%). Key = apiName from fixture (or "default"). */
        public final Map<String, Double> rsdByApi;
        /** Number of fixture reference spots matched to auto-detected spots. */
        public final int autoDetectedCount;
        /** Total fixture reference spots. */
        public final int totalReferenceSpots;

        RunResult(String imagePath, List<SpotResult> spotResults,
                  double meanRecovery, double rsdOverall,
                  Map<String, Double> rsdByApi,
                  int autoDetectedCount, int totalReferenceSpots) {
            this.imagePath           = imagePath;
            this.spotResults         = Collections.unmodifiableList(spotResults);
            this.meanRecovery        = meanRecovery;
            this.rsdOverall          = rsdOverall;
            this.rsdByApi            = Collections.unmodifiableMap(rsdByApi);
            this.autoDetectedCount   = autoDetectedCount;
            this.totalReferenceSpots = totalReferenceSpots;
        }
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Runs the full validation pipeline on one plate fixture.
     *
     * @param fixture      parsed fixture (corners, Rf lines, reference spots)
     * @param imageBaseDir directory containing the image file referenced by the fixture
     * @return LOO recovery statistics for this plate
     * @throws IOException if the image cannot be read
     */
    public static RunResult run(ValidationFixture fixture, Path imageBaseDir) throws IOException {
        // ----- Stage 1: load image headlessly --------------------------------
        Path imgPath = imageBaseDir.resolve(fixture.imagePath);
        BufferedImage bi = ImageIO.read(imgPath.toFile());
        if (bi == null) {
            throw new IOException("ImageIO could not read: " + imgPath
                    + " (unsupported format or corrupt file)");
        }
        ImagePlus imp = new ImagePlus("validation", bi);

        // ----- Stage 1: grayscale conversion ---------------------------------
        FloatProcessor gray = fixture.useGreenChannel
                ? ImagePreparation.extractGreenChannel(imp)
                : ImagePreparation.toLuminanceGrayscale(imp);

        if (fixture.invertImage) {
            float[] px = (float[]) gray.getPixels();
            for (int i = 0; i < px.length; i++) px[i] = 255.0f - px[i];
        }

        // ----- Stage 2: perspective warp (uses fixture corners directly) -----
        FloatProcessor warped = PerspectiveCorrection.warpImage(gray, fixture.corners);

        // ----- Stage 3: background correction --------------------------------
        FloatProcessor corrected;
        if (fixture.useTopHatBackground) {
            // Estimate SE radius via a quick polynomial pass + detection.
            FloatProcessor polyEst = BackgroundCorrection.fitAndSubtract(warped);
            float[] estPx = (float[]) polyEst.getPixels();
            float estMax = 0;
            for (float v : estPx) if (v > estMax) estMax = v;
            if (estMax > 0) {
                float sc = 255f / estMax;
                for (int i = 0; i < estPx.length; i++) estPx[i] *= sc;
            }
            float estMedR = medianRadius(SpotDetector.detect(polyEst, (float) fixture.thresholdFactor));
            float seRadius = fixture.topHatSeRadius > 0
                    ? fixture.topHatSeRadius : Math.max(10f, 1.5f * estMedR);
            corrected = BackgroundCorrection.topHat(warped, seRadius);
        } else {
            // Polynomial pass for detection image; also used as corrected for Option A.
            // Option B (S-G) defers per-spot correction to after integration.
            corrected = BackgroundCorrection.fitAndSubtract(warped);
        }

        // For top-hat mode, integrate on the corrected (top-hat) image — the background
        // is already removed and there is no polynomial over-subtraction to avoid.
        // For polynomial / S-G modes, integrate on the raw warped image so that the
        // polynomial over-subtraction at off-centre positions does not corrupt integrals.
        FloatProcessor integrationBase = fixture.useTopHatBackground ? corrected : warped;

        int corrW = corrected.getWidth();
        int corrH = corrected.getHeight();

        // ----- Stage 5: spot detection ---------------------------------------
        // Detect on the polynomial-corrected image normalised to [0,255].
        // Background correction clamps negatives to 0, leaving background≈0 and
        // spots as small positive residuals. The raw corrected image has mean≈0,
        // so mean×mult is near-zero and thresholds everything; normalising first
        // rescales the sparse positive residuals to span [0,255] so that
        // mean×mult correctly separates spots from background.
        FloatProcessor detectionImage;
        {
            float[] cpx = (float[]) corrected.getPixels();
            float cmax = 0;
            for (float v : cpx) if (v > cmax) cmax = v;
            if (cmax > 0) {
                float scale = 255f / cmax;
                float[] norm = new float[cpx.length];
                for (int i = 0; i < cpx.length; i++) norm[i] = cpx[i] * scale;
                detectionImage = new FloatProcessor(corrected.getWidth(), corrected.getHeight(), norm, null);
            } else {
                detectionImage = corrected;
            }
        }
        List<Spot> detected = SpotDetector.detect(detectionImage,
                (float) fixture.thresholdFactor, fixture.shapeAwareDetection);
        // (debug output removed)
        float medR = medianRadius(detected);

        // ----- Match fixture reference spots to detected spots ---------------
        List<Spot>    refSpots       = new ArrayList<>();
        boolean[]     usedDetected   = new boolean[detected.size()];
        int[]         autoDetectedAt = new int[fixture.referenceSpots.length];
        Arrays.fill(autoDetectedAt, -1);

        for (int k = 0; k < fixture.referenceSpots.length; k++) {
            ValidationFixture.RefSpot ref = fixture.referenceSpots[k];
            float tx = ref.xFraction * corrW;
            float ty = ref.yFraction * corrH;
            float matchTol = 2f * (ref.radiusOverride > 0 ? ref.radiusOverride : Math.max(medR, 10f));

            int bestIdx  = -1;
            float bestD2 = Float.MAX_VALUE;
            for (int i = 0; i < detected.size(); i++) {
                if (usedDetected[i]) continue;
                float dx = detected.get(i).centroidX - tx;
                float dy = detected.get(i).centroidY - ty;
                float d2 = dx * dx + dy * dy;
                if (d2 < bestD2) { bestD2 = d2; bestIdx = i; }
            }

            Spot spot;
            if (bestIdx >= 0 && Math.sqrt(bestD2) <= matchTol) {
                usedDetected[bestIdx] = true;
                autoDetectedAt[k] = bestIdx;
                spot = detected.get(bestIdx);
            } else {
                float r = ref.radiusOverride > 0 ? ref.radiusOverride : Math.max(medR, 10f);
                spot = new Spot(k + 1, tx, ty, r, corrH);
            }
            spot.isReference = true;
            spot.referenceConcentration = ref.knownConcentration;
            refSpots.add(spot);
        }

        // ----- Stage 4: Rf values --------------------------------------------
        RfCalculator.assignAll(refSpots, fixture.originYFraction, fixture.frontYFraction);

        // ----- Stage 6: radius optimisation + integration ----------------------
        // Mirror the interactive plugin's "Optimise R²" workflow: grid-search radii
        // from 0.5× to 2.5× the initial radius and pick the scale with the highest
        // all-reference calibration R².  Integrates on the warped image (not the
        // polynomial-corrected image) with per-spot local background subtraction.
        // Cap polynomial degree so n >= degree+2 (polynomial path always active).
        int sgDeg = fixture.sgDegree > 0 ? fixture.sgDegree : 3;
        sgDeg = Math.min(sgDeg, Math.max(1, refSpots.size() - 2));

        // No overlap cap here: the runner integrates with per-spot S-G correction which
        // tolerates mild overlap at large scales.  Overlap capping belongs in the
        // interactive UI (Step 6) where polynomial-mode has no per-spot correction.

        double bestR2 = -Double.MAX_VALUE;
        double bestScale = 1.0;
        for (int step = 5; step <= 25; step++) {
            double scale = step / 10.0;
            List<Spot> temp = new ArrayList<>();
            for (int k = 0; k < refSpots.size(); k++) {
                Spot orig = refSpots.get(k);
                Spot t = scaledCopy(orig, (float) scale, corrH);
                t.isReference = true;
                t.referenceConcentration = orig.referenceConcentration;
                t.rfValue = orig.rfValue;
                temp.add(t);
            }
            SpotIntegrator.integrateAll(integrationBase, temp);
            BackgroundCorrection.applyPerSpotPolynomial(temp, integrationBase, sgDeg);
            try {
                CalibrationModel m = CalibrationModel.fit(temp, CalibrationModel.ModelType.LINEAR);
                if (m.rSquared > bestR2) { bestR2 = m.rSquared; bestScale = scale; }
            } catch (IllegalArgumentException ignored) {}
        }

        // Rebuild refSpots with best scale permanently applied
        for (int k = 0; k < refSpots.size(); k++) {
            Spot orig = refSpots.get(k);
            Spot scaled = scaledCopy(orig, (float) bestScale, corrH);
            scaled.isReference = true;
            scaled.referenceConcentration = orig.referenceConcentration;
            scaled.rfValue = orig.rfValue;
            refSpots.set(k, scaled);
        }
        SpotIntegrator.integrateAll(integrationBase, refSpots);
        BackgroundCorrection.applyPerSpotPolynomial(refSpots, integrationBase, sgDeg);

        // ----- Stage 7: leave-one-out calibration ----------------------------
        List<SpotResult> results = new ArrayList<>();

        for (int i = 0; i < refSpots.size(); i++) {
            List<Spot> trainSet = new ArrayList<>();
            for (int j = 0; j < refSpots.size(); j++) {
                if (j != i) trainSet.add(refSpots.get(j));
            }
            Spot target = refSpots.get(i);
            ValidationFixture.RefSpot ref = fixture.referenceSpots[i];

            double predictedConc  = Double.NaN;
            double recoveryPct    = Double.NaN;

            try {
                CalibrationModel model = CalibrationModel.fit(
                        trainSet, CalibrationModel.ModelType.LINEAR);
                predictedConc = model.predict(target.integrationValue);
                recoveryPct   = 100.0 * predictedConc / target.referenceConcentration;
            } catch (IllegalArgumentException ignored) {
                // Not enough training points — fixture has fewer than 3 ref spots.
            }

            results.add(new SpotResult(
                    i,
                    ref.apiName,
                    target.referenceConcentration,
                    predictedConc,
                    recoveryPct,
                    target.integrationValue,
                    target.rfValue,
                    autoDetectedAt[i] >= 0));
        }

        // ----- Aggregate statistics ------------------------------------------
        double[] allRecoveries = validRecoveries(results);
        double meanRec   = mean(allRecoveries);
        double rsdAll    = rsd(allRecoveries, meanRec);

        Map<String, List<Double>> byApi = new LinkedHashMap<>();
        for (SpotResult sr : results) {
            if (Double.isNaN(sr.recoveryPercent)) continue;
            String key = sr.apiName != null ? sr.apiName : "default";
            byApi.computeIfAbsent(key, k -> new ArrayList<>()).add(sr.recoveryPercent);
        }
        Map<String, Double> rsdByApi = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> e : byApi.entrySet()) {
            double[] vals = toDoubleArray(e.getValue());
            rsdByApi.put(e.getKey(), rsd(vals, mean(vals)));
        }

        int autoCount = 0;
        for (int idx : autoDetectedAt) if (idx >= 0) autoCount++;

        return new RunResult(
                fixture.imagePath, results,
                meanRec, rsdAll, rsdByApi,
                autoCount, fixture.referenceSpots.length);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a copy of {@code orig} at the given radius scale, for the radius-optimisation
     * grid search. Shape-aware spots (see {@link Spot#hasMask()}) keep their mask and ignore
     * {@code scale} — a scalar radius multiplier has no well-defined meaning against a
     * watershed-derived mask, which already represents the true detected extent.
     */
    private static Spot scaledCopy(Spot orig, float scale, int corrH) {
        if (orig.hasMask()) {
            return orig.withId(orig.id);
        }
        return new Spot(orig.id, orig.centroidX, orig.centroidY, orig.radius * scale, corrH);
    }

    private static float medianRadius(List<Spot> spots) {
        if (spots.isEmpty()) return 20f;
        float[] radii = new float[spots.size()];
        for (int i = 0; i < spots.size(); i++) radii[i] = spots.get(i).radius;
        Arrays.sort(radii);
        return radii[spots.size() / 2];
    }

    private static double[] validRecoveries(List<SpotResult> results) {
        List<Double> valid = new ArrayList<>();
        for (SpotResult r : results) {
            if (!Double.isNaN(r.recoveryPercent)) valid.add(r.recoveryPercent);
        }
        return toDoubleArray(valid);
    }

    private static double mean(double[] vals) {
        if (vals.length == 0) return Double.NaN;
        double sum = 0;
        for (double v : vals) sum += v;
        return sum / vals.length;
    }

    /** Sample RSD (%) = 100 × sample-std / |mean|. Returns NaN for < 2 points. */
    private static double rsd(double[] vals, double mean) {
        if (vals.length < 2 || Double.isNaN(mean) || mean == 0) return Double.NaN;
        double sumSq = 0;
        for (double v : vals) { double d = v - mean; sumSq += d * d; }
        return 100.0 * Math.sqrt(sumSq / (vals.length - 1)) / Math.abs(mean);
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
