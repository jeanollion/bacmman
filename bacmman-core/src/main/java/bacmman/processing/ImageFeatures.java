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
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.wrappers.ImagescienceWrapper;
import imagescience.feature.Differentiator;
import imagescience.feature.Hessian;
import imagescience.feature.Laplacian;
import imagescience.feature.Structure;
import imagescience.image.Aspects;
import imagescience.segment.Thresholder;
import java.util.ArrayList;
import java.util.Vector;

/**
 *
 * @author Jean Ollion
 */
public class ImageFeatures {
    public static void hysteresis(Image image, double lowval, double highval) {
        final imagescience.image.Image is = ImagescienceWrapper.getImagescience(image);
        final Thresholder thres = new Thresholder();
        thres.hysteresis(is, lowval, highval);
    }
    
    public static ImageFloat[] getStructure(Image image, double smoothScale, double integrationScale, boolean overrideIfFloat) {
        ImageFloat[] res = new ImageFloat[image.sizeZ()<=1?2:3];
        boolean duplicate = !((image instanceof ImageFloat) && overrideIfFloat);
        imagescience.image.Image is = ImagescienceWrapper.getImagescience(image);
        double sscale = smoothScale;
        double iscale = integrationScale;
        sscale *= image.getScaleXY();
        iscale *= image.getScaleXY();
        
        Vector vector = (new Structure()).run(duplicate?is.duplicate():is, sscale, iscale);
        for (int i=0;i<res.length;i++) res[i] = (ImageFloat)ImagescienceWrapper.wrap((imagescience.image.Image)vector.get(i));
        
        for (int i = 0; i < res.length; i++) {
            res[i].setName(image.getName() + ":structure:" + (i + 1));
            res[i].setCalibration(image);
            res[i].resetOffset().translate(image);
            ImageOperations.affineOperation(res[i], res[i], smoothScale*smoothScale, 0);
        }
        return res;
    }
    
    public static ImageFloat[] getStructureMaxAndDeterminant(Image image,  double smoothScale, double integrationScale, boolean overrideIfFloat) {
        ImageFloat[] structure=getStructure(image, smoothScale, integrationScale, overrideIfFloat);
        ImageFloat det = structure[structure.length-1];
        if (structure.length==2) {
            for (int xy = 0; xy<structure[0].sizeXY(); ++xy) det.setPixel(xy, 0, structure[0].getPixel(xy, 0)*structure[1].getPixel(xy, 0)); 
        } else if (structure.length==3) {
            //double pow = 1d/3d;
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
        return getDerivative(image, scale, scale * image.getScaleXY()/image.getScaleZ(), xOrder, yOrder, zOrder, overrideIfFloat);
    }
    public static ImageFloat getDerivative(Image image, double scaleXY, double scaleZ, int xOrder, int yOrder, int zOrder, boolean overrideIfFloat) {
        if (image.sizeZ()==1) zOrder=0;
        if (image.sizeY()==1) yOrder=0;
        if (image.sizeX()==1) xOrder=0;
        final imagescience.image.Image is = ImagescienceWrapper.getImagescience(image);
        is.aspects(new Aspects(1, 1, scaleXY / scaleZ));
        boolean duplicate = !((image instanceof ImageFloat) && overrideIfFloat);
        final Differentiator differentiator = new Differentiator();
        ImageFloat res = (ImageFloat)ImagescienceWrapper.wrap(differentiator.run(duplicate?is.duplicate():is, scaleXY, xOrder, yOrder, zOrder));
        res.setCalibration(image);
        res.resetOffset().translate(image);
        return res;
    }
    public static ImageFloat[] getGradient(Image image, double scale, boolean overrideIfFloat) {
        return getGradient(image, scale, scale * image.getScaleXY()/image.getScaleZ(), overrideIfFloat);
    }
    public static ImageFloat[] getGradient(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        final int dims = image.sizeZ()==1?2:3;
        final ImageFloat[] res = new ImageFloat[dims];
        boolean duplicate = !((image instanceof ImageFloat) && overrideIfFloat);
        final imagescience.image.Image is = ImagescienceWrapper.getImagescience(image);
        is.aspects(new Aspects(1, 1, scaleXY / scaleZ));
        final Differentiator differentiator = new Differentiator();
        for (int i =0;i<dims; i++) {
            boolean dup= i==dims-1?duplicate : image instanceof ImageFloat;
            res[i] = (ImageFloat)ImagescienceWrapper.wrap(differentiator.run(dup?is.duplicate():is, scaleXY, i==0?1:0, i==1?1:0, i==2?1:0));
            res[i].setCalibration(image);
            res[i].resetOffset().translate(image);
        }
        return res;
    }
    
    public static ImageFloat getGradientMagnitude(Image image, double scale, boolean overrideIfFloat) {
        return getGradientMagnitude(image, scale, scale * image.getScaleXY()/image.getScaleZ(), overrideIfFloat);
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
        return getLaplacian(image, scale, scale * image.getScaleXY()/image.getScaleZ(), invert, overrideIfFloat);
    }
    public static ImageFloat getLaplacian(Image image, double scaleXY, double scaleZ, boolean invert, boolean overrideIfFloat) {
        final imagescience.image.Image is = ImagescienceWrapper.getImagescience(image);
        is.aspects(new Aspects(1, 1, scaleXY / scaleZ));
        boolean duplicate = !((image instanceof ImageFloat) && overrideIfFloat);
        ImageFloat res = (ImageFloat)ImagescienceWrapper.wrap(new Laplacian().run(duplicate?is.duplicate():is, scaleXY));
        double norm = getNorm(scaleXY, 2);
        if (invert) ImageOperations.affineOperation(res, res, -norm, 0);
        else ImageOperations.affineOperation(res, res, norm, 0);
        res.setCalibration(image).resetOffset().translate(image).setName(image.getName() + ":laplacian:"+scaleXY);
        return res;
    }
    
    public static ImageFloat[] getHessian(Image image, double scale, boolean overrideIfFloat) {
        ImageFloat[] res = new ImageFloat[image.sizeZ()==1?2:3];
        final imagescience.image.Image is = ImagescienceWrapper.getImagescience(image);
        double s = scale * image.getScaleXY();
        boolean duplicate = !((image instanceof ImageFloat) && overrideIfFloat);
        Vector vector = new Hessian().run(duplicate?is.duplicate():is, s, false);
        for (int i=0;i<res.length;i++) {
            res[i] = (ImageFloat)ImagescienceWrapper.wrap((imagescience.image.Image) vector.get(i));
            res[i].setCalibration(image);
            res[i].resetOffset().translate(image);
            res[i].setName(image.getName() + ":hessian" + (i + 1));
            ImageOperations.affineOperation(res[i], res[i], getNorm(scale, 2), 0);
        }
        return res;
    }
    public static ImageFloat[] getHessianMaxAndDeterminant(Image image, double scale, boolean overrideIfFloat) {
        ImageFloat[] hess=getHessian(image, scale, overrideIfFloat);
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
        return ImageOperations.affineOperation(res, res, -1, 0);
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
        return ImageOperations.affineOperation(res, res, -1, 0);
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
        return gaussianSmooth(image, scale, scale*image.getScaleXY()/image.getScaleZ(), overrideIfFloat);
    }
    public static ImageFloat gaussianSmooth(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        if (image.sizeZ()>1 && scaleZ<=0) throw new IllegalArgumentException("Scale Z should be >0 ");
        else if (scaleZ<=0) scaleZ=1;
        if (scaleXY<=0) throw new IllegalArgumentException("Scale XY should be >0 ");
        boolean duplicate = !((image instanceof ImageFloat) && overrideIfFloat);
        final imagescience.image.Image is = ImagescienceWrapper.getImagescience(image);
        is.aspects(new Aspects(1, 1, scaleXY / scaleZ));
        Differentiator differentiator = new Differentiator();
        ImageFloat res = (ImageFloat)ImagescienceWrapper.wrap(differentiator.run(duplicate?is.duplicate():is, scaleXY, 0, 0, 0));
        res.setCalibration(image);
        res.resetOffset().translate(image);
        return res;
    }
    public static ImageFloat gaussianSmoothScaleIndep(Image image, double scaleXY, double scaleZ, boolean overrideIfFloat) {
        ImageFloat res = gaussianSmooth(image, scaleXY, scaleZ, overrideIfFloat);
        double norm = getNorm(scaleXY, 0);
        if (norm!=1) ImageOperations.affineOperation(res, res, norm, 0);
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
            //return 1;
            return Math.pow(scale, 1);
        } else if (order==2) {
            return Math.pow(scale, 2)*Math.sqrt(2 * Math.PI);
            //return Math.pow(scale, 3);
        } else return 1;
    }
}
