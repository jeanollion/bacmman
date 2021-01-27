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
package bacmman.utils.geom;

import bacmman.data_structure.Voxel;
import bacmman.image.BoundingBox;
import bacmman.image.Offset;
import bacmman.utils.JSONSerializable;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public class Point<T extends Point<T>> implements Offset<T>, RealLocalizable, JSONSerializable, Localizable{
    protected float[] coords;
    public Point(float... coords) {
        this.coords=coords;
    }

    public boolean isValid() {
        for (float d : coords) {
            if (Float.isNaN(d) || Float.isInfinite(d) || d==Float.MAX_VALUE || d==-Float.MAX_VALUE || d==Integer.MAX_VALUE || d==Integer.MIN_VALUE) return false;
        }
        return true;
    }
    public float getWithDimCheck(int dim) {
        if (dim>=coords.length) return 0;
        return coords[dim];
    }
    public float get(int dim) {
        return coords[dim];
    }
    public T setData(float... coords) {
        //System.arraycopy(coords, 0, this.coords, 0, Math.min(coords.length, this.coords.length));
        this.coords=coords;
        return (T)this;
    }
    
    public T setData(Point other) {
        this.coords=other.coords;
        return (T)this;
    }
    public T set(float value, int dim) {
        this.coords[dim] = value;
        return (T)this;
    }
    
    public T translate(Vector other) {
        for (int i = 0; i<Math.min(coords.length, other.coords.length); ++i) coords[i]+=other.coords[i];
        return (T)this;
    }
    public T translateRev(Vector other) {
        for (int i = 0; i<Math.min(coords.length, other.coords.length); ++i) coords[i]-=other.coords[i];
        return (T)this;
    }
    public T averageWith(Point other) {
        for (int i = 0; i<coords.length; ++i) coords[i] = (coords[i]+other.coords[i])/2f;
        return (T)this;
    }
    public T duplicate() {
        return (T)new Point(Arrays.copyOf(coords, coords.length));
    }
    /**
     * 
     * @param untilDimensionIncluded dimension of the output point
     * @return duplicated point with {@param dimensions}
     */
    public Point duplicate(int untilDimensionIncluded) {
        float[] res=  new float[untilDimensionIncluded];
        System.arraycopy(coords, 0, res, 0, Math.min(untilDimensionIncluded, coords.length));
        return new Point(res);
    }
    public static Point middle(Offset o1, Offset o2) {
        return new Point((o1.xMin()+o2.xMin())/2f, (o1.yMin()+o2.yMin())/2f, (o1.zMin()+o2.zMin())/2f);
    }
    public static Point middle2DOffset(Offset o1, Offset o2) {
        return new Point((o1.xMin()+o2.xMin())/2f, (o1.yMin()+o2.yMin())/2f);
    }
    public static Point middle2D(RealLocalizable start, RealLocalizable end) {
        return new Vector((float)((end.getDoublePosition(0)+start.getDoublePosition(0))/2d), (float)((end.getDoublePosition(1)+start.getDoublePosition(1))/2d));
    }
    public T weightedSum(Point other, double weight, double weightOther) {
        for (int i = 0; i<coords.length; ++i) coords[i] = (float)(coords[i] * weight + other.coords[i]*weightOther);
        return (T) this;
    }
    public static Point asPoint2D(Offset off) {
        return new Point(off.xMin(), off.yMin());
    }
    public static Point asPoint2D(RealLocalizable loc) {
        return new Point(loc.getFloatPosition(0), loc.getFloatPosition(1));
    }
    public static Point asPoint(RealLocalizable loc) {
        float[] coords = new float[loc.numDimensions()];
        loc.localize(coords);
        return new Point(coords);
    }
    public static Point asPoint(Offset off) {
        return new Point(off.xMin(), off.yMin(), off.zMin());
    }
    public static Point wrap(RealLocalizable p) {
        if (p instanceof Point) return ((Point)p);
        float[] coords = new float[p.numDimensions()];
        p.localize(coords);
        return new Point(coords);
    }
    public T toMiddlePoint() {
        for (int i = 0; i<coords.length; ++i) coords[i]/=2f;
        return (T)this;
    }
    public double distSq(Offset other) {
        return IntStream.range(0, Math.min(coords.length, 3)).mapToDouble(i->Math.pow(coords[i]-other.getIntPosition(i), 2)).sum();
    }
    public double distSq(Point other) {
        return IntStream.range(0, Math.min(coords.length, other.coords.length)).mapToDouble(i->Math.pow(coords[i]-other.coords[i], 2)).sum();
    }
    public double distSqXY(Point other) {
        return IntStream.range(0, 2).mapToDouble(i->Math.pow(coords[i]-other.coords[i], 2)).sum();
    }
    public double distXY(Point other) {
        return Math.sqrt(distSqXY(other));
    }
    public double distSq(RealLocalizable other) {
        return IntStream.range(0,  coords.length).mapToDouble(i->Math.pow(coords[i]-other.getDoublePosition(i), 2)).sum();
    }
    public double distSqXY(RealLocalizable other) {
        return Math.sqrt(distSqXY(other));
    }
    public double dist(Point other) {
        return Math.sqrt(distSq(other));
    }
    public double dist(Offset other) {
        return Math.sqrt(distSq(other));
    }
    public T multiplyDim(double factor, int dim) {
        if (coords.length>dim) coords[dim]*=factor;
        return (T)this;
    }
    public T multiply(double factor) {
        for (int i = 0; i<coords.length; ++i) coords[i]*=factor;
        return (T)this;
    }
    public T add(Point other, double w) {
        for (int i = 0; i<coords.length; ++i) coords[i]+=other.coords[i]*w;
        return (T)this;
    }
    public T addDim(double value, int dim) {
        if (coords.length>dim) coords[dim]+=value;
        return (T)this;
    }
    /**
     * Coordinates are not copies any modification on the will impact this instance 
     * @return a vector with same coordinates as this point
     */
    public Vector asVector() {
        return new Vector(coords);
    }
    public Voxel asVoxel() {
        return new Voxel(xMin(), yMin(), zMin());
    }
    public void copyLocationToVoxel(Voxel dest) {
        dest.x = xMin();
        dest.y = yMin();
        dest.z = zMin();
    }
    // offset implementation
    @Override
    public int xMin() {
        return (int)Math.round(coords[0]);
    }

    @Override
    public int yMin() {
        return coords.length<=1 ? 0 :(int)Math.round(coords[1]);
    }

    @Override
    public int zMin() {
        return coords.length<=2 ? 0 : (int)Math.round(coords[2]);
    }

    @Override
    public T resetOffset() {
        for (int i = 0; i<coords.length; ++i) coords[i]=0;
        return (T)this;
    }

    @Override
    public T reverseOffset() {
        for (int i = 0; i<coords.length; ++i) coords[i]=-coords[i];
        return (T)this;
    }

    @Override
    public T translate(Offset other) {
        coords[0] +=other.xMin();
        if (coords.length>1) {
            coords[1]+=other.yMin();
            if (coords.length>2) coords[2]+=other.zMin();
        }
        return (T)this;
    }
    public T translateRev(Offset other) {
        coords[0] -=other.xMin();
        if (coords.length>1) {
            coords[1]-=other.yMin();
            if (coords.length>2) coords[2]-=other.zMin();
        }
        return (T)this;
    }
    public Point ensureWithinBounds(BoundingBox bounds) {
        if (coords[0]<bounds.xMin()) coords[0] = bounds.xMin();
        else if (coords[0]>bounds.xMax()) coords[0] = bounds.xMax();
        if (coords.length>1) {
            if (coords[1]<bounds.yMin()) coords[1] = bounds.yMin();
            else if (coords[1]>bounds.yMax()) coords[1] = bounds.yMax();
            if (coords.length>2) {
                if (coords[2]<bounds.zMin()) coords[2] = bounds.zMin();
                else if (coords[2]>bounds.zMax()) coords[2] = bounds.zMax();
            }
        }
        return this;
    }
    // RealLocalizable implementation
    @Override
    public void localize(float[] floats) {
        System.arraycopy(coords, 0, floats, 0, Math.min(floats.length, coords.length));
    }

    @Override
    public void localize(double[] doubles) {
        for (int i = 0; i<Math.min(doubles.length, coords.length); ++i) doubles[i] = coords[i];
    }

    @Override
    public float getFloatPosition(int i) {
        return coords[i];
    }

    @Override
    public double getDoublePosition(int i) {
        return coords[i];
    }

    @Override
    public int numDimensions() {
        return coords.length;
    }
    // object methods
    @Override public String toString() {
        return Utils.toStringArray(coords);
    }
    @Override
    public int hashCode() {
        return Arrays.hashCode(coords);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Point<?> other = (Point<?>) obj;
        return Arrays.equals(this.coords, other.coords);
    }
    public boolean equals(Point other, double accuracy) {
        if (other==null) return false;
        if (coords.length!=other.coords.length) return false;
        if (Arrays.equals(this.coords, other.coords)) return true;
        for (int i = 0; i<coords.length; ++i) {
            if (Math.abs(coords[i]-other.coords[i])>accuracy) return false;
        }
        return true;
    }
    public static Point intersect2D(Point line1Point1, Point line1Point2, Point line2Point1, Point line2Point2) {
        double d = (line1Point1.coords[0]-line1Point2.coords[0])*(line2Point1.coords[1]-line2Point2.coords[1]) - (line1Point1.coords[1]-line1Point2.coords[1])*(line2Point1.coords[0]-line2Point2.coords[0]);
        if (d == 0) return null;
        double xi = ((line2Point1.coords[0]-line2Point2.coords[0])*(line1Point1.coords[0]*line1Point2.coords[1]-line1Point1.coords[1]*line1Point2.coords[0])-(line1Point1.coords[0]-line1Point2.coords[0])*(line2Point1.coords[0]*line2Point2.coords[1]-line2Point1.coords[1]*line2Point2.coords[0]))/d;
        double yi = ((line2Point1.coords[1]-line2Point2.coords[1])*(line1Point1.coords[0]*line1Point2.coords[1]-line1Point1.coords[1]*line1Point2.coords[0])-(line1Point1.coords[1]-line1Point2.coords[1])*(line2Point1.coords[0]*line2Point2.coords[1]-line2Point1.coords[1]*line2Point2.coords[0]))/d;
        return new Point((float)xi, (float)yi);
    }
    // json interface
    @Override
    public Object toJSONEntry() {
        return JSONUtils.toJSONArray(coords);
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        coords = JSONUtils.fromFloatArray((List)jsonEntry);
    }
    // localizable interface
    @Override
    public void localize(int[] position) {
        for (int i = 0; i<coords.length; ++i) position[i] = (int)(coords[i]+0.5);
    }

    @Override
    public void localize(long[] position) {
        for (int i = 0; i<coords.length; ++i) position[i] = (int)(coords[i]+0.5);
    }

    @Override
    public int getIntPosition(int d) {
        return (int)(coords[d]+0.5);
    }
    public int getIntPositionWithDimCheck(int d) {
        if (d>=coords.length) return 0;
        return (int)(coords[d]+0.5);
    }

    @Override
    public long getLongPosition(int d) {
        return (long)(coords[d]+0.5);
    }
}
