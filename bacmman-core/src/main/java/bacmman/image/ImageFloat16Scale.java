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

public class ImageFloat16Scale extends ImageFloatingPoint<ImageFloat16Scale> {

    final private short[][] pixels;
    private double scale;

    /**
     * Builds a new blank image with same properties as {@param properties}
     * @param name name of the new image
     * @param properties properties of the new image
     */
    public ImageFloat16Scale(String name, ImageProperties properties, double scale) {
        super(name, properties);
        this.pixels=new short[sizeZ][sizeXY];
        this.scale = scale;
    }
    public ImageFloat16Scale(String name, int sizeX, int sizeY, int sizeZ, double scale) {
        super(name, sizeX, sizeY, sizeZ);
        if (sizeZ>0 && sizeX>0 && sizeY>0) this.pixels=new short[sizeZ][sizeX*sizeY];
        else pixels = null;
        this.scale = scale;
    }

    public ImageFloat16Scale(String name, int sizeX, short[][] pixels, double scale) {
        super(name, sizeX, sizeX>0?pixels[0].length/sizeX:0, pixels.length);
        this.pixels=pixels;
        this.scale = scale;
    }

    public ImageFloat16Scale(String name, int sizeX, short[] pixels, double scale) {
        super(name, sizeX, sizeX>0?pixels.length/sizeX:0, 1);
        this.pixels=new short[][]{pixels};
        this.scale = scale;
    }
    public double getScale() {return scale;}
    public void multiply(double factor) {
        scale /= factor;
    }
    public static double getOptimalScale(double... minAndMax) {
        double min = minAndMax[0];
        double max = minAndMax.length>1 ? minAndMax[1] : min;
        if (Math.abs(max)>=Math.abs(min)) {
            return Short.MAX_VALUE / max;
        } else { // min<0
            return Short.MIN_VALUE / min;
        }
    }
    @Override
    public ImageFloat16Scale getZPlane(int idxZ) {
        if (idxZ>=sizeZ) throw new IllegalArgumentException("Z-plane cannot be superior to sizeZ");
        else {
            ImageFloat16Scale res = new ImageFloat16Scale(name+"_z"+String.format("%05d", idxZ), sizeX, pixels[idxZ], scale);
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
                else return IntStream.range(0,sizeXY).mapToDouble(i->mask.insideMask(i, z)?pixels[z][i]/scale:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z+zMin)?pixels[z][x+y*sizeX-offsetXY]/scale:Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
        else { // masks is relative to image
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0, sizeXY).mapToDouble(i->mask.insideMask(i, z)?pixels[z][i]/scale:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z)?pixels[z][x+y*sizeX]/scale:Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
    }
    @Override
    public float getPixel(int x, int y, int z) {
        return (float)(pixels[z][x+y*sizeX]/scale);
    }

    @Override
    public float getPixelLinInterX(int x, int y, int z, float dx) {
        if (dx==0) return (float)(pixels[z][x + y * sizeX]/scale);
        return  (float)((pixels[z][x + y * sizeX]/scale) * (1-dx) + dx * (pixels[z][x + 1 + y * sizeX]/scale));
    }

    @Override
    public float getPixel(int xy, int z) {
        return (float)(pixels[z][xy] / scale);
    }
    
    @Override
    public void setPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]=(short)Math.round(value*scale);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]+=(short) Math.round(value * scale);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        pixels[z][xy]=(short)Math.round(value*scale);
    }
    
    public void setPixel(int x, int y, int z, float value) {
        pixels[z][x+y*sizeX]=(short)Math.round(value*scale);
    }

    public void setPixelWithOffset(int x, int y, int z, float value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (short)Math.round(value*scale);
    }
    
    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (short)Math.round(value*scale);
    }
    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] += (short)Math.round(value*scale);
    }

    public void setPixel(int xy, int z, float value) {
        pixels[z][xy]=(short)Math.round(value*scale);
    }
    
    @Override
    public float getPixelWithOffset(int x, int y, int z) {
        return (float)(pixels[z-zMin][x-offsetXY + y * sizeX] / scale);
    }

    @Override
    public ImageFloat16Scale duplicate(String name) {
        short[][] newPixels = new short[sizeZ][sizeXY];
        for (int z = 0; z< sizeZ; ++z) System.arraycopy(pixels[z], 0, newPixels[z], 0, sizeXY);
        return (ImageFloat16Scale)new ImageFloat16Scale(name, sizeX, newPixels, scale).setCalibration(this).translate(this);
    }
    
    @Override
    public short[][] getPixelArray() {
        return pixels;
    }

    @Override
    public ImageFloat16Scale newImage(String name, ImageProperties properties) {
        return new ImageFloat16Scale(name, properties, scale);
    }
    
    @Override
    public void invert() {
        double[] minAndMax = this.getMinAndMax(null);
        short off = (short) (minAndMax[1] / scale);
        for (int z = 0; z < sizeZ; z++) {
            for (int xy = 0; xy<sizeXY; ++xy) {
                pixels[z][xy] = (short) (off - pixels[z][xy]);
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
