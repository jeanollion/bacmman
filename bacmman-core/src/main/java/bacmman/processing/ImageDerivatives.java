package bacmman.processing;

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.PrimitiveType;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import net.imglib2.RandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.parallel.Parallelization;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.MixedTransformView;
import net.imglib2.view.Views;
import net.imglib2.algorithm.gradient.PartialDerivative;
import net.imglib2.algorithm.gauss3.Gauss3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class ImageDerivatives {
    static Logger logger = LoggerFactory.getLogger(ImageDerivatives.class);

    public static <T extends NumericType<T>&NativeType<T>> T getOptimalType(Image image) {
        if (image instanceof PrimitiveType.ByteType || image instanceof PrimitiveType.ShortType || image instanceof PrimitiveType.IntType || image instanceof PrimitiveType.FloatType) return (T)new FloatType();
        else return (T)new DoubleType();
    }

    public static <S extends NumericType<S>&NativeType<S>, T extends NumericType<T>&NativeType<T>> Image gaussianSmooth(Image image, double scale, boolean parallel) {
        return gaussianSmooth(image, getScaleArray(scale, image), parallel);
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

    public static ImageFloat getGradientMagnitude(Image image, double scale, boolean scaled, boolean parallel, int... axis) {
        return getGradientMagnitude(image, getScaleArray(scale, image), scaled, parallel, axis);
    }

    public static ImageFloat getGradientMagnitude(Image image, double[] scale, boolean scaled, boolean parallel, int... axis) {
        List<ImageFloat> grad = getGradient(image, scale, scaled, parallel, axis);
        ImageFloat res = new ImageFloat(image.getName() + ":gradientMagnitude", image);
        ImageFloat g0 = grad.get(0);
        if (grad.size() == 3) {
            ImageFloat g1 = grad.get(1);
            ImageFloat g2 = grad.get(2);
            BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> {
                res.setPixel(x, y, z, (float) Math.sqrt(Math.pow(g0.getPixel(x, y, z), 2) + Math.pow(g1.getPixel(x, y, z), 2) + Math.pow(g2.getPixel(x, y, z), 2) ));
            }, parallel);
        } else if (grad.size() == 2) {
            ImageFloat g1 = grad.get(1);
            BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> {
                res.setPixel(x, y, z, (float) Math.sqrt(Math.pow(g0.getPixel(x, y, z), 2) + Math.pow(g1.getPixel(x, y, z), 2) ));
            }, parallel);
        } else if (grad.size() == 1) {
            BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> {
                res.setPixel(x, y, z, (float) Math.abs(g0.getPixel(x, y, z) ));
            }, parallel);
        }
        return res;
    }

    public static List<ImageFloat> getGradient(Image image, double scale, boolean scaled, boolean parallel, int... axis) {
        return getGradient(image, getScaleArray(scale, image), scaled, parallel, axis);
    }

    public static List<ImageFloat> getGradient(Image image, double[] scale, boolean scaled, boolean parallel, int... axis) {
        Img input = ImgLib2ImageWrapper.getImage(image);
        if (axis.length == 0) axis = ArrayUtil.generateIntegerArray(input.numDimensions());
        for (int ax : axis) if (ax >= input.numDimensions()) throw new IllegalArgumentException("Invalid axis provided: "+ax+" Image has "+input.numDimensions() + " dimensions");
        if ((scaled || scale.length>0) && scale.length != input.numDimensions()) throw new IllegalArgumentException("Image has "+input.numDimensions() + " dimensions and "+scale.length+" scales are provided");
        RandomAccessible inputRA = Views.extendBorder(input);
        if (scale.length > 0 && DoubleStream.of(scale).anyMatch(s->s>0)) {
            Img<FloatType> smooth = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            RandomAccessible inputG = inputRA;
            Runnable r = () -> Gauss3.gauss(scale, inputG, smooth);
            if (parallel) Parallelization.runWithNumThreads(Runtime.getRuntime().availableProcessors(), r);
            else r.run();
            inputRA = Views.extendBorder(smooth);
        }
        RandomAccessible inputD = inputRA;
        IntFunction<ImageFloat> compute = d -> {
            Img<FloatType> der = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            PartialDerivative.gradientCentralDifference(inputD, der, d);
            return (ImageFloat) ImgLib2ImageWrapper.wrap(der);
        };
        List<ImageFloat> res = Utils.parallel(IntStream.of(axis), parallel).mapToObj(compute).collect(Collectors.toList());
        if (scaled) {
            for (int i = 0; i<axis.length; ++i) {
                Image im = res.get(i);
                double s = scale[axis[i]];
                BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> im.setPixel(x, y, z, im.getPixel(x, y, z) / s), parallel);
            }
        }
        res.forEach(i -> i.setCalibration(image.getScaleXY(), image.getScaleZ()));
        res.forEach(i -> i.translate(image));
        return res;
    }

    public static ImageFloat getLaplacian(Image image, double[] scale, boolean performSmooth, boolean scaled, boolean invert, boolean parallel) {
        Img input = ImgLib2ImageWrapper.getImage(image);
        int nDim = input.numDimensions();
        RandomAccessible inputRA = Views.extendBorder(input);
        if (scale.length>0 && performSmooth) {
            Img<FloatType> smooth = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            RandomAccessible inputG = inputRA;
            Runnable r = () -> Gauss3.gauss(scale, inputG, smooth);
            if (parallel) Parallelization.runWithNumThreads( Runtime.getRuntime().availableProcessors(), r);
            else r.run();
            inputRA = Views.extendBorder(smooth);
        }
        Img<FloatType> grad = ImgLib2ImageWrapper.createImage(new FloatType(), appendDim(image.dimensions(), image.dimensions().length));
        Img<FloatType> grad2 = ImgLib2ImageWrapper.createImage(new FloatType(), appendDim(image.dimensions(), image.dimensions().length));
        RandomAccessible inputH = inputRA;
        IntConsumer der1 = dim -> PartialDerivative.gradientCentralDifference(inputH, Views.hyperSlice(grad, nDim, dim), dim);
        Utils.parallel(IntStream.range(0, nDim), parallel).forEach(der1);
        RandomAccessible gradRA = Views.extendBorder( grad );
        IntConsumer der2 = dim -> PartialDerivative.gradientCentralDifference(Views.hyperSlice(gradRA, nDim, dim), Views.hyperSlice(grad2, nDim, dim), dim);
        Utils.parallel(IntStream.range(0, nDim), parallel).forEach(der2);
        ImageFloat[] res = Utils.parallel(IntStream.range(0, nDim), parallel).mapToObj(d -> (ImageFloat)ImgLib2ImageWrapper.wrap(Views.hyperSlice( grad2, nDim, d ))).toArray(ImageFloat[]::new);
        BoundingBox.LoopFunction fun;
        double[] scale2 = scaled ? DoubleStream.of(scale).map(d -> d*d).toArray() : null;
        double mul = invert ? -1 : 1;
        if (nDim == 2) {
            fun = scaled ? (x, y, z) -> res[0].setPixel(x, y, z, mul * (res[0].getPixel(x, y, z)/scale2[0] + res[1].getPixel(x, y, z)/scale2[1])) : (x, y, z) -> res[0].setPixel(x, y, z, mul * (res[0].getPixel(x, y, z) + res[1].getPixel(x, y, z)));
        } else if (nDim == 3) {
            fun = scaled ? (x, y, z) -> res[0].setPixel(x, y, z, mul * (res[0].getPixel(x, y, z)/scale2[0] + res[1].getPixel(x, y, z)/scale2[1] + res[2].getPixel(x, y, z)/scale2[2])) : (x, y, z) -> res[0].setPixel(x, y, z, mul * (res[0].getPixel(x, y, z) + res[1].getPixel(x, y, z) + res[2].getPixel(x, y, z)));
        } else {
            if (scaled) {
                fun = (x, y, z) -> {
                    double v=0;
                    for (int d = 0 ; d<nDim; ++d) v+=res[d].getPixel(x, y, z)/scale2[d];
                    res[0].setPixel(x, y, z, mul * v);
                };
            } else {
                fun = (x, y, z) -> {
                    double v=0;
                    for (int d = 0 ; d<nDim; ++d) v+=res[d].getPixel(x, y, z);
                    res[0].setPixel(x, y, z, mul * v);
                };
            }
        }
        BoundingBox.loop(image.getBoundingBox().resetOffset(), fun, parallel);
        res[0].setCalibration(image.getScaleXY(), image.getScaleZ());
        res[0].translate(image);
        return res[0];
    }

    public static ImageFloat[][] getHessian(Image image, double[] scale, boolean performSmooth, boolean scaled, boolean parallel) {
        Img input = ImgLib2ImageWrapper.getImage(image);
        int nDim = input.numDimensions();
        RandomAccessible inputRA = Views.extendBorder(input);
        if (scale.length>0 && performSmooth) {
            Img<FloatType> smooth = ImgLib2ImageWrapper.createImage(new FloatType(), image.dimensions());
            RandomAccessible inputG = inputRA;
            Runnable r = () -> Gauss3.gauss(scale, inputG, smooth);
            if (parallel) Parallelization.runWithNumThreads( Runtime.getRuntime().availableProcessors(), r);
            else r.run();
            inputRA = Views.extendBorder(smooth);
        }
        Img<FloatType> grad = ImgLib2ImageWrapper.createImage(new FloatType(), appendDim(image.dimensions(), image.dimensions().length));
        Img<FloatType> hess = ImgLib2ImageWrapper.createImage(new FloatType(), appendDim(image.dimensions(), image.dimensions().length * (image.dimensions().length+1) / 2));
        RandomAccessible inputH = inputRA;
        IntConsumer der1 = dim -> PartialDerivative.gradientCentralDifference(inputH, Views.hyperSlice(grad, nDim, dim), dim);
        Utils.parallel(IntStream.range(0, nDim), parallel).forEach(der1);
        RandomAccessible gradRA = Views.extendBorder( grad );
        ImageFloat[][] res = new ImageFloat[image.dimensions().length][image.dimensions().length];
        Utils.TriConsumer<Integer, Integer, Integer> der2 = (d1, d2, count) -> {
            final MixedTransformView< FloatType > hs1 = Views.hyperSlice( gradRA, nDim, d1 );
            final IntervalView< FloatType > hs2 = Views.hyperSlice( hess, nDim, count );
            PartialDerivative.gradientCentralDifference( hs1, hs2, d2 );
            res[d1][d2] = (ImageFloat)ImgLib2ImageWrapper.wrap(hs2);
            if (!d2.equals(d1)) res[d2][d1] = res[d1][d2];
        };
        List<int[]> tasks = new ArrayList<>();
        int count = 0;
        for ( int d1 = 0; d1 < nDim; ++d1 ) {
            for ( int d2 = d1; d2 < nDim; ++d2 ) {
                tasks.add(new int[]{d1, d2, count++});
            }
        }
        Utils.parallel(tasks.stream(), parallel).forEach(t -> der2.accept(t[0], t[1], t[2]));
        if (scaled) {
            for (int[] t : tasks) {
                Image im = res[t[0]][t[1]];
                double s = scale[t[0]] * scale[t[1]];
                BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> im.setPixel(x, y, z, im.getPixel(x, y, z) / s), parallel);
                im.setCalibration(image.getScaleXY(), image.getScaleZ());
                im.translate(image);
            }
        }
        return res;
    }

    public static ImageFloat[] getHessianEigenValues(Image image, double[] scale, boolean performSmooth, boolean performScaling, boolean parallel) {
        if (image.dimensions().length != 2 && image.dimensions().length != 3) throw new IllegalArgumentException("Hessian Eigen Values are only supported in 3D");
        ImageFloat[][] hessian = getHessian(image, scale, performSmooth, performScaling, parallel);
        if (image.dimensions().length == 2) {
            BoundingBox.loop(image.getBoundingBox().resetOffset(), eignvalues2D(hessian, hessian[0][0], hessian[1][1]), parallel);
            return new ImageFloat[]{hessian[0][0], hessian[1][1]};
        } else {
            BoundingBox.loop(image.getBoundingBox().resetOffset(), eignvalues3D(hessian, hessian[0][0], hessian[1][1], hessian[2][2]), parallel);
            return new ImageFloat[]{hessian[0][0], hessian[1][1], hessian[2][2]};
        }
    }

    // adapted from Imagescience by Erik Meijering: https://imagescience.org/meijering/software/imagescience/
    protected static BoundingBox.LoopFunction eignvalues2D(ImageFloat[][] hessian, ImageFloat e1, ImageFloat e2) {
        return (x, y, z) -> {
            double xx = hessian[0][0].getPixel(x, y, z);
            double xy = hessian[0][1].getPixel(x, y, z);
            double yy = hessian[1][1].getPixel(x, y, z);
            final double b = -(xx + yy);
            final double c = xx * yy - xy * xy;
            final double q = -0.5 * (b + (b < 0 ? -1 : 1) * Math.sqrt(b * b - 4 * c));
            double h1, h2;
            if (q == 0) {
                h1 = 0;
                h2 = 0;
            } else {
                h1 = q;
                h2 = c / q;
            }
            if (h1 > h2) {
                e1.setPixel(x, y, z, h1);
                e2.setPixel(x, y, z, h2);
            } else {
                e2.setPixel(x, y, z, h1);
                e1.setPixel(x, y, z, h2);
            }
        };
    }
    // adapted from Imagescience by Erik Meijering: https://imagescience.org/meijering/software/imagescience/
    protected static BoundingBox.LoopFunction eignvalues3D(ImageFloat[][] hessian, ImageFloat e1, ImageFloat e2, ImageFloat e3) {
        return (x, y, z) -> {
            final double fhxx = hessian[0][0].getPixel(x, y, z);
            final double fhxy = hessian[0][1].getPixel(x, y, z);
            final double fhxz = hessian[0][2].getPixel(x, y, z);
            final double fhyy = hessian[1][1].getPixel(x, y, z);
            final double fhyz = hessian[1][2].getPixel(x, y, z);
            final double fhzz = hessian[2][2].getPixel(x, y, z);
            final double a = -(fhxx + fhyy + fhzz);
            final double b = fhxx*fhyy + fhxx*fhzz + fhyy*fhzz - fhxy*fhxy - fhxz*fhxz - fhyz*fhyz;
            final double c = fhxx*(fhyz*fhyz - fhyy*fhzz) + fhyy*fhxz*fhxz + fhzz*fhxy*fhxy - 2*fhxy*fhxz*fhyz;
            final double q = (a*a - 3*b)/9;
            final double r = (a*a*a - 4.5*a*b + 13.5*c)/27;
            final double sqrtq = (q > 0) ? Math.sqrt(q) : 0;
            final double sqrtq3 = sqrtq*sqrtq*sqrtq;
            double h1, h2, h3;
            if (sqrtq3 == 0) {
                h1 = 0;
                h2 = 0;
                h3 = 0;
            } else {
                final double rsqq3 = r/sqrtq3;
                final double angle = (rsqq3*rsqq3 <= 1) ? Math.acos(rsqq3) : Math.acos(rsqq3 < 0 ? -1 : 1);
                h1 = -2*sqrtq*Math.cos(angle/3) - a/3;
                h2 = -2*sqrtq*Math.cos((angle + TWOPI)/3) - a/3;
                h3 = -2*sqrtq*Math.cos((angle - TWOPI)/3) - a/3;
            }
            if (h2 < h3) { final double tmp = h2; h2 = h3; h3 = tmp; }
            if (h1 < h2) { final double tmp1 = h1; h1 = h2; h2 = tmp1;
                if (h2 < h3) { final double tmp2 = h2; h2 = h3; h3 = tmp2; }}
            e1.setPixel(x, y, z, h1);
            e2.setPixel(x, y, z, h2);
            e3.setPixel(x, y, z, h3);
        };
    }

    private static final double TWOPI = 2*Math.PI;

    protected static int[] appendDim(int[] dims, int n) {
        int[] res = new int[dims.length + 1];
        System.arraycopy(dims, 0, res, 0, dims.length);
        res[dims.length] = n;
        return res;
    }

    public static double[] getScaleArray(double scale, Image image) {
        return scale>0 ? IntStream.range(0, image.dimensions().length).mapToDouble(i->scale).toArray(): new double[0];
    }
    public static double[] getScaleArray(double scaleXY, double scaleZ, Image image) {
        if (scaleXY==0 && scaleZ == 0) return new double[0];
        return image.dimensions().length == 3 ? new double[]{scaleXY, scaleXY, scaleZ} : new double[]{scaleXY, scaleXY};
    }
}
