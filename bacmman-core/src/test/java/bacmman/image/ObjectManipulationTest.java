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

import bacmman.test_utils.TestUtils;
import bacmman.data_structure.Region;
import bacmman.processing.RegionFactory;

import java.util.TreeMap;
import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author Jean Ollion
 */
public class ObjectManipulationTest {
    ImageByte im, imRelabel;
    BoundingBox bound1;
    BoundingBox bound3;
    Region o1;
    Region o3;
    
    @Before
    public void setUp() {
        im = new ImageByte("", 4, 4, 5);
        im.setPixel(0, 0, 0, 1);
        im.setPixel(1, 0, 0, 1);
        im.setPixel(2, 1, 1, 3);
        im.setPixel(3, 1, 1, 3);
        imRelabel = new ImageByte("", 4, 4, 5);
        imRelabel.setPixel(0, 0, 0, 1);
        imRelabel.setPixel(1, 0, 0, 1);
        imRelabel.setPixel(2, 1, 1, 2);
        imRelabel.setPixel(3, 1, 1, 2);
        
        bound1=new SimpleBoundingBox(0, 1, 0, 0,0,0);
        bound3 = new SimpleBoundingBox(2, 3, 1,1,1,1);
        o1 = new Region(im.cropLabel(1, bound1), 1, false);
        o3 = new Region(im.cropLabel(3, bound3), 3, false);
        
    }

    
    @Test
    public void testGetObjectsBounds() {
        TreeMap<Integer, BoundingBox> bds = RegionFactory.getBounds(im);
        assertEquals("object number", 2, bds.size());
        assertTrue("bound1", bound1.sameBounds(bds.get(1)));
        assertTrue("bound3", bound3.sameBounds(bds.get(3)));
    }
    
    @Test
    public void testGetObjectsImages() {
        Region[] obs = RegionFactory.getObjectsImage(im, null, false);
        assertEquals("object number", 2, obs.length);
        TestUtils.assertImage((ImageByte)obs[0].getMask(), (ImageByte)o1.getMask(), 0);
        TestUtils.assertImage((ImageByte)obs[1].getMask(), (ImageByte)o3.getMask(), 0);
    }
    
    @Test 
    public void testConversionVoxelMask() {
        Region[] obs = RegionFactory.getRegions(im, false);
        ImageByte imtest = new ImageByte("", im);
        int label=1;
        for (Region o : obs) o.draw(imtest, label++);
        ImageByte imtest2 = new ImageByte("", im);
        label = 1;
        for (Region o : obs) imtest2.appendBinaryMasks(label++, o.getMask());
        TestUtils.assertImage(imtest, imtest2, 0);
    }
    
    @Test 
    public void testConversionMaskVoxels() {
        Region[] obs = RegionFactory.getObjectsImage(im, null, false);
        ImageByte imtest = new ImageByte("", im);
        int label=1;
        for (Region o : obs) {
            o.getVoxels(); // get voxels to ensure draw with voxels over draw with mask
            o.draw(imtest, label++);
        }
        ImageByte imtest2 = new ImageByte("", im);
        label = 1;
        for (Region o : obs) imtest2.appendBinaryMasks(label++, o.getMask());
        TestUtils.assertImage(imtest, imtest2, 0);
    }
    
    @Test
    public void testRelabelImage() {
        ImageByte im2 = im.duplicate("");
        RegionFactory.relabelImage(im2, null);
        TestUtils.assertImage(imRelabel, im2, 0);
    }


    
    
}
