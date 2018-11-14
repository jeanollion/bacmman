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

import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public abstract class DisplacementNeighborhood implements Neighborhood {
    public int[] dx, dy, dz;
    boolean is3D;        
    float[] values;
    float[] distances;
    int valueCount=0;
    
    @Override public void setPixels(Voxel v, Image image, ImageMask mask) {setPixels(v.x, v.y, v.z, image, mask);}
    
    @Override public void setPixels(int x, int y, int z, Image image, ImageMask mask) {
        valueCount=0;
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz) && (mask==null||mask.insideMask(xx, yy, zz))) values[valueCount++]=image.getPixel(xx, yy, zz);
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z) 
                        && (mask==null||mask.insideMask(xx, yy, z))) {
                    values[valueCount++]=image.getPixel(xx, yy, z);
                }
            }
        }
    }
    public void setPixelsByIndex(Voxel v, Image image) {setPixelsByIndex(v.x, v.y, v.z, image);}
    /**
     * The value array is filled according to the index of the displacement array; if a voxel is not in the neighborhood, sets its value to NaN
     * @param x coord along X-axis
     * @param y coord along Y-axis
     * @param z coord along Z-axis
     * @param image 
     */
    public void setPixelsByIndex(int x, int y, int z, Image image) {
        valueCount=0;
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz)) {
                    values[i]=image.getPixel(xx, yy, zz);
                    valueCount++;
                } else values[i]=Float.NaN;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z)) {
                    values[i]=image.getPixel(xx, yy, z);
                    valueCount++;
                } else values[i]=Float.NaN;
            }
        }
    }
    
    @Override public int getSize() {return dx.length;}

    @Override public float[] getPixelValues() {
        return values;
    }
    @Override public int getValueCount() {
        return valueCount;
    }
    @Override public float[] getDistancesToCenter() {
        return distances;
    }
    
    @Override public float getMin(int x, int y, int z, Image image, float... outOfBoundValue) {
        int xx, yy;
        float min = Float.MAX_VALUE;
        boolean returnOutOfBoundValue = outOfBoundValue.length>=1;
        float ofbv = returnOutOfBoundValue? outOfBoundValue[0] : 0;
        float temp;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz)) {
                    temp=image.getPixel(xx, yy, zz);
                    if (temp<min) min=temp;
                } else if (returnOutOfBoundValue) return ofbv;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z)) {
                    temp=image.getPixel(xx, yy, z);
                    if (temp<min) min=temp;
                } else if (returnOutOfBoundValue) return ofbv;
            }
        }
        if (min==Float.MAX_VALUE) min = Float.NaN;
        return min;
    }

    @Override public float getMax(int x, int y, int z, Image image) {
        int xx, yy;
        float max = -Float.MAX_VALUE;
        float temp;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz)) {
                    temp=image.getPixel(xx, yy, zz);
                    if (temp>max) max=temp;
                }
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z)) {
                    temp=image.getPixel(xx, yy, z);
                    if (temp>max) max=temp;
                }
            }
        }
        if (max==Float.MIN_VALUE) max = Float.NaN;
        return max;
    }
    @Override public boolean hasNonNullValue(int x, int y, int z, ImageMask image, boolean outOfBoundIsNonNull) {
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz)) {
                    if (image.insideMask(xx, yy, zz)) return true;
                } else if (outOfBoundIsNonNull) return true;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z)) {
                    if (image.insideMask(xx, yy, z)) return true;
                } else if (outOfBoundIsNonNull) return true;
            }
        }
        return false;
    }
    @Override public boolean hasNullValue(int x, int y, int z, ImageMask image, boolean outOfBoundIsNull) {
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz)) {
                    if (!image.insideMask(xx, yy, zz)) return true;
                } else if (outOfBoundIsNull) return true;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z)) {
                    if (!image.insideMask(xx, yy, z)) return true;
                } else if (outOfBoundIsNull) return true;
            }
        }
        return false;
    }
    
    public boolean is3D() {
        return is3D;
    }
    
    public Stream<Voxel> stream(Voxel v, ImageMask mask, boolean withOffset) {
        Stream<Voxel> res = IntStream.range(0, dx.length).mapToObj(i->new Voxel(v.x+dx[i], v.y+dy[i], v.z+dz[i]));
        if (mask!=null) {
            if (withOffset) res = res.filter(vox->mask.containsWithOffset(vox.x, vox.y, vox.z) && mask.containsWithOffset(vox.x, vox.y, vox.z));
            else res = res.filter(vox->mask.contains(vox.x, vox.y, vox.z) && mask.insideMask(vox.x, vox.y, vox.z));
        }
        return res;
    }
}
