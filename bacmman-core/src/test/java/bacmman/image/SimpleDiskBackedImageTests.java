package bacmman.image;

import bacmman.core.DiskBackedImageManager;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;


public class SimpleDiskBackedImageTests {
    static Logger logger = LoggerFactory.getLogger(SimpleDiskBackedImageTests.class);
    static DiskBackedImageManager manager;
    @BeforeClass
    public static void setUp() throws IOException {
        logger.debug("init ");
        manager = new DiskBackedImageManager(DiskBackedImageManager.getDefaultTempDir().toString());
    }

    @AfterClass
    public static void clear() {
        logger.debug("clearing");
        manager.clear(true);
    }

    @Test
    public void testDaemon() throws IOException, InterruptedException {
        Image im = new ImageByte("", 100, 100, 100);
        DiskBackedImageManager manager = new DiskBackedImageManager(DiskBackedImageManager.getDefaultTempDir().toString());
        SimpleDiskBackedImage dbIm = manager.createSimpleDiskBackedImage(im, false, false);
        manager.startDaemon(1, 1);
        Thread.sleep(100);
        assertTrue("image not freed by daemon", !dbIm.isOpen());
        manager.stopDaemon();
        manager.clear(true);
    }

    @Test
    public void testImageByte() throws IOException {
        testImage(new ImageByte("", 4, 5, 6));
    }

    @Test
    public void testImageFloatU8Scale() throws IOException {
        testImage(new ImageFloatU8Scale("", 4, 5, 6, 2));
    }

    @Test
    public void testImageFloat8Scale() throws IOException {
        testImage(new ImageFloat8Scale("", 4, 5, 6, 2));
    }

    @Test
    public void testImageShort() throws IOException {
        testImage(new ImageShort("", 4, 5, 6));
    }

    @Test
    public void testImageHalfFloat() throws IOException {
        testImage(new ImageFloat16("", 4, 5, 6));
    }

    @Test
    public void testImageHalfFloatScale() throws IOException {
        testImage(new ImageFloat16Scale("", 4, 5, 6, 2));
    }

    @Test
    public void testImageFloat() throws IOException {
        testImage(new ImageFloat("", 4, 5, 6));
    }

    @Test
    public void testImageInt() throws IOException {
        testImage(new ImageInt("", 4, 5, 6));
    }
    @Test
    public void testImageDouble() throws IOException {
        testImage(new ImageDouble("", 4, 5, 6));
    }

    protected <T extends Image<T>> void testImage(T image) throws IOException {
        image.setPixel(2, 3, 4, 1);
        image.setPixel(3, 3, 4, 2);
        image.setPixel(2, 2, 4, 3);
        image.setPixel(3, 2, 3, 4);
        image.setPixel(2, 2, 3, 5);


        T original = image.duplicate();
        SimpleDiskBackedImage<T> dbImage = manager.createSimpleDiskBackedImage(image, true, true);
        assertTrue("image not closed", !dbImage.isOpen());
        assertImageEquals("images differ", original, dbImage);
        assertTrue("image not open", dbImage.isOpen());

        // test modification
        dbImage.addPixel(0, 1, 2, 1);
        original = dbImage.getImage().duplicate();
        dbImage.freeMemory(true);
        assertTrue("image not closed", !dbImage.isOpen());
        assertImageEquals("image modification not stored", original, dbImage);
    }

    protected static void assertImageEquals(String message, Image i1, Image i2) {
        assertEquals(message + "(sizeZ)", i1.sizeZ(), i2.sizeZ());
        assertEquals(message + "(byte count)", i1.byteCount(), i2.byteCount());
        assertEquals(message + "(float type)", i1.floatingPoint(), i2.floatingPoint());
        switch (i1.byteCount()) {
            case 1:
                for (int z = 0; z < i1.sizeZ(); ++z) assertArrayEquals(message, (byte[])i1.getPixelArray()[z], (byte[])i2.getPixelArray()[z]);
                return;
            case 2:
                for (int z = 0; z < i1.sizeZ(); ++z) assertArrayEquals(message, (short[])i1.getPixelArray()[z], (short[])i2.getPixelArray()[z]);
                return;
            case 4:
                if (i1 instanceof ImageFloatingPoint) {
                    for (int z = 0; z < i1.sizeZ(); ++z) assertArrayEquals(message, (float[])i1.getPixelArray()[z], (float[])i2.getPixelArray()[z], 1e-6f);
                    return;
                } else {
                    for (int z = 0; z < i1.sizeZ(); ++z) assertArrayEquals(message, (int[])i1.getPixelArray()[z], (int[])i2.getPixelArray()[z]);
                    return;
                }
            case 8:
                if (i1 instanceof ImageFloatingPoint) {
                    for (int z = 0; z < i1.sizeZ(); ++z) assertArrayEquals(message, (double[])i1.getPixelArray()[z], (double[])i2.getPixelArray()[z], 1e-6d);
                    return;
                } else {
                    for (int z = 0; z < i1.sizeZ(); ++z) assertArrayEquals(message, (long[])i1.getPixelArray()[z], (long[])i2.getPixelArray()[z]);
                    return;
                }
        }

    }
}
