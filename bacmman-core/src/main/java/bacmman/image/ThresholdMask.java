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
public class ThresholdMask extends SimpleImageProperties<ThresholdMask> implements ImageMask<ThresholdMask> {
    final InsideMaskFunction insideMask;
    final InsideMaskXYFunction insideMaskXY;
    final boolean is2D;
    public ThresholdMask(Image imageToThreshold, double threshold, boolean foregroundOverthreshold, boolean strict) {
        super(imageToThreshold);
        if (foregroundOverthreshold) {
            if (strict) insideMask = (x, y, z) -> imageToThreshold.getPixel(x, y, z)>threshold;
            else insideMask = (x, y, z) -> imageToThreshold.getPixel(x, y, z)>threshold;
        } else {
            if (strict) insideMask = (x, y, z) -> imageToThreshold.getPixel(x, y, z)<threshold;
            else insideMask = (x, y, z) -> imageToThreshold.getPixel(x, y, z)<=threshold;
        }
        if (foregroundOverthreshold) {
            if (strict) insideMaskXY = (xy, z) -> imageToThreshold.getPixel(xy, z)>threshold;
            else insideMaskXY = (xy, z) -> imageToThreshold.getPixel(xy, z)>threshold;
        } else {
            if (strict) insideMaskXY = (xy, z) -> imageToThreshold.getPixel(xy, z)<threshold;
            else insideMaskXY = (xy, z) -> imageToThreshold.getPixel(xy, z)<=threshold;
        }
        is2D = false;
    }
    /**
     * Construct ThresholdMask2D
     * @param imageToThreshold
     * @param threshold
     * @param foregroundOverthreshold
     * @param strict
     * @param z 
     */
    public ThresholdMask(Image imageToThreshold, double threshold, boolean foregroundOverthreshold, boolean strict, int z) {
        super(imageToThreshold);
        if (foregroundOverthreshold) {
            if (strict) insideMask = (x, y, zz) -> imageToThreshold.getPixel(x, y, z)>threshold;
            else insideMask = (x, y, zz) -> imageToThreshold.getPixel(x, y, z)>threshold;
        } else {
            if (strict) insideMask = (x, y, zz) -> imageToThreshold.getPixel(x, y, z)<threshold;
            else insideMask = (x, y, zz) -> imageToThreshold.getPixel(x, y, z)<=threshold;
        }
        if (foregroundOverthreshold) {
            if (strict) insideMaskXY = (xy, zz) -> imageToThreshold.getPixel(xy, z)>threshold;
            else insideMaskXY = (xy, zz) -> imageToThreshold.getPixel(xy, z)>threshold;
        } else {
            if (strict) insideMaskXY = (xy, zz) -> imageToThreshold.getPixel(xy, z)<threshold;
            else insideMaskXY = (xy, zz) -> imageToThreshold.getPixel(xy, z)<=threshold;
        }
        is2D=true;
    }
    public ThresholdMask(ImageProperties imageProperties, InsideMaskFunction insideMask, InsideMaskXYFunction insideMaskXY, boolean is2D) {
        super(imageProperties);
        this.insideMask=insideMask;
        this.insideMaskXY=insideMaskXY;
        this.is2D = is2D;
    }
    public static ThresholdMask or(ThresholdMask mask1, ThresholdMask mask2) {
        if (mask1.sizeX()!=mask2.sizeX() || mask1.sizeY()!=mask2.sizeY()) throw new IllegalArgumentException("Mask1 & 2 should have same XY dimensions");
        if (mask1.sizeZ()!=mask2.sizeZ() && !mask1.is2D && !mask2.is2D) throw new IllegalArgumentException("Mask1 & 2 should either have same Z dimensions or be 2D");
        return new ThresholdMask(mask1.is2D?mask2:mask1, (x, y, z)->mask1.insideMask.insideMask(x, y, z)||mask2.insideMask(x,y, z), (xy, z)->mask1.insideMask(xy, z)||mask2.insideMask(xy, z), mask1.is2D && mask2.is2D);
    }
    public static ThresholdMask and(ThresholdMask mask1, ThresholdMask mask2) {
        if (mask1.sizeX()!=mask2.sizeX() || mask1.sizeY()!=mask2.sizeY()) throw new IllegalArgumentException("Mask1 & 2 should have same XY dimensions");
        if (mask1.sizeZ()!=mask2.sizeZ() && !mask1.is2D && !mask2.is2D) throw new IllegalArgumentException("Mask1 & 2 should either have same Z dimensions or be 2D");
        return new ThresholdMask(mask1.is2D?mask2:mask1, (x, y, z)->mask1.insideMask.insideMask(x, y, z)&&mask2.insideMask(x,y, z), (xy, z)->mask1.insideMask(xy, z)&&mask2.insideMask(xy, z), mask1.is2D && mask2.is2D);
    }
    @Override
    public boolean insideMask(int x, int y, int z) {
        return insideMask.insideMask(x, y, z);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return insideMaskXY.insideMask(xy, z);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return insideMask.insideMask(x-xMin(), y-yMin(), z-zMin());
    }

    @Override
    public boolean insideMaskWithOffset(int xy, int z) {
        return insideMaskXY.insideMask(xy-(xMin()+sizeY()*yMin()), z-zMin());
    }

    @Override
    public int count() {
        int count = 0;
        for (int z = 0; z< sizeZ(); ++z) {
            for (int xy=0; xy<sizeXY(); ++xy) {
                if (insideMask(xy, z)) ++count;
            }
        }
        return count;
    }

    @Override
    public ThresholdMask duplicateMask() {
        return new ThresholdMask(this, insideMask, insideMaskXY, is2D);
    }

    public interface InsideMaskFunction {
        public boolean insideMask(int x, int y, int z);
    }
    public interface InsideMaskXYFunction {
        public boolean insideMask(int xy, int z);
    }
}
