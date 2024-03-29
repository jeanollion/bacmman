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

import bacmman.data_structure.Voxel;
import bacmman.utils.geom.Point;

/**
 *
 * @author Jean Ollion
 */

public class MutableBoundingBox extends SimpleBoundingBox<MutableBoundingBox>  {

    public MutableBoundingBox(){
        super();
    }
    
    public MutableBoundingBox(int xMin, int xMax, int yMin, int yMax, int zMin, int zMax) {
        super(xMin, xMax, yMin, yMax, zMin, zMax);
    }
    public MutableBoundingBox(int x, int y, int z) {
        super(x, x, y, y, z, z);
    }

    @Override
    public MutableBoundingBox duplicate() {
        return new MutableBoundingBox(this);
    }

    public MutableBoundingBox(BoundingBox other) {
        super(other);
    }
    
    public MutableBoundingBox setxMin(int xMin) {
        this.xMin = xMin;
        return this;
    }

    public MutableBoundingBox setxMax(int xMax) {
        this.xMax = xMax;
        return this;
    }

    public MutableBoundingBox setyMin(int yMin) {
        this.yMin = yMin;
        return this;
    }

    public MutableBoundingBox setyMax(int yMax) {
        this.yMax = yMax;
        return this;
    }

    public MutableBoundingBox setzMin(int zMin) {
        this.zMin = zMin;
        return this;
    }

    public MutableBoundingBox setzMax(int zMax) {
        this.zMax = zMax;
        return this;
    }
    public enum DIRECTION {START, CENTER, END}
    public MutableBoundingBox setSize(int size, DIRECTION dir, int dim) {
        switch (dim) {
            case 0: return setSizeX(size, dir);
            case 1: return setSizeY(size, dir);
            case 2: return setSizeZ(size, dir);
            default: throw new IllegalArgumentException("invalid dimension. must be in range [0;2]");
        }
    }
    public MutableBoundingBox setSizeX(int size, DIRECTION dir) {
        if (sizeX()==size) return this;
        switch (dir) {
            case START: {
                xMin = xMax - size + 1 ;
                return this;
            } case END: {
                xMax = size + xMin - 1;
                return this;
            } case CENTER: default: {
                int diff = (size - sizeX())/2;
                xMin -= diff;
                xMax = size + xMin - 1;
                return this;
            }
        }
    }
    public MutableBoundingBox setSizeY(int size, DIRECTION dir) {
        if (sizeY()==size) return this;
        switch (dir) {
            case START: {
                yMin = yMax - size + 1 ;
                return this;
            } case END: {
                yMax = size + yMin - 1;
                return this;
            } case CENTER: default: {
                int diffS = (size - sizeY())/2;
                yMin -= diffS;
                yMax = size + yMin - 1;
                return this;
            }
        }
    }
    public MutableBoundingBox setSizeZ(int size, DIRECTION dir) {
        if (sizeZ()==size) return this;
        switch (dir) {
            case START: {
                zMin = zMax - size + 1 ;
                return this;
            } case END: {
                zMax = size + zMin - 1;
                return this;
            } case CENTER: default: {
                int diffS = (size - sizeZ())/2;
                zMin -= diffS;
                zMax = size + zMin - 1;
                return this;
            }
        }
    }

    /**
     * translate the current bounding box so that it is located into other as much as possible
     * @param other
     * @return
     */
    public MutableBoundingBox translateInto(BoundingBox other) {
        translate(Math.max(other.xMin()-xMin, 0), Math.max(other.yMin()-yMin, 0), Math.max(other.zMin()-zMin, 0));
        translate(Math.min(other.xMax()-xMax, 0), Math.min(other.yMax()-yMax, 0), Math.min(other.zMax()-zMax, 0));
        translate(Math.max((other.xMin()-xMin)/2, 0), Math.max((other.yMin()-yMin)/2, 0), Math.max((other.zMin()-zMin)/2, 0));
        return this;
    }
    /**
     * Modify the bounds so that is contains the {@param x} coordinate
     * @param x coordinate in the X-Axis
     */
    public MutableBoundingBox unionX(int x) {
        if (x < xMin) {
            xMin = x;
        } 
        if (x > xMax) {
            xMax = x;
        }
        return this;
    }
    /**
     * Modify the bounds so that is contains the {@param y} coordinate
     * @param y coordinate in the X-Axis
     */
    public MutableBoundingBox unionY(int y) {
        if (y < yMin) {
            yMin = y;
        } 
        if (y > yMax) {
            yMax = y;
        }
        return this;
    }
    /**
     * Modify the bounds so that is contains the {@param z} coordinate
     * @param z coordinate in the X-Axis
     */
    public MutableBoundingBox unionZ(int z) {
        if (z < zMin) {
            zMin = z;
        } 
        if (z > zMax) {
            zMax = z;
        }
        return this;
    }
    /**
     * Copies zMin & zMax from {@param bb}
     * @param bb
     * @return this object modified
     */
    public MutableBoundingBox copyZ(BoundingBox bb) {
        this.zMin = bb.zMin();
        this.zMax = bb.zMax();
        return this;
    }
    public MutableBoundingBox contractX(int xm, int xM) {
        if (xm > xMin) {
            xMin = xm;
        } 
        if (xM < xMax) {
            xMax = xM;
        }
        return this;
    }
    public MutableBoundingBox contractY(int ym, int yM) {
        if (ym > yMin) {
            yMin = ym;
        } 
        if (yM < yMax) {
            yMax = yM;
        }
        return this;
    }
    public MutableBoundingBox contractZ(int zm, int zM) {
        if (zm > zMin) {
            zMin = zm;
        } 
        if (zM < zMax) {
            zMax = zM;
        }
        return this;
    }
    
    public MutableBoundingBox union(int x, int y, int z) {
        unionX(x);
        unionY(y);
        unionZ(z);
        return this;
    }
    public MutableBoundingBox union(Point p) {
        unionX(p.getIntPosition(0));
        unionY(p.getIntPosition(1));
        if (p.numDimensions()>2) unionZ(p.getIntPosition(2));
        return this;
    }
    public MutableBoundingBox union(Voxel v) {
        unionX(v.x);
        unionY(v.y);
        unionZ(v.z);
        return this;
    }
    
    public MutableBoundingBox union(BoundingBox other) {
        unionX(other.xMin());
        unionX(other.xMax());
        unionY(other.yMin());
        unionY(other.yMax());
        unionZ(other.zMin());
        unionZ(other.zMax());
        return this;
    }
    public MutableBoundingBox contract(BoundingBox other) {
        contractX(other.xMin(), other.xMax());
        contractY(other.yMin(), other.yMax());
        contractZ(other.zMin(), other.zMax());
        return this;
    }
    
    public MutableBoundingBox center(BoundingBox other) {
        int deltaX = (int)(other.xMean() - this.xMean() + 0.5);
        int deltaY = (int)(other.yMean() - this.yMean() + 0.5);
        int deltaZ = (int)(other.zMean() - this.zMean() + 0.5);
        return translate(deltaX, deltaY, deltaZ);
    }
    
    /**
     * add {@param border} value in each direction and both ways
     * @param border value of the border
     */
    public MutableBoundingBox addBorder(int border, boolean addInZDirection) {
        xMin-=border;
        xMax+=border;
        yMin-=border;
        yMax+=border;
        if (addInZDirection) {
            zMin-=border;
            zMax+=border;
        }
        return this;
    }
    /**
     * adds a border of 1 pixel in each directions and both ways
     */
    public MutableBoundingBox addBorder() {
        xMin--;
        xMax++;
        yMin--;
        yMax++;
        zMin--;
        zMax++;
        return this;
    }
    /**
     * ensures the bounds are included in the bounds of the {@param properties} object.
     * @param properties 
     * @return  current modified boundingbox object
     */
    public MutableBoundingBox trim(BoundingBox properties) {
        if (xMin<properties.xMin()) xMin=properties.xMin();
        if (yMin<properties.yMin()) yMin=properties.yMin();
        if (zMin<properties.zMin()) zMin=properties.zMin();
        if (xMax>properties.xMax()) xMax=properties.xMax();
        if (yMax>properties.yMax()) yMax=properties.yMax();
        if (zMax>properties.zMax()) zMax=properties.zMax();
        return this;
    }
    
    public MutableBoundingBox extend(BoundingBox extent) {
        xMax += extent.xMax();
        xMin += extent.xMin();
        yMax += extent.yMax();
        yMin += extent.yMin();
        zMax += extent.zMax();
        zMin += extent.zMin();
        return this;
    }
    
    
}
