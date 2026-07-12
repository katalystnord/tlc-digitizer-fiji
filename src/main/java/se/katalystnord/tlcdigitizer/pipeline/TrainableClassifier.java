package se.katalystnord.tlcdigitizer.pipeline;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelRegions;
import net.imglib2.roi.labeling.LabelingType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.scijava.Context;
import sc.fiji.labkit.pixel_classification.classification.Segmenter;
import sc.fiji.labkit.pixel_classification.classification.Trainer;
import sc.fiji.labkit.pixel_classification.pixel_feature.filter.GroupedFeatures;
import sc.fiji.labkit.pixel_classification.pixel_feature.filter.SingleFeatures;
import sc.fiji.labkit.pixel_classification.pixel_feature.settings.ChannelSetting;
import sc.fiji.labkit.pixel_classification.pixel_feature.settings.FeatureSettings;
import sc.fiji.labkit.pixel_classification.pixel_feature.settings.GlobalSettings;
import sc.fiji.labkit.pixel_classification.utils.SingletonContext;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Trainable Random Forest pixel classification (Labkit's {@code labkit-pixel-classification}
 * library, itself built on Weka's {@code Trainable_Segmentation} — the same classifier engine as
 * classic Trainable Weka Segmentation, per CLAUDE.md's v1.5 roadmap "adaptive detection" item,
 * with a modern API and, notably, genuine programmatic (non-GUI) training/prediction).
 *
 * <p>Distinct from every other detection path in this codebase: {@link SpotDetector}'s
 * mean-threshold approach and the shape-aware/lane-detection extensions all reason from global
 * intensity or geometry. This classifier instead learns local pixel context (multi-scale Gaussian,
 * Hessian, and difference-of-Gaussians features) from a handful of user-labeled example regions —
 * "this is a spot," "this is background" — per image. It is the only detection path in this
 * project that does not need a single global threshold to separate faint and bright spots (see
 * class-level feasibility notes below).
 *
 * <h2>Feasibility spike (2026-07-12) — what this proves and what it doesn't</h2>
 * A hand-labeled feasibility test against a real plate photo (img_00451, 6 lanes, two long
 * tailing/streaking lanes that no threshold-based method in this codebase fully integrates — see
 * CLAUDE.md's shape-aware-detection writeup) found that with only ~13 small (16px) labeled boxes
 * (8 on known spot positions spanning both bright and faint spots, 5 on background), the trained
 * classifier's spot-probability map correctly highlighted <b>all 8 real spots, including the two
 * streaking lanes' full extent — cap and tail, not just the bright cap</b> that every
 * threshold-based method in this codebase (including shape-aware hysteresis-linking) has so far
 * only partially captured. This is Phase A (algorithm only, proven on hand-picked labels standing
 * in for interactive painting) — not yet wired into the UI (Phase B) and not run against the
 * MOESM2–4 validation fixtures.
 *
 * <h2>Real bug found and fixed during the spike: class imbalance destroys spot recall</h2>
 * An early iteration of the spike's labeling replaced 5 small background boxes (~8,000 labeled
 * pixels) with two full-width background strips (~580,000+ labeled pixels) to suppress false
 * positives on a hand-drawn ruler/text region. This introduced a ~300:1 background:spot labeled-
 * pixel ratio and visibly collapsed spot recall on the streaking lanes' fainter tails — only the
 * exact training box glowed at full confidence, with the rest of the true spot extent reading as
 * noise. Reverting to small, comparably-sized background boxes (matching the spot boxes' own
 * footprint) restored full-extent spot recall <em>and</em> suppressed the false positives
 * simultaneously. This is why {@link #train} logs a warning (see {@link #IMBALANCE_WARNING_RATIO})
 * when the two classes' labeled-pixel counts differ by more than an order of magnitude — a real,
 * reproducible failure mode, not a hypothetical one.
 *
 * <h2>Implementation notes for anyone extending this class</h2>
 * <ul>
 *   <li><b>Never write raw label-class integers directly into an {@link ImgLabeling}'s backing
 *   image</b> (e.g. via a {@code RandomAccess} before wrapping it). {@code ImgLabeling}'s backing
 *   {@code IntType} image must stay all-zero (its correct initial "empty label set" state) and be
 *   mutated only via {@code LabelingType.add(...)} on the labeling's own cursor — poking raw
 *   integer values in first corrupts {@code LabelingMapping}'s internal index table and throws
 *   {@code IndexOutOfBoundsException} (confirmed via a minimal repro during the spike; the
 *   "IndexOutOfBoundsException ... ArrayList.get" symptom is exactly this mistake).</li>
 *   <li><b>Do not add {@code net.imagej:imagej} (the full ImageJ2 application aggregator) as a
 *   dependency.</b> This class constructs a SciJava {@link Context} ({@link #train}), and if
 *   {@code net.imagej:imagej} (which pulls in {@code imagej-legacy}) is anywhere on the classpath,
 *   {@code Context}'s auto-discovery finds {@code net.imagej.legacy.LegacyService} and insists the
 *   ImageJ1/2 legacy patcher ({@code LegacyInjector.preinit()}) run before any {@code ij.*} class
 *   loads — which, with this project's specific {@code ij1-patcher} version (transitively pulled
 *   in via {@code Trainable_Segmentation}), throws {@code NoSuchFieldException: _hooks} no matter
 *   when {@code preinit()} is called (confirmed: same failure whether called from a static
 *   initializer or via the {@code -javaagent=init} flag {@code net.imagej}'s own error message
 *   recommends — an apparent incompatibility with this project's Java 21 + managed {@code ij}
 *   version, not a call-ordering mistake). This project's {@code net.imagej:imagej} dependency was
 *   already unused (nothing in this codebase imports {@code net.imagej.*} — everything is built on
 *   classic {@code ij.*}), so it was removed rather than worked around: without
 *   {@code imagej-legacy} on the classpath, {@code Context}'s discovery never finds
 *   {@code LegacyService} to instantiate, and training/prediction work with no patching step
 *   needed at all. If a future change reintroduces a real need for {@code net.imagej:imagej},
 *   re-test this class before assuming it still works.</li>
 * </ul>
 */
public final class TrainableClassifier {

    /** Label string for the positive ("spot") class, per Labkit's string-labeled regions. */
    private static final String SPOT_LABEL = "1";

    /** Label string for the negative ("background") class. */
    private static final String BACKGROUND_LABEL = "2";

    /**
     * Gaussian scales (pixels) used for multi-scale feature computation. An unvalidated starting
     * choice (spans a 16x range) — not yet tuned against real plate images at scale, same status
     * as other freshly-introduced parameters in this codebase (e.g. {@code H_MAXIMA_FRACTION} was
     * before its own validation round).
     */
    private static final List<Double> FEATURE_SIGMAS = Arrays.asList(1.0, 2.0, 4.0, 8.0, 16.0);

    /**
     * If the larger class's labeled-pixel count exceeds the smaller class's by more than this
     * multiple, {@link #train} logs a warning. See class javadoc for the concrete failure mode
     * this guards against.
     */
    private static final float IMBALANCE_WARNING_RATIO = 20f;

    private final Segmenter segmenter;

    private TrainableClassifier(Segmenter segmenter) {
        this.segmenter = segmenter;
    }

    /**
     * Trains a classifier from labeled example regions on a single image.
     *
     * @param image            the image to learn from (typically {@code AnalysisState.corrected}
     *                         or an equivalent perspective-/background-corrected image)
     * @param spotRegions      rectangles (image-pixel coordinates) containing known spot examples;
     *                         include both bright and faint examples if the plate has both — the
     *                         classifier learns one "spot" concept spanning whatever variety it
     *                         is shown
     * @param backgroundRegions rectangles containing known background examples (plain background,
     *                         annotation lines, ruler/text, etc. — anything that should not be
     *                         detected as a spot)
     * @return a trained classifier, ready for {@link #predictSpotProbability}
     */
    public static TrainableClassifier train(FloatProcessor image, List<Rectangle> spotRegions,
                                             List<Rectangle> backgroundRegions) {
        warnIfImbalanced(spotRegions, backgroundRegions);

        ImagePlus imp = new ImagePlus("tlc-digitizer-training", image);
        Img<FloatType> img = ImageJFunctions.convertFloat(imp);

        Img<IntType> labelImg = new ArrayImgFactory<>(new IntType()).create(img);
        ImgLabeling<String, IntType> labeling = new ImgLabeling<>(labelImg);
        Cursor<LabelingType<String>> cursor = labeling.cursor();
        int[] pos = new int[2];
        while (cursor.hasNext()) {
            LabelingType<String> lt = cursor.next();
            cursor.localize(pos);
            int x = pos[0], y = pos[1];
            if (containsPoint(spotRegions, x, y)) {
                lt.add(SPOT_LABEL);
            } else if (containsPoint(backgroundRegions, x, y)) {
                lt.add(BACKGROUND_LABEL);
            }
        }
        LabelRegions<String> regions = new LabelRegions<>(labeling);

        Context context = SingletonContext.getInstance();
        GlobalSettings globals = GlobalSettings.default2d()
                .channels(ChannelSetting.SINGLE)
                .dimensions(img.numDimensions())
                .sigmas(FEATURE_SIGMAS)
                .build();
        FeatureSettings featureSettings = new FeatureSettings(globals, SingleFeatures.identity(),
                GroupedFeatures.gauss(), GroupedFeatures.hessian(),
                GroupedFeatures.differenceOfGaussians());

        Segmenter segmenter = Trainer.train(context, img, regions, featureSettings);
        return new TrainableClassifier(segmenter);
    }

    /**
     * Runs the trained classifier on an image, returning the per-pixel probability of the "spot"
     * class as a {@link FloatProcessor} (same dimensions as the input), values in {@code [0, 1]}.
     *
     * @param image the image to classify — may be the same image used for {@link #train}, or a
     *              different one (e.g. re-running a plate-specific classifier is not supported by
     *              this method signature; train a new classifier per image)
     */
    public FloatProcessor predictSpotProbability(FloatProcessor image) {
        ImagePlus imp = new ImagePlus("tlc-digitizer-predict", image);
        Img<FloatType> img = ImageJFunctions.convertFloat(imp);

        RandomAccessibleInterval<? extends RealType<?>> probMap = segmenter.predict(img);
        int spotChannel = segmenter.classNames().indexOf(SPOT_LABEL);
        RandomAccessibleInterval<? extends RealType<?>> spotProb =
                Views.hyperSlice(probMap, probMap.numDimensions() - 1, spotChannel);

        int width = image.getWidth(), height = image.getHeight();
        float[] outPixels = new float[width * height];
        Cursor<? extends RealType<?>> pc = Views.flatIterable(spotProb).cursor();
        int idx = 0;
        while (pc.hasNext()) {
            outPixels[idx++] = (float) pc.next().getRealDouble();
        }
        return new FloatProcessor(width, height, outPixels, null);
    }

    private static boolean containsPoint(List<Rectangle> regions, int x, int y) {
        for (Rectangle r : regions) {
            if (r.contains(x, y)) return true;
        }
        return false;
    }

    private static long area(List<Rectangle> regions) {
        long total = 0;
        for (Rectangle r : regions) total += (long) r.width * r.height;
        return total;
    }

    /**
     * Ratio of the larger class's labeled-pixel count to the smaller's, or {@code NaN} if either
     * class has zero labeled pixels. Exposed package-private for testing; see class javadoc for
     * why this matters (a real, reproduced failure mode — not a hypothetical one).
     */
    static double imbalanceRatio(List<Rectangle> spotRegions, List<Rectangle> backgroundRegions) {
        long spotArea = area(spotRegions);
        long backgroundArea = area(backgroundRegions);
        if (spotArea == 0 || backgroundArea == 0) return Double.NaN;
        return Math.max(spotArea, backgroundArea) / (double) Math.min(spotArea, backgroundArea);
    }

    private static void warnIfImbalanced(List<Rectangle> spotRegions, List<Rectangle> backgroundRegions) {
        double ratio = imbalanceRatio(spotRegions, backgroundRegions);
        if (!Double.isNaN(ratio) && ratio > IMBALANCE_WARNING_RATIO) {
            IJ.log(String.format(
                "[TrainableClassifier] Warning: labeled-pixel counts are imbalanced %.0f:1 "
                    + "(spot=%d px, background=%d px). This has been observed to suppress spot "
                    + "recall on faint/tailing regions — prefer roughly balanced labeling per class.",
                ratio, area(spotRegions), area(backgroundRegions)));
        }
    }
}
