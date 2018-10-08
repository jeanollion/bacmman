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

import bacmman.data_structure.Region;

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import org.junit.Test;

/**
 *
 * @author Jean Ollion
 */
public class ImageManipulationTest {
    /**
     * simple set / get on image byte
     */
    @Test
    public void testImageByte() {
        ImageByte imByte = new ImageByte("im test byte", 2, 3, 2);
        imByte.setPixel(3, 1, 10);
        assertEquals("Read voxel value: byte", imByte.getPixelInt(1, 1, 1), 10);
        
        imByte.setPixel(1, 0, 1, 10.5f);
        assertEquals("Read voxel value: float cast to byte", imByte.getPixelInt(1, 0, 1), 10);
    }
    
    /**
     * simple set / get on image int
     */
    @Test
    public void testImageInt() {
        ImageInt imInt = new ImageInt("im test int", 2, 2, 2);
        imInt.setPixel(1,0, 1, Integer.MAX_VALUE);
        assertEquals("Read voxel value: integer", imInt.getPixelInt(1, 0, 1), Integer.MAX_VALUE);
    }
    
    /**
     * type conversion test
     */
    @Test
    public void testImageSimpleConversion() {
        ImageInt imInt = new ImageInt("im test int", 2, 2, 2);
        imInt.setPixel(0, 1, 1, Integer.MAX_VALUE);
        imInt.setPixel(0, 0, 1, 2);
        ImageByte imByte = TypeConverter.toByteMask(imInt, null, 1);
        assertEquals("Read voxel value: mask", imByte.getPixelInt(0, 1, 1), 1);
        
        ImageFloat imFloat = TypeConverter.toFloat(imInt, null);
        assertEquals("Read voxel value: int cast to float", imFloat.getPixel(0, 0, 1), 2f);
        assertEquals("Read voxel value: int max value cast to float", Integer.MAX_VALUE, imFloat.getPixel(0, 1, 1), 0.01f);
        
        ImageShort imShort = TypeConverter.toShort(imInt, null);
        assertEquals("Read voxel value: max value int cast to short", 65535, imShort.getPixelInt(0, 1, 1));
    }
    
    @Test
    public void testCropImage() {
        ImageByte im = new ImageByte("", 4, 5, 6);
        im.setPixel(2, 3, 4, 1);
        im.setPixel(3, 3, 4, 2);
        im.setPixel(2, 2, 4, 3);
        im.setPixel(3, 2, 3, 4);
        im.setPixel(2, 2, 3, 5);
        MutableBoundingBox bounds = new MutableBoundingBox(2, 3, 2, 3, 3, 4 );
        ImageByte imCrop = im.crop(bounds);
        assertEquals("crop X size", bounds.sizeX(), imCrop.sizeX());
        assertEquals("crop Y size", bounds.sizeY(), imCrop.sizeY());
        assertEquals("crop Z size", bounds.sizeZ(), imCrop.sizeZ());
        assertEquals("voxel value", 1, imCrop.getPixelInt(2-2, 3-2, 4-3));
        assertEquals("voxel value", 2, imCrop.getPixelInt(3-imCrop.xMin(), 3-imCrop.yMin(), 4-imCrop.zMin()));
        assertEquals("voxel value", 3, imCrop.getPixelInt(2-imCrop.xMin(), 2-imCrop.yMin(), 4-imCrop.zMin()));
        assertEquals("voxel value", 4, imCrop.getPixelInt(3-imCrop.xMin(), 2-imCrop.yMin(), 3-imCrop.zMin()));
        assertEquals("voxel value", 5, imCrop.getPixelInt(2-2, 2-2, 3-3));
    }
    
    
    @Test
    public void testCropImageNegativeValues() {
        ImageByte im = new ImageByte("", 4, 5, 6);
        im.setPixel(2, 3, 4, 1);
        MutableBoundingBox bounds = new MutableBoundingBox(-1, 3, -2, 5, -3, 4);
        ImageByte imCrop = im.crop(bounds);
        MutableBoundingBox bounds2 = im.getBoundingBox();
        bounds2.translate(1, 2, 3);
        ImageByte imCrop2 = imCrop.crop(bounds2);
        assertArrayEquals("voxel values2", im.getPixelArray()[4], imCrop2.getPixelArray()[4]);
    }
    
    @Test
    public void testCropLabel() {
        ImageByte im = new ImageByte("", 4, 5, 6);
        im.setPixel(2, 3, 4, 2);
        im.setPixel(3, 3, 4, 2);
        im.setPixel(2, 4, 4, 1);
        im.setPixel(2, 3, 5, 1);
        MutableBoundingBox bounds = new MutableBoundingBox(2, 3, 3, 4, 4, 5);
        ImageByte imCrop = im.cropLabel(2, bounds);
        assertEquals("crop X size", bounds.sizeX(), imCrop.sizeX());
        assertEquals("crop Y size", bounds.sizeY(), imCrop.sizeY());
        assertEquals("crop Z size", bounds.sizeZ(), imCrop.sizeZ());
        
        assertEquals("voxel value label2", 1, imCrop.getPixelInt(2-2, 3-3, 4-4));
        assertEquals("voxel value label2", 1, imCrop.getPixelInt(3-imCrop.xMin(), 3-imCrop.yMin(), 4-imCrop.zMin()));
        assertEquals("voxel value label1", 0, imCrop.getPixelInt(2-imCrop.xMin(), 4-imCrop.yMin(), 4-imCrop.zMin()));
        assertEquals("voxel value label1", 0, imCrop.getPixelInt(2-imCrop.xMin(), 3-imCrop.yMin(), 5-imCrop.zMin()));
    }
    
    @Test
    public void testCropLabelImageNegativeValues() {
        ImageByte im = new ImageByte("", 4, 5, 6);
        im.setPixel(2, 3, 4, 1);
        MutableBoundingBox bounds = new MutableBoundingBox(-1, 3, -2, 5, -3, 4);
        ImageByte imCrop = im.cropLabel(1, bounds);
        assertEquals("value value", 1, imCrop.getPixelInt(2+1, 3+2, 4+3));
        
        MutableBoundingBox bounds2 = im.getBoundingBox();
        bounds2.translate(1, 2, 3);
        ImageByte imCrop2 = imCrop.crop(bounds2);
        
        assertArrayEquals("voxel array values", im.getPixelArray()[4], imCrop2.getPixelArray()[4]);
    }
    
    @Test
    public void testImageLabeller2D() {
        ImageByte mask = new ImageByte("", 11, 6, 1);
        //spot1
        mask.setPixel(0, 0, 0, 1);
        mask.setPixel(0, 1, 0, 1);
        mask.setPixel(1, 0, 0, 1);
        mask.setPixel(1, 1, 0, 1);
        
        //spot2
        mask.setPixel(8, 0, 0, 1);
        mask.setPixel(7, 1, 0, 1);
        mask.setPixel(8, 1, 0, 1);
        mask.setPixel(9, 1, 0, 1);
        mask.setPixel(8, 2, 0, 1);
        
        //spot3
        mask.setPixel(4, 3, 0, 1);
        mask.setPixel(3, 2, 0, 1);
        mask.setPixel(5, 2, 0, 1);
        mask.setPixel(3, 4, 0, 1);
        mask.setPixel(5, 4, 0, 1);
        
        int[] sizes = new int[]{4, 5, 5};
        
        Region[] objects = ImageLabeller.labelImage(mask);
        assertEquals("Number of object detected", 3, objects.length);
        int i=0;
        int[] observedSizes = new int[objects.length];
        for (Region o : objects) observedSizes[i++]=o.getVoxels().size();
        assertArrayEquals("Size of objects", sizes, observedSizes);
        
        ImageByte mask2 = new ImageByte("", mask);
        for (Region o : objects) o.draw(mask2, 1);
        for (int z = 0; z < mask2.sizeZ(); ++z) assertArrayEquals("Spot voxels slice:"+z, mask.getPixelArray()[z], mask2.getPixelArray()[z]);       
    }
    
    @Test
    public void testImageLabeller3D() {
        ImageByte mask = new ImageByte("", 11, 6, 4);
        //spot1
        mask.setPixel(0, 0, 0, 1);
        mask.setPixel(0, 1, 0, 1);
        mask.setPixel(1, 0, 0, 1);
        mask.setPixel(1, 1, 0, 1);
        mask.setPixel(0, 0, 1, 1);
        mask.setPixel(0, 1, 1, 1);
        mask.setPixel(1, 0, 1, 1);
        mask.setPixel(1, 1, 1, 1);
        
        //spot2
        mask.setPixel(8, 0, 1, 1);
        mask.setPixel(7, 1, 1, 1);
        mask.setPixel(8, 1, 1, 1);
        mask.setPixel(9, 1, 1, 1);
        mask.setPixel(8, 2, 1, 1);
        mask.setPixel(8, 1, 0, 1);
        mask.setPixel(8, 1, 2, 1);
        
        //spot3
        mask.setPixel(4, 3, 1, 1);
        mask.setPixel(3, 2, 0, 1);
        mask.setPixel(5, 2, 0, 1);
        mask.setPixel(3, 4, 0, 1);
        mask.setPixel(5, 4, 0, 1);
        mask.setPixel(3, 2, 1, 1);
        mask.setPixel(5, 2, 1, 1);
        mask.setPixel(3, 4, 1, 1);
        mask.setPixel(5, 4, 1, 1);
        
        //spot4
        mask.setPixel(0, 0, 3, 1);
        mask.setPixel(0, 1, 3, 1);
        mask.setPixel(1, 0, 3, 1);
        mask.setPixel(1, 1, 3, 1);
        
        int[] sizes = new int[]{8, 7, 9, 4};
        
        Region[] objects = ImageLabeller.labelImage(mask);
        assertEquals("Number of object detected", 4, objects.length);
        int i=0;
        int[] observedSizes = new int[objects.length];
        for (Region o : objects) observedSizes[i++]=o.getVoxels().size();
        assertArrayEquals("Size of objects", sizes, observedSizes);
        
        ImageByte mask2 = new ImageByte("", mask);
        for (Region o : objects) o.draw(mask2, 1);
        for (int z = 0; z < mask2.sizeZ(); ++z) assertArrayEquals("Spot voxels slice:"+z, mask.getPixelArray()[z], mask2.getPixelArray()[z]);       
    }
    
    
    
}
