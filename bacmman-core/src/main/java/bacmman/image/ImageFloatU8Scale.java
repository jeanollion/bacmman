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
import bacmman.utils.Utils;

import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class ImageFloatU8Scale extends ImageFloatingPoint<ImageFloatU8Scale> implements PrimitiveType.ByteType {

    final private byte[][] pixels;
    final private double scale;

    /**
     * Builds a new blank image with same properties as {@param properties}
     * @param name name of the new image
     * @param properties properties of the new image
     */
    public ImageFloatU8Scale(String name, ImageProperties properties, double scale) {
        super(name, properties);
        this.pixels=new byte[sizeZ][sizeXY];
        this.scale = scale;
    }
    public ImageFloatU8Scale(String name, int sizeX, int sizeY, int sizeZ, float scale) {
        super(name, sizeX, sizeY, sizeZ);
        if (sizeZ>0 && sizeX>0 && sizeY>0) this.pixels=new byte[sizeZ][sizeX*sizeY];
        else pixels = null;
        this.scale = scale;
    }

    public ImageFloatU8Scale(String name, int sizeX, byte[][] pixels, double scale) {
        super(name, sizeX, sizeX>0?pixels[0].length/sizeX:0, pixels.length);
        this.pixels=pixels;
        this.scale = scale;
    }

    public ImageFloatU8Scale(String name, int sizeX, byte[] pixels, double scale) {
        super(name, sizeX, sizeX>0?pixels.length/sizeX:0, 1);
        this.pixels=new byte[][]{pixels};
        this.scale = scale;
    }
    public double getScale() {return scale;}
    public static double getOptimalScale(double... minAndMax) {
        double min = minAndMax[0];
        double max = minAndMax.length>1 ? minAndMax[1] : min;
        if (min < 0) throw new IllegalArgumentException("Minimum should be positive: min value="+ Utils.format(min, 5));
        if (max <= 0) throw new IllegalArgumentException("Maximum should be strictly positive");
        return 255. / max;
    }
    @Override
    public ImageFloatU8Scale getZPlane(int idxZ) {
        if (idxZ>=sizeZ) throw new IllegalArgumentException("Z-plane cannot be superior to sizeZ");
        else {
            ImageFloatU8Scale res = new ImageFloatU8Scale(name+"_z"+String.format("%05d", idxZ), sizeX, pixels[idxZ], scale);
            res.setCalibration(this);
            res.translate(xMin, yMin, zMin+idxZ);
            return res;
        }
    }
    @Override public DoubleStream streamPlane(int z) {
        return IntStream.range(0, sizeXY).mapToDouble(i->(pixels[z][i]&0xff)/scale);
    }
    @Override public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        if (maskHasAbsoluteOffset) {
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(this, mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0,sizeXY).mapToDouble(i->mask.insideMask(i, z)?(pixels[z][i]& 0xff)/scale:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z+zMin)?(pixels[z][x+y*sizeX-offsetXY]& 0xff)/scale:Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
        else { // masks is relative to image
            if (z<0 || z>=sizeZ || z-mask.zMin()<0 || z-mask.zMin()>mask.sizeZ()) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0, sizeXY).mapToDouble(i->mask.insideMask(i, z)?(pixels[z][i]& 0xff)/scale:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z)?(pixels[z][x+y*sizeX]& 0xff)/scale:Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
    }
    @Override
    public double getPixel(int x, int y, int z) {
        return (pixels[z][x+y*sizeX]& 0xff)/scale;
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        if (dx==0) return (pixels[z][x + y * sizeX]& 0xff)/scale;
        return  (pixels[z][x + y * sizeX]& 0xff) * (1-dx) + dx * (pixels[z][x + 1 + y * sizeX]& 0xff)/scale;
    }

    @Override
    public double getPixel(int xy, int z) {
        return (float)((pixels[z][xy]& 0xff) / scale);
    }
    
    @Override
    public void setPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]=(byte)(value<=0?0:value*scale+0.5);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]+=(byte)(value<=0?0:value * scale+0.5);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        pixels[z][xy]=(byte)(value<=0?0:value*scale+0.5);
    }
    
    public void setPixel(int x, int y, int z, float value) {
        pixels[z][x+y*sizeX]=(byte)(value<=0?0:value*scale+0.5);
    }

    public void setPixelWithOffset(int x, int y, int z, float value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (byte)(value<=0?0:value*scale+0.5);
    }
    
    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (byte)(value<=0?0:value*scale+0.5);
    }
    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] += (value<=0?0:value*scale+0.5);
    }

    public void setPixel(int xy, int z, float value) {
        pixels[z][xy]=(byte)(value<=0?0:value*scale+0.5);
    }
    
    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        return (pixels[z-zMin][x-offsetXY + y * sizeX]& 0xff) / scale;
    }

    @Override
    public ImageFloatU8Scale duplicate(String name) {
        byte[][] newPixels = new byte[sizeZ][sizeXY];
        for (int z = 0; z< sizeZ; ++z) System.arraycopy(pixels[z], 0, newPixels[z], 0, sizeXY);
        return new ImageFloatU8Scale(name, sizeX, newPixels, scale).setCalibration(this).translate(this);
    }
    
    @Override
    public byte[][] getPixelArray() {
        return pixels;
    }

    @Override
    public boolean floatingPoint() {return true;}

    @Override
    public ImageFloatU8Scale newImage(String name, ImageProperties properties) {
        return new ImageFloatU8Scale(name, properties, scale);
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
