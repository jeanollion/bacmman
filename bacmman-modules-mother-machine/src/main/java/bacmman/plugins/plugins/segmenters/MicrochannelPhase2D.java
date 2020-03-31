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
package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.*;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import ij.process.AutoThresholder;
import bacmman.image.BlankMask;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.segmenters.MicrochannelPhase2D.X_DER_METHOD.CONSTANT;

/**
 *
 * @author Jean Ollion
 */
public class MicrochannelPhase2D implements MicrochannelSegmenter, TestableProcessingPlugin, Hint, HintSimple, TrackConfigurable<MicrochannelPhase2D> {

    
    public enum X_DER_METHOD {CONSTANT, RELATIVE_TO_INTENSITY_RANGE}
    public enum END_DETECTION_METHOD {OPEN_END, CLOSED_END_WITH_ADJUST}
    IntervalParameter channelWidth = new IntervalParameter("Microchannel Width", 0, 0, null, 15, 20, 28).setEmphasized(true).setHint("Width of microchannel in pixels. The first value is the minimal value, the last value the maximal one and the value in-between should be the typical width.");
    EnumChoiceParameter<X_DER_METHOD> xDerPeakThldMethod = new EnumChoiceParameter("X-Derivative Threshold Method", X_DER_METHOD.values(), X_DER_METHOD.RELATIVE_TO_INTENSITY_RANGE);
    NumberParameter localDerExtremaThld = new BoundedNumberParameter("X-Derivative Threshold", 3, 10, 0, null).setHint("Threshold for Microchannel side detection (peaks of 1st-order x-derivative). <br />This optimal value of this parameter depends on the intensity of the image. This parameter should be adjusted if microchannels are poorly detected : a higher value if too many channels are detected and a lower value in the contrary. <br />Configuration Hint: Refer to <em>Side detection graph</em> (displayed through right-click menu in test mode) to display peak heights.<br />Set a higher value if too many channels are detected and a lower value in the contrary.");
    NumberParameter relativeDerThld = new BoundedNumberParameter("X-Derivative Ratio", 3, 40, 1, null).setHint("Threshold for Microchannel side detection (peaks of 1st-order x-derivative). <br />To compute x-derivative threshold, the signal range is computed as range = median signal value - mean background value. The x-derivative threshold is the range / this parameter.<br />Configuration Hint: Refer to <em>Side detection graph</em> (displayed through right-click menu in test mode) to display peak heights. The threshold is written in the graph title <br />Decrease this value if too many microchannels are detected.");
    ConditionalParameter xDerPeakThldCond = new ConditionalParameter(xDerPeakThldMethod).setActionParameters(CONSTANT.toString(), localDerExtremaThld).setActionParameters(X_DER_METHOD.RELATIVE_TO_INTENSITY_RANGE.toString(), relativeDerThld).setHint("Peak selection method for microchannel side detection: <ol><li>"+ CONSTANT.toString()+": Constant threshold</li><li>"+X_DER_METHOD.RELATIVE_TO_INTENSITY_RANGE.toString()+": The threshold is relative to the signal Range. This method is more robust to changes in the signal range and can be used with the same parameter on a larger number of datasets</li></ol>");

    EnumChoiceParameter<END_DETECTION_METHOD> endDetectionMethod = new EnumChoiceParameter<>("End Detection Method", END_DETECTION_METHOD.values(), END_DETECTION_METHOD.CLOSED_END_WITH_ADJUST).setHint("Method for precise detection of microchannel closed-ends");
    NumberParameter closedEndYAdjustWindow = new BoundedNumberParameter("Closed-end Y Adjust Window", 0, 5, 0, null).setHint("Defines the window (in pixels) within which the y-coordinate of the closed-end of the microchannel will be refined. Searches for the first local maximum of the Y-derivative within the window: [y - <em>Closed-end Y adjust window</em>; y + <em>Closed-end Y adjust window</em>] ");
    ConditionalParameter endDetectionMethodCond = new ConditionalParameter(endDetectionMethod).setActionParameters(END_DETECTION_METHOD.CLOSED_END_WITH_ADJUST.toString(), closedEndYAdjustWindow);

    Parameter[] parameters = new Parameter[]{channelWidth, xDerPeakThldCond, endDetectionMethodCond};
    public final static double PEAK_RELATIVE_THLD = 0.6;
    public static boolean debug = false;
    public static int debugIdx = -1;
    protected static String simpleToolTip = "<b>Microchannel Segmentation in phase-contrast images:</b>"
            + "This algorithm requires that microchannels are aligned along the Y-axis, with their closed-end up. At this stage no bright line (or other strong intensity perturbation) should be visible. " +
            "<br /><b>The microchannel width should be indicated in the corresponding parameter. If the width parameter is incorrect, some microchannels may not be segmented</b><br />";
    protected static String toolTip =
            "<br />Main steps of the algorithm:"
            + "<ol><li>Searches for global closed-end y-coordinate of Microchannels: computes the first-order y-derivative and finds the maximum of its projection onto the Y-axis</li>"
            + "<li>Searches the x-positions of the microchannel sides, using the projection of the first-order x-derivative onto the X-axis: <br />"
            + "Positive and negative peaks that verify the criterion defined in the <em>X-Derivative Threshold Method</em> parameter are detected.<br />"
            + "Among those peaks, those that are separated by a distance that is as close as possible to a typical width and within a range defined in the <em>Microchannel Width</em> parameter are selected and considered as the sides of the microchannels</li>"
            + "<li>The y-coordinate of each microchannel is then adjusted to the first local maximum of the 1srt-order y-derivative in a window defined by the parameter <em>Closed end Y adjust Window</em> (see <em>End detection Method</em>)</li></ol>"
            + "<br /> <em>Test mode</em>: after running the Test command, select one segmented microchannel (or one viewfield if no microchannels were segmented; set the <em>interactive objects</em> from the <em>Data Browsing</em> tab), and right click on the image and select <em>Show test data</em> to display intermediate images"
            + "<br />List of displayed graphs:"
            + "<ul><li><em>Closed-end detection image</em>: image used to detect closed-end of microchannels (first-order y-derivative)</li>"
            + "<li><em>Closed-end detection graph</em>: mean intensity profile of the <em>Closed-end detection image</em> used  to detect closed-end of microchannels. The peak of maximal intensity should correspond to the closed end. If not, adjust the pre-processing (cropping step) so that no other structure disturbing the detection is visible </li>"
            + "<li><em>Side detection graph</em>: Mean intensity profile of the <em>Side detection image</em> used for detection of microchannel sides<br />Refer to <em>X-Derivative Threshold Method</em> and <em>Microchannel Width</em> parameters</li>"
            + "<li><em>Side detection image</em>: Image used for detection of microchannel sides (corresponding to a 1st-order x-derivative).</li>"
            + "</ul>" ;


    // tooltip interface
    @Override
    public String getHintText() {
        return simpleToolTip+toolTip;
    }
    @Override
    public String getSimpleHintText() {
        return simpleToolTip;
    }

    public MicrochannelPhase2D() {}

    // testable
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }
    
    public MicrochannelPhase2D setyStartAdjustWindow(int yStartAdjustWindow) {
        this.closedEndYAdjustWindow.setValue(yStartAdjustWindow);
        return this;
    }
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        Result r = segment(input, objectClassIdx, parent);
        if (r==null) return null;
        ArrayList<Region> objects = new ArrayList<>(r.size());
        for (int idx = 0; idx<r.xMax.length; ++idx) {
            objects.add(new Region(new BlankMask(r.getBounds(idx, true), input.getScaleXY(), input.getScaleZ()), idx+1, true));
            //logger.debug("mc: {}: bds: {}", idx, objects.get(objects.size()-1).getBounds());
        }
        return new RegionPopulation(objects, input);
    }

    @Override public Parameter[] getParameters() {
        return parameters;
    }
    
    /**
      1) if {@param opticalAberration}: search for optical aberration and crop image to remove it see  searchYLimWithOpticalAberration}
      2) Search for global closed-end y-coordinate of Microchannels: global max of the Y-proj of d/dy -> yEnd
      3) search of x-positions of microchannels using X-projection (y in [ yEnd; yAberration]) of d/dx image & peak detection (detection of positive peak & negative peak over {@param localExtremaThld} separated by a distance closest of {@param channelWidth} and in the range [{@param widthMin} ; {@param widthMax}]
      4) Adjust yStart for each channel: first local max of d/dy image in the range [yEnd-{@param yStartAdjustWindow}; yEnd+{@param yStartAdjustWindow}]
     * @param image
     * @return Result object containing bounding boxes of segmented microchannels
     */
    @Override
    public Result segment(Image image, int structureIdx, SegmentedObject parent) {
        int closedEndYAdjustWindow = this.closedEndYAdjustWindow.getValue().intValue();
        int[] width = this.channelWidth.getValuesAsInt();
        int channelWidth = width[1];
        int channelWidthMin = width[0];
        int channelWidthMax = width[2];
        END_DETECTION_METHOD endDetMethod = endDetectionMethod.getSelectedEnum();
        boolean openEnd = END_DETECTION_METHOD.OPEN_END.equals(endDetMethod);
        double localDerExtremaThld;
        switch(xDerPeakThldMethod.getSelectedEnum()) {
            case CONSTANT:
                localDerExtremaThld = this.localDerExtremaThld.getValue().doubleValue();
                break;
            case RELATIVE_TO_INTENSITY_RANGE:
            default:
                localDerExtremaThld = this.globalLocalDerThld;
                if (Double.isNaN(globalLocalDerThld)) throw new RuntimeException("Global X-Der threshold not set");
        }
        
        double derScale = 2;
        // get aberration
        int[] yStartStop = new int[]{0, image.sizeY()-1};
        Image imCrop = (image instanceof ImageFloat ? image.duplicate() : image);
        
        // get global closed-end Y coordinate
        Image imDerY = ImageFeatures.getDerivative(imCrop, derScale, 0, 1, 0, true);
        float[] yProj = openEnd?null: ImageOperations.meanProjection(imDerY, ImageOperations.Axis.Y, null);
        int closedEndY = openEnd?yStartStop[0] : ArrayUtil.max(yProj, 0, yProj.length) + yStartStop[0];

        // get X coordinates of each microchannel
        imCrop = closedEndY==0?image:image.crop(new MutableBoundingBox(0, image.sizeX()-1, closedEndY, image.sizeY()-1, 0, image.sizeZ()-1));
        float[] xProj = ImageOperations.meanProjection(imCrop, ImageOperations.Axis.X, null);
        // check for null values @ start & end that could be introduces by rotation and replace by first non-null value
        int start = 0;
        while (start<xProj.length && xProj[start]==0) ++start;
        if (start>0) Arrays.fill(xProj, 0, start, xProj[start]);
        int end = xProj.length-1;
        while (end>0 && xProj[end]==0) --end;
        if (end<xProj.length-1) Arrays.fill(xProj, end+1, xProj.length, xProj[end]);
        //derivate
        ArrayUtil.gaussianSmooth(xProj, 1); // derScale
        Image imDerX = ImageFeatures.getDerivative(imCrop, derScale, 1, 0, 0, closedEndY>0);
        float[] xProjDer = ImageOperations.meanProjection(imDerX, ImageOperations.Axis.X, null);
        
        if (stores!=null) {
            stores.get(parent).addMisc("Show test data", l->{
                stores.get(parent).imageDisp.accept(imDerY.setName("Closed-end detection image (dI/dy)"));
                stores.get(parent).imageDisp.accept(imDerX.setName("Side detection image (dI/dx)"));
                if (yProj!=null) Utils.plotProfile("Closed-end detection (mean projection)", yProj, "y", "dI/dy");
                Utils.plotProfile("Side detection (mean projection of dI/dx) Threshold: "+localDerExtremaThld+(this.xDerPeakThldMethod.getSelectedIndex()==1? " Signal Range: "+(relativeDerThld.getValue().doubleValue()*localDerExtremaThld) : ""), xProjDer, "x", "dI/dx");
                //plotProfile("Side detection (mean projection of I)", xProj);
            });
        }
        
        final float[] derMap = xProjDer;
        List<Integer> localMax = ArrayUtil.getRegionalExtrema(xProjDer, (int)(derScale+0.5), true);
        List<Integer> localMin = ArrayUtil.getRegionalExtrema(xProjDer, (int)(derScale+0.5), false);
        final Predicate<Integer> rem = i -> Math.abs(derMap[i])<localDerExtremaThld ;
        localMax.removeIf(rem);
        localMin.removeIf(rem);
        
        Comparator<int[]> segmentScoreComparator = (int[] o1, int[] o2) -> { // >0 -> o2 better that o1
            double score1 = Math.abs(derMap[localMax.get(o1[0])]) + Math.abs(derMap[localMin.get(o1[1])]);
            double score2 = Math.abs(derMap[localMax.get(o2[0])]) + Math.abs(derMap[localMin.get(o2[1])]);
            int comp = -Double.compare(score1, score2);
            if (comp ==0) {
                int d1 = localMin.get(o1[1]) - localMax.get(o1[0]);
                int d2 = localMin.get(o2[1]) - localMax.get(o2[0]);
                comp =  Integer.compare(Math.abs(d1-channelWidth), Math.abs(d2-channelWidth));
            }
            return comp;
        };
        
        if (stores!=null) {
            logger.debug("{} max found, {} min found", localMax.size(), localMin.size());
            logger.debug("max: {}", localMax);
            logger.debug("min: {}", localMin);
        }
        
        List<int[]> peaks = new ArrayList<>();
        int lastMinIdx = 0;
        int maxIdx = 0;
        while (maxIdx<localMax.size()) {
            if (stores!=null) logger.debug("VALID MAX: {}", localMax.get(maxIdx));
            int minIdx = getNextMinIdx(derMap, localMin, localMax, maxIdx, lastMinIdx, channelWidthMin,channelWidthMax, segmentScoreComparator, stores!=null);
            if (minIdx>=0 ) {
                // check all valid max between current max and min
                int nextMaxIdx = maxIdx+1;
                while (nextMaxIdx<localMax.size() && localMax.get(nextMaxIdx)<localMin.get(minIdx)) {
                    if (Math.abs(derMap[localMax.get(maxIdx)])*PEAK_RELATIVE_THLD<Math.abs(derMap[localMax.get(nextMaxIdx)])) {
                        int nextMinIdx = getNextMinIdx(derMap, localMin, localMax, nextMaxIdx, lastMinIdx, channelWidthMin,channelWidthMax, segmentScoreComparator, stores!=null);
                        if (nextMinIdx>=0) {
                            int comp = segmentScoreComparator.compare(new int[]{maxIdx, minIdx}, new int[]{nextMaxIdx, nextMinIdx});
                            if (comp>0) {
                                maxIdx = nextMaxIdx;
                                minIdx = nextMinIdx;
                                if (stores!=null) logger.debug("BETTER VALID MAX: {}, d: {}", localMax.get(maxIdx), localMin.get(minIdx) - localMax.get(maxIdx));
                            }
                        }
                    }
                    ++nextMaxIdx;
                }
                if (stores!=null) {
                    int x1 = localMax.get(maxIdx);
                    int x2 = localMin.get(minIdx);
                    logger.debug("Peak found X: [{};{}], distance: {}, value: [{};{}], normedValue: [{};{}]", x1, x2, localMin.get(minIdx) - localMax.get(maxIdx), xProjDer[x1], xProjDer[x2], xProjDer[x1]/xProj[x1], xProjDer[x2]/xProj[x2]);
                }
                peaks.add(new int[]{localMax.get(maxIdx), localMin.get(minIdx), 0});
                lastMinIdx = minIdx;
                maxIdx = nextMaxIdx; // first max after min
            } else ++maxIdx;
        }
        if (END_DETECTION_METHOD.CLOSED_END_WITH_ADJUST.equals(endDetMethod) && closedEndYAdjustWindow > 0) {
            // refine Y-coordinate of closed-end for each microchannel. As MC shape is generally ellipsoidal @ close-end, only get the profile in the 1/3-X center part
            for (int idx = 0; idx < peaks.size(); ++idx) {
                int[] peak = peaks.get(idx);
                double sizeX = peak[1] - peak[0] + 1;
                MutableBoundingBox win = new MutableBoundingBox((int) (peak[0] + sizeX / 3 + 0.5), (int) (peak[1] - sizeX / 3 + 0.5), Math.max(0, closedEndY - closedEndYAdjustWindow), Math.min(imDerY.sizeY() - 1, closedEndY + closedEndYAdjustWindow), 0, 0);
                float[] proj = ImageOperations.meanProjection(imDerY, ImageOperations.Axis.Y, win);
                List<Integer> localMaxY = ArrayUtil.getRegionalExtrema(proj, 2, true);
                //peak[2] = ArrayUtil.max(proj)-yStartAdjustWindow;
                if (localMaxY.isEmpty()) continue;
                peak[2] = localMaxY.get(0) - (closedEndY >= closedEndYAdjustWindow ? closedEndYAdjustWindow : 0);
                if (stores != null) {
                    int ii = idx;
                    stores.get(parent).addMisc("Display closed-end adjument", l -> {
                        Set<Integer> idxes = l.stream().map(o -> o.getIdx()).collect(Collectors.toSet());
                        if (idxes.contains(ii))
                            Utils.plotProfile("Closed-end y-adjustment: first local max @ y=:" + (localMaxY.get(0) + win.yMin()), proj, win.yMin(), "y", "dI/dy");
                    });

                }
            }
        }
        Result r= new Result(peaks, closedEndY, image.sizeY()-1);
         
        int xLeftErrode = (int)(derScale/2.0+0.5); // adjust Y: remove derScale from left 
        for (int i = 0; i<r.size(); ++i) r.xMax[i]-=xLeftErrode;
        if (stores!=null) for (int i = 0; i<r.size(); ++i) logger.debug("mc: {} -> {}", i, r.getBounds(i, true));
        return r;
    }
    double globalLocalDerThld = Double.NaN;
    // track parametrizable interface
    @Override
    public TrackConfigurer<MicrochannelPhase2D> run(int structureIdx, List<SegmentedObject> parentTrack) {
        switch(xDerPeakThldMethod.getSelectedEnum()) {
            case CONSTANT:
                return null;
            case RELATIVE_TO_INTENSITY_RANGE:
            default:
                // compute signal range on all images
                //logger.debug("parent track: {}",parentTrack.stream().map(p->p.getPreFilteredImage(structureIdx)).collect(Collectors.toList()) );
                Map<Image, ImageMask> maskMap = parentTrack.stream().collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask()));
                Histogram histo = HistogramFactory.getHistogram(()->Image.stream(maskMap, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
                double thld = IJAutoThresholder.runThresholder(AutoThresholder.Method.Otsu, histo);
                int thldIdx = (int)histo.getIdxFromValue(thld);
                double foreground = histo.duplicate(thldIdx, histo.getData().length).getQuantiles(0.5)[0];
                double background = histo.getValueFromIdx(histo.getMeanIdx(0, thldIdx-1));
                double range =foreground - background;
                double xDerThld = range / this.relativeDerThld.getValue().doubleValue(); // divide by ratio and set to segmenter
                return (p, s) -> s.globalLocalDerThld = xDerThld;
        }
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        if (CONSTANT.equals(xDerPeakThldMethod.getSelectedEnum())) return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
        else return ProcessingPipeline.PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY;
    }

    private static int getNextMinIdx(final float[] derMap, final List<Integer> localMin, final List<Integer> localMax, final int maxIdx, int lastMinIdx, final double widthMin, final double widthMax, Comparator<int[]> segmentScoreComparator, boolean testMode) {
        int minIdx = lastMinIdx;
        while(minIdx<localMin.size()) {
            int d = localMin.get(minIdx) - localMax.get(maxIdx);
            //if (debug) logger.debug("Test MIN: {}, d: {}", localMin.get(minIdx), d);
            if (d>=widthMin && d<=widthMax) {
                if (testMode) logger.debug("VALID MIN: {}, d: {}", localMin.get(minIdx), d);
                // see if next mins yield to better segmentation
                boolean better=true;
                while(better) {
                    better=false;
                    if (minIdx+1<localMin.size() && Math.abs(derMap[localMin.get(minIdx+1)])>Math.abs(derMap[localMin.get(minIdx)])*PEAK_RELATIVE_THLD) {
                        int d2 = localMin.get(minIdx+1) - localMax.get(maxIdx);
                        if (testMode) logger.debug("Test BETTER VALID MIN: {}, d: {}", localMin.get(minIdx), d2);
                        if (d2>=widthMin && d2<=widthMax) {
                            if (segmentScoreComparator.compare(new int[]{maxIdx, minIdx}, new int[]{maxIdx, minIdx+1})>0) {
                                ++minIdx;
                                better = true;
                                if (testMode) logger.debug("BETTER VALID MIN: {}, d: {}", localMin.get(minIdx), d2);
                            } 
                        }
                    }
                }
                return minIdx;
            } else if (d>widthMax) return -1;
            
            minIdx++;
        }
        return -1;
    }

    
}
