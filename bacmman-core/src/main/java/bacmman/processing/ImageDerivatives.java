package bacmman.processing;

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.PrimitiveType;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.plugins.Plugin;
import bacmman.utils.ArrayUtil;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.parallel.Parallelization;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.algorithm.gradient.PartialDerivative;
import net.imglib2.algorithm.gauss3.Gauss3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ImageDerivatives {
    static Logger logger = LoggerFactory.getLogger(ImageDerivatives.class);

    public static <T extends NumericType<T>&NativeType<T>> T getOptimalType(Image image) {
        if (image instanceof PrimitiveType.ByteType || image instanceof PrimitiveType.ShortType || image instanceof PrimitiveType.IntType || image instanceof PrimitiveType.FloatType) return (T)new FloatType();
        else return (T)new DoubleType();
    }

    public static <S extends NumericType<S>&NativeType<S>, T extends NumericType<T>&NativeType<T>> Image gaussianSmooth(Image image, double scale, boolean parallel) {
        return gaussianSmooth(image, scale, scale, parallel);
    }

    public static Image gaussianSmooth(Image<? extends Image<?>> image, double scaleXY, double scaleZ, boolean parallel) {
        double[] scaleA = image.dimensions().length==3 ? new double[]{scaleXY, scaleXY, scaleZ} : new double[]{scaleXY, scaleXY};
        return gaussianSmooth(image, scaleA, parallel);
    }

    public static <S extends NumericType<S>&NativeType<S>, T extends NumericType<T>&NativeType<T>> Image gaussianSmooth(Image<? extends Image<?>> image, double[] scale, boolean parallel) {
        int numDims = image.dimensions().length;
        if (scale.length>numDims) throw new IllegalArgumentException("More scale provided than dimensions");
        if (scale.length<numDims) {
            if (numDims == 3) { // plane by plane
                List<Image> smoothed = image.splitZPlanes().stream().map(i -> gaussianSmooth(i, scale, parallel)).collect(Collectors.toList());
                return Image.mergeZPlanes(smoothed);
            } else { // only 1 sigma for a 1D / 2D image
                return gaussianSmooth(image, scale[0], parallel);
            }
        }
        Img input = ImgLib2ImageWrapper.getImage(image);
        RandomAccessible<S> inputRA = Views.extendBorder(input);
        T type = getOptimalType(image);
        Img<T> smooth = ImgLib2ImageWrapper.createImage(type, image.dimensions());
        Runnable r = () -> Gauss3.gauss(scale, inputRA, smooth);
        if (parallel) Parallelization.runWithNumThreads( Runtime.getRuntime().availableProcessors(), r);
        else r.run();
        return ImgLib2ImageWrapper.wrap(smooth);
    }

    public static ImageFloat getGradientMagnitude(Image image, double scale, boolean parallel, int... axis) {
        return getGradientMagnitude(image, scale, scale, parallel, axis);
    }

    public static ImageFloat getGradientMagnitude(Image image, double scaleXY, double scaleZ, boolean parallel, int... axis) {
        double[] scaleA = image.dimensions().length==3 ? new double[]{scaleXY, scaleXY, scaleZ} : new double[]{scaleXY, scaleXY};
        return getGradientMagnitude(image, scaleA, parallel, axis);
    }

    public static ImageFloat getGradientMagnitude(Image image, double[] scale, boolean parallel, int... axis) {
        ImageFloat[] grad = getGradient(image, scale, parallel, axis);
        ImageFloat res = new ImageFloat(image.getName() + ":gradientMagnitude", image);
        if (grad.length == 3) {
            BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> {
                res.setPixel(x, y, z, (float) Math.sqrt(Math.pow(grad[0].getPixel(x, y, z), 2) + Math.pow(grad[1].getPixel(x, y, z), 2) + Math.pow(grad[2].getPixel(x, y, z), 2) ));
            }, parallel);

        } else {
            BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> {
                res.setPixel(x, y, z, (float) Math.sqrt(Math.pow(grad[0].getPixel(x, y, z), 2) + Math.pow(grad[1].getPixel(x, y, z), 2) ));
            }, parallel);
        }
        return res;
    }

    public static ImageFloat[] getGradient(Image image, double scale, boolean parallel, int... axis) {
        double[] scaleA = scale>0 ? IntStream.range(0, image.dimensions().length).mapToDouble(i->scale).toArray(): new double[0];
        return getGradient(image, scaleA, parallel, axis);
    }

    public static ImageFloat[] getGradient(Image image, double scaleXY, double scaleZ, boolean parallel, int... axis) {
        double[] scaleA = scaleXY>0 || scaleZ>0 ? image.dimensions().length==3 ? new double[]{scaleXY, scaleXY, scaleZ} : new double[]{scaleXY, scaleXY} : new double[0];
        return getGradient(image, scaleA, parallel, axis);
    }

    public static ImageFloat[] getGradient(Image image, double[] scale, boolean parallel, int... axis) {
        Img input = ImgLib2ImageWrapper.getImage(image);
        if (axis.length == 0) axis = ArrayUtil.generateIntegerArray(input.numDimensions());
        ImageFloat[] res = new ImageFloat[axis.length];
        RandomAccessible inputRA = Views.extendBorder(input);
        if (scale.length>0) {
            Img<FloatType> smooth = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            RandomAccessible inputG = inputRA;
            Runnable r = () -> Gauss3.gauss(scale, inputG, smooth);
            if (parallel) Parallelization.runWithNumThreads( Runtime.getRuntime().availableProcessors(), r);
            else r.run();
            inputRA = Views.extendBorder(smooth);
        }
        for (int d : axis) {
            Img<FloatType> der = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            RandomAccessible inputD = inputRA;
            Runnable r = () -> PartialDerivative.gradientCentralDifference(inputD, der, d);
            if (parallel) Parallelization.runWithNumThreads( Runtime.getRuntime().availableProcessors(), r);
            else r.run();
            res[d] = (ImageFloat)ImgLib2ImageWrapper.wrap(der);
        }
        return res;
    }
    /*public static ImageFloat[] getGradient(Image image, double derScale, double smoothScale) {
        if (smoothScale == derScale) return getGradient(image, derScale);
        Img input = ImgLib2ImageWrapper.getImage(image);
        ImageFloat[] res = new ImageFloat[input.numDimensions()];
        RandomAccessible inputRA = Views.extendBorder(input);
        Img<FloatType> smooth = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
        for (int d = 0; d<input.numDimensions(); ++d) {
            Img<FloatType> der = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            double[] scale = IntStream.range(0, input.numDimensions()).mapToDouble(i -> smoothScale).toArray();
            scale[d] = derScale;
            Gauss3.gauss(scale, inputRA, smooth);
            inputRA = Views.extendBorder(smooth);
            PartialDerivative.gradientCentralDifference(inputRA, der, d);
            res[d] = (ImageFloat)ImgLib2ImageWrapper.wrap(der);
        }
        return res;
    }*/
}
