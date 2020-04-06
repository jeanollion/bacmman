package bacmman.processing;

import bacmman.core.Core;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class Resize {

    public enum INTERPOLATION {
        NEAREST(NearestNeighborInterpolatorFactory::new),
        NLINEAR(NLinearInterpolatorFactory::new),
        CLAMPING_NLINEAR(ClampingNLinearInterpolatorFactory::new),
        LANCZOS3(()->new LanczosInterpolatorFactory(3, false)),
        LANCZOS5(()->new LanczosInterpolatorFactory(5, false)),
        LANCZOS7(()->new LanczosInterpolatorFactory(7, false));
        private final Supplier<InterpolatorFactory> factory;
        INTERPOLATION(Supplier<InterpolatorFactory> factory) {
            this.factory=factory;
        }
        public InterpolatorFactory factory() {
            return factory.get();
        }
    }

    /**
     *
     * @param input
     * @param dimensions dimension of the final image order = (X, Y (Z)). If a dimension is negative, original will be cropped to that dimension if it is larger, or resampled if it is smaller
     * @return
     */
    public static <T extends Image<T>> T resample(T input, InterpolatorFactory interpolation, int... dimensions) {
        if (dimensions==null || dimensions.length==0) return input;
        // negative dimension = crop
        if (Arrays.stream(dimensions).anyMatch(i->i<0)) { // needs cropping
            BoundingBox cropBB = new SimpleBoundingBox(0, dimensions[0]<0 && -dimensions[0]<input.sizeX() ? -dimensions[0]-1 : input.sizeX()-1, 0, dimensions.length>1 && dimensions[1]<0 && -dimensions[1]<input.sizeY() ? -dimensions[1]-1 : input.sizeY()-1, 0, dimensions.length>2 && dimensions[2]<0 && -dimensions[2]<input.sizeZ() ? -dimensions[2]-1 : input.sizeZ()-1);
            input = input.crop(cropBB);
            int[] dims = new int[dimensions.length];
            for (int i = 0; i<dimensions.length; ++i) dims[i] = Math.abs(dimensions[i]);
            dimensions=dims;
        }
        double[] scaleFactors = new double[input.sizeZ()>1? 3:2];
        for (int i = 0; i<scaleFactors.length;++i) scaleFactors[i] = dimensions.length>i && dimensions[i]>0 ? (double)dimensions[i]/input.size(i) : 1;
        if (Arrays.stream(scaleFactors).allMatch(i->i==1)) return input;
        Img in = ImgLib2ImageWrapper.getImage(input);
        return (T)ImgLib2ImageWrapper.wrap(resample(in, scaleFactors, interpolation));
    }
    /**
     *
     * @param input
     * @param dimensions dimension of the final image order = (X, Y (Z)). If a dimension is negative, original will be cropped to that dimension if it is larger, or resampled if it is smaller
     * @return
     */
    public static <T extends Image<T>> T resample(T input, INTERPOLATION interpolation, int... dimensions) {
        return resample(input, interpolation.factory(), dimensions);
    }
    /**
     *
     * @param input
     * @param dimensions dimension of the final image order = (X, Y (Z)). If a dimension is negative, original will be cropped to that dimension if it is larger, or resampled if it is smaller
     * @return
     */
    public static <T extends Image<T>> T resample(T input, boolean binary, int... dimensions) {
        return resample(input, binary?INTERPOLATION.NEAREST.factory():INTERPOLATION.LANCZOS5.factory(), dimensions);
    }
    public static Image resampleBack(Image im, BoundingBox target, boolean binary, int... dimensions) {
        if (im.sameDimensions(target)) return im;
        // if resampled dim negative: need to crop to size -> will set zeros!
        if (Arrays.stream(dimensions).anyMatch(i->i<0)) { // needs cropping
            BoundingBox cropBB = new SimpleBoundingBox(0, dimensions[0]<0 && im.sizeX()<target.sizeX() ? target.sizeX()-1 : im.sizeX()-1, 0, dimensions.length>1 && dimensions[1]<0 && im.sizeY()<target.sizeY() ? target.sizeY()-1 : im.sizeY()-1, 0, dimensions.length>2 && dimensions[2]<0 && im.sizeZ()<target.sizeZ() ? target.sizeZ()-1 : im.sizeZ()-1);
            im = im.crop(cropBB);
        }
        return resample(im, binary?INTERPOLATION.NEAREST:INTERPOLATION.LANCZOS5, target.sizeX(), target.sizeY(), target.sizeZ());
    }


    // adapted from package net.imagej.ops.transform.scaleView;
    // * @author Martin Horn (University of Konstanz)
    // * @author Stefan Helfrich (University of Konstanz)
    public static <T extends RealType<T>> RandomAccessibleInterval<T> resample(RandomAccessibleInterval<T> input, double[] scaleFactors, InterpolatorFactory<T, RandomAccessible<T>> interpolator) {
        if (Arrays.stream(scaleFactors).allMatch(i->i==1)) return input;
        final long[] newDims = Intervals.dimensionsAsLongArray(input);
        for (int i = 0; i < Math.min(scaleFactors.length, input.numDimensions()); i++) {
            newDims[i] = Math.round(input.dimension(i) * scaleFactors[i]);
        }

        IntervalView interval = Views.interval(Views.raster(RealViews.affineReal(
                Views.interpolate(Views.extendMirrorSingle(input), interpolator),
                new Scale(scaleFactors))), new FinalInterval(newDims));

        return interval;
    }
    public enum EXPAND_MODE {MIRROR, BORDER, ZERO}
    public enum EXPAND_POSITION {BEFORE, CENTER, AFTER}

    public static <T extends Image<T>> T pad(T input, EXPAND_MODE mode, EXPAND_POSITION position, int... dimensions) {
        if (dimensions==null || dimensions.length==0) return input;
        Img in = ImgLib2ImageWrapper.getImage(input);
        return (T)ImgLib2ImageWrapper.wrap(pad(in, mode, position, dimensions));
    }

    public static <T extends RealType<T>> RandomAccessibleInterval<T> pad(RandomAccessibleInterval<T> input, EXPAND_MODE mode, EXPAND_POSITION position, int... newDimensions) {
        if (newDimensions.length==0) return input;
        long[] oldDims = Intervals.dimensionsAsLongArray(input);
        long[] mins = new long[oldDims.length];
        long[] sizes = new long[oldDims.length];

        for (int dimIdx = 0; dimIdx<oldDims.length; ++dimIdx) {
            sizes[dimIdx] = dimIdx<newDimensions.length ? newDimensions[dimIdx] : oldDims[dimIdx];
            long delta = sizes[dimIdx] - oldDims[dimIdx];
            if (delta!=0) {
                switch (position) {
                    case CENTER:
                    default:
                        mins[dimIdx] = - delta / 2;
                        break;
                    case BEFORE:
                        mins[dimIdx] = - delta;
                        break;
                    case AFTER:
                        break;
                }
            }
        }
        FinalInterval newInterval = FinalInterval.createMinSize(mins, sizes);
        switch (mode) {
            case MIRROR:
                return Views.interval( Views.extendMirrorSingle( input ), newInterval );
            case BORDER:
            default:
                return Views.interval( Views.expandBorder( input ), newInterval );
            case ZERO:
                return Views.interval( Views.expandZero( input ), newInterval );
        }
    }

    public static <T extends RealType<T>> RandomAccessibleInterval<T> crop(RandomAccessibleInterval<T> input, long[] coords, long[] sizes, EXPAND_MODE mode) {
        FinalInterval newInterval = FinalInterval.createMinSize(coords, sizes);
        switch (mode) {
            case MIRROR:
                return Views.interval( Views.extendMirrorSingle( input ), newInterval );
            case BORDER:
            default:
                return Views.interval( Views.expandBorder( input ), newInterval );
            case ZERO:
                return Views.interval( Views.expandZero( input ), newInterval );
        }
    }

    public static Image[] crop(Image input, long[][] coords, long[][] sizes, EXPAND_MODE mode) {
        RandomAccessibleInterval<? extends RealType<?>> in = ImgLib2ImageWrapper.getImage(input);
        RandomAccessible<? extends RealType<?>> inView;
        switch (mode) {
            case MIRROR:
                inView = Views.extendMirrorSingle(  in );
                break;
            case BORDER:
            default:
                inView = Views.expandBorder(  in );
                break;
            case ZERO:
                inView = Views.expandZero( (RandomAccessibleInterval<? extends NumericType>) in );
                break;
        }
        Image[] res = IntStream.range(0, coords.length)
                .mapToObj(i -> FinalInterval.createMinSize(coords[i], sizes.length>1 ? sizes[i] : sizes[0]))
                .map(interval -> Views.interval( inView, interval )).map(im->ImgLib2ImageWrapper.wrap(im))
                .toArray(Image[]::new);
        for (int i = 0; i<res.length; ++i) { // absolute offset
            res[i].setCalibration(input);
            res[i].resetOffset().translate(input).translate((int)coords[i][0], (int)coords[i][1], coords[i].length>2 ? (int)coords[i][2] : 0);
        }
        return res;
    }
}
