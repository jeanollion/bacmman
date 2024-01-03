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

import bacmman.utils.geom.Point;

/**
 *
 * @author Jean Ollion
 */
public class SimpleImageProperties<T extends SimpleImageProperties<T>> implements ImageProperties<T> {
    protected double scaleXY, scaleZ;
    protected int sizeX, sizeY, sizeZ, sizeXY, sizeXYZ, offsetXY, xMin, yMin, zMin;
    protected String name;
    public SimpleImageProperties(ImageProperties properties) {
        this(properties, properties.getScaleXY(), properties.getScaleZ());
        this.name=properties.getName();
    }
    public SimpleImageProperties(BoundingBox bounds, double scaleXY, double scaleZ) {
        this.scaleXY=scaleXY;
        this.scaleZ = scaleZ;
        this.name="";
        this.sizeX = bounds.sizeX();
        this.sizeY = bounds.sizeY();
        this.sizeZ = bounds.sizeZ();
        this.xMin = bounds.xMin();
        this.yMin = bounds.yMin();
        this.zMin = bounds.zMin();
        sizeXY = sizeX * sizeY;
        sizeXYZ = sizeXY * sizeZ;
        offsetXY = xMin+yMin * sizeX;
    }
    public SimpleImageProperties(String name, BoundingBox bounds, double scaleXY, double scaleZ) {
        this(bounds, scaleXY, scaleZ);
        this.name=name;
    }
    public SimpleImageProperties(int sizeX, int sizeY, int sizeZ, double scaleXY, double scaleZ) {
        this(new SimpleBoundingBox(0, sizeX-1, 0, sizeY-1, 0, sizeZ-1), scaleXY, scaleZ);
    }
    
    @Override
    public String getName() {
        return name;
    }

    @Override
    public double getScaleXY() {
        return scaleXY;
    }

    @Override
    public double getScaleZ() {
        return scaleZ;
    }

    @Override
    public T setCalibration(ImageProperties properties) {
        this.scaleXY = properties.getScaleXY();
        this.scaleZ = properties.getScaleZ();
        return (T)this;
    }

    @Override
    public T setCalibration(double scaleXY, double scaleZ) {
        this.scaleXY = scaleXY;
        this.scaleZ = scaleZ;
        return (T)this;
    }

    @Override
    public boolean sameDimensions(BoundingBox image) {
        return sizeX == image.sizeX() && sizeY == image.sizeY() && sizeZ == image.sizeZ();
    }

    @Override
    public int sizeXY() {
        return sizeXY;
    }

    @Override
    public int sizeXYZ() {
        return sizeXYZ;
    }
    
    @Override
    public int getOffsetXY() {
        return offsetXY;
    }


    @Override
    public int xMax() {
        return xMin + sizeX - 1;
    }

    @Override
    public int yMax() {
        return yMin + sizeY - 1;
    }

    @Override
    public int zMax() {
        return zMin + sizeZ - 1;
    }

    @Override
    public int getMax(int dim) {
        switch(dim) {
            case 0:
                return xMin + sizeX - 1;
            case 1:
                return yMin + sizeY - 1;
            case 2:
                return zMin + sizeZ - 1;
            default:
                throw new IllegalArgumentException("out-of-dimension");
        }
    }

    @Override
    public int getMin(int dim) {
        switch(dim) {
            case 0:
                return xMin;
            case 1:
                return yMin;
            case 2:
                return zMin;
            default:
                throw new IllegalArgumentException("out-of-dimension");
        }
    }

    @Override
    public int sizeX() {
        return sizeX;
    }

    @Override
    public int sizeY() {
        return sizeY;
    }

    @Override
    public int sizeZ() {
        return sizeZ;
    }

    @Override
    public int size(int dim) {
        switch(dim) {
            case 0:
                return sizeX;
            case 1:
                return sizeY;
            case 2:
                return sizeZ;
            default:
                throw new IllegalArgumentException("out-of-dimension");
        }
    }

    @Override
    public int volume() {
        return sizeX * sizeY * sizeZ;
    }

    public int surface() {
        return sizeX * sizeY;
    }
    public int getSizeXY() {
        return sizeX * sizeY;
    }

    @Override
    public double xMean() {
        return xMin + (sizeX-1.)/2;
    }

    @Override
    public double yMean() {
        return yMin + (sizeY-1.)/2;
    }

    @Override
    public double zMean() {
        return zMin + (sizeZ-1.)/2;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return 0<=x && sizeX>x && 0<=y && sizeY>y && 0<=z && sizeZ>z;
    }

    @Override
    public boolean contains(Point point) {
        return 0<=point.get(0) && sizeX>point.get(0) &&
                0<=point.get(1) && sizeY>point.get(1) &&
                (point.numDimensions()<=2 || (0<=point.get(2) && sizeZ>point.get(2)));
    }

    @Override
    public boolean containsWithOffset(int x, int y, int z) {
        return xMin<=x && xMax()>=x && yMin<=y && yMax()>=y && zMin<=z && zMax()>=z;
    }

    @Override
    public boolean containsWithOffset(Point point) {
        return xMin<=point.get(0) && xMax()>=point.get(0) &&
                yMin<=point.get(1) && yMax()>=point.get(1) &&
                (point.numDimensions()<=2 || (zMin<=point.get(2) && zMax()>=point.get(2)));
    }

    @Override
    public boolean sameBounds(BoundingBox boundingBox) {
        return xMin==boundingBox.xMin() && yMin==boundingBox.yMin() && zMin==boundingBox.zMin() && xMax()==boundingBox.xMax() && yMax()==boundingBox.yMax() && zMax()==boundingBox.zMax();
    }

    @Override
    public boolean sameBounds2D(BoundingBox boundingBox) {
        return xMin==boundingBox.xMin() && yMin==boundingBox.yMin() && xMax()==boundingBox.xMax() && yMax()==boundingBox.yMax();
    }

    @Override
    public SimpleImageProperties<T> duplicate() {
        return new SimpleImageProperties<>(this);
    }

    @Override
    public int xMin() {
        return xMin;
    }

    @Override
    public int yMin() {
        return yMin;
    }

    @Override
    public int zMin() {
        return zMin;
    }

    @Override
    public int getIntPosition(int dim) {
        switch(dim) {
            case 0:
                return xMin;
            case 1:
                return yMin;
            case 2:
                return zMin;
            default:
                throw new IllegalArgumentException("out-of-dimension");
        }
    }

    @Override
    public T resetOffset() {
        xMin = 0;
        yMin = 0;
        zMin = 0;
        offsetXY = 0;
        return (T)this;
    }

    @Override
    public T reverseOffset() {
        xMin = -xMin;
        yMin = -yMin;
        zMin = -zMin;
        offsetXY = xMin+yMin * sizeX;
        return (T)this;
    }

    @Override
    public T translate(Offset other) {
        xMin += other.xMin();
        yMin += other.yMin();
        zMin += other.zMin();
        offsetXY = xMin+yMin * sizeX;
        return (T)this;
    }

    @Override
    public T translate(int dx, int dy, int dz) {
        xMin += dx;
        yMin += dy;
        zMin += dz;
        offsetXY = xMin+yMin * sizeX;
        return (T)this;
    }

    @Override
    public Point getCenter() {
        return new Point((float)xMean(), (float)yMean(), (float)zMean());
    }

    @Override
    public boolean isValid() {
        return sizeX >0 && sizeY>0 && sizeZ>0;
    }

    public SimpleOffset getOffset() {
        return new SimpleOffset(xMin, yMin, zMin);
    }
}
