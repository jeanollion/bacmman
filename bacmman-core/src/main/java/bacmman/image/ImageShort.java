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

public class ImageShort extends ImageInteger<ImageShort> {

    private short[][] pixels;

    /**
     * Builds a new blank image with same properties as {@param properties}
     * @param name name of the new image
     * @param properties properties of the new image
     */
    public ImageShort(String name, ImageProperties properties) {
        super(name, properties);
        this.pixels=new short[sizeZ][sizeXY];
    }
    
    public ImageShort(String name, int sizeX, int sizeY, int sizeZ) {
        super(name, sizeX, sizeY, sizeZ);
        this.pixels=new short[sizeZ][sizeX*sizeY];
    }
    
    public ImageShort(String name, int sizeX, short[][] pixels) {
        super(name, sizeX, sizeX>0?pixels[0].length/sizeX:0, pixels.length);
        this.pixels=pixels;
    }
    
    public ImageShort(String name, int sizeX, short[] pixels) {
        super(name, sizeX, sizeX>0?pixels.length/sizeX:0, 1);
        this.pixels=new short[][]{pixels};
    }
    
    @Override
    public ImageShort getZPlane(int idxZ) {
        if (idxZ>=sizeZ) throw new IllegalArgumentException("Z-plane cannot be superior to sizeZ");
        else {
            ImageShort res = new ImageShort(name, sizeX, pixels[idxZ]);
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
            if (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ()) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(this, mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && inter.sameBounds(mask)) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0,sizeXY).mapToDouble(i->mask.insideMask(i, z)?pixels[z][i]&0xffff:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z+zMin)?pixels[z][x+y*sizeX-offsetXY]&0xffff:Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
        else { // masks is relative to image
            if (z<0 || z>=sizeZ || z-mask.zMin()<0 || z-mask.zMin()>mask.sizeZ()) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameDimensions(mask) && inter.sameDimensions(this)) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0, sizeXY).mapToDouble(i->mask.insideMask(i, z)?pixels[z][i]&0xffff:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z)?pixels[z][x+y*sizeX]&0xffff:Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
    }
    @Override public IntStream streamIntPlane(int z) {
        return ArrayUtil.streamInt(pixels[z]);
    }
    @Override public IntStream streamIntPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        if (maskHasAbsoluteOffset) {
            if (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ()) return IntStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(this, mask);
            if (inter.isEmpty()) return IntStream.empty();
            if (inter.sameBounds(this) && inter.sameBounds(mask)) {
                if (mask instanceof BlankMask) return this.streamIntPlane(z);
                else return IntStream.range(0,sizeXY).map(i->mask.insideMask(i, z)?pixels[z][i]&0xffff:Integer.MAX_VALUE).filter(v->v!=Integer.MAX_VALUE);
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).map(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z+zMin)?pixels[z][x+y*sizeX-offsetXY]&0xffff:Integer.MAX_VALUE;}
                ).filter(v->v!=Integer.MAX_VALUE);
            }
        }
        else { // masks is relative to image
            if (z<0 || z>=sizeZ || z-mask.zMin()<0 || z-mask.zMin()>mask.sizeZ()) return IntStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return IntStream.empty();
            if (inter.sameDimensions(mask) && inter.sameDimensions(this)) {
                if (mask instanceof BlankMask) return this.streamIntPlane(z);
                else return IntStream.range(0, sizeXY).map(i->mask.insideMask(i, z)?pixels[z][i]&0xffff:Integer.MAX_VALUE).filter(v->v!=Integer.MAX_VALUE);
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).map(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z)?pixels[z][x+y*sizeX]&0xffff:Integer.MAX_VALUE;}
                ).filter(v->v!=Integer.MAX_VALUE);
            }
        }
    }
    @Override
    public int getPixelInt(int x, int y, int z) {
        return pixels[z][x + y * sizeX] & 0xffff;
    }

    @Override
    public int getPixelInt(int xy, int z) {
        return pixels[z][xy] & 0xffff;
    }
    
    @Override
    public float getPixel(int xy, int z) {
        return (float) (pixels[z][xy] & 0xffff);
    }

    @Override
    public float getPixel(int x, int y, int z) {
        return (float) (pixels[z][x + y * sizeX] & 0xffff);
    }
    
    
    @Override
    public float getPixelLinInterX(int x, int y, int z, float dx) {
        if (dx==0) return (float) (pixels[z][x + y * sizeX] & 0xffff);
        return (float) ((pixels[z][x + y * sizeX] & 0xffff) * (1-dx) + dx * (pixels[z][x + 1 + y * sizeX] & 0xffff));
    }

    @Override
    public void setPixel(int x, int y, int z, int value) {
        pixels[z][x + y * sizeX] = (short) value;
    }
    
    @Override
    public void setPixelWithOffset(int x, int y, int z, int value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (short)value;
    }

    @Override
    public void setPixel(int xy, int z, int value) {
        pixels[z][xy] = (short) value;
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        pixels[z][x + y * sizeX] = value<0?0:(value>65535?(short)65535:(short)value);
    }
    
    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = value<0?0:(value>65535?(short)65535:(short)value);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        pixels[z][xy] = value<0?0:(value>65535?(short)65535:(short)value);
    }
    
    @Override
    public int getPixelIntWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x-offsetXY + y * sizeX] & 0xffff;
    }

    @Override
    public int getPixelIntWithOffset(int xy, int z) {
        return pixels[z-zMin][xy - offsetXY ] & 0xffff;
    }

    @Override
    public void setPixelWithOffset(int xy, int z, int value) {
        pixels[z-zMin][xy - offsetXY] = value<0?0:(value>65535?(short)65535:(short)value);
    }

    @Override
    public float getPixelWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x-offsetXY + y * sizeX] & 0xffff;
    }

    @Override
    public float getPixelWithOffset(int xy, int z) {
        return pixels[z-zMin][xy - offsetXY ] & 0xffff;
    }

    @Override
    public void setPixelWithOffset(int xy, int z, double value) {
        pixels[z-zMin][xy - offsetXY] = value<0?0:(value>65535?(short)65535:(short)value);
    }

    @Override
    public ImageShort duplicate(String name) {
        short[][] newPixels = new short[sizeZ][sizeXY];
        for (int z = 0; z< sizeZ; ++z) System.arraycopy(pixels[z], 0, newPixels[z], 0, sizeXY);
        return (ImageShort)new ImageShort(name, sizeX, newPixels).setCalibration(this).translate(this);
    }

    public boolean insideMask(int x, int y, int z) {
        return pixels[z][x+y*sizeX]!=0;
    }

    public boolean insideMask(int xy, int z) {
        return pixels[z][xy]!=0;
    }
    
    @Override public int count() {
        int count = 0;
        for (int z = 0; z< sizeZ; ++z) {
            for (int xy=0; xy<sizeXY; ++xy) {
                if (pixels[z][xy]!=0) ++count;
            }
        }
        return count;
    }
    
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x+y*sizeX-offsetXY]!=0;
    }

    public boolean insideMaskWithOffset(int xy, int z) {
        return pixels[z-zMin][xy-offsetXY]!=0;
    }
    
    @Override
    public short[][] getPixelArray() {
        return pixels;
    }
    
    @Override
    public ImageShort newImage(String name, ImageProperties properties) {
        return new ImageShort(name, properties);
    }
    
    @Override
    public void invert() {
        double[] minAndMax = this.getMinAndMax(null);
        int off = (int) (minAndMax[1] + minAndMax[0]);
        for (int z = 0; z < sizeZ; z++) {
            for (int xy = 0; xy<sizeXY; ++xy) {
                pixels[z][xy] = (short) (off - pixels[z][xy]&0xffff);
            }
        }
    }
    @Override
    public void appendBinaryMasks(int startLabel, ImageMask... masks) {
        if (masks == null || masks.length==0) return;
        if (startLabel==-1) startLabel = (int)this.getMinAndMax(null)[1]+1;
        //if (startLabel<0) startLabel=1;
        for (int idx = 0; idx < masks.length; ++idx) {
            int label = idx+startLabel;
            ImageMask currentImage = masks[idx];
            for (int z = 0; z < currentImage.sizeZ(); ++z) {
                for (int y = 0; y < currentImage.sizeY(); ++y) {
                    for (int x = 0; x < currentImage.sizeX(); ++x) {
                        if (currentImage.insideMask(x, y, z)) {
                            int xx = x + currentImage.xMin();
                            int yy = y + currentImage.yMin();
                            int zz = z + currentImage.zMin();
                            if (contains(xx, yy, zz)) {
                                pixels[zz][xx + yy * sizeX] = (short)label;
                            }
                        }
                    }
                }
            }
        }
    }
    
    @Override public int getBitDepth() {return 16;}
}