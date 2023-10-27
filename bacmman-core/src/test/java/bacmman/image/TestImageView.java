package bacmman.image;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
        compareImages(dup, view);
    }
    public static void compareImages(Image expected, Image actual) {
        BoundingBox.loop(expected, (x, y, z) -> {
            assertEquals("getPixelWithOffset differ at "+x+";"+y+"z", expected.getPixelWithOffset(x, y, z), actual.getPixelWithOffset(x, y, z), 1e-6);
        });
        BoundingBox.loop(new SimpleBoundingBox(expected).resetOffset(), (x, y, z) -> {
            assertEquals("getPixelWithOffset differ at "+x+";"+y+"z", expected.getPixel(x, y, z), actual.getPixel(x, y, z), 1e-6);
        });
    }
}
