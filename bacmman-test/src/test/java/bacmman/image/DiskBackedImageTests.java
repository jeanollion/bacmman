package bacmman.image;

import bacmman.data_structure.dao.DiskBackedImageManager;
import bacmman.data_structure.dao.DiskBackedImageManagerImpl;
import bacmman.processing.ImageOperations;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;

import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.*;


public class DiskBackedImageTests {
    static Logger logger = LoggerFactory.getLogger(DiskBackedImageTests.class);
    static DiskBackedImageManager manager;
    @BeforeClass
    public static void setUp() throws IOException {
        logger.debug("init ");
        manager = new DiskBackedImageManagerImpl(DiskBackedImageManagerImpl.getDefaultTempDir().toString());
    }

    @AfterClass
    public static void clear() {
        logger.debug("clearing");
        manager.clear(true);
    }

    @Test
    public void testDaemon() throws IOException, InterruptedException {
        Image im = new ImageByte("", 100, 100, 100);
        DiskBackedImageManager manager = new DiskBackedImageManagerImpl(DiskBackedImageManagerImpl.getDefaultTempDir().toString());
        DiskBackedImage dbIm = manager.createDiskBackedImage(im, false, false);
        manager.startDaemon(0, 1);
        Thread.sleep(200);
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

    @Test
    public void testTiledImageFloat() throws IOException {
        int thld = TiledDiskBackedImage.targetTileSize;
        ImageFloat original = new ImageFloat("", (int)(Math.sqrt(thld) * 3.5), (int)(Math.sqrt(thld) * 2.2), 4);
        Random r = new Random();
        BoundingBox.loop(original, (x, y, z) -> original.setPixel(x, y, z, r.nextDouble()));
        assertTrue("null value", original.stream().anyMatch(d -> d!=0));
        DiskBackedImage<ImageFloat> dbImage = manager.createDiskBackedImage(original, true, true);

        assertTrue("not tiled image", dbImage instanceof TiledDiskBackedImage);
        TiledDiskBackedImage<ImageFloat> tiledIm = ((TiledDiskBackedImage<ImageFloat>)dbImage);
        assertTrue("image not tiled", tiledIm.image == null && tiledIm.tilesZYX != null);
        logger.debug("tiled dims: {}", tiledIm.tileDimensions);
        assertImageEquals("images differ", original, dbImage);
        dbImage.freeMemory(true); // as the source image array is used the tiles are erased at this stage.
        assertTrue("image not tiled after free", tiledIm.image == null && tiledIm.tilesZYX != null);

        // test crop
        BoundingBox bds = new SimpleBoundingBox((int)Math.floor(0.4*original.sizeX), (int)Math.ceil(0.66*original.sizeX-1), (int)Math.floor(0.4*original.sizeY), (int)Math.ceil(0.66*original.sizeY-1), (int)Math.floor(0.33*original.sizeZ), (int)Math.ceil(0.66*original.sizeZ-1));
        //logger.debug("image bds: {} crop bds: {}", new SimpleBoundingBox(original), bds);
        ImageFloat origCrop = original.crop(bds);
        ImageFloat tiledCrop = dbImage.crop(bds);
        assertImageEquals("cropped images differ", origCrop, tiledCrop);
        assertTrue("tiled images not used by crio", tiledIm.image == null && tiledIm.tilesZYX != null);

        // test stream
        dbImage.freeMemory(true);
        double meanOrig = original.stream().average().getAsDouble();
        double meanDB = dbImage.stream().average().getAsDouble();
        assertEquals("images mean differ (im stream)", meanOrig, meanDB, 1e-6d);

        ImageMask mask = createMask(original);
        meanOrig = original.stream(mask, false).average().getAsDouble();
        meanDB = dbImage.stream(mask, false).average().getAsDouble();
        assertEquals("images mean differ (im stream mask)", meanOrig, meanDB, 1e-6d);
        mask.translate(original);
        meanOrig = original.stream(mask, true).average().getAsDouble();
        meanDB = dbImage.stream(mask, true).average().getAsDouble();
        assertEquals("images mean differ (im stream mask abs)", meanOrig, meanDB, 1e-6d);

        // test modification
        dbImage.freeMemory(true);
        dbImage.addPixel(10, 20, 1, 1);
        dbImage.freeMemory(true);
        assertEquals("modification not stored", 1d, dbImage.getPixel(10, 20, 1) - original.getPixel(10, 20, 1), 1e-6d);

    }

    protected <T extends Image<T>> void testImage(T image) throws IOException {
        image.setPixel(2, 3, 4, 1);
        image.setPixel(3, 3, 4, 2);
        image.setPixel(2, 2, 4, 3);
        image.setPixel(3, 2, 3, 4);
        image.setPixel(2, 2, 3, 5);


        T original = image.duplicate();
        DiskBackedImage<T> dbImage = manager.createDiskBackedImage(image, true, true);
        assertTrue("image not closed", !dbImage.isOpen());
        assertImageEquals("images differ", original, dbImage);
        assertTrue("image not open", dbImage.isOpen());

        // test modification
        dbImage.addPixel(0, 1, 2, 1);
        original = dbImage.getImage().duplicate();
        dbImage.freeMemory(true);
        assertTrue("image not closed", !dbImage.isOpen());
        assertImageEquals("image modification not stored", original, dbImage);
        assertImageEqualsStream("image modification not stored (stream)", original, dbImage);
        assertImageEqualsStreamMask("image modification not stored (stream mask)", original, dbImage);
    }

    static void assertImageEquals(String message, Image i1, Image i2) {
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

    static void assertImageEqualsStream(String message, Image i1, Image i2) {
        assertArrayEquals(message, i1.stream().toArray(), i2.stream().toArray(), 1e-6d);
    }

    static void assertImageEqualsStreamMask(String message, Image i1, Image i2) {
        ImageMask mask = createMask(i1);
        assertArrayEquals(message, i1.stream(mask, false).toArray(), i2.stream(mask, false).toArray(), 1e-6d);
        mask.translate(i1);
        assertArrayEquals(message, i1.stream(mask, true).toArray(), i2.stream(mask, true).toArray(), 1e-6d);
    }

    static ImageMask createMask(Image i1) {
        ImageByte mask = new ImageByte("", new SimpleImageProperties(new SimpleBoundingBox( i1.sizeX / 2, i1.sizeX, 0, i1.sizeY/3, 0, i1.sizeZ/2), i1.scaleXY, i1.scaleZ));
        ImageOperations.fill(mask, 1, null);
        return mask;
    }

}
