package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;

import java.util.Random;

/**
 * Builder for synthetic post-perspective-warp TLC plate images with known ground truth,
 * for regression-testing the real background-correction/detection/lane pipeline against
 * realistic defects (uneven illumination, streaking, ruler annotation, faint spots,
 * irregular lane occupancy) rather than the idealized flat-background hard-edged circles
 * {@code SpotDetectorTest}'s own {@code syntheticSpotImage} helper uses.
 *
 * <p>Deliberately smooth throughout: every feature (spots, streaks, illumination, ruler
 * ticks) is a 2D Gaussian or a sum/taper of Gaussians — no hard step edges anywhere except
 * per-pixel sensor noise ({@link #addNoise}), which is realistically sharp pixel-to-pixel by
 * construction. Real TLC spots have a diffusion-limited (Gaussian-like) intensity falloff,
 * not a binary disc, and a hard-edged synthetic spot doesn't exercise connected-component
 * thresholding / morphological opening the same way a real photo's soft edge does.
 *
 * <p>Produces images in "warped" pixel space — i.e. what {@link PerspectiveCorrection#warpImage}
 * would output, before {@link BackgroundCorrection}. Perspective correction itself already has
 * its own synthetic test coverage; this builder is deliberately scoped to the stages downstream
 * of it (background correction, spot detection, lane detection), which is where this project's
 * real interactive-testing bugs have actually occurred.
 */
public final class SyntheticPlate {

    private final int width, height;
    private final float[] pixels;

    public SyntheticPlate(int width, int height, float baseLevel) {
        this.width = width;
        this.height = height;
        this.pixels = new float[width * height];
        if (baseLevel != 0f) {
            for (int i = 0; i < pixels.length; i++) pixels[i] = baseLevel;
        }
    }

    /** Adds an isotropic 2D Gaussian spot centred at ({@code cx}, {@code cy}). */
    public SyntheticPlate addSpot(float cx, float cy, float sigma, float peak) {
        return addSpot(cx, cy, sigma, sigma, peak);
    }

    /** Adds an anisotropic 2D Gaussian spot (independent x/y sigma). */
    public SyntheticPlate addSpot(float cx, float cy, float sigmaX, float sigmaY, float peak) {
        // 4-sigma half-window: beyond this a Gaussian's contribution is negligible (<1e-7 of peak).
        int halfX = (int) Math.ceil(4 * sigmaX), halfY = (int) Math.ceil(4 * sigmaY);
        int x0 = Math.max(0, (int) cx - halfX), x1 = Math.min(width - 1, (int) cx + halfX);
        int y0 = Math.max(0, (int) cy - halfY), y1 = Math.min(height - 1, (int) cy + halfY);
        for (int y = y0; y <= y1; y++) {
            float dy = y - cy;
            float ey = (dy * dy) / (2 * sigmaY * sigmaY);
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx;
                float ex = (dx * dx) / (2 * sigmaX * sigmaX);
                pixels[y * width + x] += peak * (float) Math.exp(-(ex + ey));
            }
        }
        return this;
    }

    /**
     * Adds a vertically-elongated streak: a Gaussian cross-section (half-width
     * {@code sigmaX}) whose peak intensity tapers smoothly (raised-cosine ease, C1-continuous
     * -- no kink at either end) from {@code peakTop} at {@code yTop} to {@code peakBottom} at
     * {@code yBottom}. Monotonic by construction when {@code peakTop} and {@code peakBottom}
     * don't cross zero in between -- the "genuine single tailing compound, no internal valley"
     * case.
     */
    public SyntheticPlate addStreak(float cx, float yTop, float yBottom, float sigmaX,
                                     float peakTop, float peakBottom) {
        int halfX = (int) Math.ceil(4 * sigmaX);
        int x0 = Math.max(0, (int) cx - halfX), x1 = Math.min(width - 1, (int) cx + halfX);
        int yLo = Math.max(0, Math.round(Math.min(yTop, yBottom)));
        int yHi = Math.min(height - 1, Math.round(Math.max(yTop, yBottom)));
        float span = yBottom - yTop;
        for (int y = yLo; y <= yHi; y++) {
            float t = span == 0 ? 0f : (y - yTop) / span; // 0 at top, 1 at bottom
            // Raised-cosine ease: smooth, zero-derivative at both ends.
            float ease = 0.5f * (1f - (float) Math.cos(Math.PI * t));
            float peakAtY = peakTop + (peakBottom - peakTop) * ease;
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx;
                float ex = (dx * dx) / (2 * sigmaX * sigmaX);
                pixels[y * width + x] += peakAtY * (float) Math.exp(-ex);
            }
        }
        return this;
    }

    /** Adds a smooth, broad Gaussian illumination dome (a background-correction test defect). */
    public SyntheticPlate addIlluminationDome(float cx, float cy, float sigma, float peak) {
        return addSpot(cx, cy, sigma, peak);
    }

    /**
     * Adds a smooth horizontal annotation band (ruler/plate-mat margin) as a row of Gaussian
     * tick blobs plus a faint continuous baseline glow -- deliberately bright enough that,
     * absent the origin/front exclusion filter, each tick would threshold as its own
     * spot-like connected component after background correction.
     */
    public SyntheticPlate addRulerBand(float yCenter, float tickSigma, float tickPeak,
                                        int numTicks, float marginFraction) {
        float usableWidth = width * (1f - 2 * marginFraction);
        float startX = width * marginFraction;
        for (int i = 0; i < numTicks; i++) {
            float cx = startX + usableWidth * (i + 0.5f) / numTicks;
            addSpot(cx, yCenter, tickSigma, tickPeak);
        }
        return this;
    }

    /** Adds independent per-pixel Gaussian noise (realistically sharp pixel-to-pixel, unlike
     * every other deterministic feature this builder adds). */
    public SyntheticPlate addNoise(float stdDev, long seed) {
        Random rnd = new Random(seed);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] += (float) (rnd.nextGaussian() * stdDev);
        }
        return this;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public FloatProcessor build() {
        return new FloatProcessor(width, height, pixels.clone(), null);
    }
}
