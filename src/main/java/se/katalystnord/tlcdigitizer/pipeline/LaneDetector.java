package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import se.katalystnord.tlcdigitizer.model.Lane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Automatic lane-boundary detection via a 1D continuous wavelet transform (CWT) on the
 * column-projected intensity profile.
 *
 * <p>Distinct from {@code LaneAssigner}, which clusters already-detected spot centroids
 * on X-gaps <em>after</em> spot detection. This class instead finds lane boundaries
 * directly from the image, independent of spot detection — a different, complementary
 * primitive that can represent a genuinely empty lane (no spots at all).
 *
 * <p>Algorithm follows Moreira, Sousa, Mendonça &amp; Campilho, "Automatic Lane
 * Segmentation in TLC Images Using the Continuous Wavelet Transform", <em>Computational
 * and Mathematical Methods in Medicine</em> 2013, Article ID 218415 (open access),
 * validated at 98.9% / 97.7% F-measure on 651 and 1,422 real clinical TLC lanes. Three
 * phases, matching the paper's own structure:
 * <ol>
 *   <li>{@link #computeProfile} + CWT smoothing ({@link #computeScaleSweep},
 *   {@link #selectCutoffs}, {@link #reconstructProfile}) produce an initial set of
 *   candidate lanes via h-maxima/h-minima regional extrema
 *   ({@link #hMaxima}, {@link #hMinima}, {@link #candidateLaneRegions}).</li>
 *   <li>{@link #removeFalseLanes} validates candidates against per-image width/distance/
 *   intensity statistics.</li>
 *   <li>{@link #recoverSubtleLanes} analyses the profile derivative in remaining empty
 *   zones to recover lanes missed by the h-maxima threshold (paper's "Algorithm 1").</li>
 * </ol>
 *
 * <h2>Deliberate deviations from the source paper</h2>
 * <ul>
 *   <li><b>No separate background suppression.</b> The paper applies its own morphological
 *   closing to estimate/remove background before projecting the profile, because its
 *   pipeline has no earlier background-correction stage. Ours already does (Stage 3) —
 *   {@link #detect} takes the already perspective-corrected <em>and</em>
 *   background-subtracted image directly, avoiding a redundant second pass.</li>
 *   <li><b>Origin-band exclusion instead of a fixed "top 25%".</b> The paper excludes a
 *   fixed top fraction of rows because its images have the noisy sample-loading zone near
 *   the top edge. This project's coordinate convention has the origin near the
 *   <em>bottom</em> ({@code originYFraction} typically 0.85-0.95 — confirmed against the
 *   MOESM2 validation fixture), and Step 4 already provides the exact origin Y before this
 *   stage would run — so {@link #computeProfile} excludes the band between the origin line
 *   and whichever image edge is nearest to it, which is strictly more precise than a fixed
 *   percentage tied to a different image orientation.</li>
 *   <li><b>Scale range adapted without resampling the image.</b> The paper's scale range
 *   (30-250) is only valid because every image is first bicubic-resampled to 1024 rows.
 *   Rather than resampling (extra interpolation, extra bookkeeping to map detected
 *   boundaries back to original pixel space), {@link #detect} scales the CWT's own
 *   scale-range endpoints by {@code ROW_NORMALIZATION_TARGET / height} — CWT scale is a
 *   spatial dilation factor and this rescaling is equivalent to resampling the signal,
 *   without the interpolation.</li>
 *   <li><b>Profile reconstruction sums coefficient magnitude, not real part.</b> Summing
 *   the raw real part of the (oscillating) Morlet coefficients across scales produces
 *   spurious ringing — see {@link #reconstructProfile} for the empirical numbers. Using
 *   magnitude instead (the envelope, not the carrier) is standard practice for CWT-based
 *   peak detection specifically, as distinct from exact signal reconstruction.</li>
 *   <li><b>H-minima via reconstruction by erosion, not dilation.</b> The paper's own
 *   formula for {@code HMINh(f)} is written using the same reconstruction-by-dilation
 *   operator as {@code HMAXh(f)}, which does not satisfy the precondition that a
 *   reconstruction-by-dilation marker must be <= the mask ({@code f+h} is not <= {@code f}).
 *   This project instead uses the standard textbook dual (Soille, <em>Morphological Image
 *   Analysis</em>; Vincent 1993): h-minima via reconstruction <em>by erosion</em> of
 *   {@code f+h} under {@code f}. Treated as a transcription artifact in the source, not a
 *   deliberate departure from it.</li>
 *   <li><b>Amplitude-difference comparisons use absolute value.</b> The paper's subtle-lane
 *   recovery formulas ({@code mla}, and the per-candidate check in "Algorithm 1") are
 *   ambiguous about sign convention as transcribed — a literal reading can produce a
 *   negative threshold compared against a difference of the opposite sign. Implemented with
 *   {@code Math.abs(...)} throughout, consistent with the plain-English "amplitude
 *   difference" framing and avoiding a sign-direction trap.</li>
 * </ul>
 *
 * <p><b>Known limitation, not yet addressed:</b> {@link #H_MAXIMA_FRACTION} is a starting
 * guess (the paper's own value is dataset-tuned: 5% for one dataset, 10% for the other) —
 * not yet validated against this project's own images. Do not treat it as calibrated.
 */
public final class LaneDetector {

    /**
     * The row count the source paper resamples every image to before choosing its CWT
     * scale range. Used to scale {@link #MIN_LANE_SCALE_REF}/{@link #MAX_LANE_SCALE_REF}
     * to this project's own (unresampled) image resolution — see class javadoc.
     */
    static final float ROW_NORMALIZATION_TARGET = 1024f;

    /** Lower end of the paper's lane-range scale window, in 1024-row-equivalent pixels. */
    static final float MIN_LANE_SCALE_REF = 30f;

    /** Upper end of the paper's lane-range scale window, in 1024-row-equivalent pixels. */
    static final float MAX_LANE_SCALE_REF = 250f;

    /** Morlet wavelet nondimensional frequency; 6 satisfies the admissibility condition
     * (zero mean), per the source paper and standard wavelet-analysis convention. */
    static final double MORLET_OMEGA0 = 6.0;

    /** Truncate the Morlet kernel's Gaussian envelope at this many "standard deviations"
     * (scale units) — negligible energy beyond this, keeps convolution cost bounded. */
    static final float MORLET_KERNEL_SIGMA_MULTIPLE = 4f;

    /** Number of scales sampled across the lane-range window when searching for the
     * adaptive cutoff-min/cutoff-max values. Not critical for accuracy, only for how
     * finely the per-scale mean-amplitude curve's local extrema are resolved. */
    static final int CWT_SCALE_STEPS = 60;

    /**
     * H-maxima/h-minima threshold as a fraction of the smoothed profile's maximum value.
     * The source paper uses dataset-tuned values (5% / 10%) with no universal formula;
     * this is a starting point splitting the difference, <b>not yet validated</b> against
     * this project's own images — see class javadoc.
     */
    static final float H_MAXIMA_FRACTION = 0.075f;

    /** Subtle-lane recovery (paper's "Algorithm 1"): accepted separation range as a
     * fraction of mean lane width (mlw). */
    static final float SUBTLE_LANE_WIDTH_MIN_FACTOR = 0.6f;
    static final float SUBTLE_LANE_WIDTH_MAX_FACTOR = 1.4f;

    /** Subtle-lane recovery: minimum derivative amplitude difference as a fraction of the
     * mean validated-lane amplitude difference (mla). */
    static final float SUBTLE_LANE_AMPLITUDE_FACTOR = 0.3f;

    private LaneDetector() {}

    /**
     * Detects sample-lane boundaries on a perspective-corrected, background-subtracted
     * image.
     *
     * @param corrected       perspective-corrected AND background-subtracted image
     *                        (i.e. {@code AnalysisState.corrected} — Stages 2-3 output)
     * @param originYFraction origin line Y position as a fraction of {@code corrected}'s
     *                        height (0 = top), from Step 4; used to exclude the noisy
     *                        sample-loading band from the profile (see class javadoc)
     * @return detected lanes, left to right, partitioning the full image width
     */
    public static List<Lane> detect(FloatProcessor corrected, float originYFraction) {
        int width = corrected.getWidth();
        int height = corrected.getHeight();

        float[] profile = computeProfile(corrected, originYFraction);

        float scaleFactor = ROW_NORMALIZATION_TARGET / height;
        float minScale = Math.max(1f, MIN_LANE_SCALE_REF / scaleFactor);
        float maxScale = Math.max(minScale + 1f, MAX_LANE_SCALE_REF / scaleFactor);

        CwtSweep sweep = computeScaleSweep(profile, minScale, maxScale, CWT_SCALE_STEPS);
        int[] cutoffs = selectCutoffs(sweep.meanAmplitude);
        float[] smoothed = reconstructProfile(sweep, cutoffs[0], cutoffs[1]);

        float maxSmoothed = 0f;
        for (float v : smoothed) maxSmoothed = Math.max(maxSmoothed, v);
        float h = H_MAXIMA_FRACTION * maxSmoothed;

        float[] hmax = hMaxima(smoothed, h);
        float[] hmin = hMinima(smoothed, h);

        List<Region> candidates = candidateLaneRegions(smoothed, hmax, hmin);
        List<Region> validated = removeFalseLanes(candidates, smoothed, width);
        List<Region> recovered = recoverSubtleLanes(validated, smoothed, width);

        return finalizeBoundaries(recovered, width);
    }

    // -------------------------------------------------------------------------
    // Algorithm steps (package-private for unit testing)
    // -------------------------------------------------------------------------

    /**
     * Column-projected mean intensity profile, excluding rows in the band between the
     * origin line and whichever image edge is nearest to it (see class javadoc for why
     * this replaces the source paper's fixed "top 25%" exclusion).
     */
    static float[] computeProfile(FloatProcessor fp, float originYFraction) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        float[] pixels = (float[]) fp.getPixels();

        int originY = Math.round(originYFraction * height);
        originY = Math.max(0, Math.min(height - 1, originY));
        boolean originNearBottom = originYFraction > 0.5f;
        int excludeFrom = originNearBottom ? originY : 0;
        int excludeTo   = originNearBottom ? height - 1 : originY;

        float[] profile = new float[width];
        for (int x = 0; x < width; x++) {
            double sum = 0;
            int count = 0;
            for (int y = 0; y < height; y++) {
                if (y >= excludeFrom && y <= excludeTo) continue;
                sum += pixels[y * width + x];
                count++;
            }
            profile[x] = count > 0 ? (float) (sum / count) : 0f;
        }
        return profile;
    }

    /** Holds the per-scale CWT coefficient magnitude (used both for the adaptive cutoff
     * selection curve and for profile reconstruction — see {@link #reconstructProfile}
     * for why magnitude, not the raw real part). */
    static final class CwtSweep {
        final float[] scales;
        final float[] meanAmplitude;
        final float[][] magnitude; // [scaleIndex][x]

        CwtSweep(float[] scales, float[] meanAmplitude, float[][] magnitude) {
            this.scales = scales;
            this.meanAmplitude = meanAmplitude;
            this.magnitude = magnitude;
        }
    }

    /**
     * Sweeps {@code steps} scales linearly across {@code [minScale, maxScale]}, computing
     * the 1D Morlet-wavelet CWT magnitude at each. Performance note: cost is
     * {@code O(width * steps * scale)} — fine for the synthetic profiles used in unit
     * tests, but worth revisiting for real (multi-thousand-pixel-wide) plate images once
     * this is wired into the interactive UI.
     */
    static CwtSweep computeScaleSweep(float[] profile, float minScale, float maxScale, int steps) {
        int width = profile.length;
        float[] scales = new float[steps];
        float[] meanAmp = new float[steps];
        float[][] magnitude = new float[steps][];
        float scaleStep = steps > 1 ? (maxScale - minScale) / (steps - 1) : 0f;

        for (int si = 0; si < steps; si++) {
            float s = minScale + si * scaleStep;
            scales[si] = s;
            float[] real = new float[width];
            float[] imag = new float[width];
            cwtAtScale(profile, s, real, imag);

            float[] mag = new float[width];
            double sumAmp = 0;
            for (int x = 0; x < width; x++) {
                mag[x] = (float) Math.hypot(real[x], imag[x]);
                sumAmp += mag[x];
            }
            magnitude[si] = mag;
            meanAmp[si] = (float) (sumAmp / width);
        }
        return new CwtSweep(scales, meanAmp, magnitude);
    }

    /**
     * Complex 1D CWT at a single scale, direct discrete convolution with the Morlet
     * mother wavelet ({@code psi0(eta) = pi^-1/4 * e^{i*omega0*eta} * e^{-eta^2/2}}, its
     * complex conjugate applied per the standard CWT definition). Out-of-range samples
     * are edge-clamped rather than zero-padded, to avoid a spurious energy drop-off near
     * the profile boundaries.
     */
    static void cwtAtScale(float[] profile, float scale, float[] realOut, float[] imagOut) {
        int width = profile.length;
        int kernelHalf = Math.max(1, (int) Math.ceil(MORLET_KERNEL_SIGMA_MULTIPLE * scale));
        double invSqrtScale = 1.0 / Math.sqrt(scale);
        double normConst = Math.pow(Math.PI, -0.25);

        for (int x0 = 0; x0 < width; x0++) {
            double sumRe = 0, sumIm = 0;
            for (int k = -kernelHalf; k <= kernelHalf; k++) {
                int xi = x0 + k;
                if (xi < 0) xi = 0;
                if (xi >= width) xi = width - 1;

                double eta = k / (double) scale;
                double gauss = Math.exp(-0.5 * eta * eta);
                double p = profile[xi];

                sumRe += p * normConst * Math.cos(MORLET_OMEGA0 * eta) * gauss;
                sumIm += -p * normConst * Math.sin(MORLET_OMEGA0 * eta) * gauss;
            }
            realOut[x0] = (float) (sumRe * invSqrtScale);
            imagOut[x0] = (float) (sumIm * invSqrtScale);
        }
    }

    /**
     * Adaptive cutoff-min/cutoff-max selection from the per-scale mean-amplitude curve,
     * per the source paper: cutoff-min is the local minimum immediately before the
     * tallest peak in the window; cutoff-max is the local minimum after a secondary peak
     * if one exists, otherwise the window's own ceiling.
     *
     * @return {@code {cutoffMinIndex, cutoffMaxIndex}} into the sweep's scale array
     */
    static int[] selectCutoffs(float[] meanAmplitude) {
        int n = meanAmplitude.length;
        int peakIdx = 0;
        for (int i = 1; i < n; i++) {
            if (meanAmplitude[i] > meanAmplitude[peakIdx]) peakIdx = i;
        }

        int cutoffMinIdx = 0;
        for (int i = peakIdx - 1; i >= 1; i--) {
            if (meanAmplitude[i] < meanAmplitude[i - 1] && meanAmplitude[i] < meanAmplitude[i + 1]) {
                cutoffMinIdx = i;
                break;
            }
        }

        int cutoffMaxIdx = n - 1;
        int secondaryPeakIdx = -1;
        for (int i = peakIdx + 1; i < n - 1; i++) {
            if (meanAmplitude[i] > meanAmplitude[i - 1] && meanAmplitude[i] > meanAmplitude[i + 1]) {
                secondaryPeakIdx = i;
                break;
            }
        }
        if (secondaryPeakIdx > 0) {
            for (int i = secondaryPeakIdx + 1; i < n - 1; i++) {
                if (meanAmplitude[i] < meanAmplitude[i - 1] && meanAmplitude[i] < meanAmplitude[i + 1]) {
                    cutoffMaxIdx = i;
                    break;
                }
            }
        }
        return new int[]{cutoffMinIdx, cutoffMaxIdx};
    }

    /**
     * Sums CWT coefficient <em>magnitude</em> (not the raw real part) across the selected
     * scale range. The Morlet wavelet's real part is an oscillating carrier (period
     * {@code ~2*pi*scale/omega0}) modulated by a Gaussian envelope — summing the raw real
     * part across scales sums that oscillation and produces spurious ringing (confirmed
     * empirically: an isolated smooth Gaussian bump produced a "reconstructed" profile
     * swinging between roughly -570 and +710 around the bump, i.e. several extra false
     * peaks per real one). Magnitude {@code sqrt(Re^2+Im^2)} tracks the envelope instead
     * of the carrier and is the standard choice for CWT-based peak detection (as opposed
     * to exact signal reconstruction/denoising) — e.g. mass-spectrometry/chromatography
     * CWT peak-picking uses coefficient magnitude for exactly this reason. Not an
     * energy-preserving wavelet reconstruction (the source paper does not specify an
     * exact reconstruction formula either); sufficient for peak-finding, the only use
     * this is put to.
     */
    static float[] reconstructProfile(CwtSweep sweep, int cutoffMinIdx, int cutoffMaxIdx) {
        int width = sweep.magnitude[0].length;
        float[] result = new float[width];
        for (int si = cutoffMinIdx; si <= cutoffMaxIdx; si++) {
            float[] mag = sweep.magnitude[si];
            for (int x = 0; x < width; x++) result[x] += mag[x];
        }
        return result;
    }

    /** Morphological reconstruction by dilation: repeatedly propagate {@code marker}
     * upward under the {@code mask} ceiling until stable. Standard algorithm (Vincent
     * 1993); forward+backward raster passes per iteration for fast convergence. */
    static float[] reconstructByDilation(float[] marker, float[] mask) {
        float[] g = marker.clone();
        int n = g.length;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                float v = g[i];
                if (i > 0) v = Math.max(v, g[i - 1]);
                if (i < n - 1) v = Math.max(v, g[i + 1]);
                v = Math.min(v, mask[i]);
                if (v != g[i]) { g[i] = v; changed = true; }
            }
            for (int i = n - 1; i >= 0; i--) {
                float v = g[i];
                if (i > 0) v = Math.max(v, g[i - 1]);
                if (i < n - 1) v = Math.max(v, g[i + 1]);
                v = Math.min(v, mask[i]);
                if (v != g[i]) { g[i] = v; changed = true; }
            }
        }
        return g;
    }

    /** Morphological reconstruction by erosion: dual of {@link #reconstructByDilation} —
     * repeatedly propagate {@code marker} downward under the {@code mask} floor until
     * stable. Used for h-minima; see class javadoc for why this (not dilation, despite
     * the source paper's formula) is the correct dual operation. */
    static float[] reconstructByErosion(float[] marker, float[] mask) {
        float[] g = marker.clone();
        int n = g.length;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                float v = g[i];
                if (i > 0) v = Math.min(v, g[i - 1]);
                if (i < n - 1) v = Math.min(v, g[i + 1]);
                v = Math.max(v, mask[i]);
                if (v != g[i]) { g[i] = v; changed = true; }
            }
            for (int i = n - 1; i >= 0; i--) {
                float v = g[i];
                if (i > 0) v = Math.min(v, g[i - 1]);
                if (i < n - 1) v = Math.min(v, g[i + 1]);
                v = Math.max(v, mask[i]);
                if (v != g[i]) { g[i] = v; changed = true; }
            }
        }
        return g;
    }

    static float[] hMaxima(float[] f, float h) {
        int n = f.length;
        float[] marker = new float[n];
        for (int i = 0; i < n; i++) marker[i] = f[i] - h;
        return reconstructByDilation(marker, f);
    }

    static float[] hMinima(float[] f, float h) {
        int n = f.length;
        float[] marker = new float[n];
        for (int i = 0; i < n; i++) marker[i] = f[i] + h;
        return reconstructByErosion(marker, f);
    }

    /** A candidate/validated lane region as [left, right] indices into the profile array
     * (inclusive both ends). Package-private for test construction/inspection. */
    static final class Region {
        final int left, right;

        Region(int left, int right) {
            this.left = left;
            this.right = right;
        }

        int width() { return right - left + 1; }
        float center() { return (left + right) / 2f; }
    }

    /**
     * Combines h-maxima and h-minima regional-extrema masks into candidate lane regions:
     * a contiguous run that is a regional maximum of the h-maxima-suppressed image and is
     * not also a regional minimum of the h-minima-suppressed image — the standard
     * "extended maxima transform" construction (Soille, <em>Morphological Image
     * Analysis</em>): {@code RegionalMaxima(HMAXh(f))}, not a direct comparison of
     * {@code HMAXh(f)} back to {@code f} itself.
     *
     * <p><b>Why not direct comparison to the original signal</b> (an earlier, incorrect
     * version of this method did exactly that): reconstruction-by-dilation from
     * {@code f-h} saturates at the mask ceiling {@code f[i]} wherever there is "dilation
     * pressure" from taller nearby markers to propagate up to that ceiling — which happens
     * at <em>valleys</em> (flanked by taller neighbours on both sides), not at true peaks
     * (which have no taller neighbour to propagate from, so their reconstruction falls
     * short of the ceiling by roughly {@code h}). Comparing {@code HMAXh(f) == f} directly
     * therefore tends to flag valleys as "maxima regions," backwards from the intent.
     * Confirmed empirically on a synthetic 6-lane profile: the direct-comparison version
     * consistently centred candidate regions on the midpoints between true lane centres,
     * not on the lane centres themselves.
     */
    static List<Region> candidateLaneRegions(float[] smoothed, float[] hmax, float[] hmin) {
        boolean[] isMaximaRegion = regionalMaxima(hmax);
        boolean[] isMinimaRegion = regionalMinima(hmin);

        int n = smoothed.length;
        List<Region> regions = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < n; i++) {
            boolean candidate = isMaximaRegion[i] && !isMinimaRegion[i];
            if (candidate && start < 0) start = i;
            if (!candidate && start >= 0) { regions.add(new Region(start, i - 1)); start = -1; }
        }
        if (start >= 0) regions.add(new Region(start, n - 1));
        return regions;
    }

    /**
     * Regional maxima of {@code g} itself: maximal runs of consecutive equal values whose
     * value is strictly greater than both flanking neighbours (or the array boundary).
     * Plateau-tolerant (works whether {@code g} is exactly flat-topped at a surviving
     * peak or only reaches a single-sample maximum), which direct floating-point equality
     * to a different array ({@code f}) is not.
     */
    static boolean[] regionalMaxima(float[] g) {
        int n = g.length;
        boolean[] result = new boolean[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && g[j + 1] == g[i]) j++;
            boolean leftOk = (i == 0) || g[i - 1] < g[i];
            boolean rightOk = (j == n - 1) || g[j + 1] < g[i];
            if (leftOk && rightOk) {
                for (int k = i; k <= j; k++) result[k] = true;
            }
            i = j + 1;
        }
        return result;
    }

    /** Dual of {@link #regionalMaxima}: maximal runs strictly lower than both flanking
     * neighbours (or the array boundary). */
    static boolean[] regionalMinima(float[] g) {
        int n = g.length;
        boolean[] result = new boolean[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && g[j + 1] == g[i]) j++;
            boolean leftOk = (i == 0) || g[i - 1] > g[i];
            boolean rightOk = (j == n - 1) || g[j + 1] > g[i];
            if (leftOk && rightOk) {
                for (int k = i; k <= j; k++) result[k] = true;
            }
            i = j + 1;
        }
        return result;
    }

    /**
     * Removes candidate lanes that are both low-intensity and either implausibly sized or
     * implausibly close to a neighbour, using per-image mean/std statistics — verbatim
     * from the source paper. "Distance to the nearest adjacent lane" falls back to the
     * distance to the image border for a lane at either extreme, per the paper's own
     * "(or image border, for the lanes in the image extremes)" clause.
     */
    static List<Region> removeFalseLanes(List<Region> candidates, float[] profile, int width) {
        if (candidates.size() < 2) return new ArrayList<>(candidates);

        List<Region> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(r -> r.left));
        int n = sorted.size();

        float[] widths = new float[n];
        float[] intensities = new float[n];
        float[] centers = new float[n];
        for (int i = 0; i < n; i++) {
            widths[i] = sorted.get(i).width();
            intensities[i] = regionMax(profile, sorted.get(i));
            centers[i] = sorted.get(i).center();
        }

        float[] distances = new float[n];
        for (int i = 0; i < n; i++) {
            float leftGap  = (i > 0) ? centers[i] - centers[i - 1] : sorted.get(i).left;
            float rightGap = (i < n - 1) ? centers[i + 1] - centers[i] : (width - 1 - sorted.get(i).right);
            distances[i] = Math.min(leftGap, rightGap);
        }

        float mw = mean(widths), stdW = stddev(widths, mw);
        float mi = mean(intensities);
        float md = mean(distances), stdD = stddev(distances, md);

        List<Region> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean lowIntensity = intensities[i] < mi / 2f;
            boolean badWidth = widths[i] < mw - stdW || widths[i] > mw + stdW;
            boolean tooClose = distances[i] < md - stdD;
            if (lowIntensity && (badWidth || tooClose)) continue;
            kept.add(sorted.get(i));
        }
        return kept;
    }

    /**
     * Recovers lanes missed by h-maxima suppression by analysing the profile derivative
     * in empty zones wider than the mean validated lane width — the source paper's
     * "Algorithm 1". No-op if there are no validated lanes to anchor mean width/amplitude
     * statistics on.
     */
    static List<Region> recoverSubtleLanes(List<Region> validated, float[] profile, int width) {
        List<Region> sorted = new ArrayList<>(validated);
        sorted.sort(Comparator.comparingInt(r -> r.left));
        int n = sorted.size();
        if (n == 0) return sorted;

        float[] derivative = centralDifference(profile);

        float mlw = 0;
        for (Region r : sorted) mlw += r.width();
        mlw /= n;

        float mla = 0;
        for (Region r : sorted) mla += Math.abs(derivative[r.right] - derivative[r.left]);
        mla /= n;

        List<Region> recovered = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            int gapStart = (i == 0) ? 0 : sorted.get(i - 1).right + 1;
            int gapEnd   = (i == n) ? width - 1 : sorted.get(i).left - 1;
            if (gapEnd < gapStart) continue;
            if ((gapEnd - gapStart + 1) > mlw) {
                recovered.addAll(findRecoverableLanesInGap(derivative, gapStart, gapEnd, mlw, mla));
            }
        }

        List<Region> result = new ArrayList<>(sorted);
        result.addAll(recovered);
        result.sort(Comparator.comparingInt(r -> r.left));
        return result;
    }

    static List<Region> findRecoverableLanesInGap(float[] derivative, int gapStart, int gapEnd,
                                                    float mlw, float mla) {
        List<Integer> maxima = new ArrayList<>();
        List<Integer> minima = new ArrayList<>();
        int lo = Math.max(1, gapStart);
        int hi = Math.min(derivative.length - 2, gapEnd);
        for (int i = lo; i <= hi; i++) {
            if (derivative[i] > derivative[i - 1] && derivative[i] > derivative[i + 1]) maxima.add(i);
            if (derivative[i] < derivative[i - 1] && derivative[i] < derivative[i + 1]) minima.add(i);
        }

        List<Region> found = new ArrayList<>();
        for (int mj : maxima) {
            Integer mk = null;
            for (int cand : minima) {
                if (cand > mj && (mk == null || cand < mk)) mk = cand;
            }
            if (mk == null) continue;
            float separation = mk - mj;
            float amplitudeDiff = Math.abs(derivative[mk] - derivative[mj]);
            if (separation > SUBTLE_LANE_WIDTH_MIN_FACTOR * mlw
                    && separation < SUBTLE_LANE_WIDTH_MAX_FACTOR * mlw
                    && amplitudeDiff > SUBTLE_LANE_AMPLITUDE_FACTOR * mla) {
                found.add(new Region(mj, mk));
            }
        }
        return found;
    }

    /** Converts final candidate regions into a clean partition of the full width: the
     * boundary between two adjacent lanes is the midpoint of the gap between them; the
     * first/last lane's outer boundary clamps to the image edge. */
    static List<Lane> finalizeBoundaries(List<Region> regions, int width) {
        List<Region> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(r -> r.left));

        List<Lane> lanes = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            float left = (i == 0) ? 0f : (sorted.get(i - 1).right + sorted.get(i).left) / 2f;
            float right = (i == sorted.size() - 1)
                    ? width
                    : (sorted.get(i).right + sorted.get(i + 1).left) / 2f;
            lanes.add(new Lane(left, right));
        }
        return lanes;
    }

    static float[] centralDifference(float[] profile) {
        int n = profile.length;
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            float left = (i > 0) ? profile[i - 1] : profile[i];
            float right = (i < n - 1) ? profile[i + 1] : profile[i];
            d[i] = (right - left) / 2f;
        }
        return d;
    }

    private static float regionMax(float[] profile, Region r) {
        float max = -Float.MAX_VALUE;
        for (int i = r.left; i <= r.right; i++) max = Math.max(max, profile[i]);
        return max;
    }

    private static float mean(float[] vals) {
        double sum = 0;
        for (float v : vals) sum += v;
        return (float) (sum / vals.length);
    }

    private static float stddev(float[] vals, float mean) {
        double sumSq = 0;
        for (float v : vals) { double d = v - mean; sumSq += d * d; }
        return (float) Math.sqrt(sumSq / vals.length);
    }
}
