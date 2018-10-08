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

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.processing.Filters;
import bacmman.processing.ImageOperations;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.utils.Utils;
import bacmman.plugins.MicrochannelSegmenter;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInteger;
import bacmman.image.ImageLabeller;

import java.util.ArrayList;

import bacmman.plugins.Plugin;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.Hint;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import bacmman.plugins.TrackConfigurable;

/**
 *
 * @author Jean Ollion
 */
public class MicrochannelFluo2D implements MicrochannelSegmenter, TrackConfigurable<MicrochannelFluo2D>, Hint, TestableProcessingPlugin {
    
    NumberParameter channelHeight = new BoundedNumberParameter("Microchannel Height", 0, 320, 5, null).setHint("Height of microchannels, in pixels");
    NumberParameter channelWidth = new BoundedNumberParameter("Microchannel Width", 0, 60, 5, null).setHint("Width of microchannels in pixels");
    NumberParameter yShift = new BoundedNumberParameter("y-Start Shift", 0, 20, 0, null).setHint("Top of the microchannel will be translated of this value (in pixels) towards upper direction");
    public final static  String THLD_TOOL_TIP = "Threshold to segment bacteria. <br />Configuration hint: result of segmentation is the image <em>Thresholded Bacteria</em>";
    PluginParameter<SimpleThresholder> threshold= new PluginParameter<>("Threshold", SimpleThresholder.class, new BackgroundFit(10), false).setHint(THLD_TOOL_TIP); //new BackgroundThresholder(3, 6, 3) when background is removed and images saved in 16b, half of background is trimmed -> higher values
    public final static  String FILL_TOOL_TIP = "Fill proportion = y-length of bacteria / height of microchannel. If proportion is under this value, the object won't be segmented. Allows to avoid segmenting islated bacteria in central channel.<br /> Configuration Hint: Refer to plot <em>Microchannel Fill proportion</em>: peaks over the filling proportion value are segmented. Decrease the value to included lower peaks";
    NumberParameter fillingProportion = new BoundedNumberParameter("Microchannel filling proportion", 2, 0.3, 0.05, 1).setHint(FILL_TOOL_TIP);
    public final static String SIZE_TOOL_TIP = "To detect microchannel a rough semgentation of bacteria is performed by simple threshold. Object under this size in pixels are removed, to avoid taking into account objects that are not bacteria. <br /> Refer to <em>Thresholded Bacteria</em> to assess the size of bacteria after removing small objects";
    NumberParameter minObjectSize = new BoundedNumberParameter("Min. Object Size", 0, 200, 1, null).setHint(SIZE_TOOL_TIP);
    Parameter[] parameters = new Parameter[]{channelHeight, channelWidth, yShift, threshold, fillingProportion, minObjectSize};
    public static boolean debug = false;
    public static final String TOOL_TIP = "<b>Detection of microchannel using bacteria fluorescence:</b>"
    + "<ol><li>Rough segmentation of cells using \"Threshold\" computed the whole track prior to segmentation step</li>"
    + "<li>Selection of filled channels: lengh in X direction should be over \"Microchannel Height\" x \"Microchannel filling proportion\"</li>"
    + "<li>Computation of Y start: min value of the min y coordinate of the selected objects at step 2</li></ol>";
    public MicrochannelFluo2D() {}

    public MicrochannelFluo2D(int channelHeight, int channelWidth, int yMargin, double fillingProportion, int minObjectSize) {
        this.channelHeight.setValue(channelHeight);
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
        double thld = Double.isNaN(thresholdValue) ? this.threshold.instanciatePlugin().runThresholder(input, parent) : thresholdValue;
        logger.debug("thresholder: {} : {}", threshold.getPluginName(), threshold.getParameters());
        Result r = segmentMicroChannels(input, null, yShift.getValue().intValue(), channelWidth.getValue().intValue(), channelHeight.getValue().intValue(), fillingProportion.getValue().doubleValue(), thld, minObjectSize.getValue().intValue(), TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent), TestableProcessingPlugin.getMiscConsumer(stores, (SegmentedObject)parent));
        if (r==null) return null;
        else return r.getObjectPopulation(input, true);
    }
    
    @Override
    public Result segment(Image input, int structureIdx, SegmentedObject parent) {
        double thld = Double.isNaN(thresholdValue) ? this.threshold.instanciatePlugin().runSimpleThresholder(input, null) : thresholdValue;
        Result r = segmentMicroChannels(input, null, yShift.getValue().intValue(), channelWidth.getValue().intValue(), channelHeight.getValue().intValue(), fillingProportion.getValue().doubleValue(), thld, minObjectSize.getValue().intValue(), TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent), TestableProcessingPlugin.getMiscConsumer(stores, (SegmentedObject)parent));
        return r;
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    // use threshold implementation
    protected double thresholdValue = Double.NaN;


    @Override
    public TrackConfigurer<MicrochannelFluo2D> run(int structureIdx, List<SegmentedObject> parentTrack) {
        double thld = TrackConfigurable.getGlobalThreshold(structureIdx, parentTrack, this.threshold.instanciatePlugin());
        return (p, s)->s.thresholdValue=thld;
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
     * @param channelHeight used to determined if channels are filled enough
     * @param fillingProportion used to determined if channels are filled enough
     * @param minObjectSize used to filter small objects
     * @param thld optiona lused for image binarization, can be NaN
     * @return 
     */
    public static Result segmentMicroChannels(Image image, ImageInteger thresholdedImage, int yShift, int channelWidth, int channelHeight, double fillingProportion, double thld, int minObjectSize, Consumer<Image> imageTestDisplayer, BiConsumer<String, Consumer<List<SegmentedObject>>> miscDataDisplayer) {
        
        // get thresholded image
        if (Double.isNaN(thld) && thresholdedImage == null) {
            thld = BackgroundThresholder.runThresholder(image, null, 3, 6, 3, Double.MAX_VALUE, null); //IJAutoThresholder.runThresholder(image, null, AutoThresholder.Method.Triangle); // OTSU / TRIANGLE / YEN
        }
        if (miscDataDisplayer!=null)  {
            double t = thld;
            miscDataDisplayer.accept("Display Threshold", l->{
                Plugin.logger.debug("Micochannels segmentation threshold : {}", t);
                Core.userLog("Microchannel Segmentation threshold: "+t);
            });
        }
        ImageInteger mask = thresholdedImage == null ? ImageOperations.threshold(image, thld, true, true) : thresholdedImage;
        // remove small objects
        Filters.binaryOpen(mask, mask, Filters.getNeighborhood(1.5, 0, image), false); // case of low intensity signal -> noisy. removes individual pixels
        List<Region> bacteria = ImageOperations.filterObjects(mask, mask, (Region o) -> o.size() < minObjectSize);
        // selected filled microchannels
        float[] xProj = ImageOperations.meanProjection(mask, ImageOperations.Axis.X, null);
        ImageFloat imProjX = new ImageFloat("Total segmented bacteria length", mask.sizeX(), new float[][]{xProj});
        ImageOperations.affineOperation(imProjX, imProjX, (double) (image.sizeY() * image.sizeZ()) / channelHeight, 0);
        ImageByte projXThlded = ImageOperations.threshold(imProjX, fillingProportion, true, false);
        if (imageTestDisplayer!=null) imageTestDisplayer.accept(mask.setName("Thresholded Bacteria"));
        if (miscDataDisplayer!=null) miscDataDisplayer.accept("Display Microchanenl Fill proportion", l -> Utils.plotProfile(imProjX.setName("Microchannel Fill proportion"), 0, 0, true, "x", "Total Length of bacteria along Y-axis/Microchannel Expected Width"));
        
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
        // fusion of overlapping objects
        it = xObjectList.iterator();
        Region prev = it.next();
        while (it.hasNext()) {
            Region next = it.next();
            if (prev.getBounds().xMax() + 1 > next.getBounds().xMin()) {
                prev.addVoxels(next.getVoxels());
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
            if (xMin < 0 || xMax >= image.sizeX()) {
                continue; // exclude outofbounds objects
            }
            int[] minMaxYShift = new int[]{xMin, xMax, yMins[i] - yMin < yShift ? 0 : yMins[i] - yMin};
            sortedMinMaxYShiftList.add(minMaxYShift);
        }
        Collections.sort(sortedMinMaxYShiftList, (int[] i1, int[] i2) -> Integer.compare(i1[0], i2[0]));
        int shift = Math.min(yMin, yShift);
        return new Result(sortedMinMaxYShiftList, yMin-shift, yMin + channelHeight - 1);
    }

    @Override
    public String getHintText() {
        return TOOL_TIP;
    }

}
