package bacmman.processing;

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.Arrays;

public class Resample {


    /**
     *
     * @param input
     * @param dimensions dimension of the final image. If a dimension is negative, original will be cropped to that dimension if it is larger, or resampled if it is smaller
     * @return
     */
    public static Image resample(Image input, boolean binary, int... dimensions) {
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
        if (Arrays.stream(scaleFactors).allMatch(i->i==1)) return input;
        for (int i = 0; i<scaleFactors.length;++i) scaleFactors[i] = dimensions.length>i && dimensions[i]>0 ? (double)dimensions[i]/input.size(i) : 1;
        Img in = ImgLib2ImageWrapper.getImage(input);
        InterpolatorFactory inter = binary ? new NearestNeighborInterpolatorFactory() : new LanczosInterpolatorFactory(3, false);
        return ImgLib2ImageWrapper.wrap(resample(in, scaleFactors, inter));
    }
    public static Image resampleBack(Image im, BoundingBox target, boolean binary, int... dimensions) {
        if (im.sameDimensions(target)) return im;
        // if resampled dim negative: need to crop to size -> will set zeros!
        if (Arrays.stream(dimensions).anyMatch(i->i<0)) { // needs cropping
            BoundingBox cropBB = new SimpleBoundingBox(0, dimensions[0]<0 && im.sizeX()<target.sizeX() ? target.sizeX()-1 : im.sizeX()-1, 0, dimensions.length>1 && dimensions[1]<0 && im.sizeY()<target.sizeY() ? target.sizeY()-1 : im.sizeY()-1, 0, dimensions.length>2 && dimensions[2]<0 && im.sizeZ()<target.sizeZ() ? target.sizeZ()-1 : im.sizeZ()-1);
            im = im.crop(cropBB);
        }
        return resample(im, binary, target.sizeX(), target.sizeY(), target.sizeZ());
    }


    // adapted from package net.imagej.ops.transform.scaleView;
    // * @author Martin Horn (University of Konstanz)
    // * @author Stefan Helfrich (University of Konstanz)
    public static <T extends RealType<T>> RandomAccessibleInterval<T> resample(RandomAccessibleInterval<T> input, double[] scaleFactors, InterpolatorFactory<T, RandomAccessible<T>> interpolator) {
        final long[] newDims = Intervals.dimensionsAsLongArray(input);
        for (int i = 0; i < Math.min(scaleFactors.length, input.numDimensions()); i++) {
            newDims[i] = Math.round(input.dimension(i) * scaleFactors[i]);
        }

        IntervalView interval = Views.interval(Views.raster(RealViews.affineReal(
                Views.interpolate(Views.extendMirrorSingle(input), interpolator),
                new Scale(scaleFactors))), new FinalInterval(newDims));

        return interval;
    }
}
