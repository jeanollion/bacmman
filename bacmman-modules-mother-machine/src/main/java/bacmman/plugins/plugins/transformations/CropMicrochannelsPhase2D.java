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
package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Hint;
import bacmman.plugins.HintSimple;
import bacmman.plugins.TestableOperation;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.plugins.Plugin;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class CropMicrochannelsPhase2D extends CropMicroChannels implements Hint, HintSimple, TestableOperation {
    private final static Logger logger = LoggerFactory.getLogger(CropMicrochannelsPhase2D.class);
    public static boolean debug = false;
    protected static String simpleToolTip = "<b>Automatically crops the image around the microchannels in phase-contrast images</b><br />"
            + "The microchannels should be aligned along the Y-axis, with their closed-end up (use for instance <em>AutorotationXY</em> and <em/>AutoFlip</em>) <br />"
            + "This algorithm is based on the detection of the bright line caused by the side of the main channel, which should correspond to the peak of highest intensity in the Y-intensity profile (displayed in test mode as <em>Peak Detection</em> graph).<br />"
            + "If the bright line is not visible, this procedure should not be used, a simple constant cropping can be applied instead to reduce the size of the image. <br />";
    protected static String peakDetectionToolTip = "<br />Displayed graphs in test mode:"
            + "<ul><li><em>Peak Detection</em>: Graph displaying the mean profile of the image along the Y-axis, used to detect the bright line(s). Y-coordinate of the detected peak(s) are displayed in the title of the graph</li>";
    protected static String toolTip = "The microchannels are detected as follows:"
            + "<ol><li>The open-end of the microchannels are detected using the peak of highest intensity in the Y-intensity profile (which corresponds to the bright line). The y-coordinate of the open-end is then set at the end of this peak, determined using the <em>Bright line peak proportion</em> and <em>Lower end y-margin</em> parameters.<br />Configuration hint: Refer to the Peak Detection plot in test mode.</li>"
            + "<li>If the <em>Two-peaks detection</em> parameter is set to <em>false</em>, the closed-ends of the microchannels are detected as the highest peak of dI/dy (y-derivative of the intensity) after excluding the bright line.<br />Configuration hint: refer to the <em>Closed-end detection</em> plot in test mode. <br />If two-peaks detection is set to <em>true</em>, the closed-ends of the microchannels are detected as a second intensity peak (such as described in step 1)</li>"
            + "<li>If a previous rotation has added null values to the image corners, the image will be cropped according to a bounding box in that excludes those null values.</li></ol>"
             + peakDetectionToolTip +
            "<li><em>Closed-end detection</em>: Graph displaying the mean profile of the 1st-order y-derivative (dI / dY) along Y axis, used to detect the closed-end of microchannels when only one bright line is present (only displayed when parameter <em>Two-peak detection</em> is set to <em>false</em>). <br />Highest peak should correspond to closed-end, if not, set the parameter <em>Distance range between bright line and microchannel ends</em> in order to limit the peak search zone</li></ul>";
    private static String PEAK_HINT = "The end of the bright line is determined using y-mean projection values, as the first y index (starting from the peak caused by the bright line and towards the microchannel closed-end) that reaches the value of this parameter * <em>peak height</em>.<br />A value of 1 means the y-coordinate of the peak is used. An additional margin might be necessary (using the <em>y-margin</em> parameter). A lower value of <em>Bright Line peak proportion</em> will keep more  of the bright line in the final images. If the value is too low, it can lead to unstable results over frames if the peak profile changes between different frames.<br />Refer to the <em>Peak Detection</em> plot displayed in test mode to set this parameter</li></ul>";
    NumberParameter aberrationPeakProp = new BoundedNumberParameter("Bright line peak proportion", 3, 0.25, 0.1, 1).setHint(PEAK_HINT);
    NumberParameter yOpenedEndMargin = new BoundedNumberParameter("Lower end Y-margin", 0, 60).setEmphasized(true).setHint("The y-coordinate of the microchannel open end will be translated of this value towards the top of the image. Allows removing the bright line from the cropped image. A positive value will results in smaller images and a negative value in larger images");
    NumberParameter yEndMarginUp = new BoundedNumberParameter("Upper end Y-margin", 0, 0).setEmphasized(true).setHint("The y-coordinate of the closed-end will be translated of this value towards the bottom of the image. A positive (respectively negative) value will results in smaller (respectively larger) images");
    NumberParameter aberrationPeakPropUp = new BoundedNumberParameter("Bright line peak", 3, 0.25, 0.1, 1).setHint(PEAK_HINT);
    IntervalParameter maxDistanceRangeFromAberration = new IntervalParameter("Distance limit", 0, 0, null, 0, 0).setEmphasized(true).setHint("Limits the search for microchannel's closed-end to a given distance range from detected open end of microchannel (see description of the module in advanced mode for details). <br />Distance is in pixels, if both values are set to 0, no limit is set.<br />This parameter is useful when there are visible structure close to the microchannels closed-end that could perturb the detection of closed-ends.");
    BooleanParameter landmarkUpperPeak = new BooleanParameter("Landmark is upper peak", false).setHint("Bounds of the crop area are computed for all frames and then the size of the image (in the y-direction) is homogenized. If this parameter is set to <em>true</em>, the upper peak will be the landmark for cropping. <br />Choose the peak that has the most stable location in relation to microchannels through time");

    BooleanParameter twoPeaks = new BooleanParameter("Two-peaks detection", false).setEmphasized(true).setHint("<ul><li>Set this parameter to <em>false</em> if the images contain a bright (side of the main channel) line that corresponds to the highest peak in a vertical intensity profile (refer to the <em>Peak Detection</em> graph displayed in test mode).</li><li>Set this parameter to <em>true</em> if there are two peaks of comparable intensity, one being located at the microchannel closed-end and the other located at the microchannel open-end (side of the main channel).</li></ul>See the description of the module for algorithmic details.");
    ConditionalParameter<Boolean> twoPeaksCond = new ConditionalParameter<>(twoPeaks)
            .setActionParameters(false, cropMarginY, maxDistanceRangeFromAberration)
            .setActionParameters(true, aberrationPeakPropUp, yEndMarginUp, landmarkUpperPeak);
    Parameter[] parameters = new Parameter[]{aberrationPeakProp, twoPeaksCond, yOpenedEndMargin, boundGroup};
    @Override public String getHintText() {
        return simpleToolTip + toolTip;
    }
    @Override public String getSimpleHintText() {
        return simpleToolTip + peakDetectionToolTip+ "</ul>";
    }
    public CropMicrochannelsPhase2D(int cropMarginY) {
        this();
        this.cropMarginY.setValue(cropMarginY);
    }
    public CropMicrochannelsPhase2D() {
        this.referencePoint.setSelectedIndex(1);
        this.frameNumber.setValue(0);
    }
    
    @Override public MutableBoundingBox getBoundingBox(Image image) {
        return getBoundingBox(image, twoPeaks.getSelected() ? 0 : cropMarginY.getValue().intValue(),xStart.getValue().intValue(), xStop.getValue().intValue(), yStart.getValue().intValue(), yStop.getValue().intValue());
    }
    
    protected MutableBoundingBox getBoundingBox(Image image, int cropMargin,  int xStart, int xStop, int yStart, int yStop ) {
        if (debug) testMode = TEST_MODE.TEST_EXPERT;
        int yMarginEndChannel = yOpenedEndMargin.getValue().intValue();
        int yMin=0, yMax, yMinMax;
        if (twoPeaks.getSelected()) {
            cropMargin = 0;
            int[] yMinAndMax = searchYLimWithTwoBrightLines(image, aberrationPeakProp.getValue().doubleValue(), yMarginEndChannel, aberrationPeakPropUp.getValue().doubleValue(), yEndMarginUp.getValue().intValue(), testMode.testSimple());
            yMin = yMinAndMax[0];
            yMax = yMinAndMax[1];
            yMinMax = yMax;
            if (yMin<0 || yMax<0) throw new RuntimeException("Did not found two bright lines");
        } else {
            int[] distanceRange=  maxDistanceRangeFromAberration.getValuesAsInt();
            yMax =  searchYLimWithBrightLine(image, aberrationPeakProp.getValue().doubleValue(), yMarginEndChannel, testMode.testSimple()) ;
            if (yMax<0) throw new RuntimeException("No bright line found");
            if (distanceRange[1]>0) {
                yMin = Math.max(yMin, yMax - distanceRange[1]);
            }
            yMinMax = Math.max(yMin, yMax - distanceRange[0]);
        }

        // in case image was rotated and 0 were added, search for xMin & xMax so that no 0's are in the image
        BoundingBox nonNullBound = getNonNullBound(image, yMin, yMax);
        if (testMode.testSimple()) logger.debug("non null bounds: {}", nonNullBound);
        if (!twoPeaks.getSelected()) {
            Image imCrop = image.crop(nonNullBound);
            Image imDerY = ImageFeatures.getDerivative(imCrop, 2, 0, 1, 0, true);
            float[] yProj = ImageOperations.meanProjection(imDerY, ImageOperations.Axis.Y, null);
            if (testMode.testExpert()) Utils.plotProfile("Closed-end detection", yProj, nonNullBound.yMin(), "y", "dI/dy");
            // when optical aberration is very extended, actual length of micro-channels can be way smaller than the parameter -> no check
            //if (yProj.length-1<channelHeight/10) throw new RuntimeException("No microchannels found in image. Out-of-Focus image ?");
            yMin = ArrayUtil.max(yProj, 0, yMinMax-nonNullBound.yMin()) + nonNullBound.yMin();
            //if (yMax<=0) yMax = yMin + channelHeight;
        }
        if (yStop==0) yStop = image.sizeY()-1;
        if (xStop==0) xStop = image.sizeX()-1;
        //yMax = Math.min(yMin+channelHeight, yMax);
        yMin = Math.max(yStart,yMin);
        yStop = Math.min(yStop, yMax);
        yStart = Math.max(yMin-cropMargin, Math.max(yStart, nonNullBound.yMin()));
        
        xStart = Math.max(nonNullBound.xMin(), xStart);
        xStop = Math.min(xStop, nonNullBound.xMax());
        MutableBoundingBox res = new MutableBoundingBox(xStart, xStop, yStart, yStop, 0, image.sizeZ()-1);
        return res;
    }
    
    /**
     * Search of bright line (shadow produced by the microfluidic device at the edge of main chanel microchannels
     * The closed-end should be towards top of image
     * All the following steps are performed on the mean projection of {@param image} along Y axis
     * 1) search for global max yMax
     * 2) search for min value after yMax (yMin>yMax) -> define bright line peak height: h = I(yMax) - I(yMin)
     * 3) search for first occurrence of the value h * {@param peakProportion} before yMax -> endOfPeakYIdx<yMax
     * @param image
     * @param peakProportion
     * @param margin removed to the endOfPeakYIdx value in order to remove long range over-illumination
     * @param testMode
     * @return the y coordinate over the bright line
     */
    public static int searchYLimWithBrightLine(Image image, double peakProportion, int margin, boolean testMode) {
        float[] yProj = ImageOperations.meanProjection(image, ImageOperations.Axis.Y, null, (double v) -> v > 0); // when image was rotated by a high angle zeros are introduced
        ArrayUtil.gaussianSmooth(yProj, 10);
        int start = getFirstNonNanIdx(yProj, true);
        int end = getFirstNonNanIdx(yProj, false);
        int peakIdx = ArrayUtil.max(yProj, start, end + 1);
        double median = ArrayUtil.median(Arrays.copyOfRange(yProj, start, end + 1 - start));
        double peakHeight = yProj[peakIdx] - median;
        float thld = (float) (peakHeight * peakProportion + median);
        int endOfPeakYIdx = ArrayUtil.getFirstOccurence(yProj, peakIdx, start, v->v<thld);
        int startOfMicroChannel = endOfPeakYIdx - margin;
        if (testMode) {
            //Core.showImage(image.setName("Peak detection Input Image"));
            Utils.plotProfile("Peak Detection: detected at y = "+peakIdx+" peak end: y = "+endOfPeakYIdx+" end of microchannels: y = "+startOfMicroChannel, yProj, "Y", "Mean Intensity projection along X");
            //Utils.plotProfile("Sliding sigma", slidingSigma);
            logger.debug("Bright line detection: start mc / end peak/ peak: idx: [{};{};{}], values: [{};{};{}]", startOfMicroChannel, endOfPeakYIdx, peakIdx, median, thld, yProj[peakIdx]);
        }
        return startOfMicroChannel;
    }
    /**
     * Search of bright line (shadow produced by the microfluidic device at the edge of main chanel microchannels
     * Case of open-microchannels : two fringes on each side
     * All the following steps are performed on the mean projection of {@param image} along Y axis
     * 1) search for 2 global max yMax1 & yMax2
     * 2) search for min value in range [yMax1; yMax2] -> define bright line peak height: h = I(yMax) - I(yMin)
     * 3) search for first occurrence of the value h * {@param peakProportion} after each peak in the area between the peaks to define the end of peaks
     * @param image
     * @param peakProportionL
     * @param marginL removed to the endOfPeakYIdx value in order to remove long range over-illumination
     * @param testMode
     * @return the y coordinate over the bright line [yMin, yMax]
     */
    public static int[] searchYLimWithTwoBrightLines(Image image, double peakProportionL, int marginL, double peakProportionUp, int marginUp, boolean testMode) {
        float[] yProj = ImageOperations.meanProjection(image, ImageOperations.Axis.Y, null, (double v) -> v > 0); // when image was rotated by a high angle zeros are introduced
        ArrayUtil.gaussianSmooth(yProj, 10);
        int start = getFirstNonNanIdx(yProj, true);
        int end = getFirstNonNanIdx(yProj, false);
        List<Integer> peaks= ArrayUtil.getRegionalExtrema(yProj, 5, true);
        peaks.removeIf(i->i<start || i>end);
        // first peak detection
        int peakIdx = peaks.stream().max(Comparator.comparingDouble(i -> yProj[i])).get();
        double median = ArrayUtil.median(Arrays.copyOfRange(yProj, start, end + 1 - start));
        double peakHeight = yProj[peakIdx] - median;
        float thld = (float) (peakHeight * Math.min(peakProportionL, peakProportionUp) + median);

        int endOfPeakYIdxB = ArrayUtil.getFirstOccurence(yProj, peakIdx, start, v->v<thld); //  end of peak before
        int endOfPeakYIdxA = ArrayUtil.getFirstOccurence(yProj, peakIdx, end, v->v<thld); // end of peak after

        // search for second peak either after or before the first peak

        int peakIdxB = endOfPeakYIdxB>=0 ? peaks.stream().filter(i->i<endOfPeakYIdxB).max(Comparator.comparingDouble(i -> yProj[i])).orElse(-1) : -1;
        int peakIdxA = endOfPeakYIdxA>=0 ? peaks.stream().filter(i->i>endOfPeakYIdxA).max(Comparator.comparingDouble(i -> yProj[i])).orElse(-1) : -1;
        boolean peak2IsLower;
        if (peakIdxB>=0 && peakIdxA>=0) peak2IsLower = yProj[peakIdxB]<yProj[peakIdxA]; // 2nd peak is the max of the two max
        else peak2IsLower = peakIdxA>=0; // only one peak found

        int endOfPeakYIdx;
        if (peakProportionL!=peakProportionUp) { // recompute end of peak 1
            double thldNew = (float) (peakHeight * (peak2IsLower?peakProportionUp:peakProportionL) + median);
            endOfPeakYIdx = ArrayUtil.getFirstOccurence(yProj, peakIdx, peak2IsLower?end:start, v->v<=thldNew);
        } else endOfPeakYIdx = peak2IsLower?endOfPeakYIdxA:endOfPeakYIdxB;

        int peakIdx2 = peak2IsLower ? peakIdxA : peakIdxB;
        double peakHeight2 = yProj[peakIdx2] - median;
        double thld2 =  (peakHeight2 * (peak2IsLower?peakProportionL:peakProportionUp) + median);
        int endOfPeak2YIdx = ArrayUtil.getFirstOccurence(yProj, peakIdx2, endOfPeakYIdx, v->v<=thld2);



        int[] startOfMicroChannel = peak2IsLower ? new int[] {endOfPeakYIdx + marginUp, endOfPeak2YIdx - marginL} : new int[] {endOfPeak2YIdx + marginUp, endOfPeakYIdx - marginL};
        if (startOfMicroChannel[0]>=startOfMicroChannel[1]) throw new RuntimeException("Null length for microchannels while try to crop: margins are to large?");
        if (startOfMicroChannel[0]<0) startOfMicroChannel[0] = 0;
        if (startOfMicroChannel[1]>image.yMax()) startOfMicroChannel[1] = image.yMax();

        if (testMode) {
            //Core.showImage(image.setName("Peak detection Input Image"));
            Utils.plotProfile("Peak Detection: detected at y = "+peakIdx+" peak end: y = "+endOfPeakYIdx+" peak2: y = "+peakIdx2+ " end of peak 2: y = "+endOfPeak2YIdx+ " microchannel: ["+startOfMicroChannel[0]+ ";" + startOfMicroChannel[1]+"]", yProj, "Y", "Mean Intensity projection along X");
            //Utils.plotProfile("Sliding sigma", slidingSigma);
            Plugin.logger.debug("Bright line detection: peak1 {} / end of peak1 {} , peak2: {} end of peak2: {}, microchannels: {}", peakIdx, endOfPeakYIdx, peakIdx2, endOfPeak2YIdx, startOfMicroChannel);
        }
        return startOfMicroChannel;
    }

    private static int getFirstNonNanIdx(float[] array, boolean fromStart) {
        if (fromStart) {
            int start = 0;
            while (start<array.length && Float.isNaN(array[start])) ++start;
            return start;
        } else {
            int end = array.length-1;
            while (end>0 && Float.isNaN(array[end])) --end;
            return end;
        }
    }
    
    private static BoundingBox getNonNullBound(Image image, int yMin, int yMax) {
        int[] xMinMaxDown = getXMinAndMax(image, yMax);
        if (yMin==0) {
            int[] yMinMaxLeft = getYMinAndMax(image, xMinMaxDown[0]);
            int[] yMinMaxRigth = getYMinAndMax(image, xMinMaxDown[1]);
            yMin = Math.min(yMinMaxLeft[0], yMinMaxRigth[0]);
        }
        int[] xMinMaxUp = getXMinAndMax(image, yMin);
        int xMin = Math.max(xMinMaxDown[0], xMinMaxUp[0]);
        int xMax = Math.min(xMinMaxDown[1], xMinMaxUp[1]);
        return new SimpleBoundingBox(xMin, xMax, yMin, yMax, image.zMin(), image.zMax());
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    protected void uniformizeBoundingBoxes(Map<Integer, MutableBoundingBox> allBounds, InputImages inputImages, int channelIdx) {
        boolean stabFromDown = false;

        int maxSizeY = allBounds.values().stream().mapToInt(b->b.sizeY()).max().getAsInt();
        int sY = allBounds.entrySet().stream().mapToInt(b-> {
            int yMinNull = getYmin(inputImages.getImage(channelIdx, b.getKey()), b.getValue().xMin(), b.getValue().xMax()); // limit sizeY so that no null pixels (due to rotation) is present in the image & not out-of-bounds
            logger.debug("yMinnull: {}", yMinNull);
            return b.getValue().yMax() - Math.max(b.getValue().yMax()-(maxSizeY-1), yMinNull)+1;
        }).min().getAsInt();
        logger.info("max size Y: {} uniformized sizeY: {}", maxSizeY, sY);
        logger.info("all bounds: {}", allBounds.entrySet().stream().filter(e->e.getKey()%100==0).sorted(Comparator.comparingInt(Map.Entry::getKey)).map(e->new Pair(e.getKey(), e.getValue())).collect(Collectors.toList()));
        if (!landmarkUpperPeak.getSelected()) allBounds.values().stream().filter(bb->bb.sizeY()!=sY).forEach(bb-> bb.setyMin(bb.yMax()-(sY-1)));
        else allBounds.values().stream().filter(bb->bb.sizeY()!=sY).forEach(bb-> bb.setyMax(bb.yMin()+(sY-1)));
        //logger.info("all bounds after uniformize Y: {}", allBounds.entrySet().stream().filter(e->e.getKey()%100==0).sorted((e1, e2)->Integer.compare(e1.getKey(), e2.getKey())).map(e->new Pair(e.getKey(), e.getValue())).collect(Collectors.toList()));
        uniformizeX(allBounds);
        
    }
    @Override
    public boolean highMemory() {return false;}
}
