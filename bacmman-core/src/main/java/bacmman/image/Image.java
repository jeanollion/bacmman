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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.StreamConcatenation;
import bacmman.utils.Utils;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;


public abstract class Image<I extends Image<I>> extends SimpleImageProperties<I> implements ImageMask<I>, PrimitiveType {
    public final static Logger logger = LoggerFactory.getLogger(Image.class);

    public static void pasteImage(Image source, ImageMask sourceMask, Image dest, Offset offset) {
        if (source.getClass() != dest.getClass()) {
            throw new IllegalArgumentException("Paste Image: source and destination should be of the same type (source: " + source.getClass().getSimpleName() + " destination: " + dest.getClass().getSimpleName() + ")");
        }
        if (offset == null) {
            offset = new MutableBoundingBox(0, 0, 0);
        }
        if (source.sizeX() + offset.xMin() > dest.sizeX() || source.sizeY() + offset.yMin() > dest.sizeY() || source.sizeZ() + offset.zMin() > dest.sizeZ()) {
            throw new IllegalArgumentException("Paste Image: source (" + source.getBoundingBox().resetOffset() + ") does not fit in destination (" + dest.getBoundingBox().resetOffset() + ") offset: " + offset);
        }
        int oX = offset.xMin();
        int oY = offset.yMin();
        int oZ = offset.zMin();
        ImageMask.loop(sourceMask, (x, y, z) -> {
            dest.setPixel(x + oX, y + oY, z + oZ, source.getPixel(x, y, z));
        });
    }
    public static void pasteImage(Image source, Image dest, Offset offset) {
        if (source.getClass() != dest.getClass()) {
            throw new IllegalArgumentException("Paste Image: source and destination should be of the same type (source: " + source.getClass().getSimpleName() + " destination: " + dest.getClass().getSimpleName() + ")");
        }
        if (offset == null) {
            offset = new MutableBoundingBox(0, 0, 0);
        }
        if (source.sizeX() + offset.xMin() > dest.sizeX() || source.sizeY() + offset.yMin() > dest.sizeY() || source.sizeZ() + offset.zMin() > dest.sizeZ()) {
            throw new IllegalArgumentException("Paste Image: source (" + source.getBoundingBox().resetOffset() + ") does not fit in destination (" + dest.getBoundingBox().resetOffset() + ") offset: " + offset);
        }
        Object[] sourceP = source.getPixelArray();
        Object[] destP = dest.getPixelArray();
        final int offDestFinal = dest.sizeX() * offset.yMin() + offset.xMin();
        int offDest = offDestFinal;
        int offSource = 0;
        for (int z = 0; z < source.sizeZ(); ++z) {
            for (int y = 0; y < source.sizeY(); ++y) {
                //logger.debug("paste imate: z source: {}, z dest: {}, y source: {} y dest: {} off source: {} off dest: {} size source: {} size dest: {}", z, z+offset.getzMin(), y, y+offset.getyMin(), offSource, off, ((byte[])sourceP[z]).length, ((byte[])destP[z+offset.getzMin()]).length);
                System.arraycopy(sourceP[z], offSource, destP[z + offset.zMin()], offDest, source.sizeX());
                offDest += dest.sizeX();
                offSource += source.sizeX();
            }
            offDest = offDestFinal;
            offSource = 0;
        }
    }

    public static void pasteImageView(Image source, Image dest, Offset destinationOffset, BoundingBox sourceView) {
        if (source.getClass() != dest.getClass()) {
            throw new IllegalArgumentException("Paste Image: source and destination should be of the same type (source: " + source.getClass().getSimpleName() + " destination: " + dest.getClass().getSimpleName() + ")");
        }
        if (destinationOffset == null) {
            destinationOffset = new MutableBoundingBox(0, 0, 0);
        }
        if (sourceView == null) {
            sourceView = source.getBoundingBox();
        }
        if (sourceView.sizeX() + destinationOffset.xMin() > dest.sizeX() || sourceView.sizeY() + destinationOffset.yMin() > dest.sizeY() || sourceView.sizeZ() + destinationOffset.zMin() > dest.sizeZ()) {
            throw new IllegalArgumentException("Paste Image: source does not fit in destination");
        }
        if (sourceView.sizeX() == 0 || sourceView.sizeY() == 0 || sourceView.sizeZ() == 0) {
            throw new IllegalArgumentException("Source view volume null: sizeX:" + sourceView.sizeX() + " sizeY:" + sourceView.sizeY() + " sizeZ:" + sourceView.sizeZ());
        }
        Object[] sourceP = source.getPixelArray();
        Object[] destP = dest.getPixelArray();
        final int offDestFinal = dest.sizeX() * destinationOffset.yMin() + destinationOffset.xMin();
        destinationOffset.translate(new SimpleOffset(sourceView).reverseOffset()); //loop is made over source coords
        int offDest = offDestFinal;
        final int offSourceFinal = sourceView.xMin() + sourceView.yMin() * source.sizeX();
        int offSource = offSourceFinal;
        for (int z = sourceView.zMin(); z <= sourceView.zMax(); ++z) {
            for (int y = sourceView.yMin(); y <= sourceView.yMax(); ++y) {
                //logger.debug("paste image: z source: {}, z dest: {}, y source: {} y dest: {} x source: {} x dest: {}", z, z+destinationOffset.getzMin(), y, y+destinationOffset.getyMin(), offSource-y*source.getSizeX(), offDest-(y+destinationOffset.getyMin())*dest.getSizeX());
                System.arraycopy(sourceP[z], offSource, destP[z + destinationOffset.zMin()], offDest, sourceView.sizeX());
                offDest += dest.sizeX();
                offSource += source.sizeX();
            }
            offDest = offDestFinal;
            offSource = offSourceFinal;
        }
    }

    protected LUT lut=LUT.Grays;
    
    protected Image(String name, int sizeX, int sizeY, int sizeZ, int offsetX, int offsetY, int offsetZ, double scaleXY, double scaleZ) {
        super(name, new SimpleBoundingBox(offsetX, offsetX+sizeX-1, offsetY, offsetY+sizeY-1, offsetZ, offsetZ+sizeZ-1), scaleXY, scaleZ);
    }
    
    protected Image(String name, int sizeX, int sizeY, int sizeZ) {
        this(name, sizeX, sizeY, sizeZ, 0, 0, 0, 1, 1);
    }
    
    protected Image(String name, ImageProperties properties) {
        super(properties);
        this.name = name;
    }
    
    public I setName(String name) {
        this.name=name;
        return (I)this;
    }
    
    public SimpleImageProperties getProperties() {return new SimpleImageProperties(this);}

    public static <T extends Image<T>> T createEmptyImage(String name, Image<T> imageType, ImageProperties properties) {
        return imageType.newImage(name, properties);
    }
    public static <T extends Image<T>> T copyType(Image<T> imageType) {
        return imageType.newImage("", new SimpleImageProperties("", new SimpleBoundingBox(0, -1, 0, -1, 0, -1), imageType.getScaleXY(), imageType.getScaleZ()));
    }
    
    public static Image createImageFrom2DPixelArray(String name, Object pixelArray, int sizeX) {
        if (pixelArray instanceof byte[]) return new ImageByte(name, sizeX, (byte[])pixelArray);
        else if (pixelArray instanceof short[]) return new ImageShort(name, sizeX, (short[])pixelArray);
        else if (pixelArray instanceof float[]) return new ImageFloat(name, sizeX, (float[])pixelArray);
        else if (pixelArray instanceof int[]) return new ImageInt(name, sizeX, (int[])pixelArray);
        else throw new IllegalArgumentException("Pixel Array should be of type byte, short, float or int");
    }
    
    public abstract I getZPlane(int idxZ);
    
    /**
     * 
     * @param zLimit array containing minimal Z plane idx (included) and maximal Z-plane idx (included).
     * @return List of Z planes
     */
    public List<I> splitZPlanes(int... zLimit) {
        int zMin = 0;
        int zMax = this.sizeZ()-1;
        if (zLimit.length>0) zMin = Math.max(zMin, zLimit[0]);
        if (zLimit.length>1) zMax = Math.min(zMax, zLimit[1]);
        ArrayList<I> res = new ArrayList<>(sizeZ());
        for (int i = zMin; i<=zMax; ++i) res.add(getZPlane(i));
        return res;
    }
    public static <T extends Image<T>> T mergeZPlanesResize(List<T> planes, boolean expand) {
        if (planes==null || planes.isEmpty()) return null;
        Iterator<T> it = planes.iterator();
        MutableBoundingBox bds  = it.next().getBoundingBox().resetOffset();
        if (expand) while (it.hasNext()) bds.union(it.next().getBoundingBox().resetOffset());
        else while(it.hasNext()) bds.contract(it.next().getBoundingBox().resetOffset());
        bds.resetOffset();
        logger.debug("after contract: {}", bds);
        planes = Utils.transform(planes, p -> p.getBoundingBox().resetOffset().equals(bds) ? p : p.crop(bds.duplicate().center(p.getBoundingBox().resetOffset())));
        return mergeZPlanes(planes);
    }
    public static <T extends Image<T>> T mergeZPlanes(T... planes) {
        return mergeZPlanes(Arrays.asList(planes));
    }
    public static <T extends Image<T>> T mergeZPlanes(List<T> planes) {
        if (planes==null || planes.isEmpty()) return null;
        int maxZ  = planes.stream().mapToInt(SimpleBoundingBox::sizeZ).max().getAsInt();
        if (maxZ>1) planes = planes.stream().map(Image::splitZPlanes).flatMap(List::stream).collect(Collectors.toList());
        String title = "merged planes";
        Image<T> plane0 = planes.get(0);
        if (plane0 instanceof ImageByte) {
            byte[][] pixels = new byte[planes.size()][];
            for (int i = 0; i<pixels.length; ++i) pixels[i]=((byte[][])planes.get(i).getPixelArray())[0];
            return (T)new ImageByte(title, plane0.sizeX(), pixels).setCalibration(plane0).translate(plane0);
        } else if (plane0 instanceof ImageShort) {
            short[][] pixels = new short[planes.size()][];
            for (int i = 0; i<pixels.length; ++i) pixels[i]=((short[][])planes.get(i).getPixelArray())[0];
            return (T)new ImageShort(title, plane0.sizeX(), pixels).setCalibration(plane0).translate(plane0);
        } else if (plane0 instanceof ImageFloat) {
            float[][] pixels = new float[planes.size()][];
            for (int i = 0; i<pixels.length; ++i) pixels[i]=((float[][])planes.get(i).getPixelArray())[0];
            return (T)new ImageFloat(title, plane0.sizeX(), pixels).setCalibration(plane0).translate(plane0);
        } else if (plane0 instanceof ImageInt) {
            int[][] pixels = new int[planes.size()][];
            for (int i = 0; i<pixels.length; ++i) pixels[i]=((int[][])planes.get(i).getPixelArray())[0];
            return (T)new ImageInt(title, plane0.sizeX(), pixels).setCalibration(plane0).translate(plane0);
        } else {
            logger.error("merge plane Z: unrecognized image type");
            return null;
        }
    }
    /**
     * 
     * @param <T> images type
     * @param images images to merge
     * @return array of image, dimension of array = z dimension of original image, each image has the corresponding z plane of each image of {@param images}
     */
    public static <T extends Image<T>> List<T> mergeImagesInZ(List<T> images) {
        if (images==null || images.isEmpty()) return Collections.EMPTY_LIST;
        if (!sameSize(images)) throw new IllegalArgumentException("All images should have same size");
        int sizeZ = images.get(0).sizeZ();
        if (sizeZ==1) return new ArrayList<T>(){{add(mergeZPlanes(images));}};
        else {
            List<T> res = new ArrayList<>(sizeZ);
            for (int z = 0; z<sizeZ; ++z) {
                final int zz = z;
                List<T> planes = Utils.transform(images, i->(T)i.getZPlane(zz));
                res.add(mergeZPlanes(planes));
            }
            
            return res;
        }
    }
    public static <T extends Image<T>> boolean sameSize(Collection<T> images) {
        if (images==null || images.isEmpty()) return true;
        Iterator<T> it = images.iterator();
        T ref=it.next();
        while(it.hasNext()) {
            if (!it.next().sameDimensions(ref)) return false;
        }
        return true;
    }
    @Override
    public boolean sameDimensions(BoundingBox other) {
        return sizeX==other.sizeX() && sizeY==other.sizeY() && sizeZ==other.sizeZ();
    }
    
    //public abstract float getPixel(float x, float y, float z); // interpolation
    public abstract double getPixel(int x, int y, int z);
    public abstract double getPixelWithOffset(int x, int y, int z);
    public abstract double getPixelLinInterX(int x, int y, int z, float dx);
    public double getPixelLinInterXY(int x, int y, int z, float dx, float dy) {
        if (dy==0) return getPixelLinInterX(x, y, z, dx);
        return getPixelLinInterX(x, y, z, dx) * (1 - dy) + dy * getPixelLinInterX(x, y+1, z, dx);
    }
    public double getPixelLinInter(int x, int y, int z, float dx, float dy, float dz) {
        if (this.sizeZ()<=1 || dz==0) return getPixelLinInterXY(x, y, z, dx, dy);
        return getPixelLinInterXY(x, y, z, dx, dy) * (1 - dz) + dz * getPixelLinInterXY(x, y, z+1, dx, dy);
    }
    public double getPixel(double x, double y, double z) {
        //return getPixel((int)x, (int)y, (int)z);
        return getPixelLinInter((int)x, (int)y, (int)z, (float)(x-(int)x), (float)(y-(int)y), (float)(z-(int)z));
    }
    public double getPixelWithOffset(double x, double y, double z) {
        //return getPixel((int)x, (int)y, (int)z);
        return getPixelLinInter((int)x-xMin, (int)y-yMin, (int)z-zMin, (float)(x-(int)x), (float)(y-(int)y), (float)(z-(int)z));
    }
    public int[] shape() {
        return sizeZ>1 ? new int[]{sizeX, sizeY, sizeZ}:new int[]{sizeX, sizeY};
    }
    public abstract double getPixel(int xy, int z);
    public abstract void setPixel(int x, int y, int z, double value);
    public abstract void setPixelWithOffset(int x, int y, int z, double value);
    public abstract void addPixel(int x, int y, int z, double value);
    public abstract void addPixelWithOffset(int x, int y, int z, double value);
    public abstract void setPixel(int xy, int z, double value);
    public abstract Object[] getPixelArray();
    public abstract I duplicate(String name);
    public I duplicate() {return duplicate(name);}
    public abstract I newImage(String name, ImageProperties properties);
    public DoubleStream stream() {
        if (sizeZ==1) return streamPlane(0);
        return StreamConcatenation.concat((DoubleStream[])IntStream.range(0, sizeZ).mapToObj(z->streamPlane(z)).toArray(s->new DoubleStream[s]));
    }
    public abstract DoubleStream streamPlane(int z);
    public DoubleStream stream(ImageMask mask, boolean maskHasAbsoluteOffset) {
        if (mask==null) return stream();
        int minZ = maskHasAbsoluteOffset? Math.max(zMin, mask.zMin()) : mask.zMin();
        int maxZ = maskHasAbsoluteOffset ? Math.min(zMin+sizeZ, mask.zMin()+mask.sizeZ()) : Math.min(sizeZ, mask.sizeZ()+mask.zMin());
        if (mask instanceof ImageMask2D) {
            minZ = maskHasAbsoluteOffset ? zMin : 0;
            maxZ = maskHasAbsoluteOffset ? zMax : zMax - zMin;
        }
        if (minZ>=maxZ) return DoubleStream.empty();
        if (minZ==maxZ-1) return streamPlane(minZ-(maskHasAbsoluteOffset?zMin:0), mask, maskHasAbsoluteOffset);
        return StreamConcatenation.concat(IntStream.range(minZ-(maskHasAbsoluteOffset?zMin:0), maxZ-(maskHasAbsoluteOffset?zMin:0)).mapToObj(z->streamPlane(z, mask, maskHasAbsoluteOffset)).filter(s->s!=DoubleStream.empty()).toArray(DoubleStream[]::new));
    }
    public abstract DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset);
    
    public static DoubleStream stream(Collection<Image> images) {
        // get one single plane collection and concatenate
        boolean all2D =  (Utils.objectsAllHaveSameProperty(images, im->im.sizeZ()==1));
        List<Image> planes = all2D ?  (images instanceof List ? (List)images: new ArrayList<>(images)) : images.stream().flatMap(im -> ((List<Image>)im.splitZPlanes()).stream()).collect(Collectors.toList());
        return StreamConcatenation.concat((DoubleStream[])IntStream.range(0, planes.size()).mapToObj(i->planes.get(i).streamPlane(0)).toArray(s->new DoubleStream[s]));
    }
    public static DoubleStream stream(Map<Image, ImageMask> images, boolean masksHaveAbsoluteOffset) {
        // if all masks are blank masks or null -> use method with no masks
        if (Utils.objectsAllHaveSameProperty(images.values(), im -> im == null || im instanceof BlankMask)) return stream(images.keySet());
        else { // combine streams using image's. TODO check performances in 3D -> if necessary decompose to make a single concatenation
            DoubleStream[] streams = images.entrySet().stream().map(e->e.getKey().stream(e.getValue(), masksHaveAbsoluteOffset)).toArray(s->new DoubleStream[s]);
            return StreamConcatenation.concat(streams);
        }
    }
    /**
     * 
     * @param extent minimal values: if negative, will extend the image, if positive will crop the image. maximal values: if positive will extend the image, if negative will crop the image
     * @return 
     */
    public I extend(BoundingBox extent) {
        MutableBoundingBox resizeBB = getBoundingBox().resetOffset().extend(extent);
        if (sizeZ()==1) {
            resizeBB.zMin=0;
            resizeBB.zMax=0;
        }
        return crop(resizeBB);
    }
    public abstract void invert();
    
    public MutableBoundingBox getBoundingBox() {
        return new MutableBoundingBox(this);
    }
    public double[] getMinAndMax(ImageMask mask) {
        return getMinAndMax(mask, null);
    }
    
    /*
     * 
     * @param mask min and max are computed within the mask, or within the whole image if mask==null 
     * @return float[]{min, max}
    */
    public double[] getMinAndMax(ImageMask mask, BoundingBox limits) {
        if (limits==null) limits = new SimpleBoundingBox(this).resetOffset();
        ImageMask m = mask==null ? new BlankMask(this) : mask;
        double[] minAndMax = new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        LoopFunction function = (x, y, z) -> {
            if (m.insideMask(x, y, z)) {
                if (getPixel(x, y, z) > minAndMax[1]) {
                    minAndMax[1] = getPixel(x, y, z);
                }
                if (getPixel(x, y, z) < minAndMax[0]) {
                    minAndMax[0] = getPixel(x, y, z);
                }
            }
        };
        BoundingBox.loop(limits, function);
        return minAndMax;
    }

    public I cropWithOffset(BoundingBox bounds) {
        return crop(new SimpleBoundingBox(bounds).translate(getOffset().reverseOffset()));
    }
    
    public I crop(BoundingBox bounds) {
        //bounds.trimToImage(this);
        I res = newImage(name, new SimpleImageProperties(bounds, scaleXY, scaleZ));
        res.setCalibration(this);
        res.translate(this); // bounds are relative to this image
        if (!BoundingBox.intersect(getBoundingBox().resetOffset(), bounds)) return res; // no data is copied
        int offXSource = bounds.xMin();
        int y_min = bounds.yMin();
        int z_min = bounds.zMin();
        int x_max = bounds.xMax();
        int y_max = bounds.yMax();
        int z_max = bounds.zMax();
        int sizeXDest = bounds.sizeX();
        int oZ = -z_min;
        int oY_i = 0;
        int offXDest = 0;
        if (offXSource <= -1) {
            offXDest=-offXSource;
            offXSource = 0;
        }
        if (x_max >= sizeX) {
            x_max = sizeX - 1;
        }
        if (y_min <= -1) {
            oY_i = -sizeXDest * y_min;
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
        int sizeXCopyDest = x_max - offXSource + 1;
        for (int z = z_min; z <= z_max; ++z) {
            int offYSource = y_min * sizeX;
            int offYDest = oY_i;
            for (int y = y_min; y <= y_max; ++y) {
                System.arraycopy(getPixelArray()[z], offYSource + offXSource, res.getPixelArray()[z + oZ], offYDest + offXDest, sizeXCopyDest);
                offYDest += sizeXDest;
                offYSource += sizeX;
            }
        }
        return res;
    }

    @Override
    public String toString() {
        return byteCount()+";"+super.toString()+";"+this.hashCode();
    }
}
