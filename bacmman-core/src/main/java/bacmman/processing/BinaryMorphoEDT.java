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

import bacmman.image.BoundingBox;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.TypeConverter;

/**
 *
 * @author Jean Ollion
 */
public class BinaryMorphoEDT {
    /**
     * dilate binary mask using EDT
     * @param in
     * @param radius in pixels -> dilate in XY direction
     * @param radiusZ in pixels -> dilate in Z direction
     * @param extendImage if true resulting image will be resized with a margin of {@param radius} in XY directions and {@param radiusZ} in Z direction. If false object at the border may be truncated
     * @param multithread
     * @return 
     */
    public static ImageMask binaryDilateEDT(ImageMask in, double radius, double radiusZ, boolean extendImage, boolean multithread) {
        if (extendImage) {
            ImageInteger<? extends ImageInteger> ii = TypeConverter.maskToImageInteger(in, null);
            int rXY = (int) (radius + 1);
            int rZ = (int) (radiusZ + 1);
            if (in.sizeZ()==1) rZ=0;
            ii =  ii.extend(new SimpleBoundingBox(-rXY, rXY, -rXY, rXY, -rZ, rZ));
            in = ii;
        }
        ImageFloat edm = EDT.transform(in, false, 1, radius / radiusZ, multithread);
        BoundingBox.loop(new SimpleBoundingBox(edm).resetOffset(), (x, y, z) -> {
            if (edm.getPixel(x, y, z)>radius) edm.setPixel(x, y, z, 0);
            else  edm.setPixel(x, y, z, 1);
        }, multithread);
        return edm;
    }
    /**
     * erode binary mask using EDT
     * @param in
     * @param radius in pixels -> erode in XY direction
     * @param radiusZ in pixels -> erode in Z direction
     * @param multithread
     * @return 
     */
    public static ImageMask binaryErode(ImageMask in, double radius, double radiusZ, boolean multithread) {
        ImageFloat edm = EDT.transform(in, true, 1, radius / radiusZ, multithread);
        BoundingBox.loop(new SimpleBoundingBox(edm).resetOffset(), (x, y, z) -> {
            if (edm.getPixel(x, y, z)<=radius) edm.setPixel(x, y, z, 0);
            else edm.setPixel(x, y, z, 1);
        }, multithread);
        return edm;
    }
    public static ImageMask binaryOpen(ImageMask in, double radius, double radiusZ, boolean multithread) {
        ImageMask min = binaryErode(in, radius, radiusZ, multithread);
        return binaryDilateEDT(min, radius, radiusZ, false, multithread);
    }
    public static ImageByte binaryClose(ImageMask in, double radius, double radiusZ, boolean multithread) {
        ImageMask max = binaryDilateEDT(in, radius, radiusZ, true, multithread);
        ImageMask min = binaryErode(max, radius, radiusZ, multithread);
        ImageByte res = new ImageByte("close of "+in.getName(), in);
        int offXY = (int) (radius + 1);
        int offZ = in.sizeZ()==1 ? 0 : (int) (radiusZ + 1);
        BoundingBox.loop(new SimpleBoundingBox(res).resetOffset(), (x, y, z) -> { // copy result of min in resulting image
            if (min.insideMask(x+offXY, y+offXY, z+offZ)) res.setPixel(x, y, z, 1);
        }, multithread);
        return res;
    }
}
