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
package bacmman.data_structure;

import bacmman.image.Offset;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

import net.imglib2.Localizable;

/**
 * 
 * @author Jean Ollion
 */
public class Voxel implements Offset<Voxel>, Localizable {
    public int x, y;
    public float value;
    public int z;

    public Voxel(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    public Voxel(int[] xyz) {
        this.x = xyz[0];
        this.y = xyz[1];
        if (xyz.length>2) this.z = xyz[2];
    }

    public Voxel(int x, int y, int z, float value) {
        this(x, y, z);
        this.value = value;
    }
    public Voxel copyDataFrom(Voxel other) {
        x=other.x;
        y = other.y;
        z=other.z;
        value=other.value;
        return this;
    }
    public Voxel2D toVoxel2D() {
        return new Voxel2D(x, y, z, value);
    }
    @Override
    public boolean equals(Object other) {
        if (other instanceof Voxel) {
            Voxel otherV = (Voxel)other;
            return x==otherV.x && y==otherV.y && ( z==otherV.z || other instanceof Voxel2D);
        } else return false;
    }

    /*@Override
    public int hashCode() {
        return 97 * (97 * (679 + this.x) + this.y) + this.z;
    }*/

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "{" + x + ";" + y + ";" + z + ";V=" + value + '}';
    }

    public Voxel duplicate() {
        return new Voxel(x, y, z);
    }
    public Voxel setValue(float value) {
        this.value = value;
        return this;
    }

    public Voxel translate(int dX, int dY, int dZ) {
        x+=dX;
        y+=dY;
        z+=dZ;
        return this;
    }
    
    public Voxel translate(Offset offset) {
        x+=offset.xMin();
        y+=offset.yMin();
        z+=offset.zMin();
        return this;
    }
    
    public static Comparator<Voxel> getInvertedComparator() {
        return (Voxel voxel, Voxel other) -> {
            if (voxel.value < other.value) {
                return 1;
            } else if (voxel.value > other.value) {
                return -1;
            } else { // consistancy with equals method
                if (voxel.x<other.x) return 1;
                else if (voxel.x>other.x) return -1;
                else if (voxel.y<other.y) return 1;
                else if (voxel.y>other.y) return -1;
                else if (voxel.z<other.z) return 1;
                else if (voxel.z>other.z) return -1;
                else return 0;
            }
        };
    }
    public static Comparator<Voxel> getComparator() {
        return (Voxel voxel, Voxel other) -> {
            if (voxel.value < other.value) {
                return -1;
            } else if (voxel.value > other.value) {
                return 1;
            } else {// consistancy with equals method
                if (voxel.x<other.x) return -1;
                else if (voxel.x>other.x) return 1;
                else if (voxel.y<other.y) return -1;
                else if (voxel.y>other.y) return 1;
                else if (voxel.z<other.z) return -1;
                else if (voxel.z>other.z) return 1;
                else return 0;
            }
        };
    }
    
    public double getDistanceSquare(Voxel other, double scaleXY, double scaleZ) {
        return Math.pow((x-other.x) * scaleXY, 2) + Math.pow((y-other.y) * scaleXY, 2) + Math.pow((z-other.z) * scaleZ, 2);
    }
    
    public double getDistanceSquare(Voxel other) {
        return (x-other.x)*(x-other.x) + (y-other.y)*(y-other.y) + (z-other.z)*(z-other.z);
    }
    public double getDistanceSquareXY(Voxel other) {
        return (x-other.x)*(x-other.x) + (y-other.y)*(y-other.y);
    }
    public double getDistanceXY(Voxel other) {
        return Math.sqrt(getDistanceSquareXY(other));
    }
    public double getDistanceSquare(double xx, double yy, double zz) {
        return (x-xx)*(x-xx) + (y-yy)*(y-yy) + (z-zz)*(z-zz);
    }
    public double getDistanceSquareXY(double xx, double yy) {
        return (x-xx)*(x-xx) + (y-yy)*(y-yy);
    }
    
    public double getDistance(Voxel other, double scaleXY, double scaleZ) {
        return Math.sqrt(Math.pow((x-other.x) * scaleXY, 2) + Math.pow((y-other.y) * scaleXY, 2) + Math.pow((z-other.z) * scaleZ, 2));
    }
    
    public double getDistance(Voxel other) {
        return Math.sqrt((x-other.x)*(x-other.x) + (y-other.y)*(y-other.y) + (z-other.z)*(z-other.z));
    }
    public static Voxel getClosest(Voxel v, Collection<? extends Voxel> collection) {
        if (collection==null || collection.isEmpty()) return null;
        return collection.stream().min((v1, v2)->Double.compare(v.getDistanceSquare(v1), v.getDistanceSquare(v2))).get();
    }

    @Override 
    public int getIntPosition(int dim) {
        switch(dim) {
            case 0:
                return x;
            case 1:
                return y;
            case 2:
                return z;
            default:
                return 0;
        }
    }
    
    @Override
    public int xMin() {
        return x;    
    }

    @Override
    public int yMin() {
        return y;
    }

    @Override
    public int zMin() {
        return z;
    }

    @Override
    public Voxel resetOffset() {
        x=0; y=0;z=0;
        return this;
    }

    @Override
    public Voxel reverseOffset() {
        x = -x;
        y=-y;
        z=-z;
        return this;
    }
    // real localizale
    @Override
    public void localize(float[] position) {
        position[0] = x;
        position[1] = y;
        if (position.length>2) position[2] = z;
    }

    @Override
    public void localize(double[] position) {
        position[0] = x;
        position[1] = y;
        if (position.length>2) position[2] = z;
    }

    @Override
    public float getFloatPosition(int d) {
        switch(d) {
            case 0:
                return x;
            case 1:
                return y;
            case 2: 
                return z;
            default:
                return 0;
        }
    }

    @Override
    public double getDoublePosition(int d) {
        switch(d) {
            case 0:
                return x;
            case 1:
                return y;
            case 2: 
                return z;
            default:
                return 0;
        }
    }

    @Override
    public int numDimensions() {
        return 3;
    }
    
    // localizable implementation 
    @Override
    public void localize(int[] position) {
        position[0] = x;
        position[1] = y;
        if (position.length>2) position[2] = z;    }

    @Override
    public void localize(long[] position) {
        position[0] = x;
        position[1] = y;
        if (position.length>2) position[2] = z;
    }

    @Override
    public long getLongPosition(int d) {
        switch(d) {
            case 0:
                return x;
            case 1:
                return y;
            case 2: 
                return z;
            default:
                return 0;
        }
    }
}
