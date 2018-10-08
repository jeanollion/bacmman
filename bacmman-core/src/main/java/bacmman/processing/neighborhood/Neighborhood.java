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
package bacmman.processing.neighborhood;

import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.MutableBoundingBox;

/**
 *
 * @author Jean Ollion
 */
public interface Neighborhood {
    /**
     * Copy pixels in the neighborhood
     * @param x X-axis coordinate of the center of the neighborhood
     * @param y Y-axis coordinate of the center of the neighborhood
     * @param z Z-axis coordinate of the center of the neighborhood (0 for 2D case)
     * @param image image to copy pixels values from
     */
    public void setPixels(int x, int y, int z, Image image, ImageMask mask);
    public void setPixels(Voxel v, Image image, ImageMask mask);
    public int getSize();
    /**
     * 
     * @return bb centered @ 0 ie xMin, yMin, zMin <0
     */
    public MutableBoundingBox getBoundingBox();
    public float[] getPixelValues();
    public float getMin(int x, int y, int z, Image image, float... outOfBoundValue);
    public float getMax(int x, int y, int z, Image image);
    public boolean hasNonNullValue(int x, int y, int z, ImageMask mask, boolean outOfBoundIsNonNull);
    public boolean hasNullValue(int x, int y, int z, ImageMask mask, boolean outOfBoundIsNull);
    public float[] getDistancesToCenter();
    public int getValueCount();
    public boolean is3D();
    public Neighborhood duplicate();
    // float[] getCoefficientValue();
}
