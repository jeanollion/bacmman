package bacmman.processing;

import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.algorithm.gradient.PartialDerivative;
import net.imglib2.algorithm.gauss3.Gauss3;

import java.util.stream.IntStream;

public class ImageDerivatives {

    public static ImageFloat[] getGradient(Image image, double scale) {
        Img input = ImgLib2ImageWrapper.getImage(image);
        ImageFloat[] res = new ImageFloat[input.numDimensions()];
        RandomAccessible inputRA = Views.extendBorder(input);
        if (scale>=1) {
            Img<FloatType> smooth = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            Gauss3.gauss(scale, inputRA, smooth);
            inputRA = Views.extendBorder(smooth);
        }
        for (int d = 0; d<input.numDimensions(); ++d) {
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
