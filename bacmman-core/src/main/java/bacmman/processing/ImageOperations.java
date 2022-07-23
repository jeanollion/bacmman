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

import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;
import bacmman.image.*;
import bacmman.image.wrappers.IJImageWrapper;

import static bacmman.image.BoundingBox.loop;

import bacmman.utils.DoubleStatistics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import bacmman.utils.Utils;
import static bacmman.utils.Utils.parallel;
import ij.ImagePlus;
import ij.process.StackProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.function.BinaryOperator;
import java.util.function.DoublePredicate;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class ImageOperations {
    public final static Logger logger = LoggerFactory.getLogger(ImageOperations.class);
    /**
     * Adds a random value (norm in [0;1[, divided by 10^{@param decimal}) to each pixels of the image.
     * @param input 
     * @param mask only pixels within mask will be modified
     * @param decimal decimal from which starts the radom value to add
     * @return  same image if type is float, float cast of image if not.
     */
    public static Image jitterIntegerValues(Image input, ImageMask mask, double decimal) {
        if (mask ==null) mask = new BlankMask(input);
        ImageMask m=mask;
        Image image = (input instanceof ImageFloat) ? input : TypeConverter.toFloat(input, null);
        double div = decimal>0 ? Math.pow(10, decimal):0;
        Random r = new Random();
        double[] addValues = r.doubles(mask.count()).toArray();
        int[] idx =new int[1];
        BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z)->{
            if (m.insideMask(x, y, z)) image.setPixel(x, y, z, image.getPixel(x, y, z)+addValues[idx[0]++]/div);
        });
        return image;
    }
    public static List<Region> filterObjects(ImageInteger image, ImageInteger output, Function<Region, Boolean> removeObject) {
        List<Region> l = ImageLabeller.labelImageList(image);
        List<Region> toRemove = new ArrayList<>(l.size());
        for (Region o : l) if (removeObject.apply(o)) toRemove.add(o);
        l.removeAll(toRemove);
        //logger.debug("count before: {}/ after :{}", tot, stay);
        if (output!=null) for (Region o : toRemove) o.draw(output, 0);
        return l;
    }
    public static Image applyPlaneByPlane(Image image, Function<Image, Image> function, boolean parallel) {
        if (image.sizeZ()==1) return function.apply(image);
        else {
            List<Image> planes = image.splitZPlanes();
            planes = Utils.transform(planes, function, parallel);
            return Image.mergeZPlanes(planes);
        }
    }
    public static Image applyPlaneByPlaneMask(ImageMask image, Function<ImageMask, Image> function, boolean parallel) {
        if (image.sizeZ()==1) return function.apply(image);
        else {
            List<Image> planes = IntStream.range(0, image.sizeZ()).mapToObj(z -> function.apply(new ImageMask2D(image, z))).collect(Collectors.toList());
            return Image.mergeZPlanes(planes);
        }
    }
    public static Image applyPlaneByPlane(Image image, Function<Image, Image> function) {
        return applyPlaneByPlane(image, function, false);
    }
    public static Image applyPlaneByPlaneMask(ImageMask image, Function<ImageMask, Image> function) {
        return applyPlaneByPlaneMask(image, function, false);
    }
    public static Image average(Image output, Image... images) {
        if (images.length==0) throw new IllegalArgumentException("Cannot average zero images!");
        if (output==null) output = new ImageFloat("avg", images[0]);
        for (int i = 0; i< images.length; ++i) {
            if (!output.sameDimensions(images[i])) throw new IllegalArgumentException("image:#"+i+" dimensions differ from output ("+images[i]+" != "+output+")");
        }
        double div = images.length;
        if (div==2) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, (images[0].getPixel(xy, z) + images[1].getPixel(xy, z))/div);
                }
            }
        } else if (div == 3) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, (images[0].getPixel(xy, z) + images[1].getPixel(xy, z) + images[2].getPixel(xy, z))/div);
                }
            }
        } else {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    double avg = images[0].getPixel(xy, z);
                    for (int i = 1; i<div; ++i) avg+=images[i].getPixel(xy, z);
                    output.setPixel(xy, z, avg/div);
                }
            }
        }
        return output;
    }
    public static Image weightedSum(Image output, double[] weights, Image... images) {
        assert weights.length == images.length: "as many weights as images should be prodided";
        assert weights.length>=1 : "minimum 1 image should be provided";
        if (output==null) output = new ImageFloat("avg", images[0]);
        for (int i = 0; i< images.length; ++i) {
            if (!output.sameDimensions(images[i])) throw new IllegalArgumentException("image:#"+i+" dimensions differ from output ("+images[i]+" != "+output+")");
        }
        int nImages = images.length;
        if (nImages==2) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, (images[0].getPixel(xy, z) * weights[0] + images[1].getPixel(xy, z) * weights[1]));
                }
            }
        } else if (nImages == 3) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, (images[0].getPixel(xy, z) * weights[0] + images[1].getPixel(xy, z) * weights[1] + images[2].getPixel(xy, z) * weights[2]));
                }
            }
        } else {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    double avg = images[0].getPixel(xy, z);
                    for (int i = 1; i<nImages; ++i) avg+=images[i].getPixel(xy, z) * weights[i];
                    output.setPixel(xy, z, avg);
                }
            }
        }
        return output;
    }
    public static double[] getMinAndMax(Collection<Image> images, boolean parallele) {
        if (images.isEmpty()) {
            return new double[2];
        }
        BinaryOperator<double[]> combiner = (mm1, mm2)-> {
            if (mm1[0]>mm2[0]) mm1[0] = mm2[0];
            if (mm1[1]<mm2[1]) mm1[1] = mm2[1];
            return mm1;
        };
        return Utils.parallel(images.stream(), parallele).map(im->im.getMinAndMax(null)).reduce(combiner).get();
    }
    public static double[] getMinAndMax(Map<Image, ImageMask> images, boolean parallele) {
        if (images.isEmpty()) {
            return new double[2];
        }
        BinaryOperator<double[]> combiner = (mm1, mm2)-> {
            if (mm1[0]>mm2[0]) mm1[0] = mm2[0];
            if (mm1[1]<mm2[1]) mm1[1] = mm2[1];
            return mm1;
        };
        return Utils.parallel(images.entrySet().stream(), parallele).map(e->e.getKey().getMinAndMax(e.getValue())).reduce(combiner).get();
    }
    
    public static enum Axis {X, Y, Z;
        public static Axis get(int dim) {
            switch(dim) {
                case 0: 
                    return X;
                case 1:
                    return Y;
                case 2:
                    return Z;
                default: 
                    return null;
            }
        }
    }
    
    public static ImageByte threshold(Image image, double threshold, boolean foregroundOverThreshold, boolean strict) {
        return (ImageByte)threshold(image, threshold, foregroundOverThreshold, strict, false, null);
    }
    
    public static ImageInteger threshold(Image image, double threshold, boolean foregroundOverThreshold, boolean strict, boolean setBackground, ImageInteger dest) {
        if (dest==null) {
            dest=new ImageByte("", image);
            setBackground=false;
        }
        else if (!dest.sameDimensions(image)) {
            dest = (ImageInteger)Image.createEmptyImage(dest.getName(), dest, image);
            setBackground=false;
        }
        if (setBackground) {
            if (foregroundOverThreshold) {
                if (strict) {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)>threshold) {
                                dest.setPixel(xy, z, 1);
                            } else dest.setPixel(xy, z, 0);
                        }
                    }
                } else {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)>=threshold) {
                                dest.setPixel(xy, z, 1);
                            } else dest.setPixel(xy, z, 0);
                        }
                    }
                }
            } else {
                if (strict) {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)<threshold) {
                                dest.setPixel(xy, z, 1);
                            } else dest.setPixel(xy, z, 0);
                        }
                    }
                } else {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)<=threshold) {
                                dest.setPixel(xy, z, 1);
                            } else dest.setPixel(xy, z, 0);
                        }
                    }
                }
            }
        } else {
            if (foregroundOverThreshold) {
                if (strict) {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)>threshold) {
                                dest.setPixel(xy, z, 1);
                            }
                        }
                    }
                } else {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)>=threshold) {
                                dest.setPixel(xy, z, 1);
                            }
                        }
                    }
                }
            } else {
                if (strict) {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)<threshold) {
                                dest.setPixel(xy, z, 1);
                            }
                        }
                    }
                } else {
                    for (int z = 0; z < image.sizeZ(); z++) {
                        for (int xy = 0; xy < image.sizeXY(); xy++) {
                            if (image.getPixel(xy, z)<=threshold) {
                                dest.setPixel(xy, z, 1);
                            }
                        }
                    }
                }
            }
        }
        
        return dest;
    }

    

    public static <T extends Image<T>> T addImage(Image source1, Image source2, T output, double coeff) {
        String name = source1.getName()+" + "+coeff+" x "+source2.getName();
        if (!source1.sameDimensions(source2)) throw new IllegalArgumentException("sources images have different sizes");
        if (output==null) {
            if (coeff<0 || (int)coeff != coeff) output = (T)new ImageFloat(name, source1);
            else output = (T)Image.createEmptyImage(name, source1, source1);
        }
        else if (!output.sameDimensions(source1)) output = (T)Image.createEmptyImage(name, output, source1);
        float round = output instanceof ImageInteger?0.5f:0;
        if (coeff==1) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z)+source2.getPixel(xy, z)+round);
                }
            }
        } else if (coeff==-1) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z)-source2.getPixel(xy, z)+round);
                }
            }
        } else {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z)+source2.getPixel(xy, z)*coeff+round);
                }
            }
        }
        return output;
    }
    /**
     * 
     * @param source1
     * @param output
     * @param multiplicativeCoefficient
     * @param additiveCoefficient
     * @return multiplicative then additive
     */
    public static Image affineOperation(Image source1, Image output, double multiplicativeCoefficient, double additiveCoefficient) {
        String name = source1.getName()+" x "+multiplicativeCoefficient + " + "+additiveCoefficient;
        if (output==null) {
            if (multiplicativeCoefficient<0 || (int)multiplicativeCoefficient != multiplicativeCoefficient || additiveCoefficient<0) output = new ImageFloat(name, source1);
            else output = Image.createEmptyImage(name, source1, source1);
        } else if (!output.sameDimensions(source1)) output = Image.createEmptyImage(name, output, source1);
        additiveCoefficient += output instanceof ImageInteger?0.5:0;
        if (additiveCoefficient!=0 && multiplicativeCoefficient!=1) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z)*multiplicativeCoefficient+additiveCoefficient);
                }
            } 
        } else if (additiveCoefficient==0) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z)*multiplicativeCoefficient);
                }
            } 
        } else {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z) +additiveCoefficient );
                }
            } 
        }
        return output;
    }
    /**
     * 
     * @param source1
     * @param output
     * @param multiplicativeCoefficient
     * @param additiveCoefficient
     * @return additive coeff first then multiplicative
     */
    public static Image affineOperation2(Image source1, Image output, double multiplicativeCoefficient, double additiveCoefficient) {
        String name = "("+source1.getName()+ " + "+additiveCoefficient+") "+" x "+multiplicativeCoefficient ;
        if (output==null) {
            if (multiplicativeCoefficient<0 || (int)multiplicativeCoefficient != multiplicativeCoefficient || additiveCoefficient<0) output = new ImageFloat(name, source1);
            else output = Image.createEmptyImage(name, source1, source1);
        } else if (!output.sameDimensions(source1)) output = Image.createEmptyImage(name, output, source1);
        double end = output instanceof ImageInteger?0.5:0;
        if (additiveCoefficient!=0 && multiplicativeCoefficient!=1) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, (source1.getPixel(xy, z)+additiveCoefficient)*multiplicativeCoefficient+end);
                }
            } 
        } else if (additiveCoefficient==0) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z)*multiplicativeCoefficient+end);
                }
            } 
        } else {
            additiveCoefficient+=end;
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z) +additiveCoefficient );
                }
            } 
        }
        return output;
    }
    
    public static Image affineOperation2WithOffset(final Image source1, Image output, final double multiplicativeCoefficient, final double additiveCoefficient) {
        String name = "("+source1.getName()+ " + "+additiveCoefficient+") "+" x "+multiplicativeCoefficient ;
        if (output==null) {
            if (multiplicativeCoefficient<0 || (int)multiplicativeCoefficient != multiplicativeCoefficient || additiveCoefficient<0) output = new ImageFloat(name, source1);
            else output = Image.createEmptyImage(name, source1, source1);
        } else if (!output.sameDimensions(source1)) output = Image.createEmptyImage(name, output, source1);
        final double end = output instanceof ImageInteger?0.5:0;
        final Image out = output;
        BoundingBox.loop(source1.getBoundingBox(), (x, y, z) -> {
            out.setPixelWithOffset(x, y, z, (source1.getPixelWithOffset(x, y, z)+additiveCoefficient)*multiplicativeCoefficient+end);
        });
        return output;
    }
    
    public static <T extends Image<T>> T multiply(Image source1, Image source2, T output) {
        if (!source1.sameDimensions(source2)) throw new IllegalArgumentException("cannot multiply images of different sizes");
        if (output==null) output = (T)new ImageFloat(source1.getName()+" x "+source2.getName(), source1);
        else if (!output.sameDimensions(source1)) output = Image.createEmptyImage(source1.getName()+" x "+source2.getName(), output, source1);
        for (int z = 0; z<output.sizeZ(); ++z) {
            for (int xy=0; xy<output.sizeXY(); ++xy) {
                output.setPixel(xy, z, source1.getPixel(xy, z)*source2.getPixel(xy, z));
            }
        }
        return output;
    }
    public static <T extends Image<T>> T addValue(Image source1, double value, T output) {
        if (output==null) output = (T)new ImageFloat(source1.getName()+" + "+value, source1);
        else if (!output.sameDimensions(source1)) output = Image.createEmptyImage(source1.getName()+" + "+value, output, source1);
        for (int z = 0; z<output.sizeZ(); ++z) {
            for (int xy=0; xy<output.sizeXY(); ++xy) {
                output.setPixel(xy, z, source1.getPixel(xy, z)+value);
            }
        }
        return output;
    }
    
    public static <T extends Image<T>> T divide(Image source1, Image source2, T output, double... multiplicativeCoefficient) {
        if (!source1.sameDimensions(source2)) throw new IllegalArgumentException("cannot multiply images of different sizes");
        if (output==null) output = (T)new ImageFloat(source1.getName()+" x "+source2.getName(), source1);
        else if (!output.sameDimensions(source1)) output = Image.createEmptyImage(source1.getName()+" x "+source2.getName(), output, source1);
        if (multiplicativeCoefficient.length == 0) {
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, source1.getPixel(xy, z)/source2.getPixel(xy, z));
                }
            }
        } else {
            double m = multiplicativeCoefficient[0];
            for (int z = 0; z<output.sizeZ(); ++z) {
                for (int xy=0; xy<output.sizeXY(); ++xy) {
                    output.setPixel(xy, z, m * source1.getPixel(xy, z)/source2.getPixel(xy, z));
                }
            }
        }
        return output;
    }
    
    public static ImageInteger or(ImageMask source1, ImageMask source2, ImageInteger output) {
        if (output==null) output = new ImageByte("or", source1);
        for (int z = 0; z<source1.sizeZ(); ++z) {
            for (int xy=0; xy<source1.sizeXY(); ++xy) {
                if (source1.insideMask(xy, z) || source2.insideMask(xy, z)) output.setPixel(xy, z, 1);
                else output.setPixel(xy, z, 0);
            }
        }
        return output;
    }
    public static ImageInteger orWithOffset(final ImageMask source1, final ImageMask source2, ImageInteger output) {
        if (output==null) output = new ImageByte("or", source1);
        final ImageInteger out = output;
        BoundingBox loopBound = output.getBoundingBox().trim(new MutableBoundingBox(source1).union(source2));
        BoundingBox.loop(loopBound,(x, y, z) -> {
            if ((!source1.containsWithOffset(x, y, z) || !source1.insideMaskWithOffset(x, y, z)) 
                    && (!source2.containsWithOffset(x, y, z) || !source2.insideMaskWithOffset(x, y, z))) out.setPixelWithOffset(x, y, z, 0);
            else out.setPixelWithOffset(x, y, z, 1);
        });
        return out;
    }
    
    public static ImageInteger xorWithOffset(final ImageMask source1, final ImageMask source2, ImageInteger output) {
        if (output==null) output = new ImageByte("or", source1);
        final ImageInteger out = output;
        //logger.debug("output: {}, trimmed: {}", output.getBoundingBox(), output.getBoundingBox().trim(source1.getBoundingBox().expand(source2.getBoundingBox())));
        BoundingBox loopBound = output.getBoundingBox().trim(new MutableBoundingBox(source1).union(source2));
        BoundingBox.loop(loopBound,(x, y, z) -> {
            if ((source1.containsWithOffset(x, y, z) && source1.insideMaskWithOffset(x, y, z))!=(source2.containsWithOffset(x, y, z) && source2.insideMaskWithOffset(x, y, z))) out.setPixelWithOffset(x, y, z, 1);
            else out.setPixelWithOffset(x, y, z, 0);
        });
        return out;
    }
    
    public static ImageInteger andWithOffset(final ImageMask source1, final ImageMask source2, ImageInteger output) {
        if (output==null) output = new ImageByte("and", source1);
        final ImageInteger out = output;
        BoundingBox loopBound = output.getBoundingBox().trim(new MutableBoundingBox(source1).union(source2));
        BoundingBox.loop(loopBound,(x, y, z) -> {
            if ((source1.containsWithOffset(x, y, z) && source1.insideMaskWithOffset(x, y, z))&&(source2.containsWithOffset(x, y, z) && source2.insideMaskWithOffset(x, y, z))) out.setPixelWithOffset(x, y, z, 1);
            else out.setPixelWithOffset(x, y, z, 0);
        });
        return out;
    }
    /*public static ImageInteger andWithOffset(final ImageMask source1, final ImageMask source2, boolean source1OutOfBoundIsNull, boolean source2OutOfBoundIsNull, ImageInteger output) {
        if (output==null) output = new ImageByte("or", source1);
        final ImageInteger out = output;
        BoundingBox loopBound = output.getBoundingBox().trim(source1.getBoundingBox().expand(source2.getBoundingBox()));
        loopBound.loop(new LoopFunction() {
            public void setUp() {}
            public void tearDown() {}
            public void loop(int x, int y, int z) {
                if ((source1.containsWithOffset(x, y, z) ? source1.insideMaskWithOffset(x, y, z) : !source1OutOfBoundIsNull)&&(source2.containsWithOffset(x, y, z) ? source2.insideMaskWithOffset(x, y, z) : !source2OutOfBoundIsNull)) out.setPixelWithOffset(x, y, z, 1);
                else out.setPixelWithOffset(x, y, z, 0);
            }
        });
        return out;
    }*/
    
    public static <T extends Image<T>> T trim(T source, ImageMask mask, T output) {
        if (output==null) output = (T)Image.createEmptyImage(source.getName(), source, source);
        if (!output.sameDimensions(source)) output = Image.createEmptyImage("outside", source, source);
        for (int z = 0; z<source.sizeZ(); ++z) {
            for (int xy=0; xy<source.sizeXY(); ++xy) {
                if (!mask.insideMask(xy, z)) output.setPixel(xy, z, 0);
                else if (output!=source) output.setPixel(xy, z, source.getPixel(xy, z));
            }
        }
        return output;
    }
    
    public static <T extends ImageInteger<T>> T not(ImageMask source1, T output) {
        if (output==null) output = (T)new ImageByte("not", source1);
        if (!output.sameDimensions(source1)) output = Image.createEmptyImage("not", output, source1);
        for (int z = 0; z<source1.sizeZ(); ++z) {
            for (int xy=0; xy<source1.sizeXY(); ++xy) {
                if (source1.insideMask(xy, z)) output.setPixel(xy, z, 0);
                else output.setPixel(xy, z, 1);
            }
        }
        return output;
    }
    
    public static ImageInteger xor(ImageMask source1, ImageMask source2, ImageInteger output) {
        if (output==null) output = new ImageByte("or", source1);
        for (int z = 0; z<source1.sizeZ(); ++z) {
            for (int xy=0; xy<source1.sizeXY(); ++xy) {
                if (source1.insideMask(xy, z)!=source2.insideMask(xy, z)) output.setPixel(xy, z, 1);
                else output.setPixel(xy, z, 0);
            }
        }
        return output;
    }
    
    public static ImageInteger andNot(ImageMask source1, ImageMask source2, ImageInteger output) {
        if (output==null) output = new ImageByte("or", source1);
        for (int z = 0; z<source1.sizeZ(); ++z) {
            for (int xy=0; xy<source1.sizeXY(); ++xy) {
                if (source1.insideMask(xy, z) && !source2.insideMask(xy, z)) output.setPixel(xy, z, 1);
                else output.setPixel(xy, z, 0);
            }
        }
        return output;
    }
    
    public static ImageInteger and(ImageMask source1, ImageMask source2, ImageInteger output) {
        if (output==null) output = new ImageByte("and", source1);
        for (int z = 0; z<source1.sizeZ(); ++z) {
            for (int xy=0; xy<source1.sizeXY(); ++xy) {
                if (source1.insideMask(xy, z) && source2.insideMask(xy, z)) output.setPixel(xy, z, 1);
                else output.setPixel(xy, z, 0);
            }
        }
        return output;
    }
    
    public static void trimValues(Image image, double value, double replacementValue, boolean trimUnderValue) {
        if (trimUnderValue) {
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy=0; xy<image.sizeXY(); ++xy) {
                    if (image.getPixel(xy, z)<value) image.setPixel(xy, z, replacementValue);
                }
            }
        } else {
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy=0; xy<image.sizeXY(); ++xy) {
                    if (image.getPixel(xy, z)>value) image.setPixel(xy, z, replacementValue);
                }
            }
        }
    }
    interface ValueLoopFunction {
        double apply(int x, int y, int z);
    }
    public static Image spatialBinning(Image image, int factorXY, int factorZ, boolean max) {
        int sizeX = Math.max(1, image.sizeX()/factorXY);
        int sizeY = Math.max(1, image.sizeY()/factorXY);
        int sizeZ = Math.max(1, image.sizeZ()/factorZ);
        Image result = Image.createEmptyImage(image.getName()+"_binned", max ? image : new ImageFloat("", 0, 0, 0), new SimpleImageProperties(new SimpleBoundingBox(0, sizeX-1, 0, sizeY-1, 0, sizeZ-1), image.getScaleXY()*factorXY, image.getScaleZ()*factorZ));
        ValueLoopFunction getValue;
        if (max) {
            getValue = (x, y, z) -> {
                double res = Double.NEGATIVE_INFINITY;
                for (int xx = x; xx < x + factorXY; ++xx) {
                    for (int yy = y; yy < y + factorXY; ++yy) {
                        for (int zz = z; zz < z + factorZ; ++zz) {
                            double v = image.getPixel(xx, yy, zz);
                            if (v>res) res = v;
                        }
                    }
                }
                return res;
            };
        } else {
            getValue = (x, y, z) -> {
                double res = 0;
                double count = 0;
                for (int xx = x; xx < x + factorXY; ++xx) {
                    for (int yy = y; yy < y + factorXY; ++yy) {
                        for (int zz = z; zz < z + factorZ; ++zz) {
                            if (image.contains(xx, yy, zz)) {
                                res += image.getPixel(xx, yy, zz);
                                ++count;
                            }
                        }
                    }
                }
                return res/count;
            };
        }
        BoundingBox.loop(result, (x, y, z)-> {
            result.setPixel(x, y, z, getValue.apply(x * factorXY, y * factorXY, z * factorZ));
        }, true);
        return result;
    }

    /**
     * 
     * @param image
     * @param axis remaining axis
     * @param limit projection within the boundingbox
     * @return 
     */
    public static float[] meanProjection(Image image, Axis axis, BoundingBox limit) {
        float[] res;
        if (limit==null) limit = new SimpleBoundingBox(image).resetOffset();
        switch (axis) {
            case X:
                res = new float[limit.sizeX()];
                for (int x = limit.xMin(); x<=limit.xMax(); ++x) {
                    double sum=0;
                    for (int z=limit.zMin(); z<=limit.zMax(); ++z) for (int y=limit.yMin(); y<=limit.yMax(); ++y) sum+=image.getPixel(x, y, z);
                    res[x-limit.xMin()]=(float) (sum/(limit.sizeY()*limit.sizeZ()));
                }   break;
            case Y:
                res = new float[limit.sizeY()];
                for (int y = limit.yMin(); y<=limit.yMax(); ++y) {
                    double sum=0;
                    for (int z=limit.zMin(); z<=limit.zMax(); ++z) for (int x=limit.xMin(); x<=limit.xMax(); ++x) sum+=image.getPixel(x, y, z);
                    res[y-limit.yMin()]=(float) (sum/(limit.sizeX()*limit.sizeZ()));
                }   break;
            default:
                res = new float[limit.sizeZ()];
                for (int z = limit.zMin(); z<=limit.zMax(); ++z) {
                    double sum=0;
                    for (int x=limit.xMin(); x<=limit.xMax(); ++x) for (int y=limit.yMin(); y<=limit.yMax(); ++y) sum+=image.getPixel(x, y, z);
                    res[z-limit.zMin()]=(float) (sum/(limit.sizeY()*limit.sizeX()));
                }   break;
        }
        return res;
    }
    public static float[] meanProjection(Image image, Axis axis, BoundingBox limit, DoublePredicate useValue) {
        return meanProjection(image, axis, limit, useValue, null);
    }
    public static float[] meanProjection(Image image, Axis axis, BoundingBox limit, DoublePredicate useValue, float[] output) {
        if (limit==null) limit = new SimpleBoundingBox(image).resetOffset();
        switch (axis) {
            case X:
                output = output==null || output.length!=limit.sizeX() ? new float[limit.sizeX()] : output;
                for (int x = limit.xMin(); x<=limit.xMax(); ++x) {
                    double sum=0;
                    double count= 0;
                    for (int z=limit.zMin(); z<=limit.zMax(); ++z) {
                        for (int y=limit.yMin(); y<=limit.yMax(); ++y) {
                            double v = image.getPixel(x, y, z);
                            if (useValue.test(v)) {
                                sum+=v;
                                ++count;
                            }
                            
                        }
                    }
                    output[x-limit.xMin()]=(float) (sum/count);
                }   break;
            case Y:
                output = output==null || output.length!=limit.sizeY() ? new float[limit.sizeY()] : output;
                for (int y = limit.yMin(); y<=limit.yMax(); ++y) {
                    double sum=0;
                    double count = 0;
                    for (int z=limit.zMin(); z<=limit.zMax(); ++z) {
                        for (int x=limit.xMin(); x<=limit.xMax(); ++x) {
                            double v = image.getPixel(x, y, z);
                            if (useValue.test(v)) {
                                sum+=v;
                                ++count;
                            }
                        }
                    }
                    output[y-limit.yMin()]=(float) (sum/count);
                }   break;
            default:
                output = output==null || output.length!=limit.sizeZ() ? new float[limit.sizeZ()] : output;
                for (int z = limit.zMin(); z<=limit.zMax(); ++z) {
                    double sum=0;
                    double count = 0;
                    for (int x=limit.xMin(); x<=limit.xMax(); ++x) {
                        for (int y=limit.yMin(); y<=limit.yMax(); ++y) {
                            double v = image.getPixel(x, y, z);
                            if (useValue.test(v)) {
                                sum+=v;
                                ++count;
                            }
                        }
                    }
                    output[z-limit.zMin()]=(float) (sum/count);
                }   break;
        }
        return output;
    }
    
    /**
     * 
     * @param image
     * @param axis along which project values
     * @param limit projection within the boundingbox
     * @return 
     */
    public static float[] maxProjection(Image image, Axis axis, BoundingBox limit) {
        float[] res;
        float value;
        if (limit==null) limit = new SimpleBoundingBox(image).resetOffset();
        switch (axis) {
            case X:
                res = new float[limit.sizeX()];
                for (int x = limit.xMin(); x<=limit.xMax(); ++x) {
                    float max=image.getPixel(x, limit.yMin(), limit.zMin());
                    for (int z=limit.zMin(); z<=limit.zMax(); ++z) for (int y=limit.yMin(); y<=limit.yMax(); ++y) {value=image.getPixel(x, y, z); if (value>max) max=value;}
                    res[x-limit.xMin()]=max;
                }   break;
            case Y:
                res = new float[limit.sizeY()];
                for (int y = limit.yMin(); y<=limit.yMax(); ++y) {
                    float max=image.getPixel(limit.xMin(), y, limit.zMin());
                    for (int z=limit.zMin(); z<=limit.zMax(); ++z) for (int x=limit.xMin(); x<=limit.xMax(); ++x) {value=image.getPixel(x, y, z); if (value>max) max=value;}
                    res[y-limit.yMin()]=max;
                }   break;
            default:
                res = new float[limit.sizeZ()];
                for (int z = limit.zMin(); z<=limit.zMax(); ++z) {
                    float max=image.getPixel(limit.xMin(), limit.yMin(), z);
                    for (int x=limit.xMin(); x<=limit.xMax(); ++x) for (int y=limit.yMin(); y<=limit.yMax(); ++y) {value=image.getPixel(x, y, z); if (value>max) max=value;}
                    res[z-limit.zMin()]=max;
                }   break;
        }
        return res;
    }
    public static ImageFloat meanZProjection(Image input) {return meanZProjection(input, null);}
    public static <T extends Image<T>> T meanZProjection(Image input, T output) {
        BlankMask properties =  new BlankMask( input.sizeX(), input.sizeY(), 1, input.xMin(), input.yMin(), input.zMin(), input.getScaleXY(), input.getScaleZ());
        if (output ==null) output = (T)new ImageFloat("mean Z projection", properties);
        else if (!output.sameDimensions(properties)) output = Image.createEmptyImage("mean Z projection", output, properties);
        float size = input.sizeZ();
        for (int xy = 0; xy<input.sizeXY(); ++xy) {
            float sum = 0;
            for (int z = 0; z<input.sizeZ(); ++z) sum+=input.getPixel(xy, z);
            output.setPixel(xy, 0, sum/size);
        }
        return output;
    }
    public static <T extends Image<T>> T maxZProjection(T input, int... zLim) {return maxZProjection(input, null, zLim);}
    public static <T extends Image<T>> T maxZProjection(T input, T output, int... zLim) {
        BlankMask properties =  new BlankMask( input.sizeX(), input.sizeY(), 1, input.xMin(), input.yMin(), input.zMin(), input.getScaleXY(), input.getScaleZ());
        if (output ==null) output = (T)Image.createEmptyImage("max Z projection", input, properties);
        else if (!output.sameDimensions(properties)) output = Image.createEmptyImage("mean Z projection", output, properties);
        int zMin = 0;
        int zMax = input.sizeZ()-1;
        if (zLim.length>0) zMin = zLim[0];
        if (zLim.length>1) zMax = zLim[1];
        for (int xy = 0; xy<input.sizeXY(); ++xy) {
            float max = input.getPixel(xy, 0);
            for (int z = zMin+1; z<=zMax; ++z) {
                if (input.getPixel(xy, z)>max) {
                    max = input.getPixel(xy, z);
                }
            }
            output.setPixel(xy, 0, max);
        }
        return output;
    }
    
    public static ImageFloatingPoint normalize(Image input, ImageMask mask, ImageFloatingPoint output) {
        double[] mm = input.getMinAndMax(mask);
        if (output==null || !output.sameDimensions(input)) output = new ImageFloat(input.getName()+" normalized", input);
        if (mm[0]==mm[1]) return output;
        double scale = 1 / (mm[1] - mm[0]);
        double offset = -mm[0] * scale;
        return (ImageFloatingPoint)affineOperation(input, output, scale, offset);
    }
    public static ImageFloatingPoint normalize(Image input, ImageMask mask, ImageFloatingPoint output, double pMin, double pMax, boolean saturate) {
        if (pMin>=pMax) throw new IllegalArgumentException("pMin should be < pMax");
        if (output==null || !output.sameDimensions(input)) output = new ImageFloat(input.getName()+" normalized", input);
        double[] minAndMax = new double[2];
        double[] mm = null;
        if (pMin<=0) {
            mm = input.getMinAndMax(mask);
            minAndMax[0] = mm[0];
        }
        if (pMax>=1) {
            if (mm==null) mm = input.getMinAndMax(mask);
            minAndMax[1] = mm[1];
        }
        if (pMin>0 && pMax<1) {
            minAndMax = getQuantiles(input, mask, null, new double[]{pMin, pMax});
        } else if (pMin>0) {
            minAndMax[0] = getQuantiles(input, mask, null, new double[]{pMin})[0];
        } else if (pMax<0) {
            minAndMax[1] = getQuantiles(input, mask, null, new double[]{pMax})[1];
        }
        double scale = 1 / (minAndMax[1] - minAndMax[0]);
        double offset = -minAndMax[0] * scale;
        //logger.debug("normalize: min ({}) = {}, max ({}) = {}, scale: {}, offset: {}", pMin, minAndMax[0], pMax, minAndMax[1], scale, offset);
        if (saturate) {
            for (int z = 0; z < input.sizeZ(); z++) {
                for (int xy = 0; xy < input.sizeXY(); xy++) {
                    float res = (float) (input.getPixel(xy, z) * scale + offset);
                    if (res<0) res = 0;
                    if (res>1) res = 1;
                    output.setPixel(xy, z, res);
                }
            }
        } else {
            for (int z = 0; z < input.sizeZ(); z++) {
                for (int xy = 0; xy < input.sizeXY(); xy++) {
                    output.setPixel( xy, z, (float) (input.getPixel(xy, z) * scale + offset) );
                }
            }
        }
        return output;
    }
    public static double[] getQuantiles(Image image, ImageMask mask, BoundingBox limits, double... percent) {
        Histogram histo = HistogramFactory.getHistogram(()->image.stream(mask, true), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        return histo.getQuantiles(percent);
    }
    
    
    public static Voxel getGlobalExtremum(Image image, BoundingBox area, boolean max) {
        float extrema = image.getPixel(area.xMin(), area.yMin(), area.zMin());
        int xEx=area.xMin(), yEx=area.yMin(), zEx=area.zMin();
        if (max) {
            for (int z= area.zMin();z<=area.zMax();++z) {
                for (int y = area.yMin(); y<=area.yMax(); y++) {
                    for (int x=area.xMin(); x<=area.xMax(); ++x) {
                        if (image.getPixel(x, y, z)>extrema) {
                            extrema = image.getPixel(x, y, z); 
                            yEx=y; xEx=x; zEx=z;
                        }
                    }
                }
            }
        } else {
            for (int z= area.zMin();z<=area.zMax();++z) {
                for (int y = area.yMin(); y<=area.yMax(); y++) {
                    for (int x=area.xMin(); x<=area.xMax(); ++x) {
                        if (image.getPixel(x, y, z)<extrema) {
                            extrema = image.getPixel(x, y, z); 
                            yEx=y; xEx=x; zEx=z;
                        }
                    }
                }
            }
        }
        return new Voxel(xEx, yEx, zEx, extrema);  
            
    }
    
    public static void fill(Image image, double value, BoundingBox area) { // TODO: use System method
        if (area==null) area=image.getBoundingBox().resetOffset();
        for (int z= area.zMin();z<=area.zMax();++z) {
            for (int y = area.yMin(); y<=area.yMax(); y++) {
                for (int x=area.xMin(); x<=area.xMax(); ++x) {
                    image.setPixel(x, y, z, value);
                }
            }
        }
    }
    
    public static float getMinOverThreshold(Image image, float threshold) {
        float min = Float.MAX_VALUE;
        BoundingBox limits = image.getBoundingBox().resetOffset();
        for (int z = limits.zMin(); z <= limits.zMax(); z++) {
            for (int y = limits.yMin(); y<=limits.yMax(); ++y) {
                for (int x = limits.xMin(); x <= limits.xMax(); ++x) {
                    //if (mask.insideMask(x, y, z)) {
                    if (image.getPixel(x, y, z) < min && image.getPixel(x, y, z)>threshold) {
                        min = image.getPixel(x, y, z);
                    }
                    //}
                }
            }
        }
        if (min==Float.MAX_VALUE) min = threshold;
        return min;
    }
    
    public static double[] getMeanAndSigma(Image image, ImageMask mask, DoublePredicate useValue) {
        if (mask==null) mask = new BlankMask(image);
        double mean = 0;
        double count = 0;
        double values2 = 0;
        double value;
        for (int z = 0; z < image.sizeZ(); ++z) {
            for (int xy = 0; xy < image.sizeXY(); ++xy) {
                if (mask.insideMask(xy, z)) {
                    value = image.getPixel(xy, z);
                    if (useValue==null || useValue.test(value)) {
                        mean += value;
                        count++;
                        values2 += value * value;
                    }
                }
            }
        }
        mean /= count;
        values2 /= count;
        return new double[]{mean, Math.sqrt(values2 - mean * mean), count};
    }
    
    public static double[] getMeanAndSigma(Image image, ImageMask mask, DoublePredicate useValue, boolean parallele) {
        DoubleStream stream = Utils.parallel(image.stream(mask, false), parallele);
        if (useValue!=null) stream = stream.filter(useValue);
        DoubleStatistics stats = DoubleStatistics.getStats(stream);
        return new double[]{stats.getAverage(), stats.getStandardDeviation()};
        /*if (mask==null) mask = new BlankMask(image);
        else if (!mask.sameDimensions(image)) throw new IllegalArgumentException("Mask should be of same size as image");
        double mean = 0;
        double count = 0;
        double values2 = 0;
        double value;
        for (int z = 0; z < image.sizeZ(); ++z) {
            for (int xy = 0; xy < image.sizeXY(); ++xy) {
                if (mask.insideMask(xy, z)) {
                    value = image.getPixel(xy, z);
                    mean += value;
                    count++;
                    values2 += value * value;
                }
            }
        }
        mean /= count;
        values2 /= count;
        return new double[]{mean, Math.sqrt(values2 - mean * mean), count};*/
    }
    
    public static double[] getMeanAndSigmaWithOffset(Image image, ImageMask mask, DoublePredicate useValue, boolean parallele) {
        DoubleStream stream = Utils.parallel(image.stream(mask, true), parallele);
        if (useValue!=null) stream = stream.filter(useValue);
        DoubleStatistics stats = DoubleStatistics.getStats(stream);
        return new double[]{stats.getAverage(), stats.getStandardDeviation()};
        /*if (mask==null) mask = new BlankMask(image);
        final ImageMask mask2 = mask;
        double[] vv2c = new double[3];
        BoundingBox intersect = BoundingBox.getIntersection(mask, image);
        if (useValue==null) {
            loop(intersect, (int x, int y, int z) -> {
                if (mask2.insideMaskWithOffset(x, y, z)) {
                    double tmp = image.getPixelWithOffset(x, y, z);
                    vv2c[0] += tmp;
                    vv2c[1] += tmp * tmp;
                    ++vv2c[2];
                }
            });
        } else {
            loop(intersect, (int x, int y, int z) -> {
                if (mask2.insideMaskWithOffset(x, y, z)) {
                    double tmp = image.getPixelWithOffset(x, y, z);
                    if (useValue.test(tmp)) {
                        vv2c[0] += tmp;
                        vv2c[1] += tmp * tmp;
                        ++vv2c[2];
                    }
                }
            });
        }
        double mean = vv2c[0] / vv2c[2];
        double values2 = vv2c[1] / vv2c[2];
        return new double[]{mean, Math.sqrt(values2 - mean * mean), vv2c[2]};*/
    }
    
    /**
     * 
     * @param image
     * @param radiusXY
     * @param radiusZ
     * @param mask area where objects can be dillated, can be null
     * @param keepOnlyDilatedPart
     * @return dilatedMask
     */
    public static ImageByte getDilatedMask(ImageInteger image, double radiusXY, double radiusZ, ImageInteger mask, boolean keepOnlyDilatedPart, boolean parallele) {
        ImageByte dilatedMask = Filters.binaryMax(image, new ImageByte("", 0, 0, 0), Filters.getNeighborhood(radiusXY, radiusZ, image), false, true, parallele);
        if (keepOnlyDilatedPart) {
            ImageOperations.xorWithOffset(dilatedMask, image, dilatedMask);
        }
        if (mask!=null) {
            ImageOperations.andWithOffset(dilatedMask, mask, dilatedMask);
            if (!keepOnlyDilatedPart) ImageOperations.andWithOffset(dilatedMask, image, dilatedMask); // ensures the object is included in the mask
        }
        return dilatedMask;
    }
    public static enum IJInterpolation {
        NEAREST_NEIGHBOR(ij.process.ImageProcessor.NEAREST_NEIGHBOR), 
        BILINEAR(ij.process.ImageProcessor.BILINEAR),
        BICUBIC(ij.process.ImageProcessor.BICUBIC);
        int methodIdx;
        private IJInterpolation(int method) {this.methodIdx=method;}
        public int method() {return methodIdx;}
    }
    
    public static Image resizeXY(Image image, int newX, int newY, IJInterpolation interpolation) {
        if (interpolation == null) interpolation = IJInterpolation.BICUBIC;
        if (newX==0 || newY==0) throw new IllegalArgumentException("cant resize to 0");
        if (newX == image.sizeX() && newY == image.sizeY()) return image.duplicate();
        ImagePlus ip = IJImageWrapper.getImagePlus(image);
        ip.getProcessor().setInterpolationMethod(interpolation.method());
        StackProcessor sp = new StackProcessor(ip.getStack(), ip.getProcessor());
        ip = new ImagePlus(image.getName() + "::resized", sp.resize(newX, newY, true));
        
        return IJImageWrapper.wrap(ip);
    }
    public static Image resizeZ(Image image, int newZ, IJInterpolation interpolation) {
        if (newZ==0) throw new IllegalArgumentException("cant resize to 0");
        if (interpolation == null) interpolation = IJInterpolation.BICUBIC;
        ImagePlus ip = IJImageWrapper.getImagePlus(image);
        if ( newZ == image.sizeZ()) return image.duplicate();
        ij.plugin.Resizer r = new ij.plugin.Resizer();
        ip = r.zScale(ip, newZ, interpolation.method());
        return IJImageWrapper.wrap(ip);
    }
}
