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
package bacmman.processing;

import bacmman.data_structure.Processor;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.TypeConverter;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.convolution.kernel.Kernel1D;
import net.imglib2.algorithm.convolution.kernel.SeparableKernelConvolution;
import net.imglib2.img.Img;
import net.imglib2.parallel.Parallelization;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import java.util.ArrayList;

/**
 * Gaussian-derivative based image features (smoothing, derivatives, gradient, Laplacian, Hessian and structure tensor eigenvalues).
 * Computations are performed with ImgLib2 separable convolutions with sampled Gaussian derivative kernels and repeated border
 * boundary conditions. Scales are standard deviations of the Gaussian, expressed in pixels: scaleXY along X and Y, scaleZ along Z.
 * When scaleZ is not given, it is computed from scaleXY and the image calibration (scaleXY * pixelSizeXY / pixelSizeZ).
 * Eigenvalues of the Hessian are sorted by decreasing value, eigenvalues of the structure tensor by decreasing absolute value.
 * @author Jean Ollion
 */
public class ImageFeatures {

    /**
     * Half-kernel of the Gaussian derivative of order {@code order} and standard deviation {@code s} (in pixels), sampled at 0..h-1
     * Kernel extent is 3s for order 0, 4s for orders 1 and 2, 5s for higher orders (truncated to the image size). Order 0 kernel is normalized to a sum of 1.
     * The full kernel is symmetric for even orders and anti-symmetric for odd orders.
     */
    static double[] gaussianDerivativeHalfKernel(double s, int order, int size) {
        if (s == 0) { // no smoothing: finite differences
            switch (order) {
                case 0: return new double[]{1};
                case 1: return new double[]{0, -0.5}; // central difference
                case 2: return new double[]{-2, 1};
                default: throw new IllegalArgumentException("Derivative of order > 2 without smoothing is not supported");
            }
        }
        double r = order == 0 ? 3 : (order <= 2 ? 4 : 5);
        int h = (int)(s * r) + 1;
        if (h > size) h = size;
        final double[] kernel = new double[h];
        kernel[0] = order == 0 ? 1 : 0;
        if (h == 1) return kernel;
        final double mis2 = -0.5 / (s * s);
        if (order == 0) {
            double integral = 0;
            for (int k = 0; k < h; ++k) {
                kernel[k] = Math.exp(k * k * mis2);
                integral += kernel[k];
            }
            integral = 2 * integral - kernel[0];
            for (int k = 0; k < h; ++k) kernel[k] /= integral;
        } else { // d^n/dx^n G(x) = (-1)^n He_n(x/s) / s^n * G(x) with He the probabilists' Hermite polynomials
            final double c = (order % 2 == 0 ? 1 : -1) / (Math.pow(s, order) * Math.sqrt(2 * Math.PI) * s);
            for (int k = 0; k < h; ++k) {
                final double u = k / s;
                double hm1 = 1, he = u; // He_0, He_1
                for (int n = 1; n < order; ++n) {
                    double hp1 = u * he - n * hm1;
                    hm1 = he;
                    he = hp1;
                }
                kernel[k] = c * he * Math.exp(k * k * mis2);
            }
            if (order % 2 == 1) kernel[0] = 0;
        }
        return kernel;
    }

    static Kernel1D gaussianDerivativeKernel(double s, int order, int size) {
        double[] half = gaussianDerivativeHalfKernel(s, order, size);
        int n = half.length;
        double[] full = new double[2 * n - 1];
        double sign = order % 2 == 0 ? 1 : -1;
        // out[i] = sum_k half[k] * ( in[i+k] * sign + in[i-k] )
        for (int k = 0; k < n; ++k) {
            full[n - 1 - k] = sign * half[k];
            full[n - 1 + k] = half[k];
        }
        full[n - 1] = half[0];
        return Kernel1D.asymmetric(full, n - 1);
    }

    /**
     * Gaussian derivative of an image (multithreaded)
     * @see #gaussianDerivative(Image, double[], int[], boolean, boolean)
     */
    public static ImageFloat gaussianDerivative(Image image, double[] sigma, int[] order, boolean overrideIfFloat) {
        return gaussianDerivative(image, sigma, order, overrideIfFloat, true);
    }

    /**
     * Gaussian derivative of an image
     * @param image input image (1D, 2D or 3D)
     * @param sigma standard deviation of the gaussian for each dimension of {@code image.dimensions()}, in pixels. A null value means no smoothing along the dimension: derivatives are then computed by finite differences (central difference for order 1, [1, -2, 1] for order 2)
     * @param order derivative order for each dimension
     * @param overrideIfFloat if true and image is an ImageFloat, the result is written in the pixel array of image
     * @param parallel multithreaded computation
     * @return derivative image. Calibration and offset are copied from input image.
     */
    public static ImageFloat gaussianDerivative(Image image, double[] sigma, int[] order, boolean overrideIfFloat, boolean parallel) {
        int[] dims = image.dimensions();
        if (sigma.length < dims.length || order.length < dims.length) throw new IllegalArgumentException("sigma and order must be provided for each of the "+dims.length+" dimensions");
        boolean inPlace = overrideIfFloat && image instanceof ImageFloat;
        ImageFloat res = inPlace ? new ImageFloat(image.getName(), image.sizeX(), ((ImageFloat)image).getPixelArray()) : new ImageFloat(image.getName(), image);
        res.setCalibration(image).resetOffset().translate(image);
        boolean zero = false;
        Kernel1D[] kernels = new Kernel1D[dims.length];
        for (int d = 0; d < dims.length; ++d) {
            if (dims[d] == 1) {
                if (order[d] > 0) zero = true;
                kernels[d] = Kernel1D.symmetric(1);
            } else {
                if (sigma[d] < 0) throw new IllegalArgumentException("Negative smoothing scale in dimension "+d);
                kernels[d] = gaussianDerivativeKernel(sigma[d], order[d], dims[d]);
            }
        }
        if (zero) {
            ImageOperations.fill(res, 0, null);
            return res;
        }
        Img<FloatType> target = ImgLib2ImageWrapper.toImg(res);
        Img input = ImgLib2ImageWrapper.toImg(image);
        RandomAccessible source = Views.extendBorder((RandomAccessibleInterval)input);
        if (parallel) SeparableKernelConvolution.convolve(kernels, source, target);
        else Parallelization.runSingleThreaded(() -> SeparableKernelConvolution.convolve(kernels, source, target));
        return res;
    }

    static double[] getSigma(Image image, double scaleXY, double scaleZ) {
        switch (image.dimensions().length) {
            case 1: return new double[]{scaleXY};
            case 2: return new double[]{scaleXY, scaleXY};
            default: return new double[]{scaleXY, scaleXY, scaleZ};
        }
    }

    static int[] getOrder(Image image, int xOrder, int yOrder, int zOrder) {
        switch (image.dimensions().length) {
            case 1: return new int[]{xOrder};
            case 2: return new int[]{xOrder, yOrder};
            default: return new int[]{xOrder, yOrder, zOrder};
        }
    }

    static double getZRatio(Image image) { // pixel size ratio XY/Z
        return image.getScaleXY() > 0 && image.getScaleZ() > 0 ? image.getScaleXY() / image.getScaleZ() : 1;
    }

    /**
     * Eigenvalues of the structure tensor, sorted by decreasing absolute value
     */
    public static ImageFloat[] getStructure(Image image, double smoothScale, double integrationScale, boolean overrideIfFloat) {
        double zRatio = getZRatio(image);
        return getStructure(image, smoothScale, smoothScale * zRatio, integrationScale, integrationScale * zRatio, overrideIfFloat);
    }

    /**
     * Eigenvalues of the structure tensor, sorted by decreasing absolute value
     * @param smoothScaleXY scale of the gaussian derivatives (gradient) in XY
     * @param smoothScaleZ scale of the gaussian derivatives (gradient) in Z
     * @param integrationScaleXY scale of the gaussian smoothing of the tensor components in XY
     * @param integrationScaleZ scale of the gaussian smoothing of the tensor components in Z
     */
    public static ImageFloat[] getStructure(Image image, double smoothScaleXY, double smoothScaleZ, double integrationScaleXY, double integrationScaleZ, boolean overrideIfFloat) {
        boolean is3D = image.sizeZ() > 1;
        double smoothScale = smoothScaleXY;
        double[] sSigma = getSigma(image, smoothScaleXY, smoothScaleZ);
        double[] iSigma = getSigma(image, integrationScaleXY, integrationScaleZ);
        int nDim = is3D ? 3 : 2;
        ImageFloat[] grad = new ImageFloat[nDim];
        for (int d = 0; d < nDim; ++d) grad[d] = gaussianDerivative(image, sSigma, getOrder(image, d==0?1:0, d==1?1:0, d==2?1:0), false);
        ImageFloat[][] tensor = new ImageFloat[nDim][nDim];
        for (int d1 = 0; d1 < nDim; ++d1) {
            for (int d2 = d1; d2 < nDim; ++d2) {
                ImageFloat prod = new ImageFloat(image.getName(), image);
                float[][] p = prod.getPixelArray(), g1 = grad[d1].getPixelArray(), g2 = grad[d2].getPixelArray();
                for (int z = 0; z < p.length; ++z) for (int i = 0; i < p[z].length; ++i) p[z][i] = g1[z][i] * g2[z][i];
                tensor[d1][d2] = gaussianDerivative(prod, iSigma, new int[iSigma.length], true);
                tensor[d2][d1] = tensor[d1][d2];
            }
        }
        ImageFloat[] res = new ImageFloat[nDim];
        for (int d = 0; d < nDim; ++d) res[d] = tensor[d][d];
        BoundingBox.loop(image.getBoundingBox().resetOffset(), nDim == 2 ? ImageDerivatives.eignvalues2D(tensor, res[0], res[1]) : ImageDerivatives.eignvalues3D(tensor, res[0], res[1], res[2]), false);
        sortByDecreasingAbsoluteValue(res);
        for (int i = 0; i < res.length; i++) {
            res[i].setName(image.getName() + ":structure:" + (i + 1));
            res[i].setCalibration(image);
            res[i].resetOffset().translate(image);
            ImageOperations.affineOpMulAdd(res[i], res[i], smoothScale*smoothScale, 0);
        }
        return res;
    }

    private static void sortByDecreasingAbsoluteValue(ImageFloat[] eigenValues) {
        float[][][] p = new float[eigenValues.length][][];
        for (int i = 0; i < p.length; ++i) p[i] = eigenValues[i].getPixelArray();
        float[] v = new float[p.length];
        for (int z = 0; z < p[0].length; ++z) {
            for (int xy = 0; xy < p[0][z].length; ++xy) {
                for (int i = 0; i < v.length; ++i) v[i] = Math.abs(p[i][z][xy]);
                java.util.Arrays.sort(v);
                for (int i = 0; i < v.length; ++i) p[i][z][xy] = v[v.length - 1 - i];
            }
        }
    }
    
    public static ImageFloat[] getStructureMaxAndDeterminant(Image image,  double smoothScale, double integrationScale, boolean overrideIfFloat) {
        double zRatio = getZRatio(image);
        return getStructureMaxAndDeterminant(image, smoothScale, smoothScale * zRatio, integrationScale, integrationScale * zRatio, overrideIfFloat);
    }

    public static ImageFloat[] getStructureMaxAndDeterminant(Image image,  double smoothScaleXY, double smoothScaleZ, double integrationScaleXY, double integrationScaleZ, boolean overrideIfFloat) {
        ImageFloat[] structure=getStructure(image, smoothScaleXY, smoothScaleZ, integrationScaleXY, integrationScaleZ, overrideIfFloat);
        ImageFloat det = structure[structure.length-1];
        if (structure.length==2) {
            for (int xy = 0; xy<structure[0].sizeXY(); ++xy) det.setPixel(xy, 0, structure[0].getPixel(xy, 0)*structure[1].getPixel(xy, 0)); 
        } else if (structure.length==3) {
            for (int z = 0; z<structure[0].sizeZ(); ++z) {
                for (int xy = 0; xy<structure[0].sizeXY(); ++xy) {
                    det.setPixel(xy, z, structure[0].getPixel(xy, z)*structure[1].getPixel(xy, z)*structure[2].getPixel(xy, z)); 
                }
            }
        } else {
            Processor.logger.warn("wrong number of dimension {}, structure determient cannot be computed", structure.length);
            return null;
        }
        
        return new ImageFloat[]{structure[0], det};
    }
    public static ImageFloat getDerivative(Image image, double scale, int xOrder, int yOrder, int zOrder, boolean overrideIfFloat) {
        return getDerivative(image, scale, scale * getZRatio(image), xOrder, yOrder, zOrder, overrideIfFloat);
    }
    public static ImageFloat getDerivative(Image image, double scaleXY, double scaleZ, int xOrder, int yOrder, int zOrder, boolean overrideIfFloat) {
        return gaussianDerivative(image, getSigma(image, scaleXY, scaleZ), getOrder(image, xOrder, yOrder, zOrder), overrideIfFloat);
    }
    public static ImageFloat[] getGradient(Image image, double scale, boolean overrideIfFloat) {
        return getGradient(image, scale, scale * getZRatio(image), overrideIfFloat);
    }
    public static ImageFloat[] getGradient(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        final int dims = image.sizeZ()==1?2:3;
        final ImageFloat[] res = new ImageFloat[dims];
        for (int i =0;i<dims; i++) {
            res[i] = getDerivative(image, scaleXY, scaleZ, i==0?1:0, i==1?1:0, i==2?1:0, false);
            if (i==2 && scaleZ!=scaleXY) ImageOperations.affineOpMulAdd(res[2], res[2], scaleZ/scaleXY, 0); // take into account anisotropy
        }
        return res;
    }
    
    public static ImageFloat getGradientMagnitude(Image image, double scale, boolean overrideIfFloat) {
        return getGradientMagnitude(image, scale, scale * getZRatio(image), overrideIfFloat);
    }
    public static ImageFloat getGradientMagnitude(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        ImageFloat[] grad = getGradient(image, scaleXY, scaleZ, overrideIfFloat);
        ImageFloat res = new ImageFloat(image.getName() + ":gradientMagnitude", image);
        final float[][] pixels = res.getPixelArray();
        if (grad.length == 3) {
            final int sizeX = image.sizeX();
            final float[][] grad0 = grad[0].getPixelArray();
            final float[][] grad1 = grad[1].getPixelArray();
            final float[][] grad2 = grad[2].getPixelArray();
            int offY, off;
            for (int z = 0; z< image.sizeZ(); ++z) {
                for (int y = 0; y< image.sizeY(); ++y) {
                    offY = y * sizeX;
                    for (int x = 0; x< sizeX; ++x) {
                        off = x + offY;
                        pixels[z][off] = (float) Math.sqrt(grad0[z][off] * grad0[z][off] + grad1[z][off] * grad1[z][off] + grad2[z][off] * grad2[z][off]);
                    }
                }
            }
        } else {
            final int sizeX = image.sizeX();
            final float[][] grad0 = grad[0].getPixelArray();
            final float[][] grad1 = grad[1].getPixelArray();
            int offY, off;
            for (int y = 0; y< image.sizeY(); ++y) {
                offY = y * sizeX;
                for (int x = 0; x< sizeX; ++x) {
                    off = x + offY;
                    pixels[0][off] = (float) Math.sqrt(grad0[0][off] * grad0[0][off] + grad1[0][off] * grad1[0][off]);
                }
            }
        }
        
        
        /*if (grad.length == 3) {
            final ThreadRunner tr = new ThreadRunner(0, image.getSizeY(), nbCPUs);
            final int sizeZ = image.getSizeZ();
            final int sizeX = image.getSizeX();
            final float[][] grad0 = grad[0].getPixelArray();
            final float[][] grad1 = grad[1].getPixelArray();
            final float[][] grad2 = grad[2].getPixelArray();
            for (int i = 0; i < tr.threads.length; i++) {
                tr.threads[i] = new Thread(
                        new Runnable() {
                            public void run() {
                                int offY, off;
                                for (int y = tr.ai.getAndIncrement(); y < tr.end; y = tr.ai.getAndIncrement()) {
                                    offY = y * sizeX;                                    
                                    for (int x = 0; x < sizeX; ++x) {
                                        off = x + offY;
                                        for (int z = 0; z< sizeZ; ++z) pixels[z][off] = (float) Math.sqrt(grad0[z][off] * grad0[z][off] + grad1[z][off] * grad1[z][off] + grad2[z][off] * grad2[z][off]);
                                    }

                                }
                            }
                        });
            }
            tr.startAndJoin();
        } else {
            final ThreadRunner tr = new ThreadRunner(0, image.getSizeY(), nbCPUs);
            final int sizeX = image.getSizeX();
            final float[][] grad0 = grad[0].getPixelArray();
            final float[][] grad1 = grad[1].getPixelArray();
            for (int i = 0; i < tr.threads.length; i++) {
                tr.threads[i] = new Thread(
                        new Runnable() {
                            public void run() {
                                int offY, off;
                                for (int y = tr.ai.getAndIncrement(); y < tr.end; y = tr.ai.getAndIncrement()) {
                                    offY = y * sizeX;                                    
                                    for (int x = 0; x < sizeX; ++x) {
                                        off = x + offY;
                                        pixels[0][off] = (float) Math.sqrt(grad0[0][off] * grad0[0][off] + grad1[0][off] * grad1[0][off]);
                                    }

                                }
                            }
                        });
            }
            tr.startAndJoin();
        }*/
        return res;
    }
    public static ImageFloat getLaplacian(Image image, double scale, boolean invert, boolean overrideIfFloat) {
        return getLaplacian(image, scale, scale * getZRatio(image), invert, overrideIfFloat);
    }
    public static ImageFloat getLaplacian(Image image, double scaleXY, double scaleZ, boolean invert, boolean overrideIfFloat) {
        if (scaleXY <= 0) throw new IllegalArgumentException("Smoothing scale less than or equal to 0");
        int nDim = image.sizeZ()>1 ? 3 : 2;
        ImageFloat res = null;
        for (int d = nDim-1; d>=0; --d) { // last derivative is computed in place if allowed
            ImageFloat der = getDerivative(image, scaleXY, scaleZ, d==0?2:0, d==1?2:0, d==2?2:0, d==0 && overrideIfFloat);
            if (res == null) res = der;
            else ImageOperations.addImage(der, res, res, 1);
        }
        double norm = getNorm(scaleXY, 2);
        if (invert) ImageOperations.affineOpMulAdd(res, res, -norm, 0);
        else ImageOperations.affineOpMulAdd(res, res, norm, 0);
        res.setCalibration(image).resetOffset().translate(image).setName(image.getName() + ":laplacian:"+scaleXY);
        return res;
    }
    public static ImageFloat[] getHessian(Image image, double scale, boolean overrideIfFloat) {
        return getHessian(image, scale, scale * getZRatio(image), overrideIfFloat);
    }
    /**
     * Shape index 2/PI * atan( (k2+k1)/(k2-k1) ) where k1 &le; k2 are the eigenvalues of the Hessian at scale {@code scaleXY}. Computed independently on each Z-plane
     */
    public static Image getShapeIndex(Image image, double scaleXY, boolean overrideIfFloat) {
        return ImageOperations.applyPlaneByPlane(image, im -> {
            ImageFloat[] hess = getHessian(im, scaleXY, overrideIfFloat);
            Image k1 = hess[1];
            Image k2 = hess[0];
            double f = 2d/Math.PI;
            for (int xy = 0; xy<k1.sizeXY(); ++xy) {
                double k1v = k1.getPixel(xy, 0);
                double k2v = k2.getPixel(xy, 0);
                k1.setPixel(xy, 0, f * Math.atan( (k2v + k1v) / (k2v - k1v)));
            }
            return k1;
        });
    }
    public static ImageFloat[] getHessian(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        if (scaleXY <= 0) throw new IllegalArgumentException("Smoothing scale less than or equal to 0");
        int nDim = image.sizeZ()==1?2:3;
        ImageFloat[][] hessian = new ImageFloat[nDim][nDim];
        for (int d1 = 0; d1<nDim; ++d1) {
            for (int d2 = d1; d2<nDim; ++d2) {
                int[] order = new int[3];
                order[d1]++;
                order[d2]++;
                hessian[d1][d2] = getDerivative(image, scaleXY, scaleZ, order[0], order[1], order[2], false);
                hessian[d2][d1] = hessian[d1][d2];
            }
        }
        ImageFloat[] res = new ImageFloat[nDim]; // eigenvalues sorted by decreasing value
        for (int d = 0; d<nDim; ++d) res[d] = hessian[d][d];
        BoundingBox.loop(image.getBoundingBox().resetOffset(), nDim == 2 ? ImageDerivatives.eignvalues2D(hessian, res[0], res[1]) : ImageDerivatives.eignvalues3D(hessian, res[0], res[1], res[2]), false);
        for (int i=0;i<res.length;i++) {
            res[i].setCalibration(image);
            res[i].resetOffset().translate(image);
            res[i].setName(image.getName() + ":hessian" + (i + 1));
            ImageOperations.affineOpMulAdd(res[i], res[i], getNorm(scaleXY, 2), 0);
        }
        return res;
    }
    public static ImageFloat[] getHessianMaxAndDeterminant(Image image, double scale, boolean overrideIfFloat) {
        return getHessianMaxAndDeterminant(image, scale, scale * getZRatio(image), overrideIfFloat);
    }

    public static ImageFloat[] getHessianMaxAndDeterminant(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        ImageFloat[] hess=getHessian(image, scaleXY, scaleZ, overrideIfFloat);
        ImageFloat det = hess[hess.length-1];
        if (hess.length==2) {
            for (int xy = 0; xy<hess[0].sizeXY(); ++xy) det.setPixel(xy, 0, hess[0].getPixel(xy, 0)*hess[1].getPixel(xy, 0)); 
        } else if (hess.length==3) {
            //double pow = 1d/3d;
            for (int z = 0; z<hess[0].sizeZ(); ++z) {
                for (int xy = 0; xy<hess[0].sizeXY(); ++xy) {
                    det.setPixel(xy, z, hess[0].getPixel(xy, z)*hess[1].getPixel(xy, z)*hess[2].getPixel(xy, z)); 
                }
            }
        } else {
            Processor.logger.warn("wrong number of dimension {}, hessian determient cannot be computed", hess.length);
            return null;
        }
        
        return new ImageFloat[]{hess[0], det};
    }
    
    public static Image getScaleSpaceHessianDet(Image plane, double[] scales) {
        if (plane.sizeZ()>1) throw new IllegalArgumentException("2D image only");
        ArrayList<ImageFloat> planes = new ArrayList<ImageFloat>(scales.length);
        for (double s : scales) planes.add(ImageFeatures.getHessianMaxAndDeterminant(plane, s, false)[1]);
        return Image.mergeZPlanes(planes).setName("Hessian Det. Scale-Space");
    }
    
    public static Image getScaleSpaceHessianDetNorm(Image plane, double[] scales, double... multiplicativeCoefficient) {
        if (plane.sizeZ()>1) throw new IllegalArgumentException("2D image only");
        ArrayList<ImageFloat> planes = new ArrayList<ImageFloat>(scales.length);
        for (double s : scales) {
            ImageFloat im = ImageFeatures.getHessianMaxAndDeterminant(plane, s, false)[1];
            ImageFloat norm = ImageFeatures.gaussianSmooth(plane, s, s, false);
            ImageOperations.divide(im, norm, im, multiplicativeCoefficient);
            ImageOperations.divide(im, norm, im, multiplicativeCoefficient);
            planes.add(im);
        }
        return Image.mergeZPlanes(planes).setName("Hessian Det. Norm Scale-Space");
    }
    
    public static Image getScaleSpaceHessianMax(Image plane, double[] scales) {
        if (plane.sizeZ()>1) throw new IllegalArgumentException("2D image only");
        ArrayList<ImageFloat> planes = new ArrayList<ImageFloat>(scales.length);
        for (double s : scales) planes.add(ImageFeatures.getHessian(plane, s, false)[0]);
        Image res = Image.mergeZPlanes(planes).setName("Hessian Max. Scale-Space");
        return ImageOperations.affineOpMulAdd(res, res, -1, 0);
    }
    
    public static Image getScaleSpaceHessianMaxNorm(Image plane, double[] scales, Image norm, double... multiplicativeCoefficient) {
        if (plane.sizeZ()>1) throw new IllegalArgumentException("2D image only");
        ArrayList<ImageFloat> planes = new ArrayList<ImageFloat>(scales.length);
        for (double s : scales) {
            ImageFloat im = ImageFeatures.getHessian(plane, s, false)[0];
            Image n = norm==null? ImageFeatures.gaussianSmooth(plane, s, s, false) : norm;
            ImageOperations.divide(im, n, im, multiplicativeCoefficient);
            planes.add(im);
        }
        Image res = Image.mergeZPlanes(planes).setName("Hessian Max. Normed Scale-Space");
        return ImageOperations.affineOpMulAdd(res, res, -1, 0);
    }
    
    public static Image getScaleSpaceLaplacian(Image plane, double[] scales) {
        if (plane.sizeZ()>1) throw new IllegalArgumentException("2D image only");
        ArrayList<ImageFloat> planes = new ArrayList<ImageFloat>(scales.length);
        for (double s : scales) planes.add(ImageFeatures.getLaplacian(plane, s, true, false));
        return Image.mergeZPlanes(planes).setName("Laplacian Scale-Space");
    }
    
    public static Image getScaleSpaceGaussian(Image plane, double[] scales) {
        if (plane.sizeZ()>1) throw new IllegalArgumentException("2D image only");
        ArrayList<ImageFloat> planes = new ArrayList<ImageFloat>(scales.length);
        for (double s : scales) planes.add(ImageFeatures.gaussianSmooth(plane, s, plane.getScaleXY()*s/plane.getScaleZ(), false));
        return Image.mergeZPlanes(planes).setName("Gaussian Scale-Space");
    }
    
    public static Image getScaleSpaceLaplacianNorm(Image plane, double[] scales, Image norm, double... multiplicativeCoefficient) {
        if (plane.sizeZ()>1) throw new IllegalArgumentException("2D image only");
        ArrayList<ImageFloat> planes = new ArrayList<ImageFloat>(scales.length);
        for (double s : scales) {
            ImageFloat im = ImageFeatures.getLaplacian(plane, s, true, false);
            Image n = norm==null? ImageFeatures.gaussianSmooth(plane, s, s, false) : norm;
            ImageOperations.divide(im, n, im, multiplicativeCoefficient);
            planes.add(im);
        }
        return Image.mergeZPlanes(planes).setName("Laplacian Norm Scale-Space");
    }
    
    private static double sqrt(double number) {
        return number>=0?Math.sqrt(number):-Math.sqrt(-number);
    }
    public static ImageFloat gaussianSmooth(Image image, double scale, boolean overrideIfFloat) {
        return gaussianSmooth(image, scale, scale*getZRatio(image), overrideIfFloat);
    }
    public static ImageFloat gaussianSmooth(Image image, double scaleXY, double scaleZ, boolean overwriteIfFloat) {
        if (image.sizeZ()>1 && scaleZ<=0) throw new IllegalArgumentException("Scale Z should be >0 ");
        else if (scaleZ<=0) scaleZ=1;
        if (scaleXY == 0) return TypeConverter.toFloat(image, null, !overwriteIfFloat);
        if (scaleXY<0) throw new IllegalArgumentException("Scale XY should be >0 ");
        return getDerivative(image, scaleXY, scaleZ, 0, 0, 0, overwriteIfFloat);
    }
    public static ImageFloat gaussianSmoothScaleIndep(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        ImageFloat res = gaussianSmooth(image, scaleXY, scaleZ, overrideIfFloat);
        double norm = getNorm(scaleXY, 0);
        if (norm!=1) ImageOperations.affineOpMulAdd(res, res, norm, 0);
        return res;
    }
    
    public static ImageFloat differenceOfGaussians(Image image, double scaleXYMin, double scaleXYMax, double ratioScaleZ, boolean trimNegativeValues) {
        Image bcg = gaussianSmooth(image, scaleXYMax, scaleXYMax*ratioScaleZ, false);
        Image fore = scaleXYMin>0?gaussianSmooth(image, scaleXYMin, scaleXYMin*ratioScaleZ, false):image;
        ImageFloat res  = (ImageFloat)ImageOperations.addImage(fore, bcg, bcg, -1);
        if (trimNegativeValues) ImageOperations.trimValues(res, 0, 0, true);
        return res;
    }
    
    /*public static ImageFloat LoG(Image image, double radX, double radZ) {
        ImageWare in = Builder.create(IJImageWrapper.getImagePlus(image), 3);
            ImageWare res;
            if (image.getSizeZ() > 1) {
                res = LoG.doLoG(in, radX, radX, radZ);
            } else {
                res = LoG.doLoG(in, radX, radX);
            }
            res.invert();
        return (ImageFloat)IJImageWrapper.wrap(new ImagePlus("LoG of "+image.getName(), res.buildImageStack())).setCalibration(image).resetOffset().addOffset(image);
    }*/
    private static double getNorm(double scale, int order) {
        //double[] kernel = kernel(scale, order, sizeMax);
        if (order == 0) {
            return scale;
        } else if (order==2) {
            return Math.pow(scale, 2)*Math.sqrt(2 * Math.PI);
            //return Math.pow(scale, 3);
        } else return 1;
    }
}
