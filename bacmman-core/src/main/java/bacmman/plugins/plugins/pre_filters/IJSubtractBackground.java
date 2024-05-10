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
import bacmman.plugins.Hint;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PreFilter;
import bacmman.processing.Filters;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import ij.ImageStack;

import static bacmman.utils.Utils.parallel;


import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.util.Tools;

import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class IJSubtractBackground implements PreFilter, Filter, Hint, MultiThreaded {
    BooleanParameter isRollingBall = new BooleanParameter("Method", "Rolling Ball", "Sliding Paraboloid", true).setEmphasized(true);

    BooleanParameter isDarkBackground = new BooleanParameter("Image Background", "Dark", "Light", true).setEmphasized(true);
    BooleanParameter smooth = new BooleanParameter("Perform Smoothing", true);
    BooleanParameter corners = new BooleanParameter("Correct corners", true);
    NumberParameter radius = new BoundedNumberParameter("Radius", 2, 20, 0.01, null).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{radius, isRollingBall, isDarkBackground, smooth, corners};
    
    public IJSubtractBackground(double radius, boolean doSlidingParaboloid, boolean lightBackground, boolean smooth, boolean corners) {
        this.radius.setValue(radius);
        isRollingBall.setSelected(!doSlidingParaboloid);
        this.isDarkBackground.setSelected(!lightBackground);
        this.smooth.setSelected(smooth);
        this.corners.setSelected(corners);
    }
    
    public IJSubtractBackground(){}
    
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        return filter(input, radius.getValue().doubleValue(), !isRollingBall.getSelected(), !isDarkBackground.getSelected(), smooth.getSelected(), corners.getSelected(), !allowInplaceModification, parallel);
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
    public static ImageFloat filter(Image input, double radius, boolean doSlidingParaboloid, boolean lightBackground, boolean smooth, boolean corners, boolean parallel) {
        return filter(input, radius, doSlidingParaboloid, lightBackground, smooth, corners, true, parallel);
    }
    public static ImageFloat filter(Image input, double radius, boolean doSlidingParaboloid, boolean lightBackground, boolean smooth, boolean corners, boolean duplicate, boolean parallel) {
        if (!(input instanceof ImageFloat)) input = TypeConverter.toFloat(input, null);
        else if (duplicate) input = input.duplicate();
        ImageStack ip = IJImageWrapper.getImagePlus(input).getImageStack();
        //Utils.parallel(IntStream.range(0, input.sizeZ()), parallel).forEach(z -> {
        //    new ij.plugin.filter.BackgroundSubtracter().rollingBallBackground(ip.getProcessor(z+1), radius, false, lightBackground, doSlidingParaboloid, smooth, corners);
        //});
        if (doSlidingParaboloid) {
            for (int z = 0; z<input.sizeZ(); ++z) modifiedSlidingParaboloid(ip.getProcessor(z+1), (float)radius, lightBackground, smooth, parallel, get_default_directions());
        } else {
            for (int z = 0; z<input.sizeZ(); ++z) rollingBall(ip.getProcessor(z+1), radius, lightBackground, smooth, parallel);
        }
        return (ImageFloat)input;
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return filter(image, radius.getValue().doubleValue(), !isRollingBall.getSelected(), !isDarkBackground.getSelected(), smooth.getSelected(), corners.getSelected(), parallel);
    }
    
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return true;
    }
    public static FILTER_DIRECTION[] get_default_directions() {
        return new FILTER_DIRECTION[]{FILTER_DIRECTION.X_DIRECTION, FILTER_DIRECTION.Y_DIRECTION, FILTER_DIRECTION.X_DIRECTION, FILTER_DIRECTION.DIAGONAL_1A, FILTER_DIRECTION.DIAGONAL_1B, FILTER_DIRECTION.DIAGONAL_2A, FILTER_DIRECTION.DIAGONAL_2B, FILTER_DIRECTION.DIAGONAL_1A, FILTER_DIRECTION.DIAGONAL_1B};
    }
    public static ImageFloat filterSlidingParaboloid(Image input, double radius, boolean lightBackground, boolean smooth, boolean duplicate, boolean parallel) {
        return filterCustomSlidingParaboloid(input, radius, lightBackground, smooth, duplicate, parallel, get_default_directions());
    }

    public static ImageFloat filterCustomSlidingParaboloid(Image input, double radius, boolean lightBackground, boolean smooth, boolean duplicate, boolean parallel, FILTER_DIRECTION... directions) {
        if (!(input instanceof ImageFloat)) input = TypeConverter.toFloat(input, null);
        else if (duplicate) input = input.duplicate();
        ImageStack ip = IJImageWrapper.getImagePlus(input).getImageStack();
        for (int z = 0; z<input.sizeZ(); ++z) {
            modifiedSlidingParaboloid(ip.getProcessor(z+1), (float)radius, lightBackground, smooth, parallel, directions);
        }
        return (ImageFloat)input;
    }
    
    private final static int MAXIMUM = 0, MEAN = 1;         //filter types of filter3x3

    @Override
    public String getHintText() {
        return "ImageJ's subtract background algorithm. See: <a href='http://imagejdocu.tudor.lu/doku.php?id=gui:process:subtract_background'>http://imagejdocu.tudor.lu/doku.php?id=gui:process:subtract_background</a>";
    }
    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }

    static void rollingBall(ImageProcessor ip, double radius, boolean light, boolean smooth, boolean parallel) {
        FloatProcessor fp = ip.toFloat(0, null);
        fp.snapshot();
        rollingBallFloatBackground(fp, light, smooth, new RollingBall(radius), parallel);
        float[] bgPixels = (float[])fp.getPixels();
        float[] snapshotPixels = (float[])fp.getSnapshotPixels(); //original data in the snapshot
        for (int p=0; p<bgPixels.length; p++) bgPixels[p] = snapshotPixels[p]-bgPixels[p];
    }

    public enum FILTER_DIRECTION { X_DIRECTION(false), Y_DIRECTION(false) , DIAGONAL_1A(true) , DIAGONAL_1B(true) , DIAGONAL_2A(true) , DIAGONAL_2B(true);
        public final boolean diag;
        FILTER_DIRECTION(boolean diag) {
            this.diag=diag;
        }
    } //filter directions
    
    static void modifiedSlidingParaboloid(ImageProcessor ip, float radius, boolean light, boolean smooth, boolean parallel, FILTER_DIRECTION... directions) {
        FloatProcessor fp = ip.toFloat(0, null);
        fp.snapshot();
        slidingParaboloidFloatBackground(fp, (float)radius, light, smooth, parallel, directions);
        float[] bgPixels = (float[])fp.getPixels();
        float[] snapshotPixels = (float[])fp.getSnapshotPixels(); //original data in the snapshot
        for (int p=0; p<bgPixels.length; p++)  bgPixels[p] = snapshotPixels[p]-bgPixels[p];
    }
    /** Create background for a float image by sliding a paraboloid over
     * the image. */
    static void slidingParaboloidFloatBackground(FloatProcessor fp, float radius, boolean invert, boolean doPresmooth, boolean parallel, FILTER_DIRECTION... directions) {
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
        for (FILTER_DIRECTION dir: directions) filter1D(fp, dir, dir.diag ? coeff2diag : coeff2, cache, nextPoint, parallel);
        
        
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
        Utils.parallel(IntStream.range(startLine, nLines), parallel).forEach(i -> {
            int startPixel = i*lineInc;
            if (direction == FILTER_DIRECTION.DIAGONAL_2B) startPixel += width-1;
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


    //  R O L L   B A L L   S E C T I O N

    /** Create background for a float image by rolling a ball over
     * the image. */
    static void rollingBallFloatBackground(FloatProcessor fp, boolean invert, boolean doPresmooth, RollingBall ball, boolean parallel) {
        float[] pixels = (float[])fp.getPixels();   //this will become the background
        boolean shrink = ball.shrinkFactor >1;

        if (invert)
            for (int i=0; i<pixels.length; i++)
                pixels[i] = -pixels[i];
        if (doPresmooth)
            filter3x3(fp, MEAN);
        double[] minmax = Tools.getMinMax(pixels);
        if (Thread.currentThread().isInterrupted()) return;
        FloatProcessor smallImage = shrink ? shrinkImage(fp, ball.shrinkFactor) : fp;
        if (Thread.currentThread().isInterrupted()) return;
        rollBall(ball, smallImage, parallel);
        if (Thread.currentThread().isInterrupted()) return;
        if (shrink)
            enlargeImage(smallImage, fp, ball.shrinkFactor);
        if (Thread.currentThread().isInterrupted()) return;

        if (invert)
            for (int i=0; i<pixels.length; i++)
                pixels[i] = -pixels[i];
    }

    /** Creates a lower resolution image for ball-rolling. */
    static FloatProcessor shrinkImage(FloatProcessor ip, int shrinkFactor) {
        int width = ip.getWidth();
        int height = ip.getHeight();
        float[] pixels = (float[])ip.getPixels();
        int sWidth = (width+shrinkFactor-1)/shrinkFactor;
        int sHeight = (height+shrinkFactor-1)/shrinkFactor;
        FloatProcessor smallImage = new FloatProcessor(sWidth, sHeight);
        float[] sPixels = (float[])smallImage.getPixels();
        float min, thispixel;
        for (int ySmall=0; ySmall<sHeight; ySmall++) {
            for (int xSmall=0; xSmall<sWidth; xSmall++) {
                min = Float.MAX_VALUE;
                for (int j=0, y=shrinkFactor*ySmall; j<shrinkFactor&&y<height; j++, y++) {
                    for (int k=0, x=shrinkFactor*xSmall; k<shrinkFactor&&x<width; k++, x++) {
                        thispixel = pixels[x+y*width];
                        if (thispixel<min)
                            min = thispixel;
                    }
                }
                sPixels[xSmall+ySmall*sWidth] = min; // each point in small image is minimum of its neighborhood
            }
        }
        //new ImagePlus("smallImage", smallImage).show();
        return smallImage;
    }

    /** 'Rolls' a filtering object over a (shrunken) image in order to find the
     image's smooth continuous background.  For the purpose of explaining this
     algorithm, imagine that the 2D grayscale image has a third (height) dimension
     defined by the intensity value at every point in the image.  The center of
     the filtering object, a patch from the top of a sphere having radius BallRadius,
     is moved along each scan line of the image so that the patch is tangent to the
     image at one or more points with every other point on the patch below the
     corresponding (x,y) point of the image.  Any point either on or below the patch
     during this process is considered part of the background.  Shrinking the image
     before running this procedure is advised for large ball radii because the
     processing time increases with ball radius^2.
     */
    static void rollBall(RollingBall ball, FloatProcessor fp, boolean parallel) {
        float[] pixels = (float[])fp.getPixels();   //the input pixels
        int width = fp.getWidth();
        int height = fp.getHeight();
        float[] zBall = ball.data;
        int ballWidth = ball.width;
        int radius = ballWidth/2;
        HashMapGetCreate<Thread, float[]> cacheMap = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(t -> new float[width*ballWidth]);
        Utils.parallel(IntStream.range(-radius, height+radius), false).forEach(y -> {  //for all positions of the ball center:
            float[] cache = cacheMap.get(Thread.currentThread());
            int nextLineToWriteInCache = (y+radius)%ballWidth;
            int nextLineToRead = y + radius;        //line of the input not touched yet
            if (nextLineToRead<height) {
                System.arraycopy(pixels, nextLineToRead*width, cache, nextLineToWriteInCache*width, width);
                for (int x=0, p=nextLineToRead*width; x<width; x++,p++)
                    pixels[p] = -Float.MAX_VALUE;   //unprocessed pixels start at minus infinity
            }
            int y0 = Math.max(0, y-radius);                      //the first line to see whether the ball touches
            int yBall0 = y0-y+radius;               //y coordinate in the ball corresponding to y0
            int yend = Math.min(height-1, y+radius);                    //the last line to see whether the ball touches
            Utils.parallel(IntStream.range(-radius, width+radius), parallel).forEach(x -> {
                float z = Float.MAX_VALUE;          //the height of the ball (ball is in position x,y)
                int x0 = x-radius;
                if (x0 < 0) x0 = 0;
                int xBall0 = x0-x+radius;
                int xend = x+radius;
                if (xend>=width) xend = width-1;
                for (int yp=y0, yBall=yBall0; yp<=yend; yp++,yBall++) { //for all points inside the ball
                    int cachePointer = (yp%ballWidth)*width+x0;
                    for (int xp=x0, bp=xBall0+yBall*ballWidth; xp<=xend; xp++, cachePointer++, bp++) {
                        float zReduced = cache[cachePointer] - zBall[bp];
                        if (z > zReduced)           //does this point imply a greater height?
                            z = zReduced;
                    }
                }
                for (int yp=y0, yBall=yBall0; yp<=yend; yp++,yBall++) //raise pixels to ball surface
                    for (int xp=x0, p=xp+yp*width, bp=xBall0+yBall*ballWidth; xp<=xend; xp++, p++, bp++) {
                        float zMin = z + zBall[bp];
                        if (pixels[p] < zMin)
                            pixels[p] = zMin;
                    }
                // if (x>=0&&y>=0&&x<width&&y<height) bgPixels[x+y*width] = z; //debug, ball height output
            });
        });
    }

    /** Uses bilinear interpolation to find the points in the full-scale background
     given the points from the shrunken image background. (At the edges, it is
     actually extrapolation.)
     */
    static void enlargeImage(FloatProcessor smallImage, FloatProcessor fp, int shrinkFactor) {
        int width = fp.getWidth();
        int height = fp.getHeight();
        int smallWidth = smallImage.getWidth();
        int smallHeight = smallImage.getHeight();
        float[] pixels = (float[])fp.getPixels();
        float[] sPixels = (float[])smallImage.getPixels();
        int[] xSmallIndices = new int[width];         //index of first point in smallImage
        float[] xWeights = new float[width];        //weight of this point
        makeInterpolationArrays(xSmallIndices, xWeights, width, smallWidth, shrinkFactor);
        int[] ySmallIndices = new int[height];
        float[] yWeights = new float[height];
        makeInterpolationArrays(ySmallIndices, yWeights, height, smallHeight, shrinkFactor);
        float[] line0 = new float[width];
        float[] line1 = new float[width];
        for (int x=0; x<width; x++)                 //x-interpolation of the first smallImage line
            line1[x] = sPixels[xSmallIndices[x]] * xWeights[x] +
                    sPixels[xSmallIndices[x]+1] * (1f - xWeights[x]);
        int ySmallLine0 = -1;                       //line0 corresponds to this y of smallImage
        for (int y=0; y<height; y++) {
            if (ySmallLine0 < ySmallIndices[y]) {
                float[] swap = line0;               //previous line1 -> line0
                line0 = line1;
                line1 = swap;                       //keep the other array for filling with new data
                ySmallLine0++;
                int sYPointer = (ySmallIndices[y]+1)*smallWidth; //points to line0 + 1 in smallImage
                for (int x=0; x<width; x++)         //x-interpolation of the new smallImage line -> line1
                    line1[x] = sPixels[sYPointer+xSmallIndices[x]] * xWeights[x] +
                            sPixels[sYPointer+xSmallIndices[x]+1] * (1f - xWeights[x]);
            }
            float weight = yWeights[y];
            for (int x=0, p=y*width; x<width; x++,p++)
                pixels[p] = line0[x]*weight + line1[x]*(1f - weight);
        }
    }

    /** Create arrays of indices and weigths for interpolation.
     <pre>
     Example for shrinkFactor = 4:
     small image pixel number         |       0       |       1       |       2       | ...
     full image pixel number          | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |10 |11 | ...
     smallIndex for interpolation(0)  | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 1 | 1 | 2 | 2 | ...
     (0) Note: This is smallIndex for the left pixel; for the right pixel used for interpolation
     it is higher by one
     </pre>
     */
    static void makeInterpolationArrays(int[] smallIndices, float[] weights, int length, int smallLength, int shrinkFactor) {
        for (int i=0; i<length; i++) {
            int smallIndex = (i - shrinkFactor/2)/shrinkFactor;
            if (smallIndex >= smallLength-1) smallIndex = smallLength - 2;
            smallIndices[i] = smallIndex;
            float distance = (i + 0.5f)/shrinkFactor - (smallIndex + 0.5f); //distance of pixel centers (in smallImage pixels)
            weights[i] = 1f - distance;
        }
    }

//  C L A S S   R O L L I N G B A L L

    /** A rolling ball (or actually a square part thereof)
     *  Here it is also determined whether to shrink the image
     */
    static class RollingBall {

        float[] data;
        int width;
        int shrinkFactor;

        RollingBall(double radius) {
            int arcTrimPer;
            if (radius<=10) {
                shrinkFactor = 1;
                arcTrimPer = 24; // trim 24% in x and y
            } else if (radius<=30) {
                shrinkFactor = 2;
                arcTrimPer = 24; // trim 24% in x and y
            } else if (radius<=100) {
                shrinkFactor = 4;
                arcTrimPer = 32; // trim 32% in x and y
            } else {
                shrinkFactor = 8;
                arcTrimPer = 40; // trim 40% in x and y
            }
            buildRollingBall(radius, arcTrimPer);
        }

        /** Computes the location of each point on the rolling ball patch relative to the
         center of the sphere containing it.  The patch is located in the top half
         of this sphere.  The vertical axis of the sphere passes through the center of
         the patch.  The projection of the patch in the xy-plane below is a square.
         */
        void buildRollingBall(double ballradius, int arcTrimPer) {
            double rsquare;     // rolling ball radius squared
            int xtrim;          // # of pixels trimmed off each end of ball to make patch
            int xval, yval;     // x,y-values on patch relative to center of rolling ball
            double smallballradius; // radius of rolling ball (downscaled in x,y and z when image is shrunk)
            int halfWidth;      // distance in x or y from center of patch to any edge (patch "radius")

            this.shrinkFactor = shrinkFactor;
            smallballradius = ballradius/shrinkFactor;
            if (smallballradius<1)
                smallballradius = 1;
            rsquare = smallballradius*smallballradius;
            xtrim = (int)(arcTrimPer*smallballradius)/100; // only use a patch of the rolling ball
            halfWidth = (int)Math.round(smallballradius - xtrim);
            width = 2*halfWidth+1;
            data = new float[width*width];

            for (int y=0, p=0; y<width; y++)
                for (int x=0; x<width; x++, p++) {
                    xval = x - halfWidth;
                    yval = y - halfWidth;
                    double temp = rsquare - xval*xval - yval*yval;
                    data[p] = temp>0. ? (float)(Math.sqrt(temp)) : 0f;
                    //-Float.MAX_VALUE might be better than 0f, but gives different results than earlier versions
                }
        }

    }


}


