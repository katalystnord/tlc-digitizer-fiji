# TLC Digitizer

A Fiji/ImageJ plugin for quantitative analysis of thin-layer chromatography plate images.

Takes a single photograph of a developed TLC plate — from a smartphone or flatbed scanner — and returns Rf values, integrated spot intensities, and calibration-derived concentrations in a reproducible CSV export.

---

## What it does

| Step | Input | Output |
|------|-------|--------|
| 1. Image preparation | Colour or greyscale image (JPEG, PNG, TIFF, BMP) | Float greyscale (luminance or green channel) |
| 2. Perspective correction | Skewed plate photo | Rectified plate image |
| 3. Background correction | Uneven illumination | Flat background subtracted |
| 4. Reference lines | User marks origin + solvent front | Rf coordinate system |
| 5. Spot detection | Background-corrected image | Spot list with centroids, radii, Rf values |
| 6. Calibration | ≥ 3 reference spots with known concentrations | Linear model (slope, intercept, R², LOD, LOQ) |
| 7. CSV export | All results | Fully reproducible results file |

---

## Algorithm

The image processing pipeline is directly derived from **TLCyzer** (Hauk et al. 2022) with additions from the qTLC and qtlc literature:

- **Grayscale conversion** — ITU-R BT.709 luminance (`Y = 0.2126R + 0.7152G + 0.0722B`) or green channel extraction for UV-fluorescence images (Anton et al. 2023)
- **Perspective correction** — Hough line detection on a downscaled image; 4-point homography warp via mpicbg
- **Background correction** — 2D quartic polynomial fit (15 coefficients) on a subsampled grid, evaluated at full resolution and subtracted
- **Spot detection** — threshold at image mean → morphological opening → connected component labelling (4-connectivity) → aspect ratio and size filtering → intensity-weighted centroid
- **Integration** — sum of the top 15% of pixel values within each spot circle (improves robustness to spot edge noise, per TLCyzer)
- **Calibration** — OLS linear regression on reference spots; LOD = 3.3σ/slope, LOQ = 10σ/slope (ICH Q2(R1))

### Key references

- Hauk C et al. *Scientific Reports* **12**, 13433 (2022) — TLCyzer algorithm (primary source) · [doi:10.1038/s41598-022-17527-y](https://doi.org/10.1038/s41598-022-17527-y)
- Mac Fhionnlaoich N et al. *J. Chem. Educ.* **95**, 2191 (2018) — qTLC; 2D area integration; on-plate calibration
- Fichou D & Morlock G. *J. Chromatogr. A* **1560**, 78 (2018) — quanTLC; videodensitometry validated vs. CAMAG slit scanner
- Pavicevic A et al. *J. Pharm. Biomed. Anal.* **129**, 43 (2016) — qtlc; Savitzky-Golay noise estimation; flatbed scanner workflow
- Anton A et al. *JPC* **36**, 257 (2023) — JPEG outperforms TIFF/RAW; green channel for UV-fluorescence

### Validation targets (from TLCyzer paper)

A publication-quality implementation should achieve:

| Metric | Target |
|--------|--------|
| Mean recovery | 100.3% (range 96.8–103.9%) |
| Repeatability RSD | ≤ 3.84% per compound |
| Mean repeatability RSD | 2.79% across all compounds |
| Intermediate precision RSD | ≤ 4.46% average |

---

## Installation

### Option A — Fiji update site (recommended, once published)

1. Open Fiji
2. **Help → Update → Manage Update Sites**
3. Add the TLC Digitizer update site *(URL to be confirmed on publication)*
4. Click **Apply changes** and restart Fiji

### Option B — Manual JAR install

1. Download `tlc-digitizer-X.Y.Z.jar` from [Releases](../../releases)
2. Copy it to your Fiji `plugins/` folder
3. Restart Fiji

The plugin appears under **Plugins → TLC → TLC Digitizer**.

---

## Usage

1. Open a TLC plate image in Fiji (**File → Open**)
2. Run **Plugins → TLC → TLC Digitizer**
3. Work through the 7-step wizard:

   | Step | What to do |
   |------|-----------|
   | 1. Image | Choose luminance or green channel conversion |
   | 2. Perspective | Verify the four corner handles; drag to correct if needed |
   | 3. Background | Choose correction method (polynomial recommended) |
   | 4. Reference lines | Enter the origin and solvent front Y positions (0–1 fractions) |
   | 5. Spots | Review auto-detected spots; confirm or adjust threshold |
   | 6. Calibration | Assign known concentrations to ≥ 3 reference spots |
   | 7. Export | Choose output CSV path |

### Recommended image capture

For best results, use a standardised photography box (matte black interior, UV lamp slots, top aperture for smartphone camera) as described in the TLCyzer paper. JPEG from a smartphone gives better results than TIFF/RAW for this application (Anton et al. 2023 — JPEG compression reduces noise that interferes with spot detection).

---

## Building from source

**Prerequisites:** Java 8+ (tested with Java 11 and 21), internet connection on first build

```sh
git clone https://github.com/katalystnord/tlc-digitizer-fiji.git
cd tlc-digitizer-fiji
./mvnw package
```

The wrapper script downloads Maven 3.9.x automatically if not already present. The output JAR is at `target/tlc-digitizer-*.jar`.

To install directly into a local Fiji:

```sh
cp target/tlc-digitizer-*.jar /path/to/Fiji.app/plugins/
```

### Running tests

```sh
./mvnw test
```

Tests cover each algorithm stage with synthetic inputs and known-good outputs. The Rf and calibration tests use exact analytical solutions; the background and spot detection tests verify residuals against published tolerance bounds.

---

## Project structure

```
src/
  main/java/se/katalystnord/tlcdigitizer/
    TlcDigitizerPlugin.java      Plugin entry point (ImageJ PlugIn)
    model/
      Spot.java                  One detected spot (centroid, Rf, integration, concentration)
      AnalysisState.java         Mutable state threaded through the wizard
    pipeline/
      ImagePreparation.java      Stage 1: greyscale conversion
      PerspectiveCorrection.java Stage 2: Hough lines + homography warp
      BackgroundCorrection.java  Stage 3: quartic polynomial fit + subtraction
      RfCalculator.java          Stage 5: Rf = (originY − spotY) / (originY − frontY)
      SpotDetector.java          Stage 4: threshold → CC labelling → filtering
      SpotIntegrator.java        Stage 6: top-15% intensity sum within spot circle
      CalibrationModel.java      Stage 7: OLS linear calibration, LOD, LOQ
    ui/
      WizardController.java      Step-by-step dialog orchestration
    export/
      CsvExporter.java           Stage 8: reproducible CSV with full metadata header
  test/java/se/katalystnord/tlcdigitizer/pipeline/
    BackgroundCorrectionTest.java
    SpotDetectorTest.java
    SpotIntegratorTest.java
    RfCalculatorTest.java
    CalibrationModelTest.java
```

---

## Relationship to TLC Flow

TLC Digitizer is the **research and validation phase** of a planned cross-platform application (TLC Flow). Once this plugin achieves the validation targets above and is published:

- The validated algorithm becomes the specification for TLC Flow's computation core
- The real-world failure modes discovered here inform TLC Flow's UX
- The methods paper provides scientific citeability at launch

Do not design TLC Digitizer to be "compatible with TLC Flow". Design it to be correct.

---

## Dependencies

All runtime dependencies are bundled with Fiji:

| Library | Version | Licence | Use |
|---------|---------|---------|-----|
| ImageJ (ij) | 1.54+ | Public domain | Core image processing |
| mpicbg | 1.6.0 | GPL-2.0 | Perspective homography |
| Apache Commons Math 3 | 3.6.1 | Apache-2.0 | OLS regression, polynomial fitting |

---

## Contact

David Sandquist · [david@katalystnord.com](mailto:david@katalystnord.com) · Katalyst Nord AB, Stockholm

For collaboration enquiries related to the TLCyzer algorithm, the Tübingen group should be contacted first: [heide@uni-tuebingen.de](mailto:heide@uni-tuebingen.de)
