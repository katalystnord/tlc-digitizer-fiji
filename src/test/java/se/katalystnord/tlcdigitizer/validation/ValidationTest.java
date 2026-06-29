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

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertTrue;

/**
 * Accuracy validation against the TLCyzer reference dataset.
 *
 * <h2>Running</h2>
 * <pre>
 *   mvn test -Dvalidation.data.dir=/path/to/tlcyzer-validation
 * </pre>
 *
 * The directory must contain:
 * <ul>
 *   <li>One {@code .json} fixture file per plate (see {@link ValidationFixture}).</li>
 *   <li>The plate image files referenced by each fixture.</li>
 * </ul>
 *
 * <p>If {@code validation.data.dir} is not set, or the directory contains no
 * {@code .json} files, the test is <em>skipped</em> (not failed).
 * This keeps CI green until the reference data is available.
 *
 * <h2>Pass criteria (TLCyzer paper benchmarks)</h2>
 * <ul>
 *   <li>Mean LOO recovery: 96.8–103.9 %</li>
 *   <li>Per-API repeatability RSD: ≤ 3.84 %</li>
 * </ul>
 * Source: Hauk et al., Sci. Rep. 12, 13433 (2022), Table 2.
 */
public class ValidationTest {

    // -------------------------------------------------------------------------
    // TLCyzer benchmark targets (Table 2, Hauk et al. 2022)
    // -------------------------------------------------------------------------

    private static final double RECOVERY_MIN    = 96.8;
    private static final double RECOVERY_MAX    = 103.9;
    private static final double MAX_API_RSD_PCT = 3.84;

    // -------------------------------------------------------------------------

    @BeforeClass
    public static void configureHeadless() {
        // Prevent Fiji / AWT from attempting to open a display window during tests.
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void tlcyzerBenchmarks() throws Exception {
        String dataDirProp = System.getProperty("validation.data.dir");
        Assume.assumeTrue(
                "Skipping validation: -Dvalidation.data.dir not set",
                dataDirProp != null && !dataDirProp.isEmpty());

        Path dataDir = Paths.get(dataDirProp);
        Assume.assumeTrue(
                "Skipping validation: directory not found: " + dataDir,
                Files.isDirectory(dataDir));

        List<Path> fixtures = Files.list(dataDir)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .collect(Collectors.toList());

        Assume.assumeFalse(
                "Skipping validation: no .json fixture files found in " + dataDir,
                fixtures.isEmpty());

        // ----- Run all fixtures ----------------------------------------------
        List<Double>              allRecoveries  = new ArrayList<>();
        Map<String, List<Double>> byApi          = new LinkedHashMap<>();
        List<String>              runSummaries   = new ArrayList<>();
        List<String>              failures        = new ArrayList<>();

        for (Path fixturePath : fixtures) {
            ValidationFixture fixture;
            try {
                fixture = ValidationFixture.fromJson(fixturePath);
            } catch (Exception e) {
                failures.add("PARSE ERROR " + fixturePath.getFileName() + ": " + e.getMessage());
                continue;
            }

            ValidationRunner.RunResult result;
            try {
                result = ValidationRunner.run(fixture, dataDir);
            } catch (Exception e) {
                failures.add("RUN ERROR  " + fixture.imagePath + ": " + e.getMessage());
                continue;
            }

            for (ValidationRunner.SpotResult sr : result.spotResults) {
                if (!Double.isNaN(sr.recoveryPercent)) {
                    allRecoveries.add(sr.recoveryPercent);
                    String key = sr.apiName != null ? sr.apiName : "default";
                    byApi.computeIfAbsent(key, k -> new ArrayList<>()).add(sr.recoveryPercent);
                }
            }

            runSummaries.add(formatRunSummary(result));
        }

        // ----- Print results table -------------------------------------------
        System.out.println();
        System.out.println("=== TLC Digitizer Validation Results ===");
        for (String s : runSummaries) System.out.println(s);
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("--- Errors ---");
            for (String f : failures) System.out.println(f);
        }

        // ----- Per-API RSD table ---------------------------------------------
        System.out.println();
        System.out.printf("%-20s  %5s  %6s  %6s  %6s%n",
                "API", "n", "mean%", "min%", "RSD%");
        System.out.println("----------------------------------------------------");
        for (Map.Entry<String, List<Double>> e : byApi.entrySet()) {
            double[] vals = toDoubleArray(e.getValue());
            double mean   = mean(vals);
            double minR   = minOf(vals);
            double rsd    = rsd(vals, mean);
            System.out.printf("%-20s  %5d  %6.2f  %6.2f  %6.2f%n",
                    e.getKey(), vals.length, mean, minR, rsd);
        }

        // ----- Overall summary -----------------------------------------------
        Assume.assumeFalse(
                "No valid LOO recoveries computed — check fixture spot counts (need ≥ 3 per plate)",
                allRecoveries.isEmpty());

        double[] all = toDoubleArray(allRecoveries);
        double meanAll = mean(all);
        double rsdAll  = rsd(all, meanAll);
        System.out.println();
        System.out.printf("OVERALL  n=%d  mean recovery=%.2f%%  RSD=%.2f%%%n",
                all.length, meanAll, rsdAll);
        System.out.println();

        // ----- Assertions ----------------------------------------------------
        assertTrue(
                String.format("Mean recovery %.2f%% outside benchmark range [%.1f, %.1f]%%",
                        meanAll, RECOVERY_MIN, RECOVERY_MAX),
                meanAll >= RECOVERY_MIN && meanAll <= RECOVERY_MAX);

        for (Map.Entry<String, List<Double>> e : byApi.entrySet()) {
            if (e.getValue().size() < 2) continue; // RSD undefined for n=1
            double[] vals  = toDoubleArray(e.getValue());
            double   apiRsd = rsd(vals, mean(vals));
            assertTrue(
                    String.format("API '%s' RSD %.2f%% exceeds benchmark ≤ %.2f%%",
                            e.getKey(), apiRsd, MAX_API_RSD_PCT),
                    apiRsd <= MAX_API_RSD_PCT);
        }
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private static String formatRunSummary(ValidationRunner.RunResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n  Image:      %s%n", r.imagePath));
        sb.append(String.format("  Spots:      %d / %d auto-detected%n",
                r.autoDetectedCount, r.totalReferenceSpots));
        sb.append(String.format("  Mean rec.:  %.2f%%%n", r.meanRecovery));
        sb.append(String.format("  RSD (all):  %.2f%%%n", r.rsdOverall));
        if (!r.rsdByApi.isEmpty()) {
            for (Map.Entry<String, Double> e : r.rsdByApi.entrySet()) {
                sb.append(String.format("    %-18s RSD = %.2f%%%n", e.getKey(), e.getValue()));
            }
        }
        sb.append(String.format("  Per-spot LOO recoveries:%n"));
        for (ValidationRunner.SpotResult sr : r.spotResults) {
            if (Double.isNaN(sr.recoveryPercent)) {
                sb.append(String.format("    [%2d] %-15s known=%-8.2f  SKIPPED (too few train pts)%n",
                        sr.fixtureIndex, label(sr.apiName), sr.knownConcentration));
            } else {
                sb.append(String.format("    [%2d] %-15s known=%-8.2f pred=%-8.2f rec=%6.2f%%  %s%n",
                        sr.fixtureIndex, label(sr.apiName),
                        sr.knownConcentration, sr.predictedConcentration,
                        sr.recoveryPercent,
                        sr.autoDetected ? "" : "(manual)"));
            }
        }
        return sb.toString();
    }

    private static String label(String apiName) {
        return apiName != null ? apiName : "—";
    }

    // -------------------------------------------------------------------------
    // Statistics helpers
    // -------------------------------------------------------------------------

    private static double mean(double[] vals) {
        if (vals.length == 0) return Double.NaN;
        double s = 0;
        for (double v : vals) s += v;
        return s / vals.length;
    }

    private static double rsd(double[] vals, double mean) {
        if (vals.length < 2 || Double.isNaN(mean) || mean == 0) return Double.NaN;
        double ss = 0;
        for (double v : vals) { double d = v - mean; ss += d * d; }
        return 100.0 * Math.sqrt(ss / (vals.length - 1)) / Math.abs(mean);
    }

    private static double minOf(double[] vals) {
        double m = Double.MAX_VALUE;
        for (double v : vals) if (v < m) m = v;
        return m;
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
