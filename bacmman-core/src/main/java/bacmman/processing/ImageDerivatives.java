package bacmman.processing;

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
    public static <S extends NumericType<S>&NativeType<S>, T extends NumericType<T>&NativeType<T>> Image gaussianSmooth(Image image, double scale) {
        Img input = ImgLib2ImageWrapper.getImage(image);
        RandomAccessible<S> inputRA = Views.extendBorder(input);
        T type = getOptimalType(image);
        Img<T> smooth = ImgLib2ImageWrapper.createImage(type, image.dimensions());
        Gauss3.gauss(scale, inputRA, smooth);
        return ImgLib2ImageWrapper.wrap(smooth);
    }

    public static <S extends NumericType<S>&NativeType<S>, T extends NumericType<T>&NativeType<T>> Image gaussianSmooth(Image<? extends Image<?>> image, double[] scale) {
        int numDims = image.dimensions().length;
        if (scale.length>numDims) throw new IllegalArgumentException("More scale provided than dimensions");
        if (scale.length<numDims) {
            if (numDims == 3) { // plane by plane
                List<Image> smoothed = image.splitZPlanes().stream().map(i -> gaussianSmooth(i, scale)).collect(Collectors.toList());
                return Image.mergeZPlanes(smoothed);
            } else { // only 1 sigma for a 1D / 2D image
                return gaussianSmooth(image, scale[0]);
            }
        }
        Img input = ImgLib2ImageWrapper.getImage(image);
        RandomAccessible<S> inputRA = Views.extendBorder(input);
        T type = getOptimalType(image);
        Img<T> smooth = ImgLib2ImageWrapper.createImage(type, image.dimensions());
        Gauss3.gauss(scale, inputRA, smooth);
        return ImgLib2ImageWrapper.wrap(smooth);
    }

     public static ImageFloat[] getGradient(Image image, double scale, int... axis) {
        Img input = ImgLib2ImageWrapper.getImage(image);
        if (axis.length == 0) axis = ArrayUtil.generateIntegerArray(input.numDimensions());
        ImageFloat[] res = new ImageFloat[axis.length];
        RandomAccessible inputRA = Views.extendBorder(input);
        if (scale>=1) {
            Img<FloatType> smooth = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            Gauss3.gauss(scale, inputRA, smooth);
            inputRA = Views.extendBorder(smooth);
        }
        for (int d : axis) {
            Img<FloatType> der = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            PartialDerivative.gradientCentralDifference(inputRA, der, d);
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
