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

import static org.junit.Assert.assertTrue;

/**
 *
 * @author Jean Ollion
 */
public class TestCrop {
    public static void main(String[] args) {
        SimpleOffset off = new SimpleOffset(1, 2, 3);
        ImageByte im1 = new ImageByte("", 10, 9, 8).translate(off);
        SimpleOffset pix = new SimpleOffset(3, 2, 1).translate(off);
        im1.setPixelWithOffset(pix.xMin, pix.yMin, pix.zMin, 1);
        BoundingBox crop = new SimpleBoundingBox(-10, 20, 2, 11, 1, 4);
        Image imCrop = im1.crop(crop);
        
        
        assertTrue("dimentions", imCrop.sameDimensions(crop));
        assertTrue("offset", imCrop.getOffset().sameOffset(new SimpleOffset(off).translate(crop)));
        SimpleOffset pix2 = new SimpleOffset(pix).translate(new SimpleOffset(crop).reverseOffset()).translate(new SimpleOffset(off).reverseOffset());
        assertTrue("value relative", imCrop.getPixel(pix2.xMin, pix2.yMin, pix2.zMin)==1);
        assertTrue("value absolute", imCrop.getPixelWithOffset(pix.xMin, pix.yMin, pix.zMin)==1);
    }
}
