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
 * <p>Algorithm is based on Moreira, Sousa, Mendonça &amp; Campilho, "Automatic Lane
 * Segmentation in TLC Images Using the Continuous Wavelet Transform", <em>Computational
 * and Mathematical Methods in Medicine</em> 2013, Article ID 218415 (open access),
 * validated at 98.9% / 97.7% F-measure on 651 and 1,422 real clinical TLC lanes — but its
 * central smoothing step (scale selection + reconstruction) was replaced after real-world
 * testing found the paper's own approach unreliable for this project's plate geometry; see
 * "Scale selection" below for the full empirical account. Three phases, matching the
 * paper's own overall structure even though phase 1's internals differ substantially:
 * <ol>
 *   <li>{@link #computeProfile} + single-scale CWT smoothing ({@link #estimateLanePitch},
 *   {@link #cwtAtScale}) produce an initial set of candidate lanes via h-maxima/h-minima
 *   regional extrema ({@link #hMaxima}, {@link #hMinima}, {@link #candidateLaneRegions}).</li>
 *   <li>{@link #removeFalseLanes} validates candidates against per-image width/distance/
 *   intensity statistics.</li>
 *   <li>{@link #recoverSubtleLanes} analyses the profile derivative in remaining empty
 *   zones to recover lanes missed by the h-maxima threshold (paper's "Algorithm 1").</li>
 * </ol>
 *
 * <h2>Scale selection — replaced after real-world testing (2026-07-12)</h2>
 * The paper picks a scale <em>window</em> (not a single scale) for reconstruction: it scans
 * a fixed range (30-250, in "resampled to 1024 rows" units), finds the scale with peak mean
 * coefficient amplitude, sets cutoff-min to the dip just before that peak, and sets
 * cutoff-max either to the dip after a secondary peak or — its own explicitly documented
 * fallback for images with no secondary peak — the window's own ceiling. Interactive
 * testing against a real 6-lane plate photo (img_00451) found this collapsed 6 clearly
 * visible lanes into just 2. Root-cause chain, each step confirmed empirically with a
 * throwaway diagnostic harness (same pattern as the shape-aware-detection bug hunts):
 * <ol>
 *   <li>The paper's own scale window, converted to this project's unresampled pixel space
 *   via {@code ROW_NORMALIZATION_TARGET / height} (a height-derived formula since replaced —
 *   see {@link #MIN_LANE_PITCH_FRACTION}), lands in the same order of magnitude as this
 *   project's typical lane <em>pitch</em> (a plate
 *   with a handful of wide lanes — this project's own CLAUDE.md documents "typically fewer,
 *   wider lanes" as the expected geometry for synthesis-monitoring plates, unlike the
 *   paper's clinical urine-screening dataset, which likely has many narrower lanes). This
 *   is a <em>resonance</em> condition: the Morlet wavelet's characteristic wavelength
 *   ({@code 2*pi*scale/MORLET_OMEGA0}) at the window's own peak-amplitude scale matches the
 *   signal's own period.</li>
 *   <li>At resonance, coefficient <em>magnitude</em> (used for reconstruction — a deviation
 *   already in place before this investigation, itself made to fix an earlier, different
 *   ringing bug — see below) stops localizing peaks at all: magnitude discards phase, and a
 *   wavelet whose support spans several full periods of a near-periodic signal returns a
 *   near-constant "local power" value regardless of where its center sits relative to
 *   individual peaks. Confirmed directly: a synthetic 6-bump profile with unambiguous,
 *   well-separated bumps in the raw signal (verified) reconstructed via magnitude-summation
 *   into just 1-2 broad humps.</li>
 *   <li>Switching reconstruction to the mathematically standard alternative — real part
 *   weighted by {@code 1/sqrt(scale)} (Torrence &amp; Compo 1998's reconstruction formula) —
 *   fixes resonance (phase is preserved) but, summed across the paper's full cutoff window,
 *   reintroduced a different artifact: interference between the different periods
 *   contributed by different scales in the window produced a spurious half-period ripple
 *   (confirmed: a clean 6-lane synthetic profile reconstructed with ~13-18 candidate
 *   regions instead of 6).</li>
 *   <li>The paper's own scale window turned out not to reliably bracket the profile's true
 *   period at all: in the existing small-image-height unit-test regime, the true period
 *   (70px) sits entirely outside the window's reachable range (max ~29px at that height),
 *   so the "peak amplitude" scale search lands artificially at the window's edge — the
 *   wrong scale for that profile — regardless of which reconstruction formula is used.</li>
 * </ol>
 * None of these four problems is fixable by tuning the reconstruction formula alone,
 * because the real defect is upstream: <b>a fixed, height-derived scale window is not a
 * reliable way to find the scale that matches a given profile's actual lane pitch.</b> The
 * fix adopted here replaces the paper's scale-window-plus-adaptive-cutoff-selection
 * entirely with direct pitch estimation from the profile's own autocorrelation
 * ({@link #estimateLanePitch}): the first local minimum of the (lag, normalized
 * autocorrelation) curve is skipped (it reflects the profile's own smoothness/bump width,
 * not periodicity — the paper's scale window falls in the same trap, since a peak-amplitude
 * search over a fixed range has no equivalent way to distinguish "this is periodicity" from
 * "this is just local smoothness"), then the strongest remaining peak gives the pitch
 * estimate directly. A single CWT evaluation at that one scale (real part, no summation
 * needed) is the reconstructed profile. Verified empirically across every regime that broke
 * the paper's own approach — small-height regular spacing, small-height irregular spacing,
 * realistic-height/width, and (the case motivating this whole class) one or two adjacent
 * lanes entirely missing — pitch estimation lands on the exact true value in all cases, and
 * single-scale reconstruction shows exactly the right number of local maxima in each. This
 * is a bigger deviation from the source paper than any other in this class, but it is
 * empirically driven, not a stylistic preference — see the git history for this file's
 * pre-2026-07-12 version if the original window-based approach is ever needed as a reference.
 *
 * <h2>Other deliberate deviations from the source paper</h2>
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
     * Lower plausibility bound for lane pitch, as a fraction of the (possibly downsampled)
     * profile's own width. Guards {@link #estimateLanePitch}'s autocorrelation search
     * against locking onto implausibly small lags (texture/noise scale). This — not a
     * height-derived formula — is the right basis for the bound: the profile being
     * searched is <em>width</em>-indexed, so its own length is what a plausible period
     * range should scale with. An earlier version of this bound was derived from
     * {@code paperScaleRef / (ROW_NORMALIZATION_TARGET / height)}, mirroring the source
     * paper's own (height-based) scale-window formula — but that reintroduced the exact
     * defect the pitch-estimation rewrite was meant to fix (see class javadoc, "Scale
     * selection", point 4): for the small-image-height unit-test regime, that formula's
     * upper bound (~29px) still excluded the profile's true pitch (70px) entirely,
     * confirmed empirically (search artificially capped at the window edge, same wrong
     * answer as the original bug). Bounding by profile width instead — the dimension
     * actually being searched — has no equivalent failure mode.
     */
    static final float MIN_LANE_PITCH_FRACTION = 0.02f;

    /** Absolute floor under {@link #MIN_LANE_PITCH_FRACTION}, for narrow profiles where the
     * fractional bound alone would allow an implausibly small lag. */
    static final int MIN_LANE_PITCH_FLOOR = 5;

    /** Morlet wavelet nondimensional frequency; 6 satisfies the admissibility condition
     * (zero mean), per the source paper and standard wavelet-analysis convention. */
    static final double MORLET_OMEGA0 = 6.0;

    /** Truncate the Morlet kernel's Gaussian envelope at this many "standard deviations"
     * (scale units) — negligible energy beyond this, keeps convolution cost bounded. */
    static final float MORLET_KERNEL_SIGMA_MULTIPLE = 4f;

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

    /**
     * Profiles wider than this are box-average-downsampled before pitch estimation and CWT
     * evaluation run (boundaries are rescaled back to original-pixel space afterwards).
     * Originally a performance mitigation for {@code cwtAtScale}'s kernel half-width, which
     * scales with the scale value itself (formerly derived from image height only, <em>not</em>
     * width — see class javadoc, "Scale selection") — on a real 1537px-wide plate image this
     * reached a ~2000px kernel half-width, i.e. a kernel wider than the image, driving what
     * was then a 60-scale sweep to ~5 seconds (measured; unacceptable for a synchronous
     * interactive Step 5 commit). Still worth keeping now that {@link #detect} evaluates the
     * CWT at only a single scale (see "Scale selection" in the class javadoc) — pitch
     * estimation's own autocorrelation is {@code O(width * maxLag)}, and downsampling keeps
     * that cheap too. Lane-boundary precision doesn't need original-pixel resolution —
     * spot-to-lane grouping is the only consumer.
     */
    static final int MAX_PROFILE_WIDTH = 500;

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

        float[] fullProfile = computeProfile(corrected, originYFraction);

        int downsampleFactor = Math.max(1, Math.round(width / (float) MAX_PROFILE_WIDTH));
        float[] profile = downsampleFactor > 1
                ? downsampleProfile(fullProfile, downsampleFactor) : fullProfile;
        int workingWidth = profile.length;

        int minLag = Math.max(MIN_LANE_PITCH_FLOOR, Math.round(workingWidth * MIN_LANE_PITCH_FRACTION));
        int maxLag = Math.max(minLag + 1, workingWidth / 2);

        float pitch = estimateLanePitch(profile, minLag, maxLag);

        float[] real = new float[workingWidth];
        float[] imag = new float[workingWidth];
        cwtAtScale(profile, pitch, real, imag);
        float[] smoothed = real;

        float maxSmoothed = 0f;
        for (float v : smoothed) maxSmoothed = Math.max(maxSmoothed, v);
        float h = H_MAXIMA_FRACTION * maxSmoothed;

        float[] hmax = hMaxima(smoothed, h);
        float[] hmin = hMinima(smoothed, h);

        List<Region> candidates = candidateLaneRegions(smoothed, hmax, hmin);
        List<Region> validated = removeFalseLanes(candidates, smoothed, workingWidth);
        List<Region> recovered = recoverSubtleLanes(validated, smoothed, workingWidth);

        List<Region> rescaled = rescaleRegions(recovered, downsampleFactor, width);
        return finalizeBoundaries(rescaled, width);
    }

    /** Box-average downsampling: bins {@code factor} consecutive samples into their mean.
     * See {@link #MAX_PROFILE_WIDTH} for why. */
    static float[] downsampleProfile(float[] profile, int factor) {
        int n = profile.length;
        int outLen = (n + factor - 1) / factor;
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            int start = i * factor;
            int end = Math.min(n, start + factor);
            double sum = 0;
            for (int j = start; j < end; j++) sum += profile[j];
            out[i] = (float) (sum / (end - start));
        }
        return out;
    }

    /** Rescales region indices from downsampled-profile space back to original-pixel
     * space. No-op (returns {@code regions} unchanged) when {@code factor <= 1}. */
    static List<Region> rescaleRegions(List<Region> regions, int factor, int originalWidth) {
        if (factor <= 1) return regions;
        List<Region> out = new ArrayList<>();
        for (Region r : regions) {
            int left = Math.min(originalWidth - 1, r.left * factor);
            int right = Math.min(originalWidth - 1, r.right * factor + factor - 1);
            out.add(new Region(left, right));
        }
        return out;
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
     * Estimates the profile's dominant lane pitch (center-to-center spacing) directly from
     * its own normalized autocorrelation, replacing the source paper's fixed scale-window
     * search — see class javadoc, "Scale selection", for why. Standard period-estimation
     * technique: the autocorrelation of a periodic-ish signal is dominated near lag 0 by the
     * signal's own smoothness/bump-width (not periodicity), so the first local minimum is
     * skipped before searching for the strongest remaining peak — otherwise the trivial
     * near-zero-lag "self-similarity" value wins over the true period, which is exactly what
     * an earlier, simpler version of this method got wrong (confirmed empirically: without
     * skipping the first trough, a profile with one lane missing out of six picked lag=10,
     * the Gaussian bump's own width, instead of the true pitch of 70).
     *
     * <p>Verified empirically against every regime that broke the paper's own scale-window
     * approach: exact pitch recovered for regular and irregular lane spacing, at both small
     * (unit-test) and realistic (real-photo) image dimensions, and — the case motivating
     * this whole class — with one or even two adjacent lanes entirely missing (recovered
     * pitch still exact, since enough same-pitch peak pairs remain among the occupied lanes).
     *
     * @param profile the (possibly downsampled) intensity profile
     * @param minLag  smallest plausible pitch, in profile-index units (see
     *                {@link #MIN_LANE_PITCH_FRACTION})
     * @param maxLag  largest plausible pitch, in profile-index units — should be capped to
     *                around half the profile length, since a pitch estimate needs at least
     *                two repetitions to mean anything
     * @return the estimated pitch, in profile-index units
     */
    static float estimateLanePitch(float[] profile, int minLag, int maxLag) {
        int n = profile.length;
        maxLag = Math.min(maxLag, n - 1);
        minLag = Math.max(1, Math.min(minLag, maxLag - 1));

        double mean = 0;
        for (float v : profile) mean += v;
        mean /= n;
        double var = 0;
        for (float v : profile) var += (v - mean) * (v - mean);

        float[] score = new float[maxLag + 1];
        for (int lag = 1; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < n; i++) sum += (profile[i] - mean) * (profile[i + lag] - mean);
            score[lag] = var > 0 ? (float) (sum / var) : 0f;
        }

        int firstTrough = 1;
        for (int lag = 2; lag <= maxLag; lag++) {
            if (score[lag] > score[lag - 1]) { firstTrough = lag - 1; break; }
            firstTrough = lag;
        }
        int searchStart = Math.max(firstTrough, minLag);

        int bestLag = searchStart;
        for (int lag = searchStart; lag <= maxLag; lag++) {
            if (score[lag] > score[bestLag]) bestLag = lag;
        }
        return bestLag;
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
