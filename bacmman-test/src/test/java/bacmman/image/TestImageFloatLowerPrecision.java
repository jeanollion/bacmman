package bacmman.image;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

import static org.junit.Assert.assertEquals;

public class TestImageFloatLowerPrecision {
    static Logger logger = LoggerFactory.getLogger(TestImageFloatLowerPrecision.class);
    @Test
    public void TestImageFloat16() {
        double precisionFactor = 1./2500;
        ImageFloat source = new ImageFloat("", 100, 100, 100);
        ImageFloat source2 = source.duplicate();
        Random r = new Random();
        double[] maxValues = new double[]{1, 10, 100, 1000, 10000};
        for (double max : maxValues) {
            BoundingBox.loop(source, (x, y, z) -> {
                source.setPixel(x, y, z, ( r.nextDouble() * 2 - 1 ) * max ) ;
                source2.setPixel(x, y, z, source.getPixel(x, y, z) * 2 ) ;
            });
            ImageFloat16 target = TypeConverter.toHalfFloat(source, null);
            compareImages(source, target, max * precisionFactor, "test with max="+max);

            ImageFloat16 target2 = TypeConverter.toHalfFloat(source2, null);
            BoundingBox.loop(target2, (x, y, z) -> {
                target2.addPixel(x, y, z, -source.getPixel(x, y, z) ) ;
            });
            compareImages(source, target2, max * precisionFactor * 4, "after remove test with max="+max);
        }
    }

    @Test
    public void TestImageFloat16Scale() {
        double precisionFactor = 1. / 50000;
        ImageFloat source = new ImageFloat("", 100, 100, 100);
        ImageFloat source2 = source.duplicate();
        Random r = new Random();
        double[] maxValues = new double[]{1, 10, 100, 1000, 10000};
        for (double max : maxValues) {
            BoundingBox.loop(source, (x, y, z) -> {
                source.setPixel(x, y, z, ( r.nextDouble() * 2 - 1 ) * max ) ;
                source2.setPixel(x, y, z, source.getPixel(x, y, z) * 2 ) ;
            });
            ImageFloat16Scale target = TypeConverter.toFloat16(source, null);
            logger.debug("ImageFloat16 : scale: {} minmax: {}", target.getScale(), source.getMinAndMax(null));
            compareImages(source, target, max * precisionFactor, "test with max="+max);

            ImageFloat16Scale target2 = TypeConverter.toFloat16(source2, null);
            BoundingBox.loop(target2, (x, y, z) -> {
                target2.addPixel(x, y, z, -source.getPixel(x, y, z) ) ;
            });
            compareImages(source, target2, max * precisionFactor * 4, "after remove test with max="+max);
        }
    }

    @Test
    public void TestImageFloat8() {
        ImageFloat source = new ImageFloat("", 100, 100, 100);
        Random r = new Random();
        double[] maxValues = new double[]{1, 10, 100, 1000, 10000};
        for (double max : maxValues) {
            BoundingBox.loop(source, (x, y, z) -> {
                source.setPixel(x, y, z, ( r.nextDouble() * 2 - 1 ) * max ) ;
            });
            ImageFloat8Scale target = TypeConverter.toFloat8(source, null);
            logger.debug("ImageFloat8 : scale: {} minmax: {}", target.getScale(), source.getMinAndMax(null));
            compareImages(source, target, max/250, "test with max="+max);
        }
    }

    @Test
    public void TestImageFloatU8() {
        ImageFloat source = new ImageFloat("", 100, 100, 100);
        Random r = new Random();
        double[] maxValues = new double[]{1, 10, 100, 1000, 10000};
        for (double max : maxValues) {
            BoundingBox.loop(source, (x, y, z) -> {
                source.setPixel(x, y, z, r.nextDouble() * max ) ;
            });
            ImageFloatU8Scale target = TypeConverter.toFloatU8(source, null);
            compareImages(source, target, max/500, "test with max="+max);
        }
    }

    public static void compareImages(Image expected, Image actual, double delta, String message) {
        BoundingBox.loop(new SimpleBoundingBox(expected).resetOffset(), (x, y, z) -> {
            assertEquals(message+" getPixel differ at "+x+";"+y+";"+z, expected.getPixel(x, y, z), actual.getPixel(x, y, z), delta);
        });
    }
}
