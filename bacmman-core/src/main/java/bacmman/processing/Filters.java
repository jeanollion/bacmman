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

import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.ImageProperties;
import bacmman.image.SimpleBoundingBox;
import bacmman.processing.neighborhood.DisplacementNeighborhood;
import java.util.Arrays;

import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.HashMapGetCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class Filters {
    public final static Logger logger = LoggerFactory.getLogger(Filters.class);
    public static DisplacementNeighborhood getNeighborhood(double radiusXY, ImageProperties image) {return image.sizeZ()>1 ?getNeighborhood(radiusXY, image.getScaleXY()/image.getScaleZ(), image) : getNeighborhood(radiusXY, 1, image);}
    public static DisplacementNeighborhood getNeighborhood(double radiusXY, double radiusZ, ImageProperties image) {return image.sizeZ()>1 ? new EllipsoidalNeighborhood(radiusXY, radiusZ, false) : new EllipsoidalNeighborhood(radiusXY, false);}
      
    public static <T extends Image<T>> T mean(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        return applyFilter(image, output, new Mean(), neighborhood, parallele);
    }
    public static <T extends Image<T>> T sigma(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        if (output==null) output = (T)new ImageFloat(Sigma.class.getSimpleName()+" of: "+image.getName(), image);
        return applyFilter(image, output, new Sigma(), neighborhood, parallele);
    }
    public static <T extends Image<T>> T sigmaMu(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        if (output==null) output = (T)new ImageFloat(SigmaMu.class.getSimpleName()+" of: "+image.getName(), image);
        return applyFilter(image, output, new SigmaMu(), neighborhood, parallele);
    }
    
    public static <T extends Image<T>> T median(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        return applyFilter(image, output, new Median(), neighborhood, parallele);
    }
    
    public static <T extends Image<T>> T max(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        return applyFilter(image, output, new Max(), neighborhood, parallele);
    }
    
    public static <T extends Image<T>> T min(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        return applyFilter(image, output, new Min(), neighborhood, parallele);
    }
    
    public static <T extends ImageInteger<T>, I extends ImageInteger<I>> T binaryMax(I image, T output, Neighborhood neighborhood, boolean extendImage, boolean parallele) {
        if (extendImage) image =  image.extend(neighborhood.getBoundingBox());
        return applyFilter(image, output, new BinaryMax(false), neighborhood, parallele);
    }
    
    public static <T extends ImageInteger<T>> T binaryMin(ImageInteger image, T output, Neighborhood neighborhood, boolean parallele) {
        return applyFilter(image, output, new BinaryMin(true), neighborhood, parallele);
    }
    
    public static <T extends Image<T>> T open(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        ImageFloat min = applyFilter(image, new ImageFloat("", 0, 0, 0), new Min(), neighborhood, parallele);
        //if (output == image) output = Image.createEmptyImage("open", output, output);
        return applyFilter(min, output, new Max(), neighborhood, parallele);
    }
    
    public static <T extends Image<T>> T close(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        ImageFloat max = applyFilter(image, new ImageFloat("", 0, 0, 0), new Max(), neighborhood, parallele);
        return applyFilter(max, output, new Min(), neighborhood, parallele);
    }
    
    public static <T extends ImageInteger<T>> T binaryOpen(ImageInteger image, T output, Neighborhood neighborhood, boolean parallele) {
        ImageByte min = applyFilter(image, new ImageByte("", 0, 0, 0), new BinaryMin(true), neighborhood, parallele);
        //if (output == image) output = Image.createEmptyImage("binary open", output, output);
        return applyFilter(min, output, new BinaryMax(false), neighborhood, parallele);
    }

    public static <T extends ImageInteger<T>> T binaryCloseExtend(ImageInteger<T> image, Neighborhood neighborhood, boolean parallele) {
        MutableBoundingBox extent = neighborhood.getBoundingBox();
        T resized =  image.extend(extent);
        ImageByte max = applyFilter(resized, new ImageByte("", 0, 0, 0), new BinaryMax(false), neighborhood, parallele);
        T min = applyFilter(max, resized, new BinaryMin(true), neighborhood, parallele);
        return min.crop(image.getBoundingBox().resetOffset().translate(extent.duplicate().reverseOffset()));
    }
    public static <T extends ImageInteger<T>> T binaryClose(ImageInteger image, T output, Neighborhood neighborhood, boolean parallele) {
        ImageByte max = applyFilter(image, new ImageByte("", 0, 0, 0), new BinaryMax(false), neighborhood, parallele);
        return applyFilter(max, output, new BinaryMin(true), neighborhood, parallele);
    }
    /*public static <T extends ImageInteger> T labelWiseBinaryCloseExtend(T image, Neighborhood neighborhood) {
        BoundingBox extent = neighborhood.getBoundingBox();
        T resized =  image.extend(extent);
        ImageByte max = applyFilter(resized, new ImageByte("", 0, 0, 0), new BinaryMaxLabelWise(), neighborhood);
        T min = applyFilter(max, resized, new BinaryMinLabelWise(false), neighborhood);
        return min.crop(image.getBoundingBox().translateToOrigin().translate(extent.duplicate().reverseOffset()));
    }*/
    public static <T extends Image<T>> T tophat(Image image, Image imageForBackground, T output, Neighborhood neighborhood, boolean parallele) {
        T open =open(imageForBackground, output, neighborhood, parallele).setName("Tophat of: "+image.getName());
        ImageOperations.addImage(image, open, open, -1); //1-open
        open.resetOffset().translate(image);
        return open;
    }
    
    public static <T extends Image<T>> T tophat(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        T open =open(image, output, neighborhood, parallele).setName("Tophat of: "+image.getName());
        ImageOperations.addImage(image, open, open, -1); //1-open
        open.resetOffset().translate(image);
        return open;
    }
    
    public static <T extends Image<T>> T tophatInv(Image image, T output, Neighborhood neighborhood, boolean parallele) {
        T close =close(image, output, neighborhood, parallele).setName("Tophat of: "+image.getName());
        ImageOperations.addImage(image, close, close, -1); //1-close
        close.resetOffset().translate(image);
        return close;
    }
    /**
     * ATTENTION: bug en dimension 1 !!
     * @param image input image
     * @param output image to store the results in. if {@param output} is null or {@param output}=={@param image} or {@param output} does not have the same dimensions as {@param image}, a new image of the type of {@param output} will be created
     * @param maxLocal if true the local maximum transform of the image is return, if false the local minimum of the image is returned
     * @param neighborhood 2D/3D neighborhood in which the local extrema is computed
     * @return an image of same type as output (that can be output). Each pixel value is 0 if the current pixel is not an extrema, or has the value of the original image if it is an extrema
     */
    public static ImageByte localExtrema(Image image, ImageByte output, boolean maxLocal, ImageMask mask, Neighborhood neighborhood, boolean parallel) {
        ImageByte res;
        String name = maxLocal?"MaxLocal of: "+image.getName():"MinLocal of: "+image.getName();
        if (output==null || !output.sameDimensions(image) || output==image) res = new ImageByte(name, image);
        else res = (ImageByte)output.setName(name);
        Filter filter = maxLocal?new LocalMax(mask):new LocalMin(mask);
        return applyFilter(image, res, filter, neighborhood, parallel);
    }
        /**
     * ATTENTION: bug en dimension 1 !!
     * @param image input image
     * @param output image to store the results in. if {@param output} is null or {@param output}=={@param image} or {@param output} does not have the same dimensions as {@param image}, a new image of the type of {@param output} will be created
     * @param maxLocal if true the local maximum transform of the image is return, if false the local minimum of the image is returned
     * @param threshold supplemental condition: to be a local extrema, the pixel value must be superior to {@param threshold} if {@param maxLocal}==true, otherwise inferior to {@param threshold}
     * @param neighborhood 2D/3D neighborhood in which the local extrema is computed
     * @return an image of same type as output (that can be output). Each pixel value is 0 if the current pixel is not an extrema, or has the value of the original image if it is an extrema
     */
    public static ImageByte localExtrema(Image image, ImageByte output, boolean maxLocal, double threshold, ImageMask mask, Neighborhood neighborhood, boolean parallel) {
        ImageByte res;
        String name = maxLocal?"MaxLocal of: "+image.getName():"MinLocal of: "+image.getName();
        if (output==null || !output.sameDimensions(image) || output==image) res = new ImageByte(name, image);
        else res = (ImageByte)output.setName(name);
        Filter filter = maxLocal?new LocalMaxThreshold(threshold, mask):new LocalMinThreshold(threshold, mask);
        return applyFilter(image, res, filter, neighborhood, parallel);
    }
    public static <T extends Image<T>, F extends Filter> T applyFilter(Image image, T output, F filter, Neighborhood neighborhood) {
        return applyFilter(image, output, filter, neighborhood, false);
    }
    public static <T extends Image<T>, F extends Filter> T applyFilter(Image image, T output, F filter, Neighborhood neighborhood, boolean parallele) {
        if (filter==null) throw new IllegalArgumentException("Apply Filter Error: Filter cannot be null");
        //if (neighborhood==null) throw new IllegalArgumentException("Apply Filter ("+filter.getClass().getSimpleName()+") Error: Neighborhood cannot be null");
        T res;
        String name = filter.getClass().getSimpleName()+" of: "+image.getName();
        if (output==null) res = (T)Image.createEmptyImage(name, image, image);
        else if (!output.sameDimensions(image) || output==image) res = Image.createEmptyImage(name, output, image);
        else res = (T)output.setName(name);
        double round=res instanceof ImageFloat ? 0: 0.5d;
        
        if (parallele && Runtime.getRuntime().availableProcessors()>1) {
            HashMapGetCreate<Thread, Filter> nMap = new HashMapGetCreate<>(t -> {
                Filter f = filter.duplicate();
                f.setUp(image, neighborhood.duplicate());
                return f;
            });
            BoundingBox.LoopFunction loopFunc = (x, y, z)->res.setPixel(x, y, z, nMap.getAndCreateIfNecessarySyncOnKey(Thread.currentThread()).applyFilter(x, y, z)+round);
            BoundingBox.loop(res, loopFunc, parallele);
        } else  {
            filter.setUp(image, neighborhood);
            BoundingBox.LoopFunction loopFunc = (x, y, z)->res.setPixel(x, y, z, filter.applyFilter(x, y, z)+round);
            BoundingBox.loop(res.getBoundingBox().resetOffset(), loopFunc);
        }
        res.resetOffset().translate(image);
        res.setCalibration(image);
        return res;
    }
    
    public static abstract class Filter {
        protected Image image;
        protected Neighborhood neighborhood;
        public void setUp(Image image, Neighborhood neighborhood) {this.image=image; this.neighborhood=neighborhood;}
        public abstract double applyFilter(int x, int y, int z);
        public abstract Filter duplicate();
    }
    public static class Mean extends Filter {
        public Mean() {}
        ImageMask mask;
        public Mean(ImageMask mask) {
            this.mask = mask;
        }
        @Override public Mean duplicate() {
            return new Mean(mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            neighborhood.setPixels(x, y, z, image, mask);
            if (neighborhood.getValueCount()==0) return 0;
            double mean = 0;
            for (int i = 0; i<neighborhood.getValueCount(); ++i) mean+=neighborhood.getPixelValues()[i];
            return mean/neighborhood.getValueCount();
        }
    }
    public static class Sigma extends Filter {
        public Sigma() {}
        ImageMask mask;
        public Sigma(ImageMask mask) {
            this.mask = mask;
        }
        @Override public Sigma duplicate() {
            return new Sigma(mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            neighborhood.setPixels(x, y, z, image, mask);
            if (neighborhood.getValueCount()==0) return 0;
            double mean = 0;
            double values2 = 0;
            for (int i = 0; i<neighborhood.getValueCount(); ++i) {
                mean+=neighborhood.getPixelValues()[i];
                values2+=Math.pow(neighborhood.getPixelValues()[i], 2);
            }
            mean/=neighborhood.getValueCount();
            values2/=neighborhood.getValueCount();
            return Math.sqrt(values2 - mean * mean);
        }
    }
    public static class Variance extends Filter {
        public Variance() {}
        ImageMask mask;
        public Variance(ImageMask mask) {
            this.mask = mask;
        }
        @Override public Variance duplicate() {
            return new Variance(mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            neighborhood.setPixels(x, y, z, image, mask);
            if (neighborhood.getValueCount()==0) return 0;
            double mean = 0;
            double values2 = 0;
            for (int i = 0; i<neighborhood.getValueCount(); ++i) {
                mean+=neighborhood.getPixelValues()[i];
                values2+=Math.pow(neighborhood.getPixelValues()[i], 2);
            }
            mean/=neighborhood.getValueCount();
            values2/=neighborhood.getValueCount();
            return values2 - mean * mean;
        }
    }
    private static class SigmaMu extends Filter {
        @Override public SigmaMu duplicate() {
            return new SigmaMu();
        }
        @Override public double applyFilter(int x, int y, int z) {
            neighborhood.setPixels(x, y, z, image, null);
            if (neighborhood.getValueCount()==0) return 0;
            double mean = 0;
            double values2 = 0;
            for (int i = 0; i<neighborhood.getValueCount(); ++i) {
                mean+=neighborhood.getPixelValues()[i];
                values2+=Math.pow(neighborhood.getPixelValues()[i], 2);
            }
            mean/=neighborhood.getValueCount();
            values2/=neighborhood.getValueCount();
            return Math.sqrt(values2 - mean * mean) / mean;
        }
    }
    public static class Median extends Filter {
        public Median(){};
        ImageMask mask;
        public Median(ImageMask mask){
            this.mask = mask;
        };
        @Override public Median duplicate() {
            return new Median(mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            neighborhood.setPixels(x, y, z, image, mask);
            if (neighborhood.getValueCount()==0) return 0;
            Arrays.sort(neighborhood.getPixelValues(), 0, neighborhood.getValueCount());
            if (neighborhood.getValueCount()%2==0) return (neighborhood.getPixelValues()[neighborhood.getValueCount()/2-1]+neighborhood.getPixelValues()[neighborhood.getValueCount()/2])/2d;
            else return neighborhood.getPixelValues()[neighborhood.getValueCount()/2];
        }
    }

    /*private static class MedianSelection extends Filter { // TODO: selection algorithm:  http://blog.teamleadnet.com/2012/07/quick-select-algorithm-find-kth-element.html
        @Override public float applyFilter(int x, int y, int z) {
            if (neighborhood.getValueCount()==0) return 0;
            Arrays.sort(neighborhood.getPixelValues(), 0, neighborhood.getValueCount());
            if (neighborhood.getValueCount()%2==0) {
                return (ArrayUtil.selectKth(neighborhood.getPixelValues(), neighborhood.getValueCount()/2-1) + ArrayUtil.selectKth(neighborhood.getPixelValues(), neighborhood.getValueCount()/2))/2f;
            }
            else return ArrayUtil.selectKth(neighborhood.getPixelValues(), neighborhood.getValueCount()/2);
        }
    }*/
    /*private static class GaussianMedian extends Filter {
        double[] gaussKernel;
        double[] gaussedValues;
        Integer[] indicies;
        Comparator<Integer> comp = new Comparator<Integer>() {
            @Override public int compare(Integer arg0, Integer arg1) {
                return Double.compare(gaussedValues[arg0], gaussedValues[arg1]);
            }
        };
        @Override public void setUp(Image image, Neighborhood neighborhood) {
            super.setUp(image, neighborhood);
            gaussKernel = new double[neighborhood.getSize()];
            gaussedValues = new double[neighborhood.getSize()];
            indicies = new Integer[gaussedValues.length];
            float[] d = neighborhood.getDistancesToCenter();
            double s = neighborhood.getRadiusXY();
            double expCoeff = -1 / (2 * s* s);
            for (int i = 0; i<gaussKernel.length; ++i) gaussKernel[i] = Math.exp(d[i]*d[i] * expCoeff);
        }
        public void resetOrders() {for (int i = 0; i < indicies.length; i++) indicies[i]=i;}
        @Override public float applyFilter(int x, int y, int z) {
            if (neighborhood.getValueCount()==0) return 0;
            resetOrders();
            float[] values = neighborhood.getPixelValues();
            for (int i = 0; i<values.length; ++i) gaussedValues[i] = values[i]*gaussKernel[i];
            Arrays.sort(indicies, 0, neighborhood.getValueCount(), comp);
            if (neighborhood.getValueCount()%2==0) return (values[indicies[neighborhood.getValueCount()/2-1]]+values[indicies[neighborhood.getValueCount()/2]])/2f;
            else return values[indicies[neighborhood.getValueCount()/2]];
        }
    }*/
    private static class Max extends Filter {
        @Override public Max duplicate() {
            return new Max();
        }
        @Override public double applyFilter(int x, int y, int z) {
            return neighborhood.getMax(x, y, z, image);
        }
    }
    public static class LocalMax extends Filter {
        
        final protected ImageMask mask;
        public LocalMax(ImageMask mask) {
            this.mask = mask;
        }
        @Override public LocalMax duplicate() {
            return new LocalMax(mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            if (mask!=null && !mask.insideMask(x, y, z)) return 0;
            neighborhood.setPixels(x, y, z, image, mask);
            if (neighborhood.getValueCount()==0) return 0;
            double max = neighborhood.getPixelValues()[0]; // coords are sorted by distance, first is center
            for (int i = 1; i<neighborhood.getValueCount(); ++i) if (neighborhood.getPixelValues()[i]>max) return 0;
            return 1;
        }
        public boolean hasNoValueOver(double value, int x, int y, int z) {
            neighborhood.setPixels(x, y, z, image, mask);
            for (int i = 0; i<neighborhood.getValueCount(); ++i) if (neighborhood.getPixelValues()[i]>value) return false;
            return true;
        }
    }
    public static class LocalMaxThreshold extends Filter {
        double threshold;
        final ImageMask mask;
        public LocalMaxThreshold(double threshold, ImageMask mask) {
            this.threshold=threshold;
            this.mask=mask;
        }
        @Override public LocalMaxThreshold duplicate() {
            return new LocalMaxThreshold(threshold, mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            if (mask!=null && !mask.insideMask(x, y, z)) return 0;
            if (image.getPixel(x, y, z)<threshold) return 0;
            neighborhood.setPixels(x, y, z, image, mask);
            if (neighborhood.getValueCount()==0) return 0;
            double max = neighborhood.getPixelValues()[0];
            for (int i = 1; i<neighborhood.getValueCount(); ++i) if (neighborhood.getPixelValues()[i]>max) return 0;
            return 1;
        }
    }
    public static class LabelWiseLocalMaxima extends Filters.Filter {
        final ImageInteger labels;
        public LabelWiseLocalMaxima(ImageInteger labels) {
            this.labels = labels;
        }
        @Override
        public double applyFilter(int x, int y, int z) {
            if (!labels.insideMask(x, y, z)) return 0;
            neighborhood.setPixels(x, y, z, image, null);
            neighborhood.setPixelsInt(x, y, z, labels, null);
            double max = neighborhood.getPixelValues()[0]; // coords are sorted by distance, first is center
            int curLabel = neighborhood.getPixelValuesInt()[0];
            for (int i = 1; i<neighborhood.getValueCount(); ++i) {
                if (neighborhood.getPixelValuesInt()[i]>0 && neighborhood.getPixelValuesInt()[i]!=curLabel) continue; // do not consider values in other labels
                if (neighborhood.getPixelValues()[i]>max) return 0;
            }
            return 1;
        }

        @Override
        public Filters.Filter duplicate() {
            return new LabelWiseLocalMaxima(labels);
        }
    }
    private static class LocalMin extends Filter {
        final ImageMask mask;
        public LocalMin(ImageMask mask) {
            this.mask = mask;
        }
        @Override public LocalMin duplicate() {
            return new LocalMin(mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            if (mask!=null && !mask.insideMask(x, y, z)) return 0;
            neighborhood.setPixels(x, y, z, image, mask);
            if (neighborhood.getValueCount()==0) return 0;
            double min = neighborhood.getPixelValues()[0];
            for (int i = 1; i<neighborhood.getValueCount(); ++i) if (neighborhood.getPixelValues()[i]<min) return 0;
            return 1;
        }
    }
    private static class LocalMinThreshold extends Filter {
        double threshold;
        final ImageMask mask;
        public LocalMinThreshold(double threshold, ImageMask mask) {
            this.threshold=threshold;
            this.mask=mask;
        }
        @Override public LocalMinThreshold duplicate() {
            return new LocalMinThreshold(threshold, mask);
        }
        @Override public double applyFilter(int x, int y, int z) {
            if (mask!=null && !mask.insideMask(x, y, z)) return 0;
            if (image.getPixel(x, y, z)>threshold) return 0;
            neighborhood.setPixels(x, y, z, image, null);
            if (neighborhood.getValueCount()==0) return 0;
            double min = neighborhood.getPixelValues()[0];
            for (int i = 1; i<neighborhood.getValueCount(); ++i) if (neighborhood.getPixelValues()[i]<min) return 0;
            return 1;
        }
    }
    private static class Min extends Filter {
        @Override public double applyFilter(int x, int y, int z) {
            return neighborhood.getMin(x, y, z, image);
        }
        @Override public Min duplicate() {
            return new Min();
        }
    }
    private static class BinaryMin extends Filter {
        final boolean outOfBoundIsNull;
        public BinaryMin(boolean outOfBoundIsNull) {
            this.outOfBoundIsNull=outOfBoundIsNull;
        }
        public BinaryMin() {
            this.outOfBoundIsNull=true;
        }
        @Override public BinaryMin duplicate() {
            return new BinaryMin(outOfBoundIsNull);
        }
        @Override public double applyFilter(int x, int y, int z) {
            if (image.getPixel(x, y, z)==0) return 0;
            return neighborhood.hasNullValue(x, y, z, image, outOfBoundIsNull) ? 0 :1;
        }
    }
    public static class BinaryMax extends Filter {
        final boolean outOfBoundIsNonNull;
        public BinaryMax() {
            this.outOfBoundIsNonNull=false;
        }
        public BinaryMax(boolean outOfBoundIsNonNull) {
            this.outOfBoundIsNonNull=outOfBoundIsNonNull;
        }
        @Override public BinaryMax duplicate() {
            return new BinaryMax(outOfBoundIsNonNull);
        }
        @Override public double applyFilter(int x, int y, int z) {
            if (image.getPixel(x, y, z)!=0) return 1;
            return neighborhood.hasNonNullValue(x, y, z, image, outOfBoundIsNonNull) ? 1 : 0;
        }
    }
    public static class BinaryMaxLabelWise extends Filter {
        ImageMask mask;
        public BinaryMaxLabelWise() {}
        public BinaryMaxLabelWise setMask(ImageMask mask) {
            this.mask=mask;
            return this;
        }
        @Override public BinaryMaxLabelWise duplicate() {
            return new BinaryMaxLabelWise().setMask(mask);
        }
        @Override
        public void setUp(Image image, Neighborhood neighborhood) {
            super.setUp(image, neighborhood);
            if (mask!=null && !image.sameDimensions(mask)) throw new IllegalArgumentException("Mask and Image to filter should have same dimentions: mask: "+new SimpleBoundingBox(mask)+" image: "+image.getBoundingBox());
            else if (mask==null) mask=new BlankMask(image);
        }
        @Override public double applyFilter(int x, int y, int z) {
            double centralValue = image.getPixel(x, y, z);
            if (image.getPixel(x, y, z)!=0) return centralValue;
            if (!mask.insideMask(x, y, z)) return 0;
            neighborhood.setPixels(x, y, z, image, null);
            int idx = 0; // central value == 0, pixels are sorted acording to distance to center -> first non null label = closest
            int count = neighborhood.getValueCount();
            double[] values = neighborhood.getPixelValues();
            while (++idx<count && values[idx]==0) {}
            if (idx==count) return 0;
            if (idx+1==count) return neighborhood.getPixelValues()[idx];
            int idx2=idx;
            while (++idx2<count && (values[idx2]==0 || values[idx2]==values[idx])) {}
            if (idx2==count) return neighborhood.getPixelValues()[idx];
            if (neighborhood.getDistancesToCenter()[idx]<neighborhood.getDistancesToCenter()[idx2]) return values[idx];
            return 0;
        }
    }
    
    //(low + high) >>> 1 <=> (low + high) / 2
}
