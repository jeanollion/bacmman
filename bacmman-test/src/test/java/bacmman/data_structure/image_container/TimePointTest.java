package bacmman.data_structure.image_container;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Time points of the different import modes, from the frame interval of ImageJ TIF files (no acquisition time in these files)
 */
public class TimePointTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    String saveTif(File dir, String name, int channels, int frames, double frameInterval) {
        ImagePlus imp = IJ.createImage(name, "16-bit black", 16, 16, channels, 1, frames);
        if (frameInterval > 0) {
            imp.getCalibration().frameInterval = frameInterval;
            imp.getCalibration().setTimeUnit("sec");
        }
        String path = new File(dir, name).getAbsolutePath();
        assertTrue(new FileSaver(imp).saveAsTiff(path));
        return path;
    }

    @Test
    public void singleFile() {
        String path = saveTif(folder.getRoot(), "multi.tif", 2, 3, 60);
        MultipleImageContainerSingleFile c = new MultipleImageContainerSingleFile("pos", path, 0, 3, 2, 1, 1, 1, false);
        for (int ch = 0; ch < 2; ++ch) {
            for (int t = 0; t < 3; ++t) assertEquals("c=" + ch + " t=" + t, 60 * t, c.getCalibratedTimePoint(t, ch, 0), 1e-6);
        }
    }

    @Test
    public void singleFileWithoutTime() {
        String path = saveTif(folder.getRoot(), "multi.tif", 1, 3, 0);
        MultipleImageContainerSingleFile c = new MultipleImageContainerSingleFile("pos", path, 0, 3, 1, 1, 1, 1, false);
        assertTrue(Double.isNaN(c.getCalibratedTimePoint(1, 0, 0)));
    }

    @Test
    public void fileByChannel() {
        String c0 = saveTif(folder.getRoot(), "c0.tif", 1, 3, 60);
        String c1 = saveTif(folder.getRoot(), "c1.tif", 1, 3, 60);
        MultipleImageContainerChannelSerie c = new MultipleImageContainerChannelSerie("pos", new String[]{c0, c1}, new int[]{0, 0}, new int[]{1, 1}, 3, new boolean[2], new int[]{1, 1}, 1, 1, false, new boolean[2], null);
        for (int ch = 0; ch < 2; ++ch) {
            for (int t = 0; t < 3; ++t) assertEquals("c=" + ch + " t=" + t, 60 * t, c.getCalibratedTimePoint(t, ch, 0), 1e-6);
        }
    }

    @Test
    public void fileByChannelInterleaved() { // channels alternate along the frame axis of the file: frame of channel c at time t is 2t + c
        String path = saveTif(folder.getRoot(), "interleaved.tif", 1, 6, 30);
        MultipleImageContainerChannelSerie c = new MultipleImageContainerChannelSerie("pos", new String[]{path, path}, new int[]{0, 1}, new int[]{2, 2}, 3, new boolean[2], new int[]{1, 1}, 1, 1, false, new boolean[2], null);
        for (int ch = 0; ch < 2; ++ch) {
            for (int t = 0; t < 3; ++t) assertEquals("c=" + ch + " t=" + t, 30 * (2 * t + ch), c.getCalibratedTimePoint(t, ch, 0), 1e-6);
        }
    }

    @Test
    public void fileByPositionChannelFrame() throws IOException {
        File dir = folder.newFolder("frames");
        for (int f = 1; f <= 3; ++f) {
            for (String ch : new String[]{"BF", "GFP"}) saveTif(dir, "exp_" + ch + "_s1_t" + f + ".tif", 1, 1, 120);
        }
        MultipleImageContainerPositionChannelFrame c = new MultipleImageContainerPositionChannelFrame(dir.getAbsolutePath(), "tif", "_s1", "_t", new String[]{"BF", "GFP"}, null, 3, "s1");
        for (int ch = 0; ch < 2; ++ch) {
            for (int t = 0; t < 3; ++t) assertEquals("c=" + ch + " t=" + t, 120 * t, c.getCalibratedTimePoint(t, ch, 0), 1e-6);
        }
    }

    @Test
    public void fileByPositionChannelFrameWithoutTime() throws IOException {
        File dir = folder.newFolder("frames");
        for (int f = 1; f <= 2; ++f) saveTif(dir, "exp_BF_s1_t" + f + ".tif", 1, 1, 0);
        MultipleImageContainerPositionChannelFrame c = new MultipleImageContainerPositionChannelFrame(dir.getAbsolutePath(), "tif", "_s1", "_t", new String[]{"BF"}, null, 2, "s1");
        assertTrue(Double.isNaN(c.getCalibratedTimePoint(1, 0, 0)));
    }
}
