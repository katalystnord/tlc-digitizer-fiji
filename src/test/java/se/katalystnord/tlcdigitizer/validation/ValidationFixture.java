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

import com.google.gson.Gson;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;

/**
 * JSON fixture describing one TLC plate image together with all parameters
 * needed to run the pipeline headlessly.
 *
 * <p>Fixture files live outside the git repository (images are large binaries).
 * Point Maven at the directory containing them with:
 * <pre>
 *   mvn test -Dvalidation.data.dir=/path/to/tlcyzer-validation
 * </pre>
 *
 * <p>Each {@code .json} file in that directory that matches this schema is
 * loaded and run by {@link ValidationTest}.
 *
 * <h2>How to create a fixture</h2>
 * <ol>
 *   <li>Open the image in TLC Digitizer and work through the wizard.
 *   <li>Note the corner pixel coordinates from Step 2.
 *   <li>Note the origin/front Y fractions from Step 4.
 *   <li>Note each reference spot's approximate centroid position and
 *       the known concentration from your reference table.
 *   <li>Encode these values in a {@code .json} file using this schema.
 * </ol>
 */
public class ValidationFixture {

    /** Image file name, relative to {@code validation.data.dir}. */
    public String imagePath;

    /** Optional free-text label shown in test output. */
    public String description;

    /** True when the image has dark spots on a bright background (staining, UV 254 nm). */
    public boolean invertImage = false;

    /**
     * True to use the green channel instead of BT.709 luminance.
     * Recommended for UV-fluorescence images (Anton et al. 2023).
     */
    public boolean useGreenChannel = false;

    /**
     * True (default) to use the quartic polynomial background correction (Method A).
     * False to use per-spot Savitzky-Golay correction (Method B).
     * Ignored when {@link #useTopHatBackground} is true.
     */
    public boolean usePolynomialBackground = true;

    /**
     * True to use the white top-hat morphological background correction (Method C).
     * When true, overrides {@link #usePolynomialBackground}.
     */
    public boolean useTopHatBackground = false;

    /**
     * Structuring element radius for top-hat correction in corrected-image pixels.
     * 0 (default) = auto: 1.5× median auto-detected spot radius.
     */
    public float topHatSeRadius = 0f;

    /** Polynomial degree for Method B (per-spot S-G correction). Ignored for Method A or C. */
    public int sgDegree = 5;

    /**
     * Threshold multiplier for spot detection.
     * 1.0 = mean intensity (TLCyzer default). Increase to raise the threshold.
     */
    public double thresholdFactor = 1.0;

    /**
     * Four plate corner pixel coordinates in the <em>original</em> image (before perspective warp):
     * {@code [tlX, tlY, trX, trY, brX, brY, blX, blY]}
     * where tl=top-left, tr=top-right, br=bottom-right, bl=bottom-left.
     */
    public float[] corners;

    /**
     * Application point (origin) Y position as a fraction of the
     * <em>perspective-corrected</em> image height (0 = top, 1 = bottom).
     * Typically 0.90–0.95 for standard TLC plates.
     */
    public float originYFraction;

    /**
     * Solvent front Y position as a fraction of corrected image height.
     * Must be less than {@link #originYFraction} (solvent front is closer to the top).
     * Typically 0.04–0.10.
     */
    public float frontYFraction;

    /** Reference spots with known concentrations. At least 3 are needed for LOO calibration. */
    public RefSpot[] referenceSpots;

    // -------------------------------------------------------------------------

    /**
     * One reference spot: its approximate position in the corrected image plus
     * its known concentration.
     */
    public static class RefSpot {

        /**
         * Approximate centroid X as a fraction of corrected image width (0 = left, 1 = right).
         * Used to match against the nearest auto-detected spot.
         */
        public float xFraction;

        /**
         * Approximate centroid Y as a fraction of corrected image height (0 = top, 1 = bottom).
         */
        public float yFraction;

        /** Known concentration (e.g. µg/mL or µg/spot). Used for LOO recovery calculation. */
        public double knownConcentration;

        /**
         * Optional compound/API name. Used to group RSD statistics per compound.
         * Leave null to lump all spots into one group.
         */
        public String apiName;

        /**
         * Optional radius override in corrected-image pixels.
         * 0 (default) means use the radius of the nearest auto-detected spot,
         * or the median detected radius if no spot was matched.
         */
        public float radiusOverride = 0;
    }

    // -------------------------------------------------------------------------

    /** Deserialises a fixture from a JSON file on disk. */
    public static ValidationFixture fromJson(Path path) throws IOException {
        try (Reader r = new FileReader(path.toFile())) {
            return new Gson().fromJson(r, ValidationFixture.class);
        }
    }

    /** Deserialises a fixture from a JSON string (convenient for unit tests). */
    public static ValidationFixture fromJson(String json) {
        return new Gson().fromJson(json, ValidationFixture.class);
    }
}
