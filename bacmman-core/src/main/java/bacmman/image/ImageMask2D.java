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

/**
 * @author Jean Ollion
 */
public class ImageMask2D extends SimpleImageProperties<ImageMask2D> implements ImageMask<ImageMask2D> {
    final ImageMask mask;
    final int z;
    
    public ImageMask2D(ImageMask mask) {
        this(mask, 0);
    }
    /**
     * Creates a 2D mask from {@param mask}, at z = {@param z}
     * @param mask 
     * @param z 
     */
    public ImageMask2D(ImageMask mask, int z) {
        super(mask);
        this.mask = mask;
        this.z=z;
    }
    
    @Override
    public boolean insideMask(int x, int y, int z) {
        return mask.insideMask(x, y, this.z);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return mask.insideMask(xy, this.z);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return mask.insideMaskWithOffset(x, y, this.z);
    }

    @Override
    public boolean insideMaskWithOffset(int xy, int z) {
        return mask.insideMaskWithOffset(xy, this.z);
    }

    @Override
    public int count() {
        int count = 0;
        for (int xy=0; xy<sizeXY(); ++xy) {
            if (insideMask(xy, z)) ++count;
        }
        return count;
    }

    @Override
    public ImageMask2D duplicateMask() {
        return new ImageMask2D(mask.duplicateMask(), z);
    }
    
}
