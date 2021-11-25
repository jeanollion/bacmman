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

import java.util.HashMap;

/**
 *
 * @author Jean Ollion
 */
public class ConditionalNeighborhoodZ implements Neighborhood {
    HashMap<Integer, Neighborhood>  neighborhoodZ;
    Neighborhood defaultNeighborhood;
    Neighborhood currentNeighborhood;
    @Override public ConditionalNeighborhoodZ duplicate() {
        ConditionalNeighborhoodZ res = new ConditionalNeighborhoodZ(defaultNeighborhood.duplicate());
        neighborhoodZ.forEach((z, n) -> res.setNeighborhood(z, n.duplicate()));
        return res;
    }
    public ConditionalNeighborhoodZ(Neighborhood  defaultNeighborhood) {
        this.defaultNeighborhood=defaultNeighborhood;
        neighborhoodZ=new HashMap<>();
    }
    public ConditionalNeighborhoodZ setNeighborhood(int z, Neighborhood n) {
        neighborhoodZ.put(z, n);
        return this;
    }
    
    public Neighborhood getNeighborhood(int z) {
        Neighborhood res = neighborhoodZ.get(z);
        if (res==null) return defaultNeighborhood;
        else return res;
    }
    
    @Override public void setPixels(int x, int y, int z, Image image, ImageMask mask) {
        currentNeighborhood = getNeighborhood(z);
        currentNeighborhood.setPixels(x, y, z, image, mask);
    }
    
    @Override public MutableBoundingBox getBoundingBox() {
        MutableBoundingBox res=null;
        for (Neighborhood n : neighborhoodZ.values()) {
            if (res == null) res = n.getBoundingBox();
            else res.union(n.getBoundingBox());
        }
        return res;
    }

    @Override public void setPixels(Voxel v, Image image, ImageMask mask) {
        setPixels(v.x, v.y, v.z, image, mask);
    }

    @Override public int getSize() {
        return currentNeighborhood.getSize();
    }

    @Override public double[] getPixelValues() {
        return currentNeighborhood.getPixelValues();
    }

    @Override public float[] getDistancesToCenter() {
        return currentNeighborhood.getDistancesToCenter();
    }

    @Override public int getValueCount() {
        return currentNeighborhood.getValueCount();
    }

    @Override public boolean is3D() {
        return currentNeighborhood.is3D();
    }

    @Override public double getMin(int x, int y, int z, Image image, double... outOfBoundValue) {
        return currentNeighborhood.getMin(x, y, z, image, outOfBoundValue);
    }

    @Override public double getMax(int x, int y, int z, Image image) {
        return currentNeighborhood.getMax(x, y, z, image);
    }

    @Override public boolean hasNonNullValue(int x, int y, int z, ImageMask mask, boolean outOfBoundIsNonNull) {
        return currentNeighborhood.hasNonNullValue(x, y, z, mask, outOfBoundIsNonNull);
    }

    @Override public boolean hasNullValue(int x, int y, int z, ImageMask mask, boolean outOfBoundIsNull) {
        return currentNeighborhood.hasNullValue(x, y, z, mask, outOfBoundIsNull);
    }
   
}
