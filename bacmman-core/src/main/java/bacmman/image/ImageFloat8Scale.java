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

public class ImageFloat8Scale extends ImageFloatingPoint<ImageFloat8Scale> implements PrimitiveType.ByteType{

    final private byte[][] pixels;
    final private double scale;

    /**
     * Builds a new blank image with same properties as {@param properties}
     * @param name name of the new image
     * @param properties properties of the new image
     */
    public ImageFloat8Scale(String name, ImageProperties properties, double scale) {
        super(name, properties);
        this.pixels=new byte[sizeZ][sizeXY];
        this.scale = scale;
    }
    public ImageFloat8Scale(String name, int sizeX, int sizeY, int sizeZ, float scale) {
        super(name, sizeX, sizeY, sizeZ);
        if (sizeZ>0 && sizeX>0 && sizeY>0) this.pixels=new byte[sizeZ][sizeX*sizeY];
        else pixels = null;
        this.scale = scale;
    }

    public ImageFloat8Scale(String name, int sizeX, byte[][] pixels, double scale) {
        super(name, sizeX, sizeX>0?pixels[0].length/sizeX:0, pixels.length);
        this.pixels=pixels;
        this.scale = scale;
    }

    public ImageFloat8Scale(String name, int sizeX, byte[] pixels, double scale) {
        super(name, sizeX, sizeX>0?pixels.length/sizeX:0, 1);
        this.pixels=new byte[][]{pixels};
        this.scale = scale;
    }
    public double getScale() {return scale;}
    public static double getOptimalScale(double... minAndMax) {
        double min = minAndMax[0];
        double max = minAndMax.length>1 ? minAndMax[1] : min;
        if (Math.abs(max)>=Math.abs(min)) {
            return Byte.MAX_VALUE / max;
        } else { // min<0
            return Byte.MIN_VALUE / min;
        }
    }
    @Override
    public ImageFloat8Scale getZPlane(int idxZ) {
        if (idxZ>=sizeZ) throw new IllegalArgumentException("Z-plane cannot be superior to sizeZ");
        else {
            ImageFloat8Scale res = new ImageFloat8Scale(name+"_z"+String.format("%05d", idxZ), sizeX, pixels[idxZ], scale);
            res.setCalibration(this);
            res.translate(xMin, yMin, zMin+idxZ);
            return res;
        }
    }
    @Override public DoubleStream streamPlane(int z) {
        return IntStream.range(0, sizeXY).mapToDouble(i->pixels[z][i]/scale);
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
    public double getPixel(int x, int y, int z) {
        return pixels[z][x+y*sizeX]/scale;
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        if (dx==0) return pixels[z][x + y * sizeX]/scale;
        return  (pixels[z][x + y * sizeX]) * (1-dx) + dx * (pixels[z][x + 1 + y * sizeX])/scale;
    }

    @Override
    public double getPixel(int xy, int z) {
        return pixels[z][xy] / scale;
    }
    
    @Override
    public void setPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]=(byte)Math.round(value*scale);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]+=(byte) Math.round(value * scale);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        pixels[z][xy]=(byte)Math.round(value*scale);
    }
    
    public void setPixel(int x, int y, int z, float value) {
        pixels[z][x+y*sizeX]=(byte)Math.round(value*scale);
    }

    public void setPixelWithOffset(int x, int y, int z, float value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (byte)Math.round(value*scale);
    }
    
    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (byte)Math.round(value*scale);
    }
    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] += Math.round(value*scale);
    }

    public void setPixel(int xy, int z, float value) {
        pixels[z][xy]=(byte)Math.round(value*scale);
    }
    
    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x-offsetXY + y * sizeX] / scale;
    }

    @Override
    public ImageFloat8Scale duplicate(String name) {
        byte[][] newPixels = new byte[sizeZ][sizeXY];
        for (int z = 0; z< sizeZ; ++z) System.arraycopy(pixels[z], 0, newPixels[z], 0, sizeXY);
        return new ImageFloat8Scale(name, sizeX, newPixels, scale).setCalibration(this).translate(this);
    }
    
    @Override
    public byte[][] getPixelArray() {
        return pixels;
    }

    @Override
    public boolean floatingPoint() {return true;}

    @Override
    public ImageFloat8Scale newImage(String name, ImageProperties properties) {
        return new ImageFloat8Scale(name, properties, scale);
    }
    
    @Override
    public void invert() {
        double[] minAndMax = this.getMinAndMax(null);
        short off = (short) (minAndMax[1] / scale);
        for (int z = 0; z < sizeZ; z++) {
            for (int xy = 0; xy<sizeXY; ++xy) {
                pixels[z][xy] = (byte) (off - pixels[z][xy]);
            }
        }
    }

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
