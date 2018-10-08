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

import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 *
 * @author Jean Ollion
 */
public class TestEDT {
    /*public static void main(String[] args) {
        new TestEDT().testEDT2D();
    }*/
    @Test
    public void testEDT2D() {
        ImageByte mask = new ImageByte("", 10, 10, 1);
        mask.setPixel(1, 0, 0, 1);
        for (int x = 0; x<=3; ++x) mask.setPixel(x, 1, 0, 1);
        for (int x = 0; x<=3; ++x) mask.setPixel(x, 2, 0, 1);
        for (int x = 0; x<=3; ++x) mask.setPixel(x, 3, 0, 1);
        ImageFloat edm = EDT.transform(mask, true, 0.1f, 0.1f, false); // inside mask
        assertEquals("pixel", edm.getPixel(1, 0, 0), 0.1, 0.001);
        assertEquals("pixel", edm.getPixel(0, 2, 0), 0.1, 0.001); // exterieur de l'image = background car inside == true
        assertEquals("pixel", edm.getPixel(1, 2, 0), 0.2, 0.001);
        assertEquals("pixel", Math.sqrt(2)*0.1, edm.getPixel(1, 1, 0) , 0.001);
        edm = EDT.transform(mask, false, 0.1f, 0.1f, false); // outside mask
        assertEquals("pixel", edm.getPixel(0, 0, 0), 0.1, 0.001);
        assertEquals("pixel", edm.getPixel(0, 5, 0), 0.2, 0.001); // exterieur de l'image = foreground car inside == false
    }
    
    @Test
    public void testEDT3D() {
        ImageByte mask = new ImageByte("", 10, 10, 3);
        for (int x = 0; x<3; ++x) mask.setPixel(x, 0, 1, 1);
        for (int x = 0; x<3; ++x) mask.setPixel(x, 1, 1, 1);
        for (int x = 0; x<3; ++x) mask.setPixel(x, 2, 1, 1);
        mask.setPixel(1, 1, 0, 1);
        mask.setPixel(1, 1, 2, 1);
        mask.setPixel(3, 1, 1, 1);
                
        ImageFloat edm = EDT.transform(mask, true, 0.1f, 0.12f, false);
        assertEquals("pixel", 0.1, edm.getPixel(0, 0, 1), 0.001); // nearest = outside image same plane
        assertEquals("pixel", 0.12, edm.getPixel(2, 1, 1), 0.001); // nearest = z+/-1
        assertEquals("pixel", Math.sqrt(1*1+1.2*1.2)*0.1, edm.getPixel(1, 1, 1), 0.001); //nearest = dia x/y+/-1 / z+/-1
    }
}
