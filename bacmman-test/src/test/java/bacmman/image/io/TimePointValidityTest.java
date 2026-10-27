package bacmman.image.io;

import org.junit.Test;

import static org.junit.Assert.*;

public class TimePointValidityTest {
    static final int[][] TWO_CHANNELS_THREE_FRAMES = {{0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {1, 0, 0}, {1, 1, 0}, {1, 2, 0}};

    @Test
    public void increasingPerChannel() {
        assertTrue(ImageReaderFile.isValid(TWO_CHANNELS_THREE_FRAMES, new double[]{0, 60, 120, 1, 61, 121}));
    }

    @Test
    public void constant() { // e.g. time offsets set to 0 when not recorded
        assertFalse(ImageReaderFile.isValid(TWO_CHANNELS_THREE_FRAMES, new double[]{0, 0, 0, 0, 0, 0}));
    }

    @Test
    public void notIncreasing() { // e.g. two distinct values only
        assertFalse(ImageReaderFile.isValid(TWO_CHANNELS_THREE_FRAMES, new double[]{0, 0, 100, 0, 0, 100}));
        assertFalse(ImageReaderFile.isValid(TWO_CHANNELS_THREE_FRAMES, new double[]{0, 60, 30, 1, 61, 121}));
    }

    @Test
    public void missingOrSingleFrame() {
        assertFalse(ImageReaderFile.isValid(TWO_CHANNELS_THREE_FRAMES, new double[]{0, 60, Double.NaN, 1, 61, 121}));
        assertTrue(ImageReaderFile.isValid(new int[][]{{0, 0, 0}, {0, 0, 1}}, new double[]{5, 5})); // z-slices of one frame can share a time
    }
}
