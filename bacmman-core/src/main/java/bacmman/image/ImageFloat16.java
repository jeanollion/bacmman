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

import bacmman.utils.ArrayUtil;

import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class ImageFloat16 extends ImageFloatingPoint<ImageFloat16>  {

    final private short[][] pixels;

    /**
     * Builds a new blank image with same properties as {@param properties}
     * @param name name of the new image
     * @param properties properties of the new image
     */
    public ImageFloat16(String name, ImageProperties properties) {
        super(name, properties);
        this.pixels=new short[sizeZ][sizeXY];
    }
    public ImageFloat16(String name, int sizeX, int sizeY, int sizeZ) {
        super(name, sizeX, sizeY, sizeZ);
        if (sizeZ>0 && sizeX>0 && sizeY>0) this.pixels=new short[sizeZ][sizeX*sizeY];
        else pixels = null;
    }

    public ImageFloat16(String name, int sizeX, short[][] pixels) {
        super(name, sizeX, sizeX>0?pixels[0].length/sizeX:0, pixels.length);
        this.pixels=pixels;
    }

    public ImageFloat16(String name, int sizeX, short[] pixels) {
        super(name, sizeX, sizeX>0?pixels.length/sizeX:0, 1);
        this.pixels=new short[][]{pixels};
    }

    @Override
    public ImageFloat16 getZPlane(int idxZ) {
        if (idxZ>=sizeZ) throw new IllegalArgumentException("Z-plane cannot be superior to sizeZ");
        else {
            ImageFloat16 res = new ImageFloat16(name+"_z"+String.format("%05d", idxZ), sizeX, pixels[idxZ]);
            res.setCalibration(this);
            res.translate(xMin, yMin, zMin+idxZ);
            return res;
        }
    }
    @Override public DoubleStream streamPlane(int z) {
        return ArrayUtil.stream(pixels[z]);
    }
    @Override public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        if (maskHasAbsoluteOffset) {
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(this, mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0,sizeXY).mapToDouble(i->mask.insideMask(i, z)?toHalfFloat(pixels[z][i]):Double.NaN).filter(v->!Double.isNaN(v));
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z+zMin)?toHalfFloat(pixels[z][x+y*sizeX-offsetXY]):Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
        else { // masks is relative to image
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0, sizeXY).mapToDouble(i->mask.insideMask(i, z)?toHalfFloat(pixels[z][i]):Double.NaN).filter(v->!Double.isNaN(v));
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z)?toHalfFloat(pixels[z][x+y*sizeX]):Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
    }

    public static float toHalfFloat(final short halfPrecision) {
        int mantisa = halfPrecision & 0x03ff;
        int exponent = halfPrecision & 0x7c00;

        if (exponent == 0x7c00) {
            exponent = 0x3fc00;
        } else if (exponent != 0) {
            exponent += 0x1c000;
            if (mantisa == 0 && exponent > 0x1c400) {
                return Float.intBitsToFloat(
                        (halfPrecision & 0x8000) << 16 | exponent << 13 | 0x3ff);
            }
        } else if (mantisa != 0) {
            exponent = 0x1c400;
            do {
                mantisa <<= 1;
                exponent -= 0x400;
            } while ((mantisa & 0x400) == 0);
            mantisa &= 0x3ff;
        }
        return Float.intBitsToFloat((halfPrecision & 0x8000) << 16 | (exponent | mantisa) << 13);
    }

    public static short toShort(final float number) {
        int fbits = Float.floatToIntBits(number);
        int sign = fbits >>> 16 & 0x8000;

        int val = (fbits & 0x7fffffff) + 0x1000;

        if (val >= 0x47800000) {
            if ((fbits & 0x7fffffff) >= 0x47800000) {
                if (val < 0x7f800000) {
                    return (short)(sign | 0x7c00);
                }
                return (short)(sign | 0x7c00 | (fbits & 0x007fffff) >>> 13);
            }
            return (short)(sign | 0x7bff);
        }
        if (val >= 0x38800000) {
            return (short)(sign | val - 0x38000000 >>> 13);
        }
        if (val < 0x33000000) {
            return (short)(sign);
        }
        val = (fbits & 0x7fffffff) >>> 23;
        return (short)(sign | ((fbits & 0x7fffff | 0x800000) + (0x800000 >>> val - 102) >>> 126 - val));
    }

    @Override
    public float getPixel(int x, int y, int z) {
        return toHalfFloat(pixels[z][x+y*sizeX]);
    }

    @Override
    public float getPixelLinInterX(int x, int y, int z, float dx) {
        if (dx==0) return toHalfFloat(pixels[z][x + y * sizeX]);
        return  (toHalfFloat(pixels[z][x + y * sizeX]) * (1-dx) + dx * toHalfFloat(pixels[z][x + 1 + y * sizeX]));
    }

    @Override
    public float getPixel(int xy, int z) {
        return toHalfFloat(pixels[z][xy]);
    }
    
    @Override
    public void setPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]=toShort((float)value);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]=toShort((float)value + toHalfFloat(pixels[z][x+y*sizeX]));
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        pixels[z][xy]=toShort((float)value);
    }
    
    public void setPixel(int x, int y, int z, float value) {
        pixels[z][x+y*sizeX]=toShort(value);
    }

    public void setPixelWithOffset(int x, int y, int z, float value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = toShort(value);
    }
    
    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = toShort((float)value);
    }
    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = toShort((float)value + toHalfFloat(pixels[z-zMin][x-offsetXY + y * sizeX]));
    }

    public void setPixel(int xy, int z, float value) {
        pixels[z][xy]=toShort(value);
    }
    
    @Override
    public float getPixelWithOffset(int x, int y, int z) {
        return toHalfFloat(pixels[z-zMin][x-offsetXY + y * sizeX]);
    }

    @Override
    public ImageFloat16 duplicate(String name) {
        short[][] newPixels = new short[sizeZ][sizeXY];
        for (int z = 0; z< sizeZ; ++z) System.arraycopy(pixels[z], 0, newPixels[z], 0, sizeXY);
        return new ImageFloat16(name, sizeX, newPixels).setCalibration(this).translate(this);
    }
    
    @Override
    public short[][] getPixelArray() {
        return pixels;
    }

    @Override
    public ImageFloat16 newImage(String name, ImageProperties properties) {
        return new ImageFloat16(name, properties);
    }
    
    @Override
    public void invert() {
        double[] minAndMax = this.getMinAndMax(null);
        float off = (float)minAndMax[1];
        for (int z = 0; z < sizeZ; z++) {
            for (int xy = 0; xy<sizeXY; ++xy) {
                pixels[z][xy] = toShort(off - toHalfFloat(pixels[z][xy]));
            }
        }
    }

    @Override public int getBitDepth() {return 32;} // return 32 so that it is considered as float. TODO improve this

    // image mask implementation
    
    @Override
    public boolean insideMask(int x, int y, int z) {
        return pixels[z][x+y*sizeX]!=0;
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return pixels[z][xy]!=0;
    }
    
    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x-offsetXY + y * sizeX]!=0;
    }

    @Override
    public int count() {
        int count = 0;
        for (int z = 0; z< sizeZ; ++z) {
            for (int xy=0; xy<sizeXY; ++xy) {
                if (pixels[z][xy]!=0) ++count;
            }
        }
        return count;
    }

    @Override
    public ImageMask duplicateMask() {
        return duplicate("");
    }
}
