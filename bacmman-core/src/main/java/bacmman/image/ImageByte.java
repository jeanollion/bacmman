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

public class ImageByte extends ImageInteger<ImageByte> {

    private byte[][] pixels;

    /**
     * Builds a new blank image with same properties as {@param properties}
     * @param name name of the new image
     * @param properties properties of the new image
     */
    public ImageByte(String name, ImageProperties properties) {
        super(name, properties);
        this.pixels=new byte[sizeZ][sizeXY];
    }

    public ImageByte(String name, int sizeX, int sizeY, int sizeZ) {
        super(name, sizeX, sizeY, sizeZ);
        this.pixels=new byte[sizeZ][sizeX*sizeY];
    }

    public ImageByte(String name, int sizeX, byte[][] pixels) {
        super(name, sizeX, sizeX>0?pixels[0].length/sizeX:0, pixels.length);
        this.pixels=pixels;
    }

    public ImageByte(String name, int sizeX, byte[] pixels) {
        super(name, sizeX, sizeX>0?pixels.length/sizeX:0, 1);
        this.pixels=new byte[][]{pixels};
    }

    @Override
    public ImageByte getZPlane(int idxZ) {
        if (idxZ>=sizeZ) throw new IllegalArgumentException("Z-plane cannot be superior to sizeZ");
        else {
            ImageByte res = new ImageByte(name+"_z"+String.format("%05d", idxZ), sizeX, pixels[idxZ]);
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
                else return IntStream.range(0,sizeXY).mapToDouble(i->mask.insideMask(i, z)?pixels[z][i]:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z+zMin)?pixels[z][x+y*sizeX-offsetXY]:Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
        else { // masks is relative to image
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0, sizeXY).mapToDouble(i->mask.insideMask(i, z)?pixels[z][i]:Double.NaN).filter(v->!Double.isNaN(v));
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z)?pixels[z][x+y*sizeX]:Double.NaN;}
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
                else return IntStream.range(0,sizeXY).map(i->mask.insideMask(i, z)?pixels[z][i]& 0xff:Integer.MAX_VALUE).filter(v->v!=Integer.MAX_VALUE);
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).map(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z+zMin)?pixels[z][x+y*sizeX-offsetXY]& 0xff:Integer.MAX_VALUE;}
                ).filter(v->v!=Integer.MAX_VALUE);
            }
        }
        else { // masks is relative to image
            if (z<0 || z>=sizeZ || z-mask.zMin()<0 || z-mask.zMin()>mask.sizeZ()) return IntStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return IntStream.empty();
            if (inter.sameDimensions(mask) && inter.sameDimensions(this)) {
                if (mask instanceof BlankMask) return this.streamIntPlane(z);
                else return IntStream.range(0, sizeXY).map(i->mask.insideMask(i, z)?pixels[z][i]& 0xff:Integer.MAX_VALUE).filter(v->v!=Integer.MAX_VALUE);
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).map(i->{
                        int x = i%sX+offX;
                        int y = i/sX+offY;
                        return mask.insideMaskWithOffset(x, y, z)?pixels[z][x+y*sizeX]& 0xff:Integer.MAX_VALUE;}
                ).filter(v->v!=Integer.MAX_VALUE);
            }
        }
    }
    @Override
    public int getPixelInt(int x, int y, int z) {
        return pixels[z][x + y * sizeX] & 0xff;
    }

    @Override
    public int getPixelInt(int xy, int z) {
        return pixels[z][xy] & 0xff;
    }

    @Override
    public float getPixel(int xy, int z) {
        return (float) (pixels[z][xy] & 0xff);
    }

    @Override
    public float getPixel(int x, int y, int z) {
        return (float) (pixels[z][x + y * sizeX] & 0xff);
    }

    @Override
    public float getPixelLinInterX(int x, int y, int z, float dx) {
        if (dx==0) return (float) (pixels[z][x + y * sizeX] & 0xff);
        return (float) ((pixels[z][x + y * sizeX] & 0xff) * (1-dx) + dx * (pixels[z][x + 1 + y * sizeX] & 0xff));
    }

    @Override
    public void setPixel(int x, int y, int z, int value) {
        pixels[z][x + y * sizeX] = (byte) value;
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, int value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (byte)value;
    }

    @Override
    public void setPixel(int xy, int z, int value) {
        pixels[z][xy] = (byte) value;
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        pixels[z][x + y * sizeX] = value<0?0:(value>255?(byte)255:(byte)value);
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = value<=0?0:(value>=255?(byte)255:(byte)value);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        pixels[z][x + y * sizeX] += value<0?0:(value>255?(byte)255:(byte)value);
    }

    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] += value<=0?0:(value>=255?(byte)255:(byte)value);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        pixels[z][xy] = value<=0?0:(value>=255?(byte)255:(byte)value);
    }

    @Override
    public int getPixelIntWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x-offsetXY + y * sizeX] & 0xff;
    }

    @Override
    public int getPixelIntWithOffset(int xy, int z) {
        return pixels[z-zMin][xy - offsetXY ] & 0xff;
    }

    @Override
    public void setPixelWithOffset(int xy, int z, int value) {
        pixels[z-zMin][xy - offsetXY] = value<=0?0:(value>=255?(byte)255:(byte)value);
    }

    @Override
    public float getPixelWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x-offsetXY + y * sizeX]& 0xff;
    }

    @Override
    public ImageByte duplicate(String name) {
        byte[][] newPixels = new byte[sizeZ][sizeXY];
        for (int z = 0; z< sizeZ; ++z) System.arraycopy(pixels[z], 0, newPixels[z], 0, sizeXY);
        ImageByte res = new ImageByte(name, sizeX, newPixels);
        res.setCalibration(this).translate(this);
        return res;
    }

    @Override public boolean insideMask(int x, int y, int z) {
        return pixels[z][x+y*sizeX]!=0;
    }

    @Override public boolean insideMask(int xy, int z) {
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

    @Override public boolean insideMaskWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x+y*sizeX-offsetXY]!=0;
    }

    @Override
    public byte[][] getPixelArray() {
        return pixels;
    }

    @Override
    public ImageByte newImage(String name, ImageProperties properties) {
        return new ImageByte(name, properties);
    }

    @Override
    public void invert() {
        for (int z = 0; z < sizeZ; z++) {
            for (int xy = 0; xy<sizeXY; ++xy) {
                pixels[z][xy] = (byte)(255 - pixels[z][xy]& 0xff);
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
                                pixels[zz][xx + yy * sizeX] = (byte)label;
                            }
                        }
                    }
                }
            }
        }
    }

    @Override public int getBitDepth() {return 8;}




}
