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
package bacmman.plugins;

import bacmman.test_utils.TestUtils;
import bacmman.image.ImageByte;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import bacmman.processing.ImageTransformation;

/**
 *
 * @author Jean Ollion
 */
public class TransformationTest {
    @Test
    public void filpTest() {
        ImageByte test = new ImageByte("", 5, 4, 3);
        test.setPixel(0, 0, 0, 1);
        test.setPixel(1, 1, 1, 1);
        ImageByte test2=test.duplicate("");
        ImageTransformation.flip(test2, ImageTransformation.Axis.X);
        assertEquals("filp-X", 1, test2.getPixelInt(test.sizeX()-1, 0, 0));
        ImageTransformation.flip(test2, ImageTransformation.Axis.X);
        TestUtils.assertImage(test, test2, 0);
        
        ImageTransformation.flip(test2, ImageTransformation.Axis.Y);
        assertEquals("filp-Y", 1, test2.getPixelInt(0, test.sizeY()-1, 0));
        ImageTransformation.flip(test2, ImageTransformation.Axis.Y);
        ImageTransformation.flip(test2, ImageTransformation.Axis.Z);
        assertEquals("filp-Z", 1, test2.getPixelInt(0, 0, test.sizeZ()-1));
    }
}
