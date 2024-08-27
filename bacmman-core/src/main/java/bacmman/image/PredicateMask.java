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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 *
 * @author Jean Ollion
 */
public class PredicateMask extends SimpleImageProperties<PredicateMask> implements ImageMask<PredicateMask> {
    public final static Logger logger = LoggerFactory.getLogger(PredicateMask.class);
    final InsideMaskFunction insideMask;
    final InsideMaskXYFunction insideMaskXY;
    final boolean is2D;
    public PredicateMask(Image imageToThreshold, double threshold, boolean foregroundOverthreshold, boolean strict) {
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
    public PredicateMask(Image imageToThreshold, double thresholdLow, boolean thresholdLowStrict, double thresholdHigh, boolean thresholdHighStrict) {
        super(imageToThreshold);
        if (thresholdLowStrict && thresholdHighStrict) {
            insideMask = (x, y, z) -> {
                double v = imageToThreshold.getPixel(x, y, z);
                return v > thresholdLow && v < thresholdHigh;
            };
            insideMaskXY = (xy, z) -> {
                double v = imageToThreshold.getPixel(xy, z);
                return v > thresholdLow && v < thresholdHigh;
            };
        } else if (thresholdLowStrict) {
            insideMask = (x, y, z) -> {
                double v = imageToThreshold.getPixel(x, y, z);
                return v > thresholdLow && v <= thresholdHigh;
            };
            insideMaskXY = (xy, z) -> {
                double v = imageToThreshold.getPixel(xy, z);
                return v > thresholdLow && v <= thresholdHigh;
            };
        } else if (thresholdHighStrict) {
            insideMask = (x, y, z) -> {
                double v = imageToThreshold.getPixel(x, y, z);
                return v >= thresholdLow && v < thresholdHigh;
            };
            insideMaskXY = (xy, z) -> {
                double v = imageToThreshold.getPixel(xy, z);
                return v >= thresholdLow && v < thresholdHigh;
            };
        } else {
            insideMask = (x, y, z) -> {
                double v = imageToThreshold.getPixel(x, y, z);
                return v >= thresholdLow && v <= thresholdHigh;
            };
            insideMaskXY = (xy, z) -> {
                double v = imageToThreshold.getPixel(xy, z);
                return v >= thresholdLow && v <= thresholdHigh;
            };
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
    public PredicateMask(Image imageToThreshold, double threshold, boolean foregroundOverthreshold, boolean strict, int z) {
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
    public PredicateMask(ImageProperties imageProperties, InsideMaskFunction insideMask, InsideMaskXYFunction insideMaskXY, boolean is2D) {
        super(imageProperties);
        this.insideMask=insideMask;
        this.insideMaskXY=insideMaskXY;
        this.is2D = is2D;
    }

    /**
     * logical operator OR applied on two masks. throws IllegalArgumentException if mask do not intersect
     * @param mask1
     * @param mask2
     * @return mask with same bounds as mask1 (except if mask1 and mask2 have same bounds in XY and mask1 is 2D and mask2 is not 2D, then returned bounds of mask2)
     */
    public static PredicateMask or(ImageMask mask1, ImageMask mask2) {
        boolean oneImageMask2D = isMask2D(mask1) || isMask2D(mask2);
        if (!mask1.sameBounds2D(mask2) || (!oneImageMask2D && mask1.sizeZ()!=mask2.sizeZ())) { // reference mask is mask1
            if (oneImageMask2D) {
                if (!BoundingBox.intersect2D(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            } else {
                if (!BoundingBox.intersect(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            }
            Offset off = new SimpleOffset(mask1).translateReverse(mask2);
            return new PredicateMask(mask1, (x, y, z) -> mask1.insideMask(x, y, z) || (mask2.contains(x+off.xMin(), y+off.yMin(), z+off.zMin()) && mask2.insideMask(x+off.xMin(), y+off.yMin(), z+off.zMin())), (xy, z) -> {
                if (mask1.insideMask(xy, z)) return true;
                int y = xy / mask1.sizeX() + off.yMin();
                int x = xy % mask1.sizeX() + off.xMin();
                return mask2.contains(x, y, z+off.zMin()) && mask2.insideMask(x, y, z+off.zMin());
            }, isMask2D(mask1));
        } else { // same bounds or same bounds in 2D and one mask is 2D
            return new PredicateMask(isMask2D(mask1) ? mask2 : mask1, (x, y, z) -> mask1.insideMask(x, y, z) || mask2.insideMask(x, y, z), (xy, z) -> mask1.insideMask(xy, z) || mask2.insideMask(xy, z), isMask2D(mask1) && isMask2D(mask2));
        }
    }

    /**
     * logical operator AND applied on two masks. throws IllegalArgumentException if mask do not intersect
     * @param mask1
     * @param mask2
     * @return mask with same bounds as mask1 (except if mask1 and mask2 have same bounds in XY and mask1 is 2D and mask2 is not 2D, then returned bounds of mask2)
     */
    public static PredicateMask and(ImageMask mask1, ImageMask mask2) {
        boolean oneImageMask2D = isMask2D(mask1) || isMask2D(mask2);
        if (!mask1.sameBounds2D(mask2) || (!oneImageMask2D && mask1.sizeZ()!=mask2.sizeZ())) { // reference mask is mask1
            if (oneImageMask2D) {
                if (!BoundingBox.intersect2D(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            } else {
                if (!BoundingBox.intersect(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            }
            Offset off = new SimpleOffset(mask1).translateReverse(mask2);
            return new PredicateMask(mask1, (x, y, z) -> mask1.insideMask(x, y, z) && (mask2.contains(x+off.xMin(), y+off.yMin(), z+off.zMin()) && mask2.insideMask(x+off.xMin(), y+off.yMin(), z+off.zMin())), (xy, z) -> {
                if (!mask1.insideMask(xy, z)) return false;
                int y = xy / mask1.sizeX() + off.yMin();
                int x = xy % mask1.sizeX() + off.xMin();
                return mask2.contains(x, y, z+off.zMin()) && mask2.insideMask(x, y, z+off.zMin());
            }, isMask2D(mask1));
        } else { // same bounds or same bounds in 2D and one mask is 2D
            return new PredicateMask(isMask2D(mask1) ? mask2 : mask1, (x, y, z) -> mask1.insideMask(x, y, z) && mask2.insideMask(x, y, z), (xy, z) -> mask1.insideMask(xy, z) && mask2.insideMask(xy, z), isMask2D(mask1) && isMask2D(mask2));
        }
    }

    /**
     * logical operator XOR applied on two masks. throws IllegalArgumentException if mask do not intersect
     * @param mask1
     * @param mask2
     * @return mask with same bounds as mask1 (except if mask1 and mask2 have same bounds in XY and mask1 is 2D and mask2 is not 2D, then returned bounds of mask2)
     */
    public static PredicateMask xor(ImageMask mask1, ImageMask mask2) {
        boolean oneImageMask2D = isMask2D(mask1) || isMask2D(mask2);
        if (!mask1.sameBounds2D(mask2) || (!oneImageMask2D && mask1.sizeZ()!=mask2.sizeZ())) { // reference mask is mask1
            if (oneImageMask2D) {
                if (!BoundingBox.intersect2D(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            } else {
                if (!BoundingBox.intersect(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            }
            Offset off = new SimpleOffset(mask1).translateReverse(mask2);
            return new PredicateMask(mask1, (x, y, z) -> mask1.insideMask(x, y, z) != (mask2.contains(x+off.xMin(), y+off.yMin(), z+off.zMin()) && mask2.insideMask(x+off.xMin(), y+off.yMin(), z+off.zMin())), (xy, z) -> {
                int y = xy / mask1.sizeX() + off.yMin();
                int x = xy % mask1.sizeX() + off.xMin();
                return mask1.insideMask(xy, z) != mask2.contains(x, y, z+off.zMin()) && mask2.insideMask(x, y, z+off.zMin());
            }, isMask2D(mask1));
        } else { // same bounds or same bounds in 2D and one mask is 2D
            return new PredicateMask(isMask2D(mask1) ? mask2 : mask1, (x, y, z) -> mask1.insideMask(x, y, z) != mask2.insideMask(x, y, z), (xy, z) -> mask1.insideMask(xy, z) != mask2.insideMask(xy, z), isMask2D(mask1) && isMask2D(mask2));
        }
    }

    /**
     * logical operator XOR applied on two masks. throws IllegalArgumentException if mask do not intersect
     * @param mask1
     * @param mask2
     * @return mask with same bounds as mask1 (except if mask1 and mask2 have same bounds in XY and mask1 is 2D and mask2 is not 2D, then returned bounds of mask2)
     */
    public static PredicateMask andNot(ImageMask mask1, ImageMask mask2) {
        boolean oneImageMask2D = isMask2D(mask1) || isMask2D(mask2);
        if (!mask1.sameBounds2D(mask2) || (!oneImageMask2D && mask1.sizeZ()!=mask2.sizeZ())) { // reference mask is mask1
            if (oneImageMask2D) {
                if (!BoundingBox.intersect2D(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            } else {
                if (!BoundingBox.intersect(mask1, mask2)) throw new IllegalArgumentException("Masks do not intersect");
            }
            Offset off = new SimpleOffset(mask1).translateReverse(mask2);
            return new PredicateMask(mask1, (x, y, z) -> mask1.insideMask(x, y, z) && !(mask2.contains(x+off.xMin(), y+off.yMin(), z+off.zMin()) && mask2.insideMask(x+off.xMin(), y+off.yMin(), z+off.zMin())), (xy, z) -> {
                if (!mask1.insideMask(xy, z)) return false;
                int y = xy / mask1.sizeX() + off.yMin();
                int x = xy % mask1.sizeX() + off.xMin();
                return !(mask2.contains(x, y, z+off.zMin()) && mask2.insideMask(x, y, z+off.zMin()));
            }, isMask2D(mask1));
        } else { // same bounds or same bounds in 2D and one mask is 2D
            return new PredicateMask(isMask2D(mask1) ? mask2 : mask1, (x, y, z) -> mask1.insideMask(x, y, z) && !mask2.insideMask(x, y, z), (xy, z) -> mask1.insideMask(xy, z) && !mask2.insideMask(xy, z), isMask2D(mask1) && isMask2D(mask2));
        }
    }

    public static PredicateMask not(ImageMask mask) {
        return new PredicateMask(mask, (x, y, z) -> !mask.insideMask(x, y, z), (xy, z) -> !mask.insideMask(xy, z), isMask2D(mask));
    }

    protected static boolean isMask2D(ImageMask m) {
        return m instanceof PredicateMask ? ((PredicateMask) m).is2D : m instanceof ImageMask2D;
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
        return insideMask.insideMask(x-xMin, y-yMin, z-zMin());
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
    public PredicateMask duplicateMask() {
        return new PredicateMask(this, insideMask, insideMaskXY, is2D);
    }

    public interface InsideMaskFunction {
        public boolean insideMask(int x, int y, int z);
    }
    public interface InsideMaskXYFunction {
        public boolean insideMask(int xy, int z);
    }
}
