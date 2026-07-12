package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TrainableClassifierTest {

    /** Sum of Gaussian bumps on a flat baseline — same helper pattern as LaneDetectorTest. */
    private static FloatProcessor gaussianBumpsImage(int size, float baseline, int[][] centers,
                                                      float[] amplitudes, float sigma) {
        float[] pixels = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double v = baseline;
                for (int i = 0; i < centers.length; i++) {
                    double dx = x - centers[i][0], dy = y - centers[i][1];
                    v += amplitudes[i] * Math.exp(-0.5 * (dx * dx + dy * dy) / (sigma * sigma));
                }
                pixels[y * size + x] = (float) v;
            }
        }
        return new FloatProcessor(size, size, pixels, null);
    }

    private static float meanIn(FloatProcessor fp, Rectangle r) {
        double sum = 0;
        int count = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                sum += fp.getf(x, y);
                count++;
            }
        }
        return (float) (sum / count);
    }

    // -------------------------------------------------------------------------

    @Test
    public void trainAndPredict_singleBrightBlob_highProbabilityAtBlobLowElsewhere() {
        int size = 120;
        FloatProcessor image = gaussianBumpsImage(size, 10f,
                new int[][]{{60, 60}}, new float[]{200f}, 12f);

        List<Rectangle> spotRegions = Arrays.asList(new Rectangle(52, 52, 16, 16));
        List<Rectangle> backgroundRegions = Arrays.asList(
                new Rectangle(10, 10, 16, 16), new Rectangle(94, 94, 16, 16));

        TrainableClassifier classifier = TrainableClassifier.train(image, spotRegions, backgroundRegions);
        FloatProcessor prob = classifier.predictSpotProbability(image);

        assertEquals(size, prob.getWidth());
        assertEquals(size, prob.getHeight());

        float atBlob = meanIn(prob, new Rectangle(52, 52, 16, 16));
        float atCorner = meanIn(prob, new Rectangle(10, 10, 16, 16));
        assertTrue("Blob center should score high spot-probability, was " + atBlob, atBlob > 0.7f);
        assertTrue("Background corner should score low spot-probability, was " + atCorner, atCorner < 0.3f);
    }

    @Test
    public void trainAndPredict_brightAndFaintBlobsBothLabeledSpot_bothRecognized() {
        // The whole point of this approach over mean-threshold detection: one classifier can
        // learn "spot" as a single concept spanning very different brightness levels, since it
        // isn't reasoning from a single global intensity threshold.
        int size = 150;
        FloatProcessor image = gaussianBumpsImage(size, 10f,
                new int[][]{{40, 40}, {110, 110}}, new float[]{200f, 40f}, 12f);

        List<Rectangle> spotRegions = Arrays.asList(
                new Rectangle(32, 32, 16, 16),   // bright blob
                new Rectangle(102, 102, 16, 16)); // faint blob
        List<Rectangle> backgroundRegions = Arrays.asList(
                new Rectangle(10, 100, 16, 16), new Rectangle(100, 10, 16, 16));

        TrainableClassifier classifier = TrainableClassifier.train(image, spotRegions, backgroundRegions);
        FloatProcessor prob = classifier.predictSpotProbability(image);

        float atBright = meanIn(prob, new Rectangle(32, 32, 16, 16));
        float atFaint = meanIn(prob, new Rectangle(102, 102, 16, 16));
        float atBackground = meanIn(prob, new Rectangle(10, 100, 16, 16));
        assertTrue("Bright blob should score high, was " + atBright, atBright > 0.7f);
        assertTrue("Faint blob should score high too, was " + atFaint, atFaint > 0.7f);
        assertTrue("Background should score low, was " + atBackground, atBackground < 0.3f);
    }

    // -------------------------------------------------------------------------
    // imbalanceRatio — regression guard for the class-imbalance bug found during the spike
    // -------------------------------------------------------------------------

    @Test
    public void imbalanceRatio_balancedRegions_lowRatio() {
        List<Rectangle> spot = Arrays.asList(new Rectangle(0, 0, 16, 16));
        List<Rectangle> background = Arrays.asList(new Rectangle(0, 0, 16, 16));
        assertEquals(1.0, TrainableClassifier.imbalanceRatio(spot, background), 0.001);
    }

    @Test
    public void imbalanceRatio_fullStripVsSmallBoxes_reproducesObservedImbalance() {
        // Mirrors the actual spike regression: 8 small 16x16 spot boxes vs. two full-width
        // background strips on a 1537x2297-sized image.
        List<Rectangle> spot = Arrays.asList(
                new Rectangle(0, 0, 16, 16), new Rectangle(0, 0, 16, 16),
                new Rectangle(0, 0, 16, 16), new Rectangle(0, 0, 16, 16),
                new Rectangle(0, 0, 16, 16), new Rectangle(0, 0, 16, 16),
                new Rectangle(0, 0, 16, 16), new Rectangle(0, 0, 16, 16));
        List<Rectangle> background = Arrays.asList(
                new Rectangle(0, 0, 1537, 380), new Rectangle(0, 0, 1537, 80));
        double ratio = TrainableClassifier.imbalanceRatio(spot, background);
        assertTrue("Expected a large imbalance ratio, was " + ratio, ratio > 100);
    }

    @Test
    public void imbalanceRatio_emptyRegionList_isNaN() {
        List<Rectangle> spot = Arrays.asList(new Rectangle(0, 0, 16, 16));
        List<Rectangle> background = Arrays.asList();
        assertTrue(Double.isNaN(TrainableClassifier.imbalanceRatio(spot, background)));
    }
}
