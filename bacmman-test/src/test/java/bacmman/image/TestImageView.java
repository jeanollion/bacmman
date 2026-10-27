package bacmman.image;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestImageView {
    @Test
    public void TestImageView() {
        ImageByte im = new ImageByte("", 4, 5, 6);
        im.setPixel(2, 3, 4, 1);
        im.setPixel(3, 3, 4, 2);
        im.setPixel(2, 2, 4, 3);
        im.setPixel(3, 2, 3, 4);
        im.setPixel(2, 2, 3, 5);

        SimpleBoundingBox bds = new SimpleBoundingBox(2, 3, 3, 4, 0, 5);
        Image dup = im.crop(bds);
        ImageView view = new ImageView(im, bds);
        compareImages(dup, view, 1e-7);
    }

    @Test
    public void testStreamPlaneWithMask() {
        ImageFloat im = new ImageFloat("", 10, 8, 3);
        BoundingBox.loop(new SimpleBoundingBox(im).resetOffset(), (x, y, z) -> im.setPixel(x, y, z, 1 + x + 10 * y + 100 * z));
        SimpleBoundingBox bds = new SimpleBoundingBox(2, 6, 1, 5, 1, 2);
        Image crop = im.crop(bds);
        ImageView view = new ImageView(im, bds);
        // mask with absolute offset, partially outside the view
        BlankMask absMask = new BlankMask(4, 4, 1, 4, 3, 1, 1, 1);
        // mask relative to the view
        BlankMask relMask = new BlankMask(3, 2, 1, 1, 2, 1, 1, 1);
        for (int z = 0; z < view.sizeZ(); ++z) {
            assertArrayEquals(crop.streamPlane(z).sorted().toArray(), view.streamPlane(z).sorted().toArray(), 1e-7);
            assertArrayEquals(crop.streamPlane(z, absMask, true).sorted().toArray(), view.streamPlane(z, absMask, true).sorted().toArray(), 1e-7);
            assertArrayEquals(crop.streamPlane(z, relMask, false).sorted().toArray(), view.streamPlane(z, relMask, false).sorted().toArray(), 1e-7);
        }
    }
    @Test
    public void testGetZPlane() {
        ImageFloat im = new ImageFloat("", 10, 8, 4);
        BoundingBox.loop(new SimpleBoundingBox(im).resetOffset(), (x, y, z) -> im.setPixel(x, y, z, 1 + x + 10 * y + 100 * z));
        im.translate(1, 2, 3); // source with non-null offset
        SimpleBoundingBox bds = new SimpleBoundingBox(3, 7, 4, 8, 4, 6); // absolute coordinates, z offset relative to source: 1
        Image crop = im.cropWithOffset(bds);
        ImageView view = new ImageView(im, bds);
        compareImages(crop, view, 1e-7);
        for (int z = 0; z < view.sizeZ(); ++z) {
            // relative landmark: plane index within the view
            Image expected = crop.getZPlane(z);
            Image plane = view.getZPlane(z);
            assertTrue("relative plane "+z+" bounds", expected.sameBounds(plane));
            compareImages(expected, plane, 1e-7);
            // absolute landmark: z coordinate including the offset
            int zAbs = z + view.zMin();
            Image planeAbs = view.getZPlaneWithOffset(zAbs);
            assertTrue("absolute plane "+zAbs+" bounds", crop.getZPlaneWithOffset(zAbs).sameBounds(planeAbs));
            compareImages(crop.getZPlaneWithOffset(zAbs), planeAbs, 1e-7);
        }
    }

    public static void compareImages(Image expected, Image actual, double delta) {
        BoundingBox.loop(expected, (x, y, z) -> {
            assertEquals("getPixelWithOffset differ at "+x+";"+y+"z", expected.getPixelWithOffset(x, y, z), actual.getPixelWithOffset(x, y, z), delta);
        });
        BoundingBox.loop(new SimpleBoundingBox(expected).resetOffset(), (x, y, z) -> {
            assertEquals("getPixelWithOffset differ at "+x+";"+y+"z", expected.getPixel(x, y, z), actual.getPixel(x, y, z), delta);
        });
    }
}
