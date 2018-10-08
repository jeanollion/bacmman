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

import bacmman.utils.StreamConcatenation;
import java.util.TreeMap;
import java.util.stream.IntStream;

public abstract class ImageInteger<I extends ImageInteger<I>> extends Image<I> implements ImageMask<I> {

    protected ImageInteger(String name, int sizeX, int sizeY, int sizeZ, int offsetX, int offsetY, int zMin, float scaleXY, float scaleZ) {
        super(name, sizeX, sizeY, sizeZ, offsetX, offsetY, zMin, scaleXY, scaleZ);
    }
    
    protected ImageInteger(String name, int sizeX, int sizeY, int sizeZ) {
        super(name, sizeX, sizeY, sizeZ);
    }
    
    protected ImageInteger(String name, ImageProperties properties) {
        super(name, properties);
    } 
    
    public static ImageInteger createEmptyLabelImage(String name, int maxLabelNumber, ImageProperties properties) {
        if (maxLabelNumber<=255) return new ImageByte(name, properties);
        else if (maxLabelNumber<=65535) return new ImageShort(name, properties);
        else return new ImageInt(name, properties);
    }
    
    public static int getMaxValue(ImageInteger image, boolean limitToShort) {
        if (image instanceof ImageByte) return 255;
        else if (image instanceof ImageShort || limitToShort) return 65535;
        else return Integer.MAX_VALUE;
    }
    
    @Override public abstract I duplicate(String name);
    @Override public I duplicateMask() {
        return duplicate("");
    }
    public abstract int getPixelInt(int x, int y, int z);
    public abstract int getPixelInt(int xy, int z);
    public abstract int getPixelIntWithOffset(int x, int y, int z);
    public abstract int getPixelIntWithOffset(int xy, int z);
    public abstract void setPixel(int x, int y, int z, int value);
    public abstract void setPixelWithOffset(int x, int y, int z, int value);
    public abstract void setPixel(int xy, int z, int value);
    public abstract void setPixelWithOffset(int xy, int z, int value);
    public IntStream streamInt(ImageMask mask, boolean useOffset) {
        int minZ = useOffset? Math.max(zMin, mask.zMin()) : 0;
        int maxZ = useOffset ? Math.min(zMin+sizeZ, mask.zMin()+mask.sizeZ()) :  Math.min(sizeZ, mask.sizeZ());
        if (minZ>=maxZ) return IntStream.empty();
        if (minZ==maxZ-1) return streamIntPlane(minZ, mask, useOffset);
        return StreamConcatenation.concat((IntStream[])IntStream.range(minZ, maxZ).mapToObj(z->streamIntPlane(z, mask, useOffset)).filter(s->s!=IntStream.empty()).toArray(s->new IntStream[s]));
    }
    public IntStream streamInt() {
        if (sizeZ==1) return streamIntPlane(0);
        return StreamConcatenation.concat((IntStream[])IntStream.range(0, sizeZ).mapToObj(z->streamIntPlane(z)).toArray(s->new IntStream[s]));
    }
    public abstract IntStream streamIntPlane(int z);
    public abstract IntStream streamIntPlane(int z, ImageMask mask, boolean useOffset);
    /**
     * 
     * @param addBorder
     * @return TreeMap with Key (Integer) = label of the object / Value Bounding Box of the object
     * @see MutableBoundingBox
     */
    public TreeMap<Integer, MutableBoundingBox> getBounds(boolean addBorder) {
        TreeMap<Integer, MutableBoundingBox> bounds = new TreeMap<>();
        for (int z = 0; z < sizeZ; ++z) {
            for (int y = 0; y < sizeY; ++y) {
                for (int x = 0; x < sizeX; ++x) {
                    int value = getPixelInt(x + y * sizeX, z);
                    if (value != 0) {
                        MutableBoundingBox bds = bounds.get(value);
                        if (bds != null) {
                            bds.unionX(x);
                            bds.unionY(y);
                            bds.unionZ(z);
                        } else {
                            bds= new MutableBoundingBox(x, y, z);
                            bounds.put(value, bds);
                        }
                    }
                }
            }
        }
        if (addBorder) {
            for (MutableBoundingBox bds : bounds.values()) {
                bds.addBorder();
                //bds.trimToImage(this);
            }
        }
        return bounds;
    }

    public ImageByte cropLabel(int label, BoundingBox bounds) {
        //bounds.trimToImage(this);
        ImageByte res = new ImageByte(name, new SimpleImageProperties(bounds, scaleXY, scaleZ));
        byte[][] pixels = res.getPixelArray();
        int x_min = bounds.xMin();
        int y_min = bounds.yMin();
        int z_min = bounds.zMin();
        int x_max = bounds.xMax();
        int y_max = bounds.yMax();
        int z_max = bounds.zMax();
        res.resetOffset().translate(bounds);
        int sX = res.sizeX();
        int oZ = -z_min;
        int oY_i = 0;
        int oX = 0;
        oX=-x_min;
        if (x_min <= -1) {
            x_min = 0;
        }
        if (x_max >= sizeX) {
            x_max = sizeX - 1;
        }
        if (y_min <= -1) {
            oY_i = -sX * y_min;
            y_min = 0;
        }
        if (y_max >= sizeY) {
            y_max = sizeY - 1;
        }
        if (z_min <= -1) {
            z_min = 0;
        }
        if (z_max >= sizeZ) {
            z_max = sizeZ - 1;
        }
        for (int z = z_min; z <= z_max; ++z) {
            int oY = oY_i;
            for (int y = y_min; y <= y_max; ++y) {
                for (int x = x_min; x<=x_max; ++x) {
                    if (getPixelInt(x, y, z) == label) {
                        pixels[z + oZ][oY + x + oX] = (byte) 1;
                    }
                }
                oY += sX;
            }
        }
        return res;
    }
    
    /**
     * 
     * @param startLabel if (startLabel==-1) startLabel = max+1
     * @param masks 
     */
    public abstract void appendBinaryMasks(int startLabel, ImageMask... masks);
    
    public static ImageInteger mergeBinary(ImageProperties properties, ImageMask... masks) {
        if (masks==null || masks.length==0) return new ImageByte("merge", properties);
        ImageInteger res;
        res = createEmptyLabelImage("merge", masks.length, properties);
        res.appendBinaryMasks(1, masks);
        return res;
    }
}
