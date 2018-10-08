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
package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.image.TypeConverter;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.plugins.Filter;
import bacmman.plugins.PreFilter;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import ij.ImageStack;

import static bacmman.plugins.plugins.pre_filters.IJSubtractBackground.FILTER_DIRECTION.DIAGONAL_2B;
import static bacmman.utils.Utils.parallele;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class IJSubtractBackground implements PreFilter, Filter {
    BooleanParameter method = new BooleanParameter("Method", "Rolling Ball", "Sliding Paraboloid", true);
    BooleanParameter imageType = new BooleanParameter("Image Background", "Dark", "Light", true);
    BooleanParameter smooth = new BooleanParameter("Perform Smoothing", true);
    BooleanParameter corners = new BooleanParameter("Correct corners", true);
    NumberParameter radius = new BoundedNumberParameter("Radius", 2, 20, 0.01, null);
    Parameter[] parameters = new Parameter[]{radius, method, imageType, smooth, corners};
    
    public IJSubtractBackground(double radius, boolean doSlidingParaboloid, boolean lightBackground, boolean smooth, boolean corners) {
        this.radius.setValue(radius);
        method.setSelected(!doSlidingParaboloid);
        this.imageType.setSelected(!lightBackground);
        this.smooth.setSelected(smooth);
        this.corners.setSelected(corners);
    }
    
    public IJSubtractBackground(){}
    
    @Override
    public Image runPreFilter(Image input, ImageMask mask) {
        return filter(input, radius.getValue().doubleValue(), !method.getSelected(), !imageType.getSelected(), smooth.getSelected(), corners.getSelected());
    }
    /**
     * IJ's subtract background {@link ij.plugin.filter.BackgroundSubtracter#rollingBallBackground(ij.process.ImageProcessor, double, boolean, boolean, boolean, boolean, boolean) }
     * @param input input image (will not be modified)
     * @param radius
     * @param doSlidingParaboloid
     * @param lightBackground
     * @param smooth
     * @param corners
     * @return subtracted image 
     */
    public static ImageFloat filter(Image input, double radius, boolean doSlidingParaboloid, boolean lightBackground, boolean smooth, boolean corners) {
        return filter(input, radius, doSlidingParaboloid, lightBackground, smooth, corners, true);
    }
    public static ImageFloat filter(Image input, double radius, boolean doSlidingParaboloid, boolean lightBackground, boolean smooth, boolean corners, boolean duplicate) {
        if (!(input instanceof ImageFloat)) input = TypeConverter.toFloat(input, null);
        else if (duplicate) input = input.duplicate();
        ImageStack ip = IJImageWrapper.getImagePlus(input).getImageStack();
        for (int z = 0; z<input.sizeZ(); ++z) {
            new ij.plugin.filter.BackgroundSubtracter().rollingBallBackground(ip.getProcessor(z+1), radius, false, lightBackground, doSlidingParaboloid, smooth, corners);
            //process(ip.getProcessor(z+1), (float)radius, lightBackground, smooth);
        }
        return (ImageFloat)input;
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return filter(image, radius.getValue().doubleValue(), !method.getSelected(), !imageType.getSelected(), smooth.getSelected(), corners.getSelected());
    }
    
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return true;
    }

    boolean testMode;
    @Override public void setTestMode(boolean testMode) {this.testMode=testMode;}
    
    public static ImageFloat filterCustomSlidingParaboloid(Image input, double radius, boolean lightBackground, boolean smooth, boolean duplicate, boolean parallele, FILTER_DIRECTION... directions) {
        if (!(input instanceof ImageFloat)) input = TypeConverter.toFloat(input, null);
        else if (duplicate) input = input.duplicate();
        ImageStack ip = IJImageWrapper.getImagePlus(input).getImageStack();
        for (int z = 0; z<input.sizeZ(); ++z) {
            modifiedSlidingParaboloid(ip.getProcessor(z+1), (float)radius, lightBackground, smooth, parallele, directions);
        }
        return (ImageFloat)input;
    }
    
    private final static int MAXIMUM = 0, MEAN = 1;         //filter types of filter3x3
    public static enum FILTER_DIRECTION { X_DIRECTION(false), Y_DIRECTION(false) , DIAGONAL_1A(true) , DIAGONAL_1B(true) , DIAGONAL_2A(true) , DIAGONAL_2B(true);
        public final boolean diag;
        private FILTER_DIRECTION(boolean diag) {
            this.diag=diag;
        }
    }; //filter directions
    
    static void modifiedSlidingParaboloid(ImageProcessor ip, float radius, boolean light, boolean smooth, boolean parallele, FILTER_DIRECTION... directions) {
        FloatProcessor fp = ip.toFloat(0, null);
        fp.snapshot();
        slidingParaboloidFloatBackground(fp, (float)radius, light, smooth, parallele, directions);
        float[] bgPixels = (float[])fp.getPixels();
        float[] snapshotPixels = (float[])fp.getSnapshotPixels(); //original data in the snapshot
        for (int p=0; p<bgPixels.length; p++)
            bgPixels[p] = snapshotPixels[p]-bgPixels[p];
    }
    /** Create background for a float image by sliding a paraboloid over
     * the image. */
    static void slidingParaboloidFloatBackground(FloatProcessor fp, float radius, boolean invert, boolean doPresmooth, boolean parallele, FILTER_DIRECTION... directions) {
        float[] pixels = (float[])fp.getPixels();   //this will become the background
        int width = fp.getWidth();
        int height = fp.getHeight();
        HashMapGetCreate<Thread, float[]> cache = new HashMapGetCreate<>(t -> new float[Math.max(width, height)]); //work array for lineSlideParabola
        HashMapGetCreate<Thread, int[]> nextPoint = new HashMapGetCreate<>(t -> new int[Math.max(width, height)]); //work array for lineSlideParabola
        float coeff2 = 0.5f/radius;                 //2nd-order coefficient of the polynomial approximating the ball
        float coeff2diag = 1.f/radius;              //same for diagonal directions where step is sqrt2
                  //start the progress bar (only filter1D will increment it)
        if (invert)
            for (int i=0; i<pixels.length; i++)
                pixels[i] = -pixels[i];

        float shiftBy = 0;
        if (doPresmooth) {
            shiftBy = (float)filter3x3(fp, MAXIMUM);//3x3 maximum to remove dust etc.
            filter3x3(fp, MEAN);                    //smoothing to remove noise
        }

        /* Slide the parabola over the image in different directions */
        /* Doing the diagonal directions at the end is faster (diagonal lines are denser,
         * so there are more such lines, and the algorithm gets faster with each iteration) */
        for (FILTER_DIRECTION dir: directions) filter1D(fp, dir, dir.diag ? coeff2diag : coeff2, cache, nextPoint, parallele);
        
        
        /*filter1D(fp, X_DIRECTION, coeff2, cache, nextPoint);
        filter1D(fp, Y_DIRECTION, coeff2, cache, nextPoint);
        //filter1D(fp, X_DIRECTION, coeff2, cache, nextPoint);    //redo for better accuracy
        if (!reducedProcess) {
            filter1D(fp, DIAGONAL_1A, coeff2diag, cache, nextPoint);
            filter1D(fp, DIAGONAL_1B, coeff2diag, cache, nextPoint);
            filter1D(fp, DIAGONAL_2A, coeff2diag, cache, nextPoint);
            filter1D(fp, DIAGONAL_2B, coeff2diag, cache, nextPoint);
            filter1D(fp, DIAGONAL_1A, coeff2diag, cache, nextPoint);//redo for better accuracy
            filter1D(fp, DIAGONAL_1B, coeff2diag, cache, nextPoint);
        }*/

        if (invert)
            for (int i=0; i<pixels.length; i++)
                pixels[i] = -(pixels[i] - shiftBy);
        else if (doPresmooth)
            for (int i=0; i<pixels.length; i++)
                pixels[i] -= shiftBy;   //correct for shift by 3x3 maximum

    }
    
    // modified from IJ SOURCE by Sliding Paraboloid by Michael Schmid, 2007.
    /** Filter by subtracting a sliding parabola for all lines in one direction, x, y or one of
     *  the two diagonal directions (diagonals are processed only for half the image per call). */
    static void filter1D(FloatProcessor fp, FILTER_DIRECTION direction, float coeff2, HashMapGetCreate<Thread, float[]> cache, HashMapGetCreate<Thread, int[]> nextPoint, boolean parallel) {
        float[] pixels = (float[])fp.getPixels();   //this will become the background
        int width = fp.getWidth();
        int height = fp.getHeight();
        int startLine = 0;          //index of the first line to handle
        int nLines;             //index+1 of the last line to handle (initialized to avoid compile-time error)
        int lineInc;            //increment from one line to the next in pixels array
        int pointInc;           //increment from one point to the next along the line
        switch (direction) {
            case X_DIRECTION:       //lines parallel to x direction
                nLines = height;
                lineInc = width;
                pointInc = 1;
            break;
            case Y_DIRECTION:       //lines parallel to y direction
            default:
                nLines = width;
                lineInc = 1;
                pointInc = width;
            break;
            case DIAGONAL_1A:       //lines parallel to x=y, starting at x axis
                nLines = width-2;   //the algorithm makes no sense for lines shorter than 3 pixels
                lineInc = 1;
                pointInc = width + 1;
            break;
            case DIAGONAL_1B:       //lines parallel to x=y, starting at y axis
                startLine = 1;
                nLines = height-2;
                lineInc = width;
                pointInc = width + 1;
            break;
            case DIAGONAL_2A:       //lines parallel to x=-y, starting at x axis
                startLine = 2;
                nLines = width;
                lineInc = 1;
                pointInc = width - 1;
            break;
            case DIAGONAL_2B:       //lines parallel to x=-y, starting at x=width-1, y=variable
                startLine = 0;
                nLines = height-2;
                lineInc = width;
                pointInc = width - 1;
            break;
        }
        Utils.parallele(IntStream.range(startLine, nLines), parallel).forEach(i -> {
            int startPixel = i*lineInc;
            if (direction == DIAGONAL_2B) startPixel += width-1;
            int length; // length of the line
            switch (direction) {
                case X_DIRECTION: 
                    length = width;
                    break;
                case Y_DIRECTION: 
                default:
                    length = height;
                    break;
                case DIAGONAL_1A: 
                    length = Math.min(height, width-i); 
                    break;
                case DIAGONAL_1B: 
                    length = Math.min(width, height-i); 
                    break;
                case DIAGONAL_2A: 
                    length = Math.min(height, i+1);     
                    break;
                case DIAGONAL_2B: 
                    length = Math.min(width, height-i); 
                    break;
            }
            lineSlideParabola(pixels, startPixel, pointInc, length, coeff2, cache.getAndCreateIfNecessarySyncOnKey(Thread.currentThread()), nextPoint.getAndCreateIfNecessarySyncOnKey(Thread.currentThread()), null);
        });
    } //void filter1D

    /** Process one straight line in the image by sliding a parabola along the line
     *  (from the bottom) and setting the values to make all points reachable by
     *  the parabola
     * @param pixels    Image data, will be modified by parabolic interpolation
     *                  where the parabola does not touch.
     * @param start     Index of first pixel of the line in pixels array
     * @param inc       Increment of index in pixels array
     * @param length    Number of points the line consists of
     * @param coeff2    2nd order coefficient of the polynomial describing the parabola,
     *                  must be positive (although a parabola with negative curvature is
     *                  actually used)
     * @param cache     Work array, length at least <code>length</code>. Will usually remain
     *                  in the CPU cache and may therefore speed up the code.
     * @param nextPoint Work array. Will hold the index of the next point with sufficient local
     *                  curvature to get touched by the parabola.
     * @param correctedEdges Should be a 2-element array used for output or null.
     * @return          The correctedEdges array (if non-null on input) with the two estimated
     *                  edge pixel values corrected for edge particles.
     */
    static float[] lineSlideParabola(float[] pixels, int start, int inc, int length, float coeff2, float[] cache, int[] nextPoint, float[] correctedEdges) {
        float minValue = Float.MAX_VALUE;
        int lastpoint = 0;
        int firstCorner = length-1;             // the first point except the edge that is touched
        int lastCorner = 0;                     // the last point except the edge that is touched
        float vPrevious1 = 0f;
        float vPrevious2 = 0f;
        float curvatureTest = 1.999f*coeff2;     //not 2: numeric scatter of 2nd derivative
        /* copy data to cache, determine the minimum, and find points with local curvature such
         * that the parabola can touch them - only these need to be examined futher on */
        for (int i=0, p=start; i<length; i++, p+=inc) {
            float v = pixels[p];
            cache[i] = v;
            if (v < minValue) minValue = v;
            if (i >= 2 && vPrevious1+vPrevious1-vPrevious2-v < curvatureTest) {
                nextPoint[lastpoint] = i-1;     // point i-1 may be touched
                lastpoint = i-1;
            }
            vPrevious2 = vPrevious1;
            vPrevious1 = v;
        }
        nextPoint[lastpoint] = length-1;
        nextPoint[length-1] = Integer.MAX_VALUE;// breaks the search loop

        int i1 = 0;                             // i1 and i2 will be the two points where the parabola touches
        while (i1<length-1) {
            float v1 = cache[i1];
            float minSlope = Float.MAX_VALUE;
            int i2 = 0;                         //(initialized to avoid compile-time error)
            int searchTo = length;
            int recalculateLimitNow = 0;        // when 0, limits for searching will be recalculated
            /* find the second point where the parabola through point i1,v1 touches: */
            for (int j=nextPoint[i1]; j<searchTo; j=nextPoint[j], recalculateLimitNow++) {
                float v2 = cache[j];
                float slope = (v2-v1)/(j-i1)+coeff2*(j-i1);
                if (slope < minSlope) {
                    minSlope = slope;
                    i2 = j;
                    recalculateLimitNow = -3;
                }
                if (recalculateLimitNow==0) {   //time-consuming recalculation of search limit: wait a bit after slope is updated
                    double b = 0.5f*minSlope/coeff2;
                    int maxSearch = i1+(int)(b+Math.sqrt(b*b+(v1-minValue)/coeff2)+1); //(numeric overflow may make this negative)
                    if (maxSearch < searchTo && maxSearch > 0) searchTo = maxSearch;
                }
            }
            if (i1 == 0) firstCorner = i2;
            if (i2 == length-1) lastCorner = i1;
            /* interpolate between the two points where the parabola touches: */
            for (int j=i1+1, p=start+j*inc; j<i2; j++, p+=inc)
                pixels[p] = v1 + (j-i1)*(minSlope - (j-i1)*coeff2);
            i1 = i2;                            // continue from this new point
        } //while (i1<length-1)
        /* Now calculate estimated edge values without an edge particle, allowing for vignetting
         * described as a 6th-order polynomial: */
        if (correctedEdges != null) {
            if (4*firstCorner >= length) firstCorner = 0; // edge particles must be < 1/4 image size
            if (4*(length - 1 - lastCorner) >= length) lastCorner = length - 1;
            float v1 = cache[firstCorner];
            float v2 = cache[lastCorner];
            float slope = (v2-v1)/(lastCorner-firstCorner); // of the line through the two outermost non-edge touching points
            float value0 = v1 - slope * firstCorner;        // offset of this line
            float coeff6 = 0;                               // coefficient of 6th order polynomial
            float mid = 0.5f * (lastCorner + firstCorner);
            for (int i=(length+2)/3; i<=(2*length)/3; i++) {// compare with mid-image pixels to detect vignetting
                float dx = (i-mid)*2f/(lastCorner-firstCorner);
                float poly6 = dx*dx*dx*dx*dx*dx - 1f;       // the 6th order polynomial, zero at firstCorner and lastCorner
                if (cache[i] < value0 + slope*i + coeff6*poly6) {
                    coeff6 = -(value0 + slope*i - cache[i])/poly6;
                }
            }
            float dx = (firstCorner-mid)*2f/(lastCorner-firstCorner);
            correctedEdges[0] = value0 + coeff6*(dx*dx*dx*dx*dx*dx - 1f) + coeff2*firstCorner*firstCorner;
            dx = (lastCorner-mid)*2f/(lastCorner-firstCorner);
            correctedEdges[1] = value0 + (length-1)*slope + coeff6*(dx*dx*dx*dx*dx*dx - 1f) + coeff2*(length-1-lastCorner)*(length-1-lastCorner);
        }
        return correctedEdges;
    } //void lineSlideParabola
    
    /** Replace the pixels by the mean or maximum in a 3x3 neighborhood.
     *  No snapshot is required (less memory needed than e.g., fp.smooth()).
     *  When used as maximum filter, it returns the average change of the
     *  pixel value by this operation
     */
    static double filter3x3(FloatProcessor fp, int type) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        double shiftBy = 0;
        float[] pixels = (float[])fp.getPixels();
        for (int y=0; y<height; y++)
            shiftBy += filter3(pixels, width, y*width, 1, type);
        for (int x=0; x<width; x++)
            shiftBy += filter3(pixels, height, x, width, type);
        return shiftBy/width/height;
    }
    /** Filter a line: maximum or average of 3-pixel neighborhood */
    static double filter3(float[] pixels, int length, int pixel0, int inc, int type) {
        double shiftBy = 0;
        float v3 = pixels[pixel0];  //will be pixel[i+1]
        float v2 = v3;              //will be pixel[i]
        float v1;                   //will be pixel[i-1]
        for (int i=0, p=pixel0; i<length; i++,p+=inc) {
            v1 = v2;
            v2 = v3;
            if (i<length-1) v3 = pixels[p+inc];
            if (type == 0) { // maximum
                float max = v1 > v3 ? v1 : v3;
                if (v2 > max) max = v2;
                shiftBy += max - v2;
                pixels[p] = max;
            } else
                pixels[p] = (v1+v2+v3)*0.33333333f;
        }
        return shiftBy;
    }
}
