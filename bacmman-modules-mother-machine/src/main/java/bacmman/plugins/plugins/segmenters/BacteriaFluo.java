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
import bacmman.plugins.*;
import bacmman.plugins.SimpleThresholder;
import bacmman.processing.ImageFeatures;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import ij.process.AutoThresholder;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;

import java.util.List;
import java.util.Set;

import static bacmman.plugins.plugins.segmenters.EdgeDetector.valueFunction;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.DoubleStream;

/**
 *
 * @author Jean Ollion
 */
public class BacteriaFluo extends BacteriaIntensitySegmenter<BacteriaFluo> implements HintSimple {
    public static boolean verbose = false;
    public enum FOREGROUND_SELECTION_METHOD {SIMPLE_THRESHOLDING, HYSTERESIS_THRESHOLDING, EDGE_FUSION}
    public enum THRESHOLD_COMPUTATION {CURRENT_FRAME, PARENT_TRACK, ROOT_TRACK}
    private enum BACKGROUND_REMOVAL {BORDER_CONTACT, THRESHOLDING, BORDER_CONTACT_AND_THRESHOLDING}
    public enum CONTOUR_ADJUSTMENT_METHOD {LOCAL_THLD_IQR}
    // configuration-related attributes
    
    PluginParameter<SimpleThresholder> bckThresholderFrame = new PluginParameter<>("Method", bacmman.plugins.SimpleThresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setHint("Threshold for selection of foreground regions after watershed-based partitioning on the edge map. All regions whose median value is lower than this threshold are considered as background").setEmphasized(true);
    PluginParameter<ThresholderHisto> bckThresholder = new PluginParameter<>("Method", ThresholderHisto.class, new BackgroundFit(10), false).setHint("Threshold for selection of foreground regions after watershed-based partitioning on the edge map. All regions whose median value is lower than this threshold are considered as background. Computed on the whole parent track.").setEmphasized(true);
    EnumChoiceParameter<THRESHOLD_COMPUTATION> bckThresholdMethod=  new EnumChoiceParameter<>("Background Threshold", THRESHOLD_COMPUTATION.values(), THRESHOLD_COMPUTATION.ROOT_TRACK, false);
    static String thldSourceHint = "If <em>CURRENT_FRAME</em> is selected, the threshold is be computed at each frame. If <em>PARENT_BRANCH</em> is selected, the threshold is be computed on the whole parent track (e.g. for segmenting bacteria, the threshold is computed on the images of the microchannel at all frames). If <em>ROOT_TRACK</em> is selected, the threshold is be computed on the whole viewfield on all raw images (so do not choose this option if pre-filters are set).";
    ConditionalParameter bckThldCond = new ConditionalParameter(bckThresholdMethod).setActionParameters(THRESHOLD_COMPUTATION.CURRENT_FRAME.toString(), bckThresholderFrame).setActionParameters(THRESHOLD_COMPUTATION.ROOT_TRACK.toString(), bckThresholder).setActionParameters(THRESHOLD_COMPUTATION.PARENT_TRACK.toString(), bckThresholder).setEmphasized(true).setHint("Threshold for filtering of background regions after watershed-based partitioning on the edge map. All regions whose median value is lower than this threshold are considered as background. <br />"+thldSourceHint+"<br />Configuration Hint: the value of this threshold is displayed from the right click menu: <em>display thresholds</em> command. Tune the value using the <em>Foreground detection: Region values after partitioning</em> intermediate image. Only background regions should be under this threshold");
    
    PluginParameter<bacmman.plugins.SimpleThresholder> foreThresholderFrame = new PluginParameter<>("Method", bacmman.plugins.SimpleThresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setEmphasized(true);
    PluginParameter<ThresholderHisto> foreThresholder = new PluginParameter<>("Method", ThresholderHisto.class, new BackgroundFit(20), false).setEmphasized(true).setHint("Threshold for selection of foreground regions, use depend on the method. Computed on the whole parent-track.");
    EnumChoiceParameter<THRESHOLD_COMPUTATION> foreThresholdMethod=  new EnumChoiceParameter<>("Foreground Threshold", THRESHOLD_COMPUTATION.values(), THRESHOLD_COMPUTATION.ROOT_TRACK, false).setEmphasized(true);
    ConditionalParameter foreThldCond = new ConditionalParameter(foreThresholdMethod).setActionParameters(THRESHOLD_COMPUTATION.CURRENT_FRAME.toString(), foreThresholderFrame).setActionParameters(THRESHOLD_COMPUTATION.ROOT_TRACK.toString(), foreThresholder).setActionParameters(THRESHOLD_COMPUTATION.PARENT_TRACK.toString(), foreThresholder)
            .setHint("Threshold for foreground region selection after watershed-based partitioning on the edge map. All regions whose median value is larger than this threshold are considered as foreground. <br />"+thldSourceHint+"<br />Configuration Hint: value is displayed on right click menu: <em>display thresholds</em> command. Tune the value using intermediate image <em>Foreground detection: Region values after partitioning</em>, only foreground regions should be over this threshold");
    static String bckEdgeAlgoHint = "<br />Definition of the criterion:<br />Criterion is Dec(E)@Inter / BCK_SD where the word <em>Interface</em> refers to the area of contact between two regions, Dec(E)@Inter refers to the first decile of intensity values of the edge map at the interface (as defined by the <em>Edge Map</em> parameter) and BCK_SD refers to the standard deviation of the background pixel intensities.";
    static String bckEdgeHint = "Threshold for fusion of background regions.<br />This threshold depends on the signal range. When this threshold is too low, background regions are segmented, and when it is too high, some bacteria or parts of bacteria are omitted<br />Each pair of adjacent regions are merged if a criterion is smaller than this threshold.<br />This method requires sharp edges (decrease smoothing in edge map to increase sharpness)<br />Configuration Hint: Tune the value using the <em>Foreground detection: Interface Values</em> image. No interface between foreground and background regions should have a value under this threshold";
    NumberParameter backgroundEdgeFusionThld = new BoundedNumberParameter("Background Edge Fusion Threshold", 4, 1.5, 0, null).setEmphasized(true).setHint(bckEdgeHint+"<br />"+bckEdgeAlgoHint).setSimpleHint(bckEdgeHint);
    NumberParameter foregroundEdgeFusionThld = new BoundedNumberParameter("Foreground Edge Fusion Threshold", 4, 0.2, 0, null).setHint("Threshold for fusion of foreground regions. Allows merging foreground regions whose median value are low in order to avoid removing them at thresholding<br /><br />Two adjacent regions are merged if Mean(E)@Inter / Mean(PF)@Regions &lt; this threshold, where <em>Interface</em> refers to the area of contact between two regions, Mean(E)@Inter refers to the mean value of the <em>Edge Map</em> at the interface, Mean(PF)@Regions refers to the mean value of the pre-filtered image within the union of the two regions.<br />This step requires sharp edges (decrease smoothing in edge map to increase sharpness).<br /><br />Configuration Hint: Tune the value using the <em>Foreground detection: interface values (foreground fusion)</em> image. No interface between foreground and background regions should have a value under this threshold");

    EnumChoiceParameter<BACKGROUND_REMOVAL> backgroundSel=  new EnumChoiceParameter<>("Background Removal", BACKGROUND_REMOVAL.values(), BACKGROUND_REMOVAL.BORDER_CONTACT, false);
    ConditionalParameter backgroundSelCond = new ConditionalParameter(backgroundSel).setActionParameters(BACKGROUND_REMOVAL.BORDER_CONTACT_AND_THRESHOLDING.toString(), foregroundEdgeFusionThld, bckThldCond).setActionParameters(BACKGROUND_REMOVAL.THRESHOLDING.toString(), foregroundEdgeFusionThld, bckThldCond)
            .setHint("Method to remove background regions after merging.<br/><ul>" +
                    "<li><em>"+BACKGROUND_REMOVAL.BORDER_CONTACT.toString()+"</em>: Removes all regions directly in contact with upper, left and right borders of the microchannel. Length & width of microchannels should be adjusted so that bacteria going out of the microchannels are not within the segmented regions of the microchannel otherwise they may touch the left/right sides of the microchannels and be erased</li>" +
                    "<li><em>"+BACKGROUND_REMOVAL.THRESHOLDING.toString()+"</em>: Removes regions whose median value is smaller than the <em>Background Threshold</em></li>" +
                    "<li><em>"+BACKGROUND_REMOVAL.BORDER_CONTACT_AND_THRESHOLDING.toString()+"</em>: Combination of the two previous methods</li></ul>" +
                    "When <em>"+BACKGROUND_REMOVAL.THRESHOLDING.toString()+"</em> or <em>"+BACKGROUND_REMOVAL.BORDER_CONTACT_AND_THRESHOLDING.toString()+"</em> methods are selected, all adjacent regions that verify the condition defined in <em>Foreground Edge Fusion Threshold</em> are merged before removing background regions");

    EnumChoiceParameter<FOREGROUND_SELECTION_METHOD> foregroundSelectionMethod=  new EnumChoiceParameter<>("Foreground selection Method", FOREGROUND_SELECTION_METHOD.values(), FOREGROUND_SELECTION_METHOD.EDGE_FUSION, false).setEmphasized(true);
    ConditionalParameter foregroundSelectionCond = new ConditionalParameter(foregroundSelectionMethod).setActionParameters(FOREGROUND_SELECTION_METHOD.SIMPLE_THRESHOLDING.toString(), bckThldCond).setActionParameters(FOREGROUND_SELECTION_METHOD.HYSTERESIS_THRESHOLDING.toString(), bckThldCond, foreThldCond).setActionParameters(FOREGROUND_SELECTION_METHOD.EDGE_FUSION.toString(), backgroundEdgeFusionThld,backgroundSelCond).setEmphasized(true)
            .setHint("Methods for foreground selection after watershed partitioning on <em>Edge Map</em><br /><ul>" +
                    "<li>"+FOREGROUND_SELECTION_METHOD.SIMPLE_THRESHOLDING.toString()+": All the regions whose median value is smaller than the threshold defined in <em>Background Threshold</em> are erased. Not suitable when the fluorescence signal is highly variable between bacteria</li>" +
                    "<li>"+FOREGROUND_SELECTION_METHOD.HYSTERESIS_THRESHOLDING.toString()+": The regions whose median value is under the <em>Background Threshold</em> are considered as background, and the regions whose median value is over the threshold defined in <em>Foreground Threshold</em> are considered as foreground. Other regions are fused to the adjacent region that has the lowest edge value at interface, until only Background and Foreground regions remain. Then the background regions are removed. This method is suitable if a foreground threshold can be defined verifying the following conditions : <ol><li>each cell contains at least one region whose median value is larger than this threshold</li><li>No foreground region (especially close to highly fluorescent bacteria) has a median value superior to this threshold</li></ol> </li> This method is suitable when the fluorescence signal is highly variable, but requires to be tuned according to the fluorescence signal (that can vary between different experiments)" +
                    "<li>"+FOREGROUND_SELECTION_METHOD.EDGE_FUSION.toString()+": </li>Foreground selection is performed in 2 steps:<ol><li>All adjacent regions that verify the condition defined in <em>Background Edge Fusion Threshold</em> are merged. This mainly merges background regions </li><li>Background regions are removed according to the method selected in the <em>Background Removal</em> parameter (available in advanced mode)</li></ol>This method is more suitable when foreground fluorescence levels are highly variable, but might not detect all bacteria (or parts of bacteria) when edges are not sharp enough (which occurs for instance when focus is lost).</ul>");
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethod = new EnumChoiceParameter<>("Contour adjustment", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_IQR, true).setEmphasized(true).setHint("Method for contour adjustment after segmentation");
    ConditionalParameter contourAdjustmentCond = new ConditionalParameter(contourAdjustmentMethod).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_IQR.toString(), localThresholdFactor);

    // attributes parametrized during track parametrization
    protected double bckThld = Double.NaN, foreThld = Double.NaN;
    private double globalBackgroundSigma=1;
    
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, edgeMap, foregroundSelectionCond, hessianScale, splitThreshold, smoothScale, contourAdjustmentCond};
    }
    @Override
    public String getHintText() {
        return "<b>Intensity-based 2D segmentation of fluorescent bacteria within microchannels:</b><br />"
            +"<br />Empty microchannels are detected prior to segmentation, using information on the whole microchannel track. See <em>Variation coefficient threshold</em> parameter"
            +"<br />Segmentation steps:"
            + "<ol><li>Detection of foreground: the image is first partitioned by performing a watershed according to the edge map (see <em>Edge Map</em> parameter)"
            + "<br />Regions corresponding to the foreground are then selected depending on the method chosen in <em>Foreground detection Method</em> and merged</li>"
            + "<li>In order to separate touching cells, the foreground region is split by applying a watershed transform on the maximal Eigenvalue of the Hessian. Regions are then merged, using a criterion described in the help of the <em>Split Threshold</em> parameter</li>"
            + "<li>The contour of bacteria is adjusted using a threshold computed for each bacterium. This threshold is set as described in the <em>Local Threshold Factor</em> parameter."
            //+ "Propagating from contour voxels, all voxels with value on the smoothed image (<em>Smooth scale</em> parameter) under the local threshold is removed</li>"
            + "</ol>"
            + "Intermediate images are displayed in test mode for each of the steps described above. In order to display the different regions after a partitioning step, we use an image displaying the median intensity value of each region, referred to as MIP"
            + "<ul><li><em>Foreground detection: edge map</em>: image of edges used for watershed partitioning. Can be modified by changing the <em>Edge map</em> parameter. (<em>see step 1</em>)</li>"
            + "<li><em>Foreground detection: Region values after partitioning</em>: MIP after watershed-based partitioning according to the edge map (see <em>step 1</em>). Importantly, regions should be either located in the foreground or in the background but should not overlap both areas. If some regions overlap both foreground and background, try modifying the <em>Edge map</em> parameter</li>"
            + "<li><em>Foreground detection: Interface Values</em>: (only displayed when the <em>Foreground selection method</em> parameter is set to <em>EDGE_FUSION</em>.) Each segment represents the area of contact between two regions of the partition (referred to as interface). The intensity of each interface is the value of a criterion used for merging all the background regions (see help of the <em>Background Edge Fusion Threshold</em>, located in the <em>Foreground selection method</em> parameter). <br />Interface values should be as high as possible between foreground and background, and as low as possible between background regions, and the interfaces are influenced by the <em>Edge map</em> parameter.<br />This image can be used to tune the <em>Background Edge Fusion Threshold</em>. This threshold should be larger than the intensity of all the segments contained in the background region of the image and smaller than the segments separating the cells from the background. (<em>used in step 1</em>)</li>"
            + "<li><em>Foreground detection: Region values after fusion</em>: (only displayed when the <em>Foreground selection method</em> parameter is set to <em>EDGE_FUSION</em>.) MIP after merging background regions. No foreground regions should be merged with the background. Some background regions located between close cells may remain. (<em>see step 1</em>). If some regions overlap both background  and foreground, decrease the <em>Background Edge Fusion Threshold</em></li>"
            + "<li><em>Foreground detection: interface values (foreground fusion)</em>: (only displayed when the <em>Foreground selection method</em> parameter is set to <em>EDGE_FUSION</em> and the <em>Background Removal</em> parameter is set to <em>THRESHOLDING</em> or <em>BORDER_CONTACT_AND_THRESHOLDING</em>.) The intensity of each interface segment is the value of a criterion used for merging all the foreground regions before removing background regions (see the help of the <em>Foreground Edge Fusion Threshold</em> parameter, located in the <em>Foreground selection Method<em> &gt; <em>Background Removal</em> parameter). Interface values should be as high as possible between foreground and background, and as low as possible between foreground regions (influenced by the <em>Edge map</em> parameter)</li>"
            + "<li><em>Foreground mask</em>: binary mask obtained after removing background regions (see the <em>Foreground selection method</em> parameter) (<em>obtained at end of step 1</em>)</li>"
            + "<li><em>Hessian</em>: max Eigenvalue of the hessian matrix used for partitioning the foreground mask in order to separate the cells. Its intensity should be as high as possible at the interface between touching cells and as low as possible within cells. It is influenced by the <em>Hessian scale</em> parameter (<em>used in step 2</em>)</li> "
            //+ "<li><em>Split cells: Region values before merge</em>: MIP after watershed-based partitioning of the Foreground mask according to the <em>Hessian</em> image(<em>used in step 2</em>)</li> "
            + "<li><em>Split cells: Interface values</em>: Each segment represents the area of contact between two regions (referred to as interface) after a watershed-based partitioning of the foreground mask according to the <em>Hessian</em> image. This image can be used to tune the <em>Split Threshold</em> parameter : bacteria will be cut where the segments displayed on this image have an intensity larger than the value of the <em>Split Threshold</em> parameter. Interface values should be as high as possible between cells and as low as possible within cells (influenced by the <em>Hessian scale</em> parameter) (<em>used in step 2</em>)</li> "
            + "<li><em>Split cells: Region values after merge</em>: MIP after merging and before contour adjustments (<em>see step 2</em>)</li>"
            +"</ul>";
    }
    @Override
    public String getSimpleHintText() {
        return "<b>Intensity-based 2D segmentation of fluorescent bacteria within microchannels</b><br />"
                + "<br />If objects are segmented in empty microchannels or if filled microchannels appear as empty, tune the <em>Variation coefficient threshold</em> parameter. "
                + "<br />If bacteria are over-segmented or touching bacteria are merged, tune the <em>Split Threshold</em> parameter"
                + "<br />The contour of bacteria can be adjusted by tuning the <em>Local Threshold Factor parameter</em> (in the <em>Contour adjustment</em> parameter)"
                + "<br /><br />Intermediate images displayed in test mode:"
                + "<ul>"
                + "<li><em>Foreground detection: Interface Values</em>: On this image, segment intensities should be high between foreground (inside bacteria) and background (outside bacteria), and low between background regions (influenced by the <em>Edge map</em> parameter, available in the advanced mode). This image can be used to tune the <em>Background Edge Fusion Threshold<em> parameter. This threshold should be larger than the intensity of all the segments contained in the background region of the image and smaller than the segments separating the cells from the background.</li>"
                + "<li><em>Foreground detection: Region values after fusion</em>: On this image, no foreground regions should be merged with background regions. If some regions overlap both background  and foreground, decrease the <em>Background Edge Fusion Threshold</em> parameter.</li>"
                + "<li><em>Hessian</em>: In this image, the intensity should be as high as possible at the interface between touching cells and as low as possible within cells. It is influenced by the <em>Hessian scale</em> parameter</li> "
                + "<li><em>Split cells: Interface values</em>: Segment intensity should be as high as possible between cells and as low as possible within cells (influenced by the <em>Hessian scale</em> parameter). This image can be used to tune the <em>Split Threshold</em> parameter : bacteria will be cut where the segments displayed on this image have an intensity larger than the <em>Split Threshold</em></li> "
                +"</ul>";

    }
    
    public BacteriaFluo() {
        super();
        localThresholdFactor.setHint("Factor defining the local threshold used in step 3.<br /> Lower value of this threshold will results in smaller cells.<br /><b>This threshold should be calibrated for each new experimental setup</b>"
                + "<br /><br />Algorithmic details: Let be Med(PF)@Region the median value of the pre-filtered image within the region, IQ(PF)@Region the inter-quartile of the pre-filtered image within the region. <br />Threshold = Med(PF)@Region - IQ(PF)@Region * (this threshold).");
        splitThreshold.setHint(splitThreshold.getHintText()+"<br /><br /><em>Algorithmic Details: </em>Let's call <em>interface</em> the contact area between two regions, <em>mean(H)@Inter</em> the mean hessian value at the interface and <em>mean(PF)@Inter</em> the mean value of the pre-filtered image at the interface, and <em>BCK</em> an estimation of the background value. <be />Criterion is:  mean(H)@Inter / ( mean(PF)@Inter - BCK )<br />");
    }
    
    @Override
    public String toString() {
        return "Bacteria Intensity: " + Utils.toStringArray(getParameters());
    }   
    
    private void ensureThresholds(SegmentedObject parent, int structureIdx, boolean bck, boolean fore) {
        if (bck && Double.isNaN(bckThld) && THRESHOLD_COMPUTATION.CURRENT_FRAME.equals(bckThresholdMethod)) {
            bckThld = bckThresholderFrame.instanciatePlugin().runSimpleThresholder(parent.getPreFilteredImage(structureIdx), parent.getMask());
        } 
        if (bck && Double.isNaN(bckThld)) throw new RuntimeException("Bck Threshold not computed");
        if (fore && Double.isNaN(foreThld) && THRESHOLD_COMPUTATION.CURRENT_FRAME.equals(foreThresholdMethod.getSelectedEnum())) {
            foreThld = foreThresholderFrame.instanciatePlugin().runSimpleThresholder(parent.getPreFilteredImage(structureIdx), parent.getMask());
        } 
        if (fore && Double.isNaN(foreThld)) throw new RuntimeException("Fore Threshold not computed");        
    }
    @Override
    protected RegionPopulation filterRegionsAfterEdgeDetector(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        switch(FOREGROUND_SELECTION_METHOD.valueOf(foregroundSelectionMethod.getValue())) {
            case SIMPLE_THRESHOLDING:
                ensureThresholds(parent, structureIdx, true, false);
                pop.filter(r->valueFunction(parent.getPreFilteredImage(structureIdx)).apply(r)>bckThld);
                break;
            case HYSTERESIS_THRESHOLDING:
                pop = filterRegionsAfterEdgeDetectorHysteresis(parent, structureIdx, pop);
                break;
            case EDGE_FUSION:
            default:
                pop = filterRegionsAfterEdgeDetectorEdgeFusion(parent, structureIdx, pop);
                break;
        }
        return pop;
    }
    protected RegionPopulation filterRegionsAfterEdgeDetectorEdgeFusion(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent);
        SplitAndMergeEdge sm = new SplitAndMergeEdge(edgeDetector.getWsMap(parent.getPreFilteredImage(structureIdx), parent.getMask()), parent.getPreFilteredImage(structureIdx), 0.1, false);
        
        // merge background regions: value is proportional to background sigma
        sm.setInterfaceValue(i-> {
            if (i.getVoxels().isEmpty() || sm.isFusionForbidden(i)) {
                return Double.NaN;
            } else {
                int size = i.getVoxels().size()+i.getDuplicatedVoxels().size();
                //double val= Stream.concat(i.getVoxels().stream(), i.getDuplicatedVoxels().stream()).mapToDouble(v->sm.getWatershedMap().getPixel(v.x, v.y, v.z)).average().getAsDouble();
                double val= ArrayUtil.quantile(Stream.concat(i.getVoxels().stream(), i.getDuplicatedVoxels().stream()).mapToDouble(v->sm.getWatershedMap().getPixel(v.x, v.y, v.z)).sorted(), size, 0.1);
                val /= this.globalBackgroundSigma;
                return val;
            }
        }).setThresholdValue(this.backgroundEdgeFusionThld.getValue().doubleValue());
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(pop).setName("Foreground detection: Interface Values"));
        sm.merge(pop, null);
        if (stores!=null) imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(structureIdx)).setName("Foreground detection: Region values after fusion"));
        BACKGROUND_REMOVAL remMethod = BACKGROUND_REMOVAL.valueOf(backgroundSel.getSelectedItem());
        boolean remContact = remMethod == BACKGROUND_REMOVAL.BORDER_CONTACT || remMethod == BACKGROUND_REMOVAL.BORDER_CONTACT_AND_THRESHOLDING;
        boolean remThld = remMethod == BACKGROUND_REMOVAL.THRESHOLDING || remMethod == BACKGROUND_REMOVAL.BORDER_CONTACT_AND_THRESHOLDING;
        if (remThld) { // merge foreground regions: normalized values
            sm.setInterfaceValue(i-> {
                if (i.getVoxels().isEmpty() || sm.isFusionForbidden(i)) {
                    return Double.NaN;
                } else {
                    //int size = i.getVoxels().size()+i.getDuplicatedVoxels().size();
                    double val= Stream.concat(i.getVoxels().stream(), i.getDuplicatedVoxels().stream()).mapToDouble(v->sm.getWatershedMap().getPixel(v.x, v.y, v.z)).average().getAsDouble();
                    double mean1 = BasicMeasurements.getMeanValue(i.getE1(), sm.getIntensityMap());
                    double mean2 = BasicMeasurements.getMeanValue(i.getE2(), sm.getIntensityMap());
                    double mean = (mean1 + mean2) / 2d;
                    val= val/(mean-globalBackgroundLevel);
                    return val;
                }
            }).setThresholdValue(this.foregroundEdgeFusionThld.getValue().doubleValue());
            if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(sm.drawInterfaceValues(pop).setName("Foreground detection: interface values (foreground fusion)"));
            sm.merge(pop, null);
            //if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(structureIdx)).setName("Foreground detection: region values after fusion (foreground fusion)"));
        }
        if (remThld) ensureThresholds(parent, structureIdx, true, false);
        RegionPopulation.ContactBorderMask contact = new RegionPopulation.ContactBorderMask(2, parent.getMask(), RegionPopulation.Border.XYup);
        pop.getRegions().removeIf(r->(remContact ? !contact.keepObject(r):false) || (remThld ? valueFunction(parent.getPreFilteredImage(structureIdx)).apply(r)<=bckThld : false)); // remove regions with low intensity
        pop.relabel(true);
        return pop;
    }
    private static Predicate<Region> touchSides(ImageMask parentMask) {
        RegionPopulation.ContactBorderMask contactLeft = new RegionPopulation.ContactBorderMask(1, parentMask, RegionPopulation.Border.Xl);
        RegionPopulation.ContactBorderMask contactRight = new RegionPopulation.ContactBorderMask(1, parentMask, RegionPopulation.Border.Xr);
        return r-> {
            //double l = GeometricalMeasurements.maxThicknessY(r);
            int cLeft = contactLeft.getContact(r);
            if (cLeft>0) return true;
            int cRight = contactRight.getContact(r);
            return cRight>0;
        };
    }
    
    // hysteresis thresholding
    private RegionPopulation filterRegionsAfterEdgeDetectorHysteresis(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        // perform hysteresis thresholding : define foreground & background + merge indeterminate regions to the closest neighbor in therms of intensity
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        ensureThresholds(parent, structureIdx, true, true);
        Map<Region, Double> values = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(parent.getPreFilteredImage(structureIdx))));
        Predicate<Region> touchSides = touchSides(parent.getMask());
        Set<Region> backgroundL = pop.getRegions().stream().filter(r->values.get(r)<=bckThld || touchSides.test(r) ).collect(Collectors.toSet());
        if (foreThld==bckThld) { // simple thresholding
            pop.getRegions().removeAll(backgroundL);
            pop.relabel(true);
            return pop;
        }
        Set<Region> foregroundL = pop.getRegions().stream().filter(r->!backgroundL.contains(r) && values.get(r)>=foreThld).collect(Collectors.toSet());
        if (stores!=null) Plugin.logger.debug("min thld: {} max thld: {}, background: {}, foreground: {}, unknown: {}", bckThld, foreThld, backgroundL.size(), foregroundL.size(), pop.getRegions().size()-backgroundL.size()-foregroundL.size());
        if (pop.getRegions().size()>foregroundL.size()+backgroundL.size()) { // merge indeterminate regions with either background or foreground
            pop.getRegions().removeAll(backgroundL);
            pop.getRegions().removeAll(foregroundL);
            pop.getRegions().addAll(0, backgroundL); // so that background region keep same instance when merged with indeterminate region
            pop.getRegions().addAll(backgroundL.size(), foregroundL);
            pop.relabel(false);
            SplitAndMergeEdge sm = new SplitAndMergeEdge(edgeDetector.getWsMap(parent.getPreFilteredImage(structureIdx), parent.getMask()), parent.getPreFilteredImage(structureIdx), 1, false);
            sm.setInterfaceValue(i-> {
                if (i.getVoxels().isEmpty() || sm.isFusionForbidden(i)) {
                    return Double.NaN;
                } else {
                    int size = i.getVoxels().size()+i.getDuplicatedVoxels().size();
                    double val= ArrayUtil.quantile(Stream.concat(i.getVoxels().stream(), i.getDuplicatedVoxels().stream()).mapToDouble(v->sm.getWatershedMap().getPixel(v.x, v.y, v.z)).sorted(), size, 0.1);
                    return val;
                }
            });
            //if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(sm.drawInterfaceValues(pop).setName("Foreground detection: interface values (foreground fusion)"));
            //sm.setTestMode(imageDisp);
            //SplitAndMergeRegionCriterion sm = new SplitAndMergeRegionCriterion(null, parent.getPreFilteredImage(structureIdx), -Double.MIN_VALUE, SplitAndMergeRegionCriterion.InterfaceValue.ABSOLUTE_DIFF_MEDIAN_BTWN_REGIONS);
            sm.addForbidFusionForegroundBackground(r->backgroundL.contains(r), r->foregroundL.contains(r));
            /*sm.addForbidFusion(i->{
                int r1 = backgroundL.contains(i.getE1()) ? -1 : (foregroundL.contains(i.getE1()) ? 1 : 0);
                int r2 = backgroundL.contains(i.getE2()) ? -1 : (foregroundL.contains(i.getE2()) ? 1 : 0);
                return r1*r2!=0; // forbid merge if both are foreground or background
            });*/
            sm.merge(pop, sm.objectNumberLimitCondition(backgroundL.size()+foregroundL.size()));
            //if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(structureIdx)).setName("Foreground detection: region values after fusion (foreground fusion)"));
            pop.getRegions().removeAll(backgroundL);
        } else pop.getRegions().removeAll(backgroundL);
        pop.relabel(true);
        return pop;
    }
    @Override 
    protected RegionPopulation filterRegionsAfterSplitByHessian(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        return pop;
    }
    @Override
    protected RegionPopulation filterRegionsAfterMergeByHessian(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        return pop;
    }
    @Override
    protected RegionPopulation localThreshold(Image input, RegionPopulation pop, SegmentedObject parent, int structureIdx, boolean callFromSplit) {
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_IQR:
                Image smooth = smoothScale.getValue().doubleValue()>=1 ? ImageFeatures.gaussianSmooth(input, smoothScale.getValue().doubleValue(), false):input;
                pop.localThreshold(smooth, localThresholdFactor.getValue().doubleValue(), true, true);
                return pop;
            default:
                return pop;
        }
    }
    
    /**
     * Splits objects
     * @param parent
     * @param structureIdx
     * @param object
     * @return split objects in absolute landmark if {@param object} is in absolute landmark or in relative landmark to {@param parent}
     */
    @Override public RegionPopulation splitObject(SegmentedObject parent, int structureIdx, Region object) {
        Image input = parent.getPreFilteredImage(structureIdx);
        if (input==null) throw new IllegalArgumentException("No prefiltered image set");
        ImageInteger mask = object.isAbsoluteLandMark() ? object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox()) :object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox().resetOffset()); // extend mask to get the same size as the image
        if (splitAndMerge==null || !parent.equals(currentParent)) {
            currentParent = parent;
            splitAndMerge = initializeSplitAndMerge(parent, structureIdx,parent.getMask());
        }
        if (stores!=null) splitAndMerge.setTestMode(TestableProcessingPlugin.getAddTestImageConsumer(stores, parent));
        RegionPopulation res = splitAndMerge.splitAndMerge(mask, MIN_SIZE_PROPAGATION, splitAndMerge.objectNumberLimitCondition(2));
        res.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);
        //res =  localThreshold(input, res, parent, structureIdx, true); 
        if (object.isAbsoluteLandMark()) res.translate(parent.getBounds(), true);
        if (res.getRegions().size()>2) RegionCluster.mergeUntil(res, 2, 0); // merge most connected until 2 objects remain
        return res;
    }

    // apply to segmenter from whole track information (will be set prior to call any other methods)
    
    @Override
    public TrackConfigurable.TrackConfigurer<BacteriaFluo> run(int structureIdx, List<SegmentedObject> parentTrack) {
        if (parentTrack.get(0).getRawImage(structureIdx)==parentTrack.get(0).getPreFilteredImage(structureIdx)) { // no prefilter -> perform on root
            Plugin.logger.debug("no prefilters detected: global mean & sigma on root track");
            double[] ms = getRootBckMeanAndSigma(parentTrack, structureIdx, null);
            this.globalBackgroundLevel = ms[0];
            this.globalBackgroundSigma = ms[1];
        } else { // prefilters -> perform on parent track
            Plugin.logger.debug("prefilters detected: global mean & sigma on parent track");
            Map<Image, ImageMask> imageMapMask = parentTrack.stream().collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() )); 
            // Background fit on parent track doesn't necessarily
            /*
            Histogram histo = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
            double[] ms = new double[2];
            BackgroundFit.backgroundFit(histo, 5, ms);
            this.globalBackgroundLevel = ms[0];
            this.globalBackgroundSigma = ms[1];
            */
            DoubleStream pixStream = Image.stream(imageMapMask, true);
            this.globalBackgroundLevel = pixStream.min().orElse(0);
            this.globalBackgroundSigma = 1;
        } 
        
        Set<SegmentedObject> voidMC = getVoidMicrochannels(structureIdx, parentTrack);
        double[] thlds = getTrackThresholds(parentTrack, structureIdx, voidMC);
        return (p, s) -> {
            if (voidMC.contains(p)) s.isVoid=true; 
            s.bckThld=thlds[0];
            s.foreThld = thlds[1];
            s.globalBackgroundLevel = globalBackgroundLevel;
            s.globalBackgroundSigma = globalBackgroundSigma;
        };
    }
    
    protected double[] getTrackThresholds(List<SegmentedObject> parentTrack, int structureIdx, Set<SegmentedObject> voidMC) {
        if (voidMC.size()==parentTrack.size()) return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        Histogram[] histoRoot=new Histogram[1], histoParent=new Histogram[1];
        Supplier<Histogram> getHistoParent = () -> {
            if (histoParent[0]==null) { 
                Map<Image, ImageMask> imageMapMask = parentTrack.stream().filter(p->!voidMC.contains(p)).collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() )); 
                histoParent[0] = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
            }
            return histoParent[0];
        };
        boolean needToComputeGlobalMin = !THRESHOLD_COMPUTATION.CURRENT_FRAME.equals(bckThresholdMethod) && (foregroundSelectionMethod.getSelectedIndex()!=2 || backgroundSel.getSelectedIndex()>0);
        boolean needToComputeGlobalMax = this.foregroundSelectionMethod.getSelectedIndex()==1 && !THRESHOLD_COMPUTATION.CURRENT_FRAME.equals(foreThresholdMethod.getSelectedEnum());
        if (!needToComputeGlobalMin && !needToComputeGlobalMax) return new double[]{Double.NaN, Double.NaN};
        double bckThld = Double.NaN, foreThld = Double.NaN;
        if (needToComputeGlobalMin) {
            ThresholderHisto thlder = bckThresholder.instanciatePlugin();
            if (THRESHOLD_COMPUTATION.ROOT_TRACK.equals(bckThresholdMethod)) { // root threshold
                if (thlder instanceof BackgroundFit) {
                    double[] ms = getRootBckMeanAndSigma(parentTrack, structureIdx, histoRoot);
                    bckThld = ms[0] + ((BackgroundFit)thlder).getSigmaFactor() * ms[1];
                } else bckThld = getRootThreshold(parentTrack, structureIdx, histoRoot, true);
            } else bckThld = thlder.runThresholderHisto(getHistoParent.get());  // parent threshold
        }
        if (needToComputeGlobalMax) { // global threshold on this track
            ThresholderHisto thlder = foreThresholder.instanciatePlugin();
            if (THRESHOLD_COMPUTATION.ROOT_TRACK.equals(foreThresholdMethod.getSelectedEnum())) { // root threshold
                if (thlder instanceof BackgroundFit) {
                    double[] ms = getRootBckMeanAndSigma(parentTrack, structureIdx, histoRoot);
                    foreThld = ms[0] + ((BackgroundFit)thlder).getSigmaFactor() * ms[1];
                } else foreThld = getRootThreshold(parentTrack, structureIdx, histoRoot, false);
            } else foreThld = thlder.runThresholderHisto(getHistoParent.get());  // parent threshold
        } 
        Plugin.logger.debug("parent: {} global threshold on images with foreground: [{};{}]", parentTrack.get(0), bckThld, foreThld);
        return new double[]{bckThld, foreThld}; 
    }
    
    @Override
    protected double getGlobalThreshold(List<SegmentedObject> parent, int structureIdx) {
        return globalBackgroundLevel + 5 * globalBackgroundSigma;
    }
    private double getRootThreshold(List<SegmentedObject> parents, int structureIdx, Histogram[] histoStore, boolean min) {
        // particular case si BackgroundFit -> call
        String key = (min ? bckThresholder.toJSONEntry().toJSONString() : foreThresholder.toJSONEntry().toJSONString())+"_"+structureIdx;
        if (parents.get(0).getRoot().getAttributes().containsKey(key)) {
            return parents.get(0).getRoot().getAttribute(key, Double.NaN);
        } else {
            synchronized(parents.get(0).getRoot()) {
                if (parents.get(0).getRoot().getAttributes().containsKey(key)) {
                    return parents.get(0).getRoot().getAttribute(key, Double.NaN);
                } else {
                    List<Image> im = parents.stream().map(p->p.getRoot()).map(p->p.getRawImage(structureIdx)).collect(Collectors.toList());
                    ThresholderHisto thlder = (min ? bckThresholder:foreThresholder).instanciatePlugin();
                    Histogram histo;
                    if (histoStore!=null && histoStore[0]!=null ) histo = histoStore[0];
                    else {
                        histo = HistogramFactory.getHistogram(()->Image.stream(im).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS) ;
                        if (histoStore!=null) histoStore[0] = histo;
                    }
                    double thld = thlder.runThresholderHisto(histo);
                    parents.get(0).getRoot().setAttribute(key, thld);
                    Plugin.logger.debug("computing thld: {} on root: {} -> {}", key, parents.get(0).getRoot(), thld);
                    return thld;
                }
            }
        }
    }
    
    private static double[] getRootBckMeanAndSigma(List<SegmentedObject> parents, int structureIdx, Histogram[] histoStore) {
        String meanK = "backgroundMean_"+structureIdx;
        String stdK = "backgroundStd_"+structureIdx;
        if (parents.get(0).getRoot().getAttributes().containsKey(meanK)) {
            Plugin.logger.debug("found on root {} mean {}, sigma: {}", parents.get(0), parents.get(0).getRoot().getAttribute(meanK, 0d),parents.get(0).getRoot().getAttribute(stdK, 1d));
            return new double[]{parents.get(0).getRoot().getAttribute(meanK, 0d), parents.get(0).getRoot().getAttribute(stdK, 1d)};
        } else {
            synchronized(parents.get(0).getRoot()) {
                if (parents.get(0).getRoot().getAttributes().containsKey(meanK)) {
                    return new double[]{parents.get(0).getRoot().getAttribute(meanK, 0d), parents.get(0).getRoot().getAttribute(stdK, 1d)};
                } else {
                    Histogram histo;
                    if (histoStore!=null && histoStore[0]!=null) histo = histoStore[0];
                    else {
                        List<Image> im = parents.stream().map(p->p.getRoot()).map(p->p.getRawImage(structureIdx)).collect(Collectors.toList());
                        histo = HistogramFactory.getHistogram(()->Image.stream(im).parallel(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
                        if (histoStore!=null) histoStore[0] = histo;
                    }
                    double[] ms = new double[2];
                    BackgroundFit.backgroundFit(histo, 10, ms);
                    parents.get(0).getRoot().setAttribute(meanK, ms[0]);
                    parents.get(0).getRoot().setAttribute(stdK, ms[1]);
                    Plugin.logger.debug("compute root {} mean {}, sigma: {}", parents.get(0), ms[0], ms[1]);
                    return ms;
                }
            }
        }
    }
    @Override
    protected void displayAttributes() {
        Core.userLog("Background Threshold: "+this.bckThld);
        Plugin.logger.info("Background Threshold: "+this.bckThld);
        Core.userLog("Foreground Threshold: "+this.foreThld);
        Plugin.logger.info("Foreground Threshold: {}", foreThld);
        Core.userLog("Background Mean: "+this.globalBackgroundLevel+" Sigma: "+globalBackgroundSigma);
        Plugin.logger.info("Background Mean: {} Sigma: {}", globalBackgroundLevel, globalBackgroundSigma);
    }
}
