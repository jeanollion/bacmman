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
import bacmman.core.Core;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Hint;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.plugins.Plugin;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class CropMicrochannelsPhase2D extends CropMicroChannels implements Hint {
    private final static Logger logger = LoggerFactory.getLogger(CropMicrochannelsPhase2D.class);
    public static boolean debug = false;
    protected String toolTip = "<b>Microchannel Detection in phase-contrast images</b><br />"
            + "Based on detection of optical aberration (shadow of opened-end of microchannels in phase contrast imaging) as the max of Y mean profile. End of peak is determined using the <em>peak proportion</em> parameter and height using the <em>channel length</em> parameter. <br />Supposes optical aberration is the highest peak. Refer to plot <em>Peak Detection</em><br />"
            + "Closed-end of microchannels is detected as the highest peak of dI/dy (refer to graph <em>Closed-end detection</em>) after excluding the optical aberration.<br />"
            + "If a previous rotation has added null values to the image corners, the final bounding box is ensured to exclude them";
    NumberParameter aberrationPeakProp = new BoundedNumberParameter("Bright line peak proportion", 3, 0.25, 0.1, 1).setEmphasized(true).setHint("The end of the bright line at the border of main channel and micro-channels is determined as the first y index (in y-mean projection values) towards closed-end of microchanel that reach: this value * peak height<br />Depending on phase-contrast setup, the optical aberration can different tail profile. <br /> A lower value will keep more tail, a higher value will remove tail. A too low value can lead to unstable results over frames.<br />Refer to plot <em>Peak Detection</em>");
    NumberParameter yEndMargin = new BoundedNumberParameter("Lower end Y-margin", 0, 60).setEmphasized(true).setHint("The y-coordinate of the microchannel end will be translated of this value. A positive value will yield in smaller images and a negative value in larger images");
    NumberParameter yEndMarginUp = new BoundedNumberParameter("Upper end Y-margin", 0, 0).setEmphasized(true).setHint("The y-coordinate of the upper end will be translated of this value. A positive value will yield in smaller images and a negative value in larger images");
    NumberParameter aberrationPeakPropUp = new BoundedNumberParameter("Optical aberration peak proportion for upper peak", 3, 0.25, 0.1, 1).setEmphasized(true).setHint("The end of optical aberration is determined as the first y index (in y-mean projection values) towards closed-end of microchanel that reach: this value * peak height<br />Depending on phase-contrast setup, the optical aberration can different tail profile. <br /> A lower value will keep more tail, a higher value will remove tail. A too low value can lead to unstable results over frames.<br />Refer to plot <em>Peak Detection</em>");
    NumberParameter maxDistanceFromAberration = new BoundedNumberParameter("Max distance from bright line", 0, 0, 0, null).setHint("Limit the search for microchannel closed end to a given distance from the bright line peak. Distance is in pixels If 0 , no limit is set.");
    BooleanParameter hallmarkUpperPeak = new BooleanParameter("Hallmark is upper peak", false).setHint("When bounds are computed for all frames, Y size is homogenized. If true, the upper peak will be the hallmark for cropping. Choose the peak that has the most stable location in relation to microchannels through time");

    BooleanParameter twoPeaks = new BooleanParameter("Two-peak detection", false).setHint("If true, the algorithm will detect two peaks, the first one being the upper (lower y coordinate). Images are cropped between the two peaks");
    ConditionalParameter twoPeaksCond = new ConditionalParameter(twoPeaks)
            .setActionParameters("false", cropMarginY, maxDistanceFromAberration)
            .setActionParameters("true", aberrationPeakPropUp, yEndMarginUp, hallmarkUpperPeak);
    Parameter[] parameters = new Parameter[]{aberrationPeakProp, twoPeaksCond, yEndMargin, boundGroup};
    @Override public String getHintText() {return toolTip;}
    public CropMicrochannelsPhase2D(int cropMarginY) {
        this();
        this.cropMarginY.setValue(cropMarginY);
    }
    public CropMicrochannelsPhase2D() {
        this.referencePoint.setSelectedIndex(1);
        this.frameNumber.setValue(0);
    }
    
    @Override public MutableBoundingBox getBoundingBox(Image image) {
        return getBoundingBox(image, cropMarginY.getValue().intValue(),xStart.getValue().intValue(), xStop.getValue().intValue(), yStart.getValue().intValue(), yStop.getValue().intValue());
    }
    
    protected MutableBoundingBox getBoundingBox(Image image, int cropMargin,  int xStart, int xStop, int yStart, int yStop ) {
        if (debug) testMode = true;
        int yMarginEndChannel = yEndMargin.getValue().intValue();
        int yMin=0, yMax;
        if (twoPeaks.getSelected()) {
            cropMargin = 0;
            int[] yMinMax = searchYLimWithTwoOpticalAberration(image, aberrationPeakProp.getValue().doubleValue(), yMarginEndChannel, aberrationPeakPropUp.getValue().doubleValue(), yEndMarginUp.getValue().intValue(), testMode);
            yMin = yMinMax[0];
            yMax = yMinMax[1];
            if (yMin<0 || yMax<0) throw new RuntimeException("Did not found two optical aberrations");
        } else {
            yMax =  searchYLimWithOpticalAberration(image, aberrationPeakProp.getValue().doubleValue(), yMarginEndChannel, testMode) ;
            if (yMax<0) throw new RuntimeException("No optical aberration found");
            if (this.maxDistanceFromAberration.getValue().intValue()>0) {
                yMin = Math.max(yMin, yMax - maxDistanceFromAberration.getValue().intValue());
            }
        }

        // in case image was rotated and 0 were added, search for xMin & xMax so that no 0's are in the image
        BoundingBox nonNullBound = getNonNullBound(image, yMin, yMax);
        if (testMode) logger.debug("non null bounds: {}", nonNullBound);
        if (!twoPeaks.getSelected()) {
            Image imCrop = image.crop(nonNullBound);
            Image imDerY = ImageFeatures.getDerivative(imCrop, 2, 0, 1, 0, true);
            float[] yProj = ImageOperations.meanProjection(imDerY, ImageOperations.Axis.Y, null);
            if (testMode) Utils.plotProfile("Closed-end detection", yProj, nonNullBound.yMin(), "y", "dI/dy");
            // when optical aberration is very extended, actual length of micro-channels can be way smaller than the parameter -> no check
            //if (yProj.length-1<channelHeight/10) throw new RuntimeException("No microchannels found in image. Out-of-Focus image ?");
            yMin = ArrayUtil.max(yProj, 0, yProj.length - 1) + nonNullBound.yMin();
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
        return new MutableBoundingBox(xStart, xStop, yStart, yStop, 0, image.sizeZ()-1);
        
    }
    
    /**
     * Search of Optical Aberration (shadow produced by the microfluidic device at the edge of main chanel microchannels
     * The closed-end should be towards top of image
     * All the following steps are performed on the mean projection of {@param image} along Y axis
     * 1) search for global max yMax
     * 2) search for min value after yMax (yMin>yMax) -> define aberration peak height: h = I(yMax) - I(yMin)
     * 3) search for first occurrence of the value h * {@param peakProportion} before yMax -> endOfPeakYIdx<yMax
     * @param image
     * @param peakProportion
     * @param margin removed to the endOfPeakYIdx value in order to remove long range over-illumination
     * @param testMode
     * @return the y coordinate over the optical aberration
     */
    public static int searchYLimWithOpticalAberration(Image image, double peakProportion, int margin, boolean testMode) {
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
            Core.showImage(image.setName("Peak detection Input Image"));
            Utils.plotProfile("Peak Detection: detected at y = "+peakIdx+" peak end:"+endOfPeakYIdx+" end of microchannels:"+startOfMicroChannel, yProj, "Y", "Mean Intensity projection along X");
            //Utils.plotProfile("Sliding sigma", slidingSigma);
            Plugin.logger.debug("Optical Aberration detection: start mc / end peak/ peak: idx: [{};{};{}], values: [{};{};{}]", startOfMicroChannel, endOfPeakYIdx, peakIdx, median, thld, yProj[peakIdx]);
        }
        return startOfMicroChannel;
    }
    /**
     * Search of Optical Aberration (shadow produced by the microfluidic device at the edge of main chanel microchannels
     * Case of open-microchannels : two fringes on each side
     * All the following steps are performed on the mean projection of {@param image} along Y axis
     * 1) search for 2 global max yMax1 & yMax2
     * 2) search for min value in range [yMax1; yMax2] -> define aberration peak height: h = I(yMax) - I(yMin)
     * 3) search for first occurrence of the value h * {@param peakProportion} after each peak in the area between the peaks to define the end of peaks
     * @param image
     * @param peakProportionL
     * @param marginL removed to the endOfPeakYIdx value in order to remove long range over-illumination
     * @param testMode
     * @return the y coordinate over the optical aberration [yMin, yMax]
     */
    public static int[] searchYLimWithTwoOpticalAberration(Image image, double peakProportionL, int marginL, double peakProportionUp, int marginUp, boolean testMode) {
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
            Core.showImage(image.setName("Peak detection Input Image"));
            Utils.plotProfile("Peak Detection: detected at y = "+peakIdx+" peak end:"+endOfPeakYIdx+" peak2: "+peakIdx2+ " end of peak 2: "+endOfPeak2YIdx+ " microchannel: ["+startOfMicroChannel[0]+ ";" + startOfMicroChannel[1]+"]", yProj, "Y", "Mean Intensity projection along X");
            //Utils.plotProfile("Sliding sigma", slidingSigma);
            Plugin.logger.debug("Optical Aberration detection: peak1 {} / end of peak1 {} , peak2: {} end of peak2: {}, microchannels: {}", peakIdx, endOfPeakYIdx, peakIdx2, endOfPeak2YIdx, startOfMicroChannel);
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
        if (xMinMaxDown[0]==0 && xMinMaxDown[1]==image.sizeX()-1) return  image.getBoundingBox().setyMax(yMax).setyMin(yMin); // no null values
        int[] yMinMaxLeft = getYMinAndMax(image, xMinMaxDown[0]);
        int[] yMinMaxRigth = getYMinAndMax(image, xMinMaxDown[1]);
        yMin = Math.max(yMin, Math.min(yMinMaxLeft[0], yMinMaxRigth[0]));
        int[] xMinMaxUp = getXMinAndMax(image, yMin);
        return new SimpleBoundingBox(Math.max(xMinMaxDown[0], xMinMaxUp[0]), Math.min(xMinMaxDown[1], xMinMaxUp[1]), yMin, yMax, image.zMin(), image.zMax());
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
            return b.getValue().yMax() - Math.max(b.getValue().yMax()-(maxSizeY-1), yMinNull)+1;
        }).min().getAsInt();
        //logger.info("max size Y: {} uniformized sizeY: {}", maxSizeY, sY);
        //logger.info("all bounds: {}", allBounds.entrySet().stream().filter(e->e.getKey()%100==0).sorted((e1, e2)->Integer.compare(e1.getKey(), e2.getKey())).map(e->new Pair(e.getKey(), e.getValue())).collect(Collectors.toList()));
        if (!hallmarkUpperPeak.getSelected()) allBounds.values().stream().filter(bb->bb.sizeY()!=sY).forEach(bb-> bb.setyMin(bb.yMax()-(sY-1)));
        else allBounds.values().stream().filter(bb->bb.sizeY()!=sY).forEach(bb-> bb.setyMax(bb.yMin()+(sY-1)));
        //logger.info("all bounds after uniformize Y: {}", allBounds.entrySet().stream().filter(e->e.getKey()%100==0).sorted((e1, e2)->Integer.compare(e1.getKey(), e2.getKey())).map(e->new Pair(e.getKey(), e.getValue())).collect(Collectors.toList()));
        uniformizeX(allBounds);
        
    }

}
