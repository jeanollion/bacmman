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
 *
 * @author Jean Ollion
 */
public class SimpleImageProperties<T extends SimpleImageProperties<T>> extends SimpleBoundingBox<T> implements ImageProperties<T> {
    protected double scaleXY, scaleZ;
    protected int sizeX, sizeY, sizeZ, sizeXY, sizeXYZ, offsetXY;
    protected String name;
    public SimpleImageProperties(ImageProperties properties) {
        this(properties, properties.getScaleXY(), properties.getScaleZ());
        this.name=properties.getName();
    }
    public SimpleImageProperties(BoundingBox bounds, double scaleXY, double scaleZ) {
        super(bounds);
        this.scaleXY=scaleXY;
        this.scaleZ = scaleZ;
        this.name="";
        this.sizeX = bounds.sizeX();
        this.sizeY = bounds.sizeY();
        this.sizeZ = bounds.sizeZ();
        this.sizeXY = sizeX*sizeY;
        this.sizeXYZ = sizeXY * sizeZ;
        updateOffset();
    }
    public SimpleImageProperties(String name, BoundingBox bounds, double scaleXY, double scaleZ) {
        this(bounds, scaleXY, scaleZ);
        this.name=name;
    }
    public SimpleImageProperties(int sizeX, int sizeY, int sizeZ, double scaleXY, double scaleZ) {
        this(new SimpleBoundingBox(0, sizeX-1, 0, sizeY-1, 0, sizeZ-1), scaleXY, scaleZ);
    }
    
    private void updateOffset() {
        offsetXY = xMin+yMin*sizeX;
    }
    
    @Override public T translate(int dX, int dY, int dZ) {
        super.translate(dX, dY, dZ);
        updateOffset();
        return (T)this;
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
    
    
}
