package bacmman.data_structure.region_container.roi;

import bacmman.data_structure.Voxel;
import bacmman.image.*;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.utils.Palette;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.ImageRoi;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.IndexColorModel;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class IJRoi3D extends HashMap<Integer, Roi> implements ObjectRoi {
    public static final Logger logger = LoggerFactory.getLogger(IJRoi3D.class);
    boolean is2D;
    int locdx, locdy; // in case of EllipseRoi -> 0.5 is added to coordinate, this can create inconsistencies in localization as IJ.ROIs use a integer reference. this is a fix when calling set location
    int frame;
    public IJRoi3D(int bucketSize) {
        super(bucketSize);
    }

    public ImageByte toMask(BoundingBox bounds, double scaleXY, double scaleZ) {
        ImageStack stack = new ImageStack(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ());
        MutableBoundingBox bounds2D= new MutableBoundingBox(bounds).setzMin(0).setzMax(0);
        IntStream.rangeClosed(bounds.zMin(), bounds.zMax()).forEachOrdered(z -> {
            Roi r = get(z);
            if (r!=null) {
                Rectangle bds = r.getBounds();
                ImageProcessor mask = r.getMask();
                if (mask == null) { // mask is rectangle
                    mask = IJImageWrapper.getImagePlus(TypeConverter.maskToImageInteger(new BlankMask(bounds.sizeX(), bounds.sizeY(), 1, bounds.xMin(), bounds.yMin(), 0, 1, 1), null)).getProcessor();
                } else if (mask.getWidth() != stack.getWidth() || mask.getHeight() != stack.getHeight()) { // need to paste image // TODO simply change ROI with before calling getMask
                    ImageByte i = (ImageByte) IJImageWrapper.wrap(new ImagePlus("", mask)).translate(new SimpleOffset(bds.x, bds.y, 0));
                    mask = IJImageWrapper.getImagePlus(i.cropWithOffset(bounds2D)).getProcessor();
                }
                stack.setProcessor(mask, z - bounds.zMin() + 1);
            } else { // in case bounds where not updated : some planes are empty
                ImageByte mask = new ImageByte("", bounds.sizeX(), bounds.sizeY(), 1);
                stack.setProcessor(IJImageWrapper.getImagePlus(mask).getProcessor(), z - bounds.zMin() + 1);
            }
        });
        ImageByte res = (ImageByte) IJImageWrapper.wrap(new ImagePlus("MASK", stack));
        res.setCalibration(new SimpleImageProperties(bounds, scaleXY, scaleZ)).translate(bounds);
        return res;
    }

    public IJRoi3D smooth(int radius) {
        if (radius == 0) return this;
        Set<Integer> frames = keySet().stream().filter(f -> f>=0).collect(Collectors.toSet());
        frames.forEach(f -> {
            FloatPolygon fp = get(f).getInterpolatedPolygon();
            if (fp.npoints>2) {
                ToIntBiFunction<Integer, Integer> get_neigh = (center, d) -> {
                    int res = center + d;
                    if (res < 0) res = fp.npoints + res;
                    else if (res >= fp.npoints) res = res - fp.npoints;
                    return res;
                };
                // limit smooth radius for small rois
                int rad = Math.min(radius, (fp.npoints-1) / 2);
                int size =  Math.min((int)Math.ceil(2 * rad), (fp.npoints-1) / 2);
                rad = Math.min(rad, (int)Math.ceil(size/2.));

                double[] ker = new double[size];
                double expCoeff = -1. / (2 * rad * rad);
                for (int i = 0; i < ker.length; ++i) ker[i] = Math.exp(i * i * expCoeff);
                double sum = DoubleStream.of(ker).sum() * 2 - ker[0]; // half kernel
                for (int i = 0; i < ker.length; ++i) ker[i] /= sum;
                ToDoubleBiFunction<Integer, float[]> convolve = (i, array) -> (float) IntStream.range(0, size).mapToDouble(n -> n == 0 ? ker[0] * array[i] : ker[n] * (array[get_neigh.applyAsInt(i, n)] + array[get_neigh.applyAsInt(i, -n)])).sum();

                float[] xPoints = new float[fp.npoints];
                float[] yPoints = new float[fp.npoints];
                for (int i = 0; i < fp.npoints; ++i) {
                    xPoints[i] = (float)convolve.applyAsDouble(i, fp.xpoints);
                    yPoints[i] = (float)convolve.applyAsDouble(i, fp.ypoints);
                }
                Roi r = new PolygonRoi(xPoints, yPoints, xPoints.length, Roi.POLYGON);
                put(f, r);
            }
        });
        return this;
    }

    public IJRoi3D setLocDelta(int locdx, int locdy) {
        this.locdx = locdx;
        this.locdy = locdy;
        return this;
    }
    public IJRoi3D setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public boolean contained(Overlay o) {
        for (Roi r : values()) if (o.contains(r)) return true;
        return false;
    }
    public IJRoi3D setFrame(int frame) {
        this.frame = frame;
        for (Roi r : values()) r.setPosition(r.getCPosition(), r.getZPosition(), frame+1);
        return this;
    }

    public int getFrame() {
        return frame;
    }

    public IJRoi3D setZToPosition() {
        for (Roi r: values()) r.setPosition(r.getZPosition());
        return this;
    }
    public IJRoi3D setTToPosition() {
        for (Roi r: values()) r.setPosition(frame+1);
        return this;
    }
    public boolean is2D() {
        return is2D;
    }

    public MutableBoundingBox getBounds() {
        try {
            int xMin = stream().mapToInt(e -> e.getBounds().x).min().getAsInt();
            int xMax = stream().mapToInt(e -> e.getBounds().x + e.getBounds().width-1).max().getAsInt();
            int yMin = stream().mapToInt(e -> e.getBounds().y).min().getAsInt();
            int yMax = stream().mapToInt(e -> e.getBounds().y + e.getBounds().height-1).max().getAsInt();
            int zMin = keySet().stream().filter(points -> points >= 0).mapToInt(i -> i).min().getAsInt();
            int zMax = keySet().stream().filter(points -> points >= 0).mapToInt(i -> i).max().getAsInt();
            return new MutableBoundingBox(xMin, xMax, yMin, yMax, zMin, zMax);
        } catch (NoSuchElementException e) {
            logger.debug("void roi : {}", keySet());
            throw e;
        }
    }
    public Stream<Roi> stream() {
        return entrySet().stream().filter(e -> e.getKey()>=0).map(Entry::getValue);
    }
    public IJRoi3D setLocation(Offset off) {
        return translate(getBounds().reverseOffset().translate(off).setzMin(0));
    }

    public IJRoi3D translate(Offset off) {
        if (off.zMin()!=0) { // need to clear map to update z-mapping
            synchronized(this) {
                HashMap<Integer, Roi> temp = new HashMap<>(this);
                this.clear();
                temp.forEach((z, r)->put(z+off.zMin(), r));
            }
        }
        forEach((z, r)-> {
            r.setLocation((int)r.getXBase()+off.xMin()+locdx, (int)r.getYBase()+off.yMin()+locdy);
            r.setPosition(r.getCPosition(), r.getZPosition()+off.zMin(), r.getTPosition());
        });
        return this;
    }
    public IJRoi3D duplicate() {
        IJRoi3D res = new IJRoi3D(this.sizeZ()).setIs2D(is2D).setLocDelta(locdx, locdy);
        super.forEach((z, r)->res.put(z, (Roi)r.clone()));
        return res;
    }
    public int sizeZ() {
        return (int)keySet().stream().filter(z->z>=0).count();
    }

    @Override
    public void setColor(Color color, boolean fill) {
        entrySet().stream().filter(e->e.getKey()>=0).forEach(e -> {
            setRoiColor(e.getValue(), color, fill);
        });
    }
    @Override
    public void setStrokeWidth(double strokeWidth) {
        entrySet().stream().filter(e->e.getKey()>=0).forEach(e -> {
            e.getValue().setStrokeWidth(strokeWidth);
        });
    }
    public void duplicateROIUntilZ(int zMaxExcl) {
        if (sizeZ()==zMaxExcl || !containsKey(0)) return;
        if (sizeZ()>zMaxExcl) {
            for (int z = sizeZ()-1; z>=zMaxExcl; --z) {
                remove(z);
                remove(-z-1);
            }
        } else if (sizeZ()<zMaxExcl){
            Roi r = this.get(0);
            for (int z = sizeZ(); z < zMaxExcl; ++z) {
                Roi dup = (Roi) r.clone();
                dup.setPosition(r.getCPosition(), z + 1, r.getTPosition());
                this.put(z, dup);
            }
            if (this.containsKey(-1)) { // flags
                r = this.get(-1);
                for (int z = sizeZ(); z < zMaxExcl; ++z) {
                    Roi dup = (Roi) r.clone();
                    dup.setPosition(r.getCPosition(), z + 1, r.getTPosition());
                    this.put(-z - 1, dup);
                }
            }
        }
    }
    public Set<Voxel> getContour(Offset offset) {
        return entrySet().stream().flatMap( e -> roiToVoxels(e.getValue(), e.getKey())).peek(offset==null? v->{} : v -> v.translate(offset)).collect(Collectors.toSet());
    }
    public static Stream<Voxel> roiToVoxels(Roi roi, int z) {
        FloatPolygon p = roi.getInterpolatedPolygon(1, false);
        return IntStream.range(0, p.npoints).mapToObj(i -> new Voxel((int)(p.xpoints[i]), (int)(p.ypoints[i]), z));
    }

    protected static void setRoiColor(Roi roi, Color color, boolean fill) {
        if (roi instanceof ImageRoi) {
            ImageRoi r = (ImageRoi)roi;
            ImageProcessor ip = r.getProcessor();
            int value = color.getRGB();
            int[] pixels = (int[])ip.getPixels();
            for (int i = 0; i<pixels.length; ++i) {
                if (pixels[i]!=0) pixels[i] = value;
            }
            roi.setStrokeColor(color);
        } else {
            if (fill) {
                roi.setFillColor(color);
                roi.setStrokeColor(null);
            } else {
                roi.setStrokeColor(color);
                roi.setFillColor(null);
            }
        }
    }

    static IndexColorModel getCM(Color color) {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        for (int i = 1; i < 256; ++i) {
            r[i] = (byte) color.getRed();
            g[i] = (byte) color.getGreen();
            b[i] = (byte) color.getBlue();
        }
        return new IndexColorModel(8, 256, r, g, b);
    }


    public static IJRoi3D createRoiImage(ImageMask mask, Offset offset, boolean is3D, Color color, double opacity) {
        if (offset == null) {
            logger.error("ROI creation : offset null for mask: {}", mask.getName());
            return null;
        }
        IJRoi3D res = new IJRoi3D(mask.sizeZ()).setIs2D(!is3D);
        ImageInteger maskIm = TypeConverter.maskToImageInteger(mask, null); // copy only if necessary
        ImagePlus maskPlus = IJImageWrapper.getImagePlus(maskIm);
        for (int z = 0; z < mask.sizeZ(); ++z) {
            ImageProcessor ip = maskPlus.getStack().getProcessor(z + 1);
            ip.setColorModel(getCM(color));
            ImageRoi roi = new ImageRoi(offset.xMin(), offset.yMin(), ip);
            roi.setZeroTransparent(true);
            roi.setOpacity(opacity);
            if (roi != null) {
                Rectangle bds = roi.getBounds();
                if (bds == null) {
                    continue;
                }
                roi.setPosition(z + 1 + offset.zMin());
                res.put(z + offset.zMin(), roi);
            }

        }
        return res;
    }

    @Override
    public boolean equals(Object obj) {
        return (this == obj);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

}