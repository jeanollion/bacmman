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
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.ImageProperties;

import java.util.ArrayList;

/**
 *
 * @author Jean Ollion
 */
public class EllipsoidalNeighborhoodCompartment extends EllipsoidalNeighborhood {
    final ImageInteger compartimentMap;
    public EllipsoidalNeighborhoodCompartment(double radius, boolean excludeCenter, ImageInteger compartimentMap) {
        super(radius, excludeCenter);
        this.compartimentMap=compartimentMap;
    }

    public EllipsoidalNeighborhoodCompartment(double radius, double radiusZ, boolean excludeCenter, ImageInteger compartimentMap) {
        super(radius, radiusZ, excludeCenter);
        this.compartimentMap = compartimentMap;
    }
    
    @Override
    public void addVoxels(Voxel v, ImageProperties p, ArrayList<Voxel> res) {
        int xx, yy;
        int label = compartimentMap.getPixelInt(v.x, v.y, v.z);
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=v.x+dx[i];
                yy=v.y+dy[i];
                zz=v.z+dz[i];
                if (p.contains(xx, yy, zz) && compartimentMap.getPixelInt(xx, yy, zz)==label) res.add(new Voxel(xx, yy, zz));
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=v.x+dx[i];
                yy=v.y+dy[i];
                if (p.contains(xx, yy, v.z) && compartimentMap.getPixelInt(xx, yy, v.z)==label) res.add(new Voxel(xx, yy, v.z));
            }
        }
    }
    @Override public void setPixels(int x, int y, int z, Image image, ImageMask mask) {
        valueCount=0;
        int label = compartimentMap.getPixelInt(x, y, z);
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz) && compartimentMap.getPixelInt(xx, yy, zz)==label  && (mask==null||mask.insideMask(xx, yy, zz))) values[valueCount++]=image.getPixel(xx, yy, zz);
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z) && compartimentMap.getPixelInt(xx, yy, z)==label && (mask==null||mask.insideMask(xx, yy, z))) values[valueCount++]=image.getPixel(xx, yy, z);
            }
        }
    }
    @Override public void setPixelsByIndex(int x, int y, int z, Image image) {
        valueCount=0;
        int label = compartimentMap.getPixelInt(x, y, z);
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz) && compartimentMap.getPixelInt(xx, yy, zz)==label) {
                    values[i]=image.getPixel(xx, yy, zz);
                    valueCount++;
                } else values[i]=Float.NaN;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z) && compartimentMap.getPixelInt(xx, yy, 0)==label) {
                    values[i]=image.getPixel(xx, yy, z);
                    valueCount++;
                } else values[i]=Float.NaN;
            }
        }
    }
    @Override public double getMin(int x, int y, int z, Image image, double... outOfBoundValue) {
        int xx, yy;
        int label = compartimentMap.getPixelInt(x, y, z);
        double min = Double.MAX_VALUE;
        boolean returnOutOfBoundValue = outOfBoundValue.length>=1;
        double ofbv = returnOutOfBoundValue? outOfBoundValue[0] : 0;
        double temp;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz) && compartimentMap.getPixelInt(xx, yy, zz)==label) {
                    temp=image.getPixel(xx, yy, zz);
                    if (temp<min) min=temp;
                } else if (returnOutOfBoundValue) return ofbv;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z) && compartimentMap.getPixelInt(xx, yy, z)==label) {
                    temp=image.getPixel(xx, yy, z);
                    if (temp<min) min=temp;
                } else if (returnOutOfBoundValue) return ofbv;
            }
        }
        if (min==Double.MAX_VALUE) min = Double.NaN;
        return min;
    }

    @Override public double getMax(int x, int y, int z, Image image) {
        int xx, yy;
        int label = compartimentMap.getPixelInt(x, y, z);
        double max = -Double.MAX_VALUE;
        double temp;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz) && compartimentMap.getPixelInt(xx, yy, zz)==label) {
                    temp=image.getPixel(xx, yy, zz);
                    if (temp>max) max=temp;
                }
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z) && compartimentMap.getPixelInt(xx, yy, z)==label) {
                    temp=image.getPixel(xx, yy, z);
                    if (temp>max) max=temp;
                }
            }
        }
        if (max==Double.MIN_VALUE) max = Double.NaN;
        return max;
    }
    @Override public boolean hasNonNullValue(int x, int y, int z, ImageMask image, boolean outOfBoundIsNonNull) {
        int xx, yy;
        int label = compartimentMap.getPixelInt(x, y, z);
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz) && compartimentMap.getPixelInt(xx, yy, zz)==label) {
                    if (image.insideMask(xx, yy, zz)) return true;
                } else if (outOfBoundIsNonNull) return true;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z) && compartimentMap.getPixelInt(xx, yy, z)==label) {
                    if (image.insideMask(xx, yy, z)) return true;
                } else if (outOfBoundIsNonNull) return true;
            }
        }
        return false;
    }
    @Override public boolean hasNullValue(int x, int y, int z, ImageMask image, boolean outOfBoundIsNull) {
        int xx, yy;
        int label = compartimentMap.getPixelInt(x, y, z);
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                zz=z+dz[i];
                if (image.contains(xx, yy, zz) && compartimentMap.getPixelInt(xx, yy, zz)==label) {
                    if (!image.insideMask(xx, yy, zz)) return true;
                } else if (outOfBoundIsNull) return true;
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=x+dx[i];
                yy=y+dy[i];
                if (image.contains(xx, yy, z) && compartimentMap.getPixelInt(xx, yy, z)==label) {
                    if (!image.insideMask(xx, yy, z)) return true;
                } else if (outOfBoundIsNull) return true;
            }
        }
        return false;
    }
}
