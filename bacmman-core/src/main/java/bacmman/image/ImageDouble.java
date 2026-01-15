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

public class ImageDouble extends ImageFloatingPoint<ImageDouble> implements PrimitiveType.DoubleType{

    final private double[][] pixels;

    /**
     * Builds a new blank image with same properties as {@param properties}
     * @param name name of the new image
     * @param properties properties of the new image
     */
    public ImageDouble(String name, ImageProperties properties) {
        super(name, properties);
        this.pixels=new double[sizeZ][sizeXY];
    }
    public ImageDouble(String name, int sizeX, int sizeY, int sizeZ) {
        super(name, sizeX, sizeY, sizeZ);
        if (sizeZ>0 && sizeX>0 && sizeY>0) this.pixels=new double[sizeZ][sizeX*sizeY];
        else pixels = null;
    }

    public ImageDouble(String name, int sizeX, double[][] pixels) {
        super(name, sizeX, sizeX>0?pixels[0].length/sizeX:0, pixels.length);
        this.pixels=pixels;
    }

    public ImageDouble(String name, int sizeX, double[] pixels) {
        super(name, sizeX, sizeX>0?pixels.length/sizeX:0, 1);
        this.pixels=new double[][]{pixels};
    }
    
    @Override
    public ImageDouble getZPlane(int z) {
        if (z >=sizeZ) throw new IllegalArgumentException("Z-plane cannot be superior to sizeZ");
        else {
            ImageDouble res = new ImageDouble(name+"_z"+String.format("%05d", z), sizeX, pixels[z]);
            res.setCalibration(this);
            res.translate(xMin, yMin, zMin+ z);
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
    @Override
    public double getPixel(int x, int y, int z) {
        return pixels[z][x+y*sizeX];
    }
    
    
    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        if (dx==0) return (float) (pixels[z][x + y * sizeX]);
        return (float) ((pixels[z][x + y * sizeX]) * (1-dx) + dx * (pixels[z][x + 1 + y * sizeX]));
    }

    @Override
    public double getPixel(int xy, int z) {
        return pixels[z][xy];
    }
    
    @Override
    public void setPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]=(float)value;
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        pixels[z][x+y*sizeX]+=(float)value;
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        pixels[z][xy]=(float)value;
    }
    
    public void setPixel(int x, int y, int z, float value) {
        pixels[z][x+y*sizeX]=value;
    }

    public void setPixelWithOffset(int x, int y, int z, float value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = value;
    }
    
    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] = (float)value;
    }
    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] += (float)value;
    }
    public void addPixelWithOffset(int x, int y, int z, float value) {
        pixels[z-zMin][x-offsetXY + y * sizeX] += value;
    }

    public void setPixel(int xy, int z, float value) {
        pixels[z][xy]=value;
    }
    
    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        return pixels[z-zMin][x-offsetXY + y * sizeX];
    }

    @Override
    public ImageDouble duplicate(String name) {
        double[][] newPixels = new double[sizeZ][sizeXY];
        for (int z = 0; z< sizeZ; ++z) System.arraycopy(pixels[z], 0, newPixels[z], 0, sizeXY);
        return (ImageDouble)new ImageDouble(name, sizeX, newPixels).setCalibration(this).translate(this);
    }
    
    @Override
    public double[][] getPixelArray() {
        return pixels;
    }

    @Override
    public ImageDouble newImage(String name, ImageProperties properties) {
        return new ImageDouble(name, properties);
    }
    
    @Override
    public void invert() {
        double[] minAndMax = this.getMinAndMax(null);
        float off = (float)minAndMax[1];
        for (int z = 0; z < sizeZ; z++) {
            for (int xy = 0; xy<sizeXY; ++xy) {
                pixels[z][xy] = off - pixels[z][xy];
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
