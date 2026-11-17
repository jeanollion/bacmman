package bacmman.processing;

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.PrimitiveType;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.utils.ArrayUtil;
import net.imglib2.RandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.parallel.Parallelization;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.algorithm.gauss3.Gauss3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
        Img input = ImgLib2ImageWrapper.toImg(image);
        RandomAccessible<S> inputRA = Views.extendBorder(input);
        T type = getOptimalType(image);
        Img<T> smooth = ImgLib2ImageWrapper.createImage(type, image.dimensions());
        Runnable r = () -> Gauss3.gauss(scale, inputRA, smooth);
        if (parallel) Parallelization.runWithNumThreads( Runtime.getRuntime().availableProcessors(), r);
        else r.run();
        Image res = ImgLib2ImageWrapper.toImage(smooth, null);
        res.resetOffset().translate(image).setCalibration(image);
        return res;
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

    /**
     * Gaussian-derivative gradient
     * @param scale standard deviation of the gaussian for each dimension (in pixels), or empty array / zeros for no smoothing (finite differences)
     * @param scaled if true, each gradient component is divided by the scale along its axis
     * @param axis axes along which the gradient is computed (all if none is provided)
     */
    public static List<ImageFloat> getGradient(Image image, double[] scale, boolean scaled, boolean parallel, int... axis) {
        int nDim = image.dimensions().length;
        if (axis.length == 0) axis = ArrayUtil.generateIntegerArray(nDim);
        for (int ax : axis) if (ax >= nDim) throw new IllegalArgumentException("Invalid axis provided: "+ax+" Image has "+nDim + " dimensions");
        double[] sigma = getSigma(scale, nDim, scaled);
        List<ImageFloat> res = new ArrayList<>(axis.length);
        for (int ax : axis) {
            int[] order = new int[nDim];
            order[ax] = 1;
            ImageFloat der = ImageFeatures.gaussianDerivative(image, sigma, order, false, parallel);
            if (scaled) ImageOperations.affineOpMulAdd(der, der, 1. / sigma[ax], 0);
            res.add(der);
        }
        return res;
    }

    /**
     * Gaussian-derivative Laplacian
     * @param scale standard deviation of the gaussian for each dimension (in pixels)
     * @param performSmooth if false, derivatives are computed by finite differences without smoothing ({@code scale} is only used for scaling)
     * @param scaled if true, each second derivative is multiplied by the squared scale along its axis
     * @param invert if true, result is multiplied by -1
     */
    public static ImageFloat getLaplacian(Image image, double[] scale, boolean performSmooth, boolean scaled, boolean invert, boolean parallel) {
        int nDim = image.dimensions().length;
        double[] sigma = getSigma(scale, nDim, scaled);
        double[] sigmaD = performSmooth ? sigma : new double[nDim];
        ImageFloat res = null;
        for (int d = 0; d < nDim; ++d) {
            int[] order = new int[nDim];
            order[d] = 2;
            ImageFloat der = ImageFeatures.gaussianDerivative(image, sigmaD, order, false, parallel);
            double mul = (invert ? -1 : 1) * (scaled ? sigma[d] * sigma[d] : 1);
            if (res == null) res = mul == 1 ? der : (ImageFloat)ImageOperations.affineOpMulAdd(der, der, mul, 0);
            else ImageOperations.weightedSum(res, new double[]{1, mul}, res, der);
        }
        return res;
    }

    /**
     * Gaussian-derivative Hessian matrix (symmetric: res[i][j] and res[j][i] are the same image)
     * @param scale standard deviation of the gaussian for each dimension (in pixels)
     * @param performSmooth if false, derivatives are computed by finite differences without smoothing
     * @param scaled if true, component (i, j) is divided by scale[i] * scale[j]
     */
    public static ImageFloat[][] getHessian(Image image, double[] scale, boolean performSmooth, boolean scaled, boolean parallel) {
        int nDim = image.dimensions().length;
        double[] sigma = getSigma(scale, nDim, scaled);
        double[] sigmaD = performSmooth ? sigma : new double[nDim];
        ImageFloat[][] res = new ImageFloat[nDim][nDim];
        for (int d1 = 0; d1 < nDim; ++d1) {
            for (int d2 = d1; d2 < nDim; ++d2) {
                int[] order = new int[nDim];
                order[d1]++;
                order[d2]++;
                ImageFloat der = ImageFeatures.gaussianDerivative(image, sigmaD, order, false, parallel);
                if (scaled) ImageOperations.affineOpMulAdd(der, der, 1. / (sigma[d1] * sigma[d2]), 0);
                res[d1][d2] = der;
                res[d2][d1] = der;
            }
        }
        return res;
    }

    private static double[] getSigma(double[] scale, int nDim, boolean scaled) {
        if (scale.length == 0) {
            if (scaled) throw new IllegalArgumentException("Scale must be provided for scaling");
            return new double[nDim];
        }
        if (scale.length != nDim) throw new IllegalArgumentException("Image has "+nDim + " dimensions and "+scale.length+" scales are provided");
        if (scaled && DoubleStream.of(scale).anyMatch(s -> s <= 0)) throw new IllegalArgumentException("Scale must be > 0 for scaling");
        return scale;
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

    // closed-form eigenvalues of a symmetric matrix, sorted by decreasing value
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
    // closed-form eigenvalues of a symmetric matrix, sorted by decreasing value
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

    public static double[] getScaleArray(double scale, Image image) {
        return scale>0 ? IntStream.range(0, image.dimensions().length).mapToDouble(i->scale).toArray(): new double[0];
    }
    public static double[] getScaleArray(double scaleXY, double scaleZ, Image image) {
        if (scaleXY==0 && scaleZ == 0) return new double[0];
        return image.dimensions().length == 3 ? new double[]{scaleXY, scaleXY, scaleZ} : new double[]{scaleXY, scaleXY};
    }
}
