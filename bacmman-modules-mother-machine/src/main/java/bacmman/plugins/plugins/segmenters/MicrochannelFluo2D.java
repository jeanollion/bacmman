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
import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.SimpleThresholder;
import bacmman.processing.Filters;
import bacmman.processing.ImageOperations;
import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.utils.ArrayUtil;
import bacmman.utils.IJUtils;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInteger;
import bacmman.processing.ImageLabeller;
import bacmman.utils.SynchronizedPool;

import java.util.*;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class MicrochannelFluo2D implements MicrochannelSegmenter, TrackConfigurable<MicrochannelFluo2D>, Hint, HintSimple, TestableProcessingPlugin {
    
    NumberParameter channelLength = new BoundedNumberParameter("Microchannel Length", 0, 320, 5, null).setEmphasized(true).setHint("Length of microchannels, in pixels. This parameter will determine the length of segmented microchannels along the y-axis");
    NumberParameter channelWidth = new BoundedNumberParameter("Microchannel Width", 0, 60, 5, null).setEmphasized(true).setHint("Width of microchannels in pixels. This parameter will determine the width of segmented microchannels");
    NumberParameter yShift = new BoundedNumberParameter("y-Start Shift", 0, 20, 0, null).setHint("Y-coordinate of the closed-end of microchannels will be translated of this value (in pixels) towards upper direction");
    public final static  String THLD_TOOL_TIP = "Threshold to segment bacteria. <br />Configuration hint: result of segmentation is the image <em>Thresholded Bacteria</em> displayed in test mode. Method should be chosen so that most bacteria and not background are detected. <br />If background pixels are segmented and background intensity is non-uniform, a background correction operation (such as <em>NonLocalMeansDenoising</em>) should be added at a previous step";
    PluginParameter<SimpleThresholder> threshold= new PluginParameter<>("Threshold", SimpleThresholder.class, new BackgroundFit(10), false).setHint(THLD_TOOL_TIP); //new BackgroundThresholder(3, 6, 3) when background is removed and images saved in 16b, half of background is trimmed -> higher values
    public final static  String FILL_TOOL_TIP = "If the ratio  <em>length of bacteria / length of microchannel</em> is smaller than this value, the object won't be segmented. This procedure allows avoiding segmenting isolated bacteria in the main channel.<br /> Configuration Hint: Refer to plot <em>Microchannel Fill proportion</em> displayed in test mode: peaks over the filling proportion value are segmented. Decrease the value to include lower peaks";
    NumberParameter fillingProportion = new BoundedNumberParameter("Microchannel filling proportion", 2, 0.3, 0.05, 1).setEmphasized(true).setHint(FILL_TOOL_TIP);
    public final static String SIZE_TOOL_TIP = "After cell segmentation step (see help of the module, step 1), objects whose size in pixels is smaller than <em>Object Size Filter</em> are removed.<br />Configuration Hint: Refer to the <em>Thresholded Bacteria</em> image displayed in test mode in order to estimate the size of bacteria";
    NumberParameter minObjectSize = new BoundedNumberParameter("Min. Object Size", 0, 200, 1, null).setHint(SIZE_TOOL_TIP);
    FloatParameter peakProportion = new FloatParameter("Peak Proportion", 1).setLowerBound(0.25).setUpperBound(1).setHint("Microchannel Center is defined by as the center of the truncated peak at this proportion of the peak.<br/>Right click on Thresholded Bacteria test images > Display microchannel fill proportion graph");
    Parameter[] parameters = new Parameter[]{channelLength, channelWidth, yShift, threshold, fillingProportion, minObjectSize, peakProportion};
    public static boolean debug = false;
    public static final String TOOL_TIP = "<ol><li>A rough segmentation of the cells is performed, using the <em>Threshold</em> parameter computed on all frames</li>"
    + "<li>Empty microchannels are discarded: the microchannel is discarded if the length of the segmented objects at step 1 (in Y-direction) is smaller than the product of two user-defined parameters : <em>Microchannel Length</em> x <em>Microchannel Filling proportion</em></li>"
    + "<li>The y-coordinate of all microchannels closed-end (Y start) is computed, as the minimum value of y-coordinates of all the microchannels selected at step 2.</li></ol>";

    // hint interface
    private static String simpleHint = "<b>Detection of microchannel using bacteria fluorescence</b><br />" +
            "The <em>Filling proportion of Microchannel</em> & <em>Channel Length</em> parameters should be tuned for each new setup. Their values should be increased if some microchannels are missed.<br />";
    private static String dispImageSimple = "<br />Displayed images in test mode:" +
            "<ul><li><em>Thresholded Bacteria</em>: Rough segmentation of bacteria by simple thresholding. No other structures than bacteria should be segmented. See the <em>Threshold</em> parameter available in advanced mode for details</li>"
            + "<li><em>Microchannel Fill proportion</em>: Plot representing the proportion of filled length of detected microchannels, available upon right-click on an image with a selected object (microchannel or viewfield). See module description and help for parameter <em>Filling proportion of Microchannel</em></li></ul>";
    @Override
    public String getHintText() {
        return simpleHint + TOOL_TIP+dispImageSimple;
    }

    @Override
    public String getSimpleHintText() {
        return simpleHint + dispImageSimple;
    }

    public enum METHOD {LEGACY, PEAK}

    public MicrochannelFluo2D() {}
    public MicrochannelFluo2D(int channelHeight, int channelWidth, int yMargin, double fillingProportion, int minObjectSize) {
        this.channelLength.setValue(channelHeight);
        this.channelWidth.setValue(channelWidth);
        this.yShift.setValue(yMargin);
        this.fillingProportion.setValue(fillingProportion);
        this.minObjectSize.setValue(minObjectSize);
    }
    // testable processing plugin
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        double thld = Double.isNaN(thresholdValue) ? this.threshold.instantiatePlugin().runThresholder(input, parent) : thresholdValue;
        logger.debug("thresholder: {} : {}", threshold.getPluginName(), threshold.getParameters());
        Result r = segmentMicroChannels(input, null, yShift.getValue().intValue(), channelWidth.getValue().intValue(), channelLength.getValue().intValue(), fillingProportion.getValue().doubleValue(), thld, minObjectSize.getValue().intValue(), peakProportion.getDoubleValue(), METHOD.PEAK,  TestableProcessingPlugin.getAddTestImageConsumer(stores, parent), TestableProcessingPlugin.getMiscConsumer(stores, parent), buffers);
        if (r==null) return null;
        else return r.getObjectPopulation(input, true);
    }
    
    @Override
    public Result segment(Image input, int structureIdx, SegmentedObject parent) {
        double thld = Double.isNaN(thresholdValue) ? this.threshold.instantiatePlugin().runSimpleThresholder(input, null) : thresholdValue;
        Result r = segmentMicroChannels(input, null, yShift.getValue().intValue(), channelWidth.getValue().intValue(), channelLength.getValue().intValue(), fillingProportion.getValue().doubleValue(), thld, minObjectSize.getValue().intValue(), peakProportion.getDoubleValue(), METHOD.PEAK, TestableProcessingPlugin.getAddTestImageConsumer(stores, parent), TestableProcessingPlugin.getMiscConsumer(stores, parent), buffers);
        return r;
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    // use threshold implementation
    protected double thresholdValue = Double.NaN;
    Buffers buffers;

    @Override
    public TrackConfigurer<MicrochannelFluo2D> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Buffers buffers = parentTrack.isEmpty() || !parentTrack.get(0).isRoot() ? null: new Buffers(parentTrack.get(0).getMaskProperties());
        double thld = TrackConfigurable.getGlobalThreshold(structureIdx, parentTrack, this.threshold.instantiatePlugin());
        return new TrackConfigurer<MicrochannelFluo2D>() {
            @Override
            public void apply(SegmentedObject parent, MicrochannelFluo2D plugin) {
                plugin.thresholdValue=thld;
                plugin.buffers=buffers;
            }
            @Override
            public void close() {
                if (buffers != null) {
                    buffers.imageIntPool.flush();
                    buffers.imageBytePool.flush();
                }
            }
        };
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY;
    }

    public static class Buffers {
        public final SynchronizedPool<ImageByte> imageBytePool;
        public final SynchronizedPool<ImageInt> imageIntPool;
        public Buffers(ImageProperties props) {
            imageBytePool = new SynchronizedPool<>(() -> new ImageByte("mask", props), (ImageByte im) -> ImageOperations.fill(im, 0, null));
            imageIntPool = new SynchronizedPool<>(() -> new ImageInt("labels", props), (ImageInt im) -> ImageOperations.fill(im, 0, null));
        }
    }

    /**
     *  Detection of microchannel using bacteria fluorescence
     *  1) Rough segmentation of cells on {@param image} using {@param thld} if {@param thld} is NaN threshold is computed using {@link BackgroundThresholder#runThresholder(Image, ImageMask, double, double, int, double, double[]) background thresholder} {@param thresholdedImage} is used instead if not null
        2) Selection of filled channels lengh in X direction should be over {@param channelHeight} x {@param fillingProportion} 
        3) Computation of Y start: min value of the min y coordinate of the selected objects at step 2
     * @param image
     * @param thresholdedImage optional (if null {@param image} will be semgmented using {@param thld}) binary image with bacteria roughly segmented
     * @param yShift y start of microchannels will be shifted of this value towards the top of the image
     * @param channelWidth microchannel width. if value <0, the average value of segmented object width is used
     * @param channelLength used to determined if channels are filled enough
     * @param fillingProportion used to determined if channels are filled enough
     * @param minObjectSize used to filter small objects
     * @param thld optiona lused for image binarization, can be NaN
     * @return 
     */
    public static Result segmentMicroChannels(Image image, ImageInteger thresholdedImage, int yShift, int channelWidth, int channelLength, double fillingProportion, double thld, int minObjectSize, double peakProportion, METHOD method, Consumer<Image> imageTestDisplayer, BiConsumer<String, Consumer<List<SegmentedObject>>> miscDataDisplayer, Buffers buffers) {

        // get thresholded image
        if (Double.isNaN(thld) && thresholdedImage == null) {
            thld = BackgroundThresholder.runThresholder(image, null, 3, 6, 3, Double.MAX_VALUE, null); //IJAutoThresholder.runThresholder(image, null, AutoThresholder.Method.Triangle); // OTSU / TRIANGLE / YEN
        }
        if (miscDataDisplayer != null) {
            double t = thld;
            miscDataDisplayer.accept("Display Threshold", l -> {
                Plugin.logger.debug("Micochannels segmentation threshold : {}", t);
                Core.userLog("Microchannel Segmentation threshold: " + t);
            });
        }
        ImageInteger mask = thresholdedImage == null ? ImageOperations.threshold(image, thld, true, true, false, null) : thresholdedImage;

        // filter out small objects
        ImageByte minBuffer = buffers == null ? null : buffers.imageBytePool.pull();
        Filters.binaryOpen(mask, mask, minBuffer, Filters.getNeighborhood(1.5, 0, image), false); // case of low intensity signal -> noisy. removes individual pixels
        if (buffers != null) buffers.imageBytePool.push(minBuffer);
        ImageInt labelBuffer = buffers == null ? null : buffers.imageIntPool.pull();
        List<Region> bacteria = ImageOperations.filterObjects(mask, mask, labelBuffer, (Region o) -> o.size() < minObjectSize);
        if (buffers != null) buffers.imageIntPool.push(labelBuffer);

        // selected filled microchannels
        float[] xProj = ImageOperations.meanProjection(mask, ImageOperations.Axis.X, null);
        ImageFloat imProjX = new ImageFloat("Total segmented bacteria length", mask.sizeX(), new float[][]{xProj});
        ImageOperations.affineOperation(imProjX, imProjX, (double) (image.sizeY() * image.sizeZ()) / channelLength, 0);
        if (imageTestDisplayer != null) imageTestDisplayer.accept(mask.setName("Thresholded Bacteria"));
        if (miscDataDisplayer != null)
            miscDataDisplayer.accept("Display Microchannel Fill proportion graph", l -> IJUtils.plotProfile(imProjX.setName("Microchannel Fill proportion"), 0, 0, true, "x", "Total Length of bacteria along Y-axis/Microchannel Expected Width"));

        switch (method) {
            case LEGACY: {
                ImageByte projXThlded = ImageOperations.threshold(imProjX, fillingProportion, true, false);

                List<Region> xObjectList = ImageLabeller.labelImageList(projXThlded);
                if (xObjectList.isEmpty()) {
                    return null;
                }
                if (channelWidth <= 1) {
                    channelWidth = (int) xObjectList.stream().mapToInt((Region o) -> o.getBounds().sizeX()).average().getAsDouble();
                }
                int leftLimit = channelWidth / 2 + 1;
                int rightLimit = image.sizeX() - leftLimit;
                Iterator<Region> it = xObjectList.iterator();
                while (it.hasNext()) {
                    BoundingBox b = it.next().getBounds();
                    if (b.xMean() < leftLimit || b.xMean() > rightLimit) {
                        it.remove(); //if (b.getxMin()<Xmargin || b.getxMax()>rightLimit) it.remove(); //
                    }
                }
                if (xObjectList.isEmpty()) {
                    return null;
                }

                // fusion of overlapping objects in X direction
                it = xObjectList.iterator();
                Region prev = it.next();
                while (it.hasNext()) {
                    Region next = it.next();
                    if (prev.getBounds().xMax() + 1 > next.getBounds().xMin()) {
                        prev.merge(next);
                        it.remove();
                    } else {
                        prev = next;
                    }
                }
                Region[] xObjects = xObjectList.toArray(new Region[xObjectList.size()]);
                if (xObjects.length == 0) {
                    return null;
                }

                if (bacteria.isEmpty()) {
                    return null;
                }
                int[] yMins = new int[xObjects.length];
                Arrays.fill(yMins, Integer.MAX_VALUE);
                for (Region o : bacteria) {
                    BoundingBox b = o.getBounds();
                    //if (debug) logger.debug("object: {}");
                    X_SEARCH:
                    for (int i = 0; i < xObjects.length; ++i) {
                        BoundingBox inter = BoundingBox.getIntersection(b, xObjects[i].getBounds());
                        if (inter.sizeX() >= 2) {
                            if (b.yMin() < yMins[i]) {
                                yMins[i] = b.yMin();
                            }
                            break X_SEARCH;
                        }
                    }
                }
                return getResult(yMins, xObjects, channelWidth, channelLength, yShift, image.sizeX());
            }
            default : case PEAK: {
                if (channelWidth<1) throw new IllegalArgumentException("channel width should be >1");
                // get center of microchannel: peaks of xProjection
                ArrayUtil.gaussianSmooth(xProj, Math.max(2, channelWidth/8));
                List<Integer> localMax = ArrayUtil.getRegionalExtrema(xProj, channelWidth/2, true);
                if (peakProportion < 1) {
                    localMax = localMax.stream().map(p -> {
                        int startOfPeak = ArrayUtil.getFirstOccurence(xProj, p, 0, v->v<xProj[p] * peakProportion);
                        int endOfPeak = ArrayUtil.getFirstOccurence(xProj, p, xProj.length, v->v<xProj[p] * peakProportion);
                        return (startOfPeak + endOfPeak) / 2;
                    }).collect(Collectors.toList());
                }
                //logger.debug("channelWidth: {}, peaks: {}", channelWidth, localMax);
                int leftLimit = channelWidth / 2 + 1;
                int rightLimit = image.sizeX() - leftLimit;
                localMax.removeIf(l -> l<leftLimit || l>rightLimit || xProj[l]<fillingProportion);
                if (localMax.isEmpty()) return null;

                // for each local max, get yMin
                float[] yProj = new float[mask.sizeY()];
                int halfWidth = channelWidth/2;
                int[] yMins = localMax.stream().mapToInt(l -> {
                    ImageOperations.meanProjection(mask, ImageOperations.Axis.Y, new SimpleBoundingBox(l-halfWidth, l+halfWidth, 0, mask.sizeY()-1, 0, mask.sizeZ()-1), d->true, yProj);
                    return ArrayUtil.getFirstOccurence(yProj, 0, yProj.length, d->d>0);
                }).toArray();
                Region[] xObjects = localMax.stream().map(l -> new Region(new BlankMask(0, 0, 0, l.intValue(), 0, 0, 1, 1), 1, true)).toArray(r->new Region[r]);
                return getResult(yMins, xObjects, channelWidth, channelLength, yShift, image.sizeX());
            }
        }
    }
    private static Result getResult(int[] yMins, Region[] xObjects, int channelWidth, int channelLength, int yShift, int imageSizeX) {
        // get median yMin
        List<Integer> yMinsList = new ArrayList<>(yMins.length);
        for (int yMin : yMins) {
            if (yMin != Integer.MAX_VALUE) {
                yMinsList.add(yMin);
            }
        }
        if (yMinsList.isEmpty()) {
            return null;
        }
        //int yMin = (int)Math.round(ArrayUtil.medianInt(yMinsList));
        //if (debug) logger.debug("Ymin: {}, among: {} values : {}, shift: {}", yMin, yMinsList.size(), yMins, yShift);
        int yMin = Collections.min(yMinsList);
        List<int[]> sortedMinMaxYShiftList = new ArrayList<>(xObjects.length);
        for (int i = 0; i < xObjects.length; ++i) {
            if (yMins[i] == Integer.MAX_VALUE) {
                continue;
            }
            int xMin = (int) (xObjects[i].getBounds().xMean() - channelWidth / 2.0);
            int xMax = (int) (xObjects[i].getBounds().xMean() + channelWidth / 2.0); // mc remains centered
            if (xMin < 0 || xMax >= imageSizeX) {
                continue; // exclude outofbounds objects
            }
            int[] minMaxYShift = new int[]{xMin, xMax, yMins[i] - yMin < yShift ? 0 : yMins[i] - yMin};
            sortedMinMaxYShiftList.add(minMaxYShift);
        }
        Collections.sort(sortedMinMaxYShiftList, Comparator.comparingInt((int[] i) -> i[0]));
        int shift = Math.min(yMin, yShift);
        return new Result(sortedMinMaxYShiftList, yMin - shift, yMin + channelLength - 1);
    }
}
