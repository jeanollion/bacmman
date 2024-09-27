/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.processing;

import bacmman.test_utils.TestUtils;
import static bacmman.test_utils.TestUtils.logger;
import ij.ImagePlus;
import ij.Prefs;
import bacmman.image.MutableBoundingBox;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.io.ImageIOCoordinates;
import bacmman.image.io.ImageReaderFile;
import bacmman.image.ImageShort;
import org.junit.Test;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;

/**
 *
 * @author Jean Ollion
 */
public class TestFilters {
    public static void main(String[] args) {
        new TestFilters().testMedian();
    }

    @Test
    public void testMean() {
        //logger.info("byte value: 61.4 {} 61.5 {} 61.6 {} 255 {}, 255.5 {}, 256 {}", new Float(61.4).byteValue(), new Float(61.5).byteValue()&0xff, new Float(61.6).byteValue()&0xff, new Float(255).byteValue()&0xff, new Float(255.5).byteValue()&0xff, new Float(256).byteValue()&0xff);
        Neighborhood n = new EllipsoidalNeighborhood(7, 4, false);
        Image test = TestUtils.generateRandomImage(20, 20, 20, new ImageByte("", 0, 0, 0));
        long t1 = System.currentTimeMillis();
        Prefs.setThreads(1);
        Image resIJ = IJImageWrapper.wrap(new ImagePlus("", ij.plugin.Filters3D.filter(IJImageWrapper.getImagePlus(test).getImageStack(), ij.plugin.Filters3D.MEAN, 7, 7, 4)));
        long t2 = System.currentTimeMillis();
        Image res = Filters.mean(test, test, n, false);
        long t3 = System.currentTimeMillis();
        logger.info("processing time IJ: {} lib: {}", (t2-t1), t3-t2);
        TestUtils.assertImage(resIJ, res, 0);
        
        test = TestUtils.generateRandomImage(20, 20, 20, new ImageShort("", 0, 0, 0));
        resIJ = IJImageWrapper.wrap(new ImagePlus("", ij.plugin.Filters3D.filter(IJImageWrapper.getImagePlus(test).getImageStack(), ij.plugin.Filters3D.MEAN, 7, 7, 4)));
        res = Filters.mean(test, test, n, false);
        TestUtils.assertImage(resIJ, res, 0);
        
        test = TestUtils.generateRandomImage(20, 20, 20, new ImageFloat("", 0, 0, 0));
        resIJ = IJImageWrapper.wrap(new ImagePlus("", ij.plugin.Filters3D.filter(IJImageWrapper.getImagePlus(test).getImageStack(), ij.plugin.Filters3D.MEAN, 7, 7, 4)));
        res = Filters.mean(test, test, n, false);
        TestUtils.assertImage(resIJ, res, 0);
        
        
        // test 2D
        n = new EllipsoidalNeighborhood(1, false);
        test = TestUtils.generateRandomImage(100, 100, 1, new ImageByte("", 0, 0, 0));
        resIJ=test.duplicate("res IJ");
        t1 = System.currentTimeMillis();
        new ij.plugin.filter.RankFilters().rank(IJImageWrapper.getImagePlus(resIJ).getProcessor(), 0.5, ij.plugin.filter.RankFilters.MEAN);
        t2 = System.currentTimeMillis();
        res = Filters.mean(test, test, n, false);
        t3 = System.currentTimeMillis();
        logger.info("2D processing time IJ: {} lib: {}", (t2-t1), t3-t2);
        //Utils.assertImage(resIJ, res, 0); 
        // values and kernels are diferents in imageJ!!
    }
    
    @Test
    public void testMedian() {
        Neighborhood n = new EllipsoidalNeighborhood(6, 6, false);
        Image test = TestUtils.generateRandomImage(20, 20, 20, new ImageByte("", 0, 0, 0));
        long t1 = System.currentTimeMillis();
        Prefs.setThreads(1);
        Image resIJ = IJImageWrapper.wrap(new ImagePlus("", ij.plugin.Filters3D.filter(IJImageWrapper.getImagePlus(test).getImageStack(), ij.plugin.Filters3D.MEDIAN, 6,6,6)));
        long t2 = System.currentTimeMillis();
        Image res = Filters.median(test, test, n, false);
        long t3 = System.currentTimeMillis();
        Image resPar = Filters.median(test, test, n, true);
        long t4 = System.currentTimeMillis();
        logger.info("processing time IJ: {} lib: {}, parallele: {}", (t2-t1), t3-t2, t4-t3);
        
        TestUtils.assertImage(resIJ, res, 0);
        TestUtils.assertImage(res, resPar, 0);
        
        test = TestUtils.generateRandomImage(20, 20, 20, new ImageShort("", 0, 0, 0));
        resIJ = IJImageWrapper.wrap(new ImagePlus("", ij.plugin.Filters3D.filter(IJImageWrapper.getImagePlus(test).getImageStack(), ij.plugin.Filters3D.MEDIAN, 6,6,6)));
        res = Filters.median(test, test, n, false);
        TestUtils.assertImage(resIJ, res, 0);
        
        test = TestUtils.generateRandomImage(20, 20, 20, new ImageFloat("", 0, 0, 0));
        resIJ = IJImageWrapper.wrap(new ImagePlus("", ij.plugin.Filters3D.filter(IJImageWrapper.getImagePlus(test).getImageStack(), ij.plugin.Filters3D.MEDIAN, 6,6,6)));
        res = Filters.median(test, test, n, false);
        TestUtils.assertImage(resIJ, res, 0);
    }
}
