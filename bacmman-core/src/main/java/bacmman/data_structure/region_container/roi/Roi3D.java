package bacmman.data_structure.region_container.roi;

import bacmman.core.Core;
import bacmman.data_structure.Voxel;
import bacmman.image.*;
import bacmman.image.wrappers.IJImageWrapper;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Roi3D extends HashMap<Integer, Roi> {
    public static final Logger logger = LoggerFactory.getLogger(Roi3D.class);
    boolean is2D;
    int locdx, locdy; // in case of EllipseRoi -> 0.5 is added to coordinate, this can create inconsistencies in localization as IJ.ROIs use a integer reference. this is a fix when calling set location
    int frame;
    public Roi3D(int bucketSize) {
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

    public Roi3D smooth(int radius) {
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

    public Roi3D setLocDelta(int locdx, int locdy) {
        this.locdx = locdx;
        this.locdy = locdy;
        return this;
    }
    public Roi3D setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public boolean contained(Overlay o) {
        for (Roi r : values()) if (o.contains(r)) return true;
        return false;
    }
    public Roi3D setFrame(int frame) {
        this.frame = frame;
        for (Roi r : values()) r.setPosition(r.getCPosition(), r.getZPosition(), frame+1);
        return this;
    }

    public int getFrame() {
        return frame;
    }

    public Roi3D setZToPosition() {
        for (Roi r: values()) r.setPosition(r.getZPosition());
        return this;
    }
    public Roi3D setTToPosition() {
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
    public Roi3D setLocation(Offset off) {
        return translate(getBounds().reverseOffset().translate(off).setzMin(0));
    }

    public Roi3D translate(Offset off) {
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
    public Roi3D duplicate() {
        Roi3D res = new Roi3D(this.size()).setIs2D(is2D).setLocDelta(locdx, locdy);
        super.forEach((z, r)->res.put(z, (Roi)r.clone()));
        return res;
    }
    public int size() {
        return (int)keySet().stream().filter(z->z>=0).count();
    }
    public void duplicateROIUntilZ(int zMax) {
        if (size()>1 || !containsKey(0)) return;
        Roi r = this.get(0);
        for (int z = 1; z<zMax; ++z) {
            Roi dup = (Roi)r.clone();
            dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
            this.put(z, dup);
        }
        if (this.containsKey(-1)) { // segmentation correction arrow
            r = this.get(-1);
            for (int z = 1; z<zMax; ++z) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
                this.put(-z-1, dup);
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
}