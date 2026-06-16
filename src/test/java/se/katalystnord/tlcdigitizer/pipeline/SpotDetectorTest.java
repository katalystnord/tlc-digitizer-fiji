package se.katalystnord.tlcdigitizer.pipeline;

import ij.process.FloatProcessor;
import org.junit.Test;
import se.katalystnord.tlcdigitizer.model.Spot;

import java.util.List;

import static org.junit.Assert.*;

public class SpotDetectorTest {

    @Test
    public void threshold_aboveMean_returnsTrue() {
        float[] pixels = {1f, 2f, 3f, 4f}; // mean = 2.5
        boolean[] result = SpotDetector.threshold(pixels, 2.5f);
        assertFalse(result[0]);
        assertFalse(result[1]);
        assertTrue(result[2]);
        assertTrue(result[3]);
    }

    @Test
    public void computeMean_correctlyAverages() {
        float[] pixels = {0f, 10f, 20f, 30f};
        assertEquals(15.0f, SpotDetector.computeMean(pixels), 1e-5f);
    }

    @Test
    public void labelComponents_singleBlob_getsLabelOne() {
        // 5×5 image with a 3×3 blob in the centre
        int w = 5, h = 5;
        boolean[] binary = new boolean[w * h];
        binary[1 * w + 1] = true;
        binary[1 * w + 2] = true;
        binary[1 * w + 3] = true;
        binary[2 * w + 1] = true;
        binary[2 * w + 2] = true;
        binary[2 * w + 3] = true;
        binary[3 * w + 1] = true;
        binary[3 * w + 2] = true;
        binary[3 * w + 3] = true;

        int[] labels = SpotDetector.labelComponents(binary, w, h);
        // All blob pixels should have the same non-zero label
        int label = labels[1 * w + 1];
        assertTrue(label > 0);
        assertEquals(label, labels[2 * w + 2]);
        assertEquals(label, labels[3 * w + 3]);
        // Background pixels should be 0
        assertEquals(0, labels[0]);
    }

    @Test
    public void labelComponents_twoBlobs_getDifferentLabels() {
        int w = 10, h = 5;
        boolean[] binary = new boolean[w * h];
        // Blob 1: column 1-2
        binary[2 * w + 1] = true;
        binary[2 * w + 2] = true;
        // Blob 2: column 7-8
        binary[2 * w + 7] = true;
        binary[2 * w + 8] = true;

        int[] labels = SpotDetector.labelComponents(binary, w, h);
        int l1 = labels[2 * w + 1];
        int l2 = labels[2 * w + 7];
        assertTrue(l1 > 0);
        assertTrue(l2 > 0);
        assertNotEquals(l1, l2);
    }

    @Test
    public void detect_syntheticSpot_findsOneSpot() {
        // Create a 200×200 image with a single bright circular spot in the centre
        int w = 200, h = 200;
        float[] pixels = new float[w * h];
        float cx = 100, cy = 100, r = 20;
        float bgLevel = 50f;
        float spotLevel = 200f;

        // Fill with background below mean
        for (int i = 0; i < pixels.length; i++) pixels[i] = bgLevel * 0.5f;

        // Draw a circle brighter than mean
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r * r) {
                    pixels[y * w + x] = spotLevel;
                }
            }
        }

        FloatProcessor fp = new FloatProcessor(w, h, pixels, null);
        List<Spot> spots = SpotDetector.detect(fp);

        assertEquals("Should detect exactly one spot", 1, spots.size());

        Spot s = spots.get(0);
        assertEquals("Centroid X near 100", cx, s.centroidX, 5.0f);
        assertEquals("Centroid Y near 100", cy, s.centroidY, 5.0f);
    }

    @Test
    public void morphologicalOpen_removesSmallNoise() {
        int w = 10, h = 10;
        boolean[] binary = new boolean[w * h];
        // Large foreground region
        for (int y = 2; y < 8; y++)
            for (int x = 2; x < 8; x++)
                binary[y * w + x] = true;
        // Single isolated noise pixel
        binary[0] = true;

        boolean[] opened = SpotDetector.morphologicalOpen(binary, w, h, 2);

        // Noise pixel should be erased
        assertFalse("Isolated pixel should be removed by opening", opened[0]);
        // Core of large region should survive
        assertTrue("Centre of large region should survive", opened[5 * w + 5]);
    }
}
