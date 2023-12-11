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
package bacmman.image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class TestStream {
    final static Logger logger = LoggerFactory.getLogger(TestStream.class);
    @Test
    public void testStream() {
        ImageFloat im = new ImageFloat("", 5, 4, 3);
        im.getPixelArray()[0][0] = 1;
        im.getPixelArray()[0][4] = 2; // x=4 y =0
        im.getPixelArray()[0][5] = 3; // x=1 // y=1
        im.getPixelArray()[1][5] = 4; // x=1 // y=1 // z=1
        double[] values = im.stream().sorted().toArray();
        double[] test = new double[im.sizeXYZ()];
        test[test.length-1] = 4;
        test[test.length-2] = 3;
        test[test.length-3] = 2;
        test[test.length-4] = 1;
        assertArrayEquals("stream", test, values, 0.0);
    }
    @Test
    public void testStreamWithMask() {
        ImageFloat im = new ImageFloat("", 5, 4, 3);
        im.translate(10, 11, 12);
        im.getPixelArray()[0][0] = 1;
        im.getPixelArray()[0][4] = 2; // x=4 y =0
        im.getPixelArray()[0][im.sizeX()*1+1] = 3; // x=1 // y=1
        im.getPixelArray()[2][im.sizeX()*1+3] = 4; // x=3 // y=1 // z=2
        double[] test = new double[im.sizeXYZ()];
        test[test.length-1] = 4;
        test[test.length-2] = 3;
        test[test.length-3] = 2;
        test[test.length-4] = 1;
        double[] values = im.stream(new BlankMask(im), true).sorted().toArray();
        assertArrayEquals("stream with blank mask, abs off", test, values, 0.0);
        values = im.stream(new BlankMask(im), false).sorted().toArray();
        assertArrayEquals("stream with blank mask, rel off", new double[0],  values, 0.0);
        logger.debug("test");
        values = im.stream(new BlankMask(im).resetOffset(), false).sorted().toArray();
        assertArrayEquals("stream with blank mask, no off, rel off", test, values, 0.0);
        values = im.stream(new BlankMask(im).resetOffset(), true).sorted().toArray();
        assertArrayEquals("stream with blank mask, no off, rel off", new double[0], values, 0.0);
        
        ImageByte mask = new ImageByte("", 1, 1, 1).translate(3, 1, 2);
        mask.getPixelArray()[0][0]=1;
        values = im.stream(mask, false).sorted().toArray();
        assertArrayEquals("stream mask, rel off", new double[]{4}, values, 0.0);
        values = im.stream(mask, true).sorted().toArray();
        assertArrayEquals("stream mask, abs off, no inter", new double[0], values, 0.0);
        mask.translate(im);
        values = im.stream(mask, true).sorted().toArray();
        assertArrayEquals("stream mask, abs off", new double[]{4}, values, 0.0);
        BlankMask mask2 = new BlankMask(3, 1, 3).translate(new MutableBoundingBox(1, 1, 0));
        test = new double[mask2.getSizeXYZ()];
        test[test.length-1] = 4;
        test[test.length-2] = 3;
        values = im.stream(mask2, false).sorted().toArray();
        assertArrayEquals("stream mask2, rel off", test, values, 0.0);
        values = im.stream(mask2, true).sorted().toArray();
        assertArrayEquals("stream mask2, abs off no off", new double[0], values, 0.0);
        values = im.stream(mask2.translate(im), true).sorted().toArray();
        assertArrayEquals("stream mask2, abs off", test, values, 0.0);
    }
}
