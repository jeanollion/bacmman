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
import bacmman.plugins.HintSimple;
import bacmman.processing.ImageFeatures;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.processing.split_merge.SplitAndMergeHessian;
import bacmman.processing.split_merge.SplitAndMergeRegionCriterion;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.ThresholderHisto;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.plugins.plugins.pre_filters.StandardDeviation;
import static bacmman.plugins.plugins.segmenters.EdgeDetector.valueFunction;

import ij.process.AutoThresholder;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Bacteria segmentation within microchannels, for phase-contrast images
 * @author Jean Ollion
 */
public class BacteriaPhaseContrast extends BacteriaIntensitySegmenter<BacteriaPhaseContrast> implements HintSimple {
    public enum CONTOUR_ADJUSTMENT_METHOD {LOCAL_THLD_W_EDGE}
    PluginParameter<ThresholderHisto> foreThresholder = new PluginParameter<>("Threshold", ThresholderHisto.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setHint("Threshold for foreground region selection (step 2). Computed on the whole parent track (all frames). <br /><em>Configuration Hint</em>: Refer to the image <em>Foreground mask</em>: background but not foreground regions should have been erased");

    BooleanParameter filterBorderArtifacts = new BooleanParameter("Filter border Artifacts", true).setHint("In some phase-contrast images, an important intensity gradient is present at border of microchannels, and thus lead to false-positive segmentation. If this option is set to true, thin objects touching sides of microchannels will be removed");
    BooleanParameter upperCellCorrection = new BooleanParameter("Upper Cell Correction", true).setHint("In some experimental setups, cell poles touching the closed-end of microchannel have significant lower intensity than the rest of the cell.<br />If true: when the upper cell is touching the top of the microchannel, a different local threshold factor is applied to the upper half of the cell.");
    NumberParameter dilateRadius = new BoundedNumberParameter("Dilate Radius", 0, 0, 0, null).setHint("Dilatation applied to cell region before local thresholding");
    NumberParameter upperCellLocalThresholdFactor = new BoundedNumberParameter("Upper cell local threshold factor", 2, 1.5, 0, null).setHint("Local Threshold factor applied to the upper part of the cell");
    NumberParameter maxYCoordinate = new BoundedNumberParameter("Max yMin coordinate of upper cell", 0, 5, 0, null).setHint("When distance between cell pole and closed-end of microchannels is under this value, the cell pole is considered to be touching the microchannel end.");
    ConditionalParameter upperCellCorrectionCond = new ConditionalParameter(upperCellCorrection).setActionParameters("true", upperCellLocalThresholdFactor, maxYCoordinate);
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethod = new EnumChoiceParameter<>("Contour Adjustment", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE, true).setEmphasized(true).setHint("Method for contour adjustment after segmentation (step 4)").setSimpleHint("Method for contour adjustment after segmentation");
    BooleanParameter adjustContoursOnRaw = new BooleanParameter("Adjust contours on raw signal", true).setHint("If false, local threshold step is performed on pre-filtered images, on the contrary it is performed on the raw images after a smoothing step");
    ConditionalParameter adjustContoursOnRawCond = new ConditionalParameter(adjustContoursOnRaw).setActionParameters("true", smoothScale);
    ConditionalParameter contourAdjustmentCond = new ConditionalParameter(contourAdjustmentMethod).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE.toString(), localThresholdFactor, adjustContoursOnRawCond, upperCellCorrectionCond);
    NumberParameter minSize = new BoundedNumberParameter("Minimum Region Size", 0, 200, 10, null).setHint("Minimum Object Size in voxels. <br />After split and merge using hessian: regions under this size will be merged by the adjacent region that has the lowest interface value (as defined in the description of the <em>Interface value</em> parameter), and if this interface value is under 2 * <em>Split Threshold</em>");
    enum SPLIT_METHOD {MIN_WIDTH, HESSIAN};
    EnumChoiceParameter<SPLIT_METHOD> splitMethod = new EnumChoiceParameter<>("Split method", SPLIT_METHOD.values(), SPLIT_METHOD.HESSIAN, false).setHint("Method for splitting objects (used during manual correction or by a tracker able to perform local correction): <ol><li>MIN_WIDTH: splits at the interface of minimal width.</li><li>HESSIAN: splits at the interface of maximal hessian value</li></ol>");

    enum INTERFACE_VALUE {MEAN_HESS_DIV_MEAN_INTENSITY, NORMED_HESS}
    EnumChoiceParameter<INTERFACE_VALUE> interfaceValue = new EnumChoiceParameter<>("Interface Value", INTERFACE_VALUE.values(), INTERFACE_VALUE.MEAN_HESS_DIV_MEAN_INTENSITY, false).setHint("Split/Merge criterion used in step 3:<br />"
            +"The term <em>Interface</em> refers to the contact area between two regions. <br />"
            +"<em>mean(H)@Inter</em> refers to the mean hessian value at the interface<br />"
            +"<em>mean(PF)@Regions</em> refers to the mean value of the pre-filtered input image within the two regions in contact"
            +"<em>BCK</em> refers to the estimation of background level, computed as the mean of pre-filtered images with value under Ostu's threshold on the whole microchannel track"
            +"<ol><li>MEAN_HESS_DIV_MEAN_INTENSITY: mean(H)@Inter /  ( mean(PF)@Regions - BCK )</li>"
            +"<li>NORMED_HESS: mean(H)@Inter / ( mean(PF)@Inter - BCK )</li></ol>"
            +"<br /><em>Configuration hint</em>: the value of the interface is displayed in the <em>Interface Values before merge by Hessian</em> image in test mode. Interface value should be as high as possible between cells and as low as possible within cells");

    // attributes parametrized during track parametrization
    double lowerThld = Double.NaN, upperThld = Double.NaN, filterThld=Double.NaN;

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, edgeMap, foreThresholder, filterBorderArtifacts, hessianScale, interfaceValue, splitThreshold, minSize , contourAdjustmentCond, splitMethod};
    }
    public BacteriaPhaseContrast() {
        super();
        this.splitThreshold.setHint("Threshold used for merging partition after splitting foreground on hessian map. Criterion is defined by the <em/>Interface Value</em> parameter. <br />A lower value will lead to more separation of bacteria. <br /><br />Configuration Hint: Tune the value using intermediate image <em>Interface Values before merge by Hessian</em>, interface with a value over this threshold will not be merged<br />This parameter should be carefully calibrated for each experimental setup");
        this.splitThreshold.setValue(0.115); // 0.15 for hessian scale = 3
        this.hessianScale.setValue(2);
        this.edgeMap.removeAll().add(new StandardDeviation(3).setMedianRadius(2));
        localThresholdFactor.setHint("Factor defining the local threshold. <br />Lower value of this factor will yield in smaller cells.<br />This threshold should be calibrated for each new experimental setup. <br />Threshold = mean_w - sigma_w * (this factor), <br />with mean_w = weighted mean of signal weighted by edge image, sigma_w = standard deviation of signal weighted by edge image. <br />Refer to images: <em>Local Threshold edge map</em> and <em>Local Threshold intensity map</em><br /><b>This threshold should be calibrated for each new experimental setup</b>");
        localThresholdFactor.setValue(1);
    }
    public BacteriaPhaseContrast setMinSize(int minSize) {
        this.minSize.setValue(minSize);
        return this;
    }
    @Override public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        if (isVoid) return null;
        RegionPopulation pop = super.runSegmenter(input, objectClassIdx, parent);
        return filterBorderArtifacts(parent, objectClassIdx, pop);
    }
    final private static String toolTip = "<b>Bacteria segmentation within microchannels</b><br />"
            + "This algorithm is designed to work on inverted (foreground is bright) and normalized phase-contrast images, and usually filtered with the <em>SubtractBackgroundMicrochannels</em> Track pre-filter<br />"
            + "Main steps of the algorithm:"
            + "<ol><li>Partitioning of the image using watershed transform on the edge map (see <em>Edge Map</em> parameter)</li>"
            + "<li>Generation of the foreground mask. To create this mask background partitions are removed, based on comparison of their median intensity with the threshold defined by the <em>Threshold parameter</em>. Optionally, thin partitions located at the border of microchannels are removed (See <em>Filter border Artifacts</em> parameter).</li>"
            + "<li>Split/Merge strategy to separate touching cells: the foreground mask is split by applying a watershed transform on the Hessian transform. Partitions are then merged using a criterion defined by the <em>Interface value</em> parameter</li>"
            + "<li>Contour of bacteria is adjusted using a threshold computed for each bacterium. The threshold value is described in the <em>local threshold factor</em> parameter</li></ol>"
            + "Intermediate images are displayed in test mode for each of the steps described above. In order to display the different partitions after a partitioning step, we use an image displaying the median intensity value of each partition, referred to as MIP"
            + "<ul><li><em>Edge Map for partitioning</em>: image of edges used for watershed partitioning (<em>used in step 1</em>)</li> " +
            "<li><em>Region values after partitioning</em>: MIP after partitioning step (<em>used in step 1</em>). Importantly, on this image regions should be either located in the foreground or in the background, but should not overlap both areas</li>"
            + "<li><em>Foreground mask</em>: binary mask obtained after the filtering step (<em>used in step 2</em>)</li>"
            + "<li><em>Hessian</em>: maximal Eigenvalue of the Hessian matrix used for the partitioning of the foreground mask in order to separate the cells. Pixel intensity should be as high as possible at the interface between touching cells and as low as possible within cells (<em>used in step 3</em>)</li> " +
            " <li><em>Region values before merge by hessian</em>: MIP after partitioning the foreground mask generated in step 2 (see <em>Foreground mask</em> image) using the <em>Hessian</em> image for the watershed algorithm (<em>see step 3</em>)</li> " +
            " <li><em>Interface values before merge by hessian</em>: Each segment represents the area of contact between two partitions (referred to as interface) of the <em>Region values before merge by hessian</em> image. The intensity of such a segment is compared to the <em>Split Threshold</em> Parameter for split/merge decisions. Interface values should be as high as possible between cells and as low as possible within cells (<em>used in step 3</em>)</li> " +
            " <li><em>Region values after merge by hessian</em>: MIP after merging using the Split/Merge criterion (<em>used in step 3</em>)</li>"
            + "<li><em>Local Threshold intensity map</em> & <em>Local threshold edge map</em>: images used for contour adjustment (see description of <em>Local Threshold Factor</em> parameter)(<em>used in step 4</em>) </li></ul>";

    final private static String toolTipSimple = "<b>Bacteria segmentation within microchannels</b><br />"
            + "This algorithm is designed to work on inverted (foreground is bright) and normalized phase-contrast images, and usually filtered with the Track-pre-filter: <em>SubtractBackgroundMicrochannels</em><br />"
            + "<br />Intermediate images displayed in test mode:"
            + "<ul>"
            + "<li><em>Hessian</em>: max Eigenvalue of the hessian matrix used for the partitioning of the foreground mask in order to separate cells. Its intensity should be as high as possible at the interface between touching cells and as low as possible within cells</li> "
            + " <li><em>Interface values before merge by hessian</em>: Each segment represents the area of contact between two partitions (referred to as interface) and its value is the criterion, to be compared with the parameter <em>Split Threshold</em>. Interface values should be as high as possible between cells and as low as possible within cells</li> "
            + "</ul>";


    @Override public String getHintText() {return toolTip;}

    @Override public String getSimpleHintText() { return toolTipSimple; }

    @Override public SplitAndMergeHessian initializeSplitAndMerge(SegmentedObject parent, int structureIdx, ImageMask foregroundMask) {
        SplitAndMergeHessian sam = super.initializeSplitAndMerge(parent, structureIdx, foregroundMask);
        Image input = parent.getPreFilteredImage(structureIdx);
        setInterfaceValue(input, sam);
        return sam;
    }
    private void setInterfaceValue(Image input, SplitAndMergeHessian sam) {
        switch (interfaceValue.getSelectedEnum()) {
            case MEAN_HESS_DIV_MEAN_INTENSITY:
            default:
                sam.setInterfaceValue(i-> {
                    Collection<Voxel> voxels = i.getVoxels();
                    if (voxels.isEmpty()) return Double.NaN;
                    else {
                        // normalize using mean value
                        double mean = Stream.concat(i.getE1().getVoxels().stream(), i.getE2().getVoxels().stream()).mapToDouble(v->(double)input.getPixel(v.x, v.y, v.z)).average().getAsDouble()-globalBackgroundLevel;
                        if (mean<=0) return Double.NaN;
                        Image hessian = sam.getHessian();
                        double val  =  voxels.stream().mapToDouble(v->hessian.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                        val/=mean;
                        return val;
                    }
                });
            break;
            case NORMED_HESS:
                if (Double.isNaN(lowerThld)) throw new RuntimeException("Segmenter not parametrized");
                sam.setInterfaceValue(i-> {
                    Collection<Voxel> voxels = i.getVoxels();
                    if (voxels.isEmpty()) return Double.NaN;
                    else {
                        Image hessian = sam.getHessian();
                        double intensitySum = voxels.stream().mapToDouble(v->input.getPixel(v.x, v.y, v.z)-globalBackgroundLevel).sum(); // was lower threshold
                        if (intensitySum<=0) return Double.NaN;
                        double hessSum = voxels.stream().mapToDouble(v->hessian.getPixel(v.x, v.y, v.z)).sum();
                        return hessSum / intensitySum;
                    }
                });
            break;
        }

    }
    
    @Override
    protected EdgeDetector initEdgeDetector(SegmentedObject parent, int structureIdx) {
        EdgeDetector seg = super.initEdgeDetector(parent, structureIdx);
        seg.minSizePropagation.setValue(0);
        seg.seedRadius.setValue(1.5);
        return seg;
    }
    @Override 
    protected RegionPopulation filterRegionsAfterSplitByHessian(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        return pop;
        //return filterBorderArtifacts(parent, structureIdx, pop);
    }
    @Override
    protected RegionPopulation filterRegionsAfterMergeByHessian(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        if (stores!=null && verbosePlus) stores.get(parent).addIntermediateImage("Region Values after merge by hessian and before filter", EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(structureIdx)).setName("Region Values before merge by Hessian"));
        if (pop.getRegions().isEmpty()) return pop;        
        // filter low value regions
        if (!Double.isNaN(filterThld)) {
            Map<Region, Double> values = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(parent.getPreFilteredImage(structureIdx))));
            pop.filter(r->values.get(r)>=filterThld);
            if (pop.getRegions().isEmpty()) return pop;        
        }
        // merge small objects with the object with the smallest edge
        int minSize = this.minSize.getValue().intValue();
        SplitAndMergeHessian sm= new SplitAndMergeHessian(parent.getPreFilteredImage(structureIdx), splitThreshold.getValue().doubleValue()*2, hessianScale.getValue().doubleValue(), globalBackgroundLevel);
        sm.setHessian(splitAndMerge.getHessian());
        sm.addForbidFusion(i -> i.getE1().size()>minSize && i.getE2().size()>minSize);
        setInterfaceValue(parent.getPreFilteredImage(structureIdx), sm);
        sm.merge(pop, null);

        // merge objects that are cut along Y-axis
        // if contact length is above 50% of min Y length -> merge objects
        sm.setInterfaceValue(i -> {
            double l  = i.getVoxels().stream().mapToDouble(v->v.y).max().getAsDouble()- i.getVoxels().stream().mapToDouble(v->v.y).min().getAsDouble();
             //CleanVoxelLine.cleanSkeleton(e1Vox); could be used to get more accurate result
            return 1 -l/Math.min(i.getE1().getBounds().sizeY(), i.getE2().getBounds().sizeY()); // 1 - contact% because S&M used inverted criterion: small are merged
        }).setThreshold(0.5).setForbidFusion(null);
        sm.merge(pop, null);
        return pop;
    }
    public static boolean verbosePlus=false;
    @Override
    protected RegionPopulation filterRegionsAfterEdgeDetector(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        // TODO: filter artifacts according to their hessian value
        if (pop.getRegions().isEmpty()) return pop;
        Map<Region, Double> values = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(parent.getPreFilteredImage(structureIdx))));
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        if (Double.isNaN(upperThld)) throw new RuntimeException("Upper Threshold not computed");
        if (Double.isNaN(lowerThld)) throw new RuntimeException("Lower Threshold not computed");
        
        // define 3 categories: background / forground / unknown
        // foreground -> high intensity, foreground -> low intensity 
        Function<Region, Integer> artifactFunc = getFilterBorderArtifacts(parent, structureIdx);
        if (stores!=null && verbosePlus) {
            Map<Region, Double> valuesArt = pop.getRegions().stream().collect(Collectors.toMap(r->r, r->artifactFunc.apply(r)+2d));
            imageDisp.accept(EdgeDetector.generateRegionValueMap(parent.getPreFilteredImage(structureIdx), valuesArt).setName("artifact map: 1 = artifact / 2 = unknown / 3 = not artifact"));
            //imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, this.splitAndMerge.getHessian()).setName("hessian"));
            imageDisp.accept(pop.getLabelMap().duplicate("region before artifact filter"));
        }
        Set<Region> backgroundL = pop.getRegions().stream().filter(r->values.get(r)<lowerThld || artifactFunc.apply(r)==-1).collect(Collectors.toSet());
        Set<Region> foregroundL = pop.getRegions().stream().filter(r->values.get(r)>upperThld && artifactFunc.apply(r)==1).collect(Collectors.toSet());
        if (foregroundL.isEmpty()) {
            pop.getRegions().clear();
            pop.relabel(true);
            return pop;
        }
        pop.getRegions().removeAll(backgroundL);
        boolean relabeled = false;
        if (pop.getRegions().size()>foregroundL.size()) { // merge indetermined regions if their intensity is higher than foreground neighbor
            pop.getRegions().removeAll(foregroundL);
            pop.getRegions().addAll(0, foregroundL);
            pop.relabel(true);
            if (stores!=null&&verbosePlus) imageDisp.accept(pop.getLabelMap().duplicate("before merge undetermined regions with foreground"));
            SplitAndMergeRegionCriterion sm = new SplitAndMergeRegionCriterion(null, parent.getPreFilteredImage(structureIdx), -Double.MIN_VALUE, SplitAndMergeRegionCriterion.InterfaceValue.ABSOLUTE_DIFF_MEDIAN_BTWN_REGIONS);
            sm.addForbidFusion(i->foregroundL.contains(i.getE1())==foregroundL.contains(i.getE2()));
            sm.merge(pop, null);
            if (stores!=null&&verbosePlus ) imageDisp.accept(pop.getLabelMap().duplicate("after merge undetermined regions with foreground"));
            relabeled= true;
        }
        if (pop.getRegions().size()>foregroundL.size()) { // there are still undetermined regions
            pop.getRegions().removeAll(foregroundL);
            Region background = Region.merge(backgroundL);
            if (background!=null) {
                Region foreground = Region.merge(foregroundL);
                pop.getRegions().add(0, background); // fixed index so that same instance is conserved when merged
                pop.getRegions().add(1, foreground); // fixed index so that same instance is conserved when merged
                pop.relabel(false);
                if (stores!=null) {
                    /*pop.getRegions().removeAll(backgroundL);
                    pop.getRegions().addAll(0, backgroundL);
                    pop.getRegions().removeAll(foregroundL);
                    pop.getRegions().addAll(backgroundL.size(), foregroundL);
                    pop.relabel(false);*/
                    if (verbosePlus) imageDisp.accept(pop.getLabelMap().duplicate("After fore & back fusion"));
                }
                SplitAndMergeEdge sm = new SplitAndMergeEdge(edgeDetector.getWsMap(parent.getPreFilteredImage(structureIdx), parent.getMask()), parent.getPreFilteredImage(structureIdx), 1, false);
                sm.setInterfaceValue(0.1, false);
                //SplitAndMergeRegionCriterion sm = new SplitAndMergeRegionCriterion(null, parent.getPreFilteredImage(structureIdx), Double.POSITIVE_INFINITY, SplitAndMergeRegionCriterion.InterfaceValue.DIFF_MEDIAN_BTWN_REGIONS);
                sm.allowMergeWithBackground(parent.getMask()); // helps to remove artifacts on the side but can remove head of mother cell
                sm.addForbidFusionForegroundBackground(r->r==background, r->r==foreground);
                //sm.addForbidFusionForegroundBackground(r->backgroundL.contains(r), r->foregroundL.contains(r));
                if (verbosePlus) sm.setTestMode(imageDisp);
                sm.merge(pop, sm.objectNumberLimitCondition(2)); // merge intertermined until 2 categories in the image
                pop.getRegions().remove(background);
                //pop.getRegions().removeAll(backgroundL);
                pop.relabel(true);
                relabeled = true;
            }
        }
        if (!relabeled) pop.relabel(true);
        
        if (stores!=null && verbosePlus) imageDisp.accept(pop.getLabelMap().duplicate("after fore & back & intertermined fusion"));
        if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(structureIdx)).setName("Foreground mask"));
        return pop;
    }
    /**
     * See {@link #getFilterBorderArtifacts(SegmentedObject, int) }
     * @param parent
     * @param structureIdx
     * @param pop
     * @return 
     */
    protected RegionPopulation filterBorderArtifacts(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        Function<Region, Integer> artifactFunc  = getFilterBorderArtifacts(parent, structureIdx);
        pop.filter(r->artifactFunc.apply(r)>=0 && (splitAndMerge.getMedianValues().getAndCreateIfNecessary(r)>lowerThld));
        return pop;
    }
    /**
     * Border Artifacts criterion
     * 1) a criterion on contact on sides and low X-thickness for side artifacts,
     * 2) a criterion on contact with closed-end of microchannel and local-thickness
     * @param parent
     * @param structureIdx
     * @return function that return 1 if the object is not a border artifact, -1 if it is one, 0 if it is not known
     */
    protected Function<Region, Integer> getFilterBorderArtifacts(SegmentedObject parent, int structureIdx) {
        if (!filterBorderArtifacts.getSelected()) return r->1;
        boolean verbose = stores!=null;
        // filter border artifacts: thin objects in X direction, contact on one side of the image
        RegionPopulation.ContactBorderMask contactLeft = new RegionPopulation.ContactBorderMask(1, parent.getMask(), RegionPopulation.Border.Xl);
        RegionPopulation.ContactBorderMask contactRight = new RegionPopulation.ContactBorderMask(1, parent.getMask(), RegionPopulation.Border.Xr);
        RegionPopulation.ContactBorderMask contactUp = new RegionPopulation.ContactBorderMask(1, parent.getMask(), RegionPopulation.Border.YUp);
        double thicknessLimitKeep = parent.getMask().sizeX() * 0.5;  // OVER this thickness objects are always kept
        double thicknessLimitRemove = Math.max(4, parent.getMask().sizeX() * 0.25); // UNDER THIS VALUE other might be artifacts
        Function<Region, Integer> f1 = r->{
            int cL = contactLeft.getContact(r);
            int cR = contactRight.getContact(r);
            if (cL==0 && cR ==0) return 1;
            int cUp = contactUp.getContact(r);
            int c = Math.max(cL, cR);
            if (cUp>c) return 1;
            double thick = GeometricalMeasurements.maxThicknessX(r);
            if (thick>thicknessLimitKeep) return 1;
            double thickY = GeometricalMeasurements.maxThicknessY(r);
            if (verbose) logger.debug("R: {} artifact: thick: {}/{} (mean: {}) contact: {}/{} ", r.getLabel(), thick, thicknessLimitRemove, GeometricalMeasurements.meanThicknessX(r), c, thickY);
            if (c < thickY*0.5) return 1; // contact with either L or right should be enough
            if (thick<=thicknessLimitRemove && c>thickY*0.9) return -1; // thin objects stuck to the border 
            return 0;
            //return false;
            //return BasicMeasurements.getQuantileValue(r, intensity, 0.5)[0]>globThld; // avoid removing foreground
        };
        
        RegionPopulation.ContactBorderMask contactUpLR = new RegionPopulation.ContactBorderMask(1, parent.getMask(), RegionPopulation.Border.XYup);
        // remove the artifact at the top of the channel
        Function<Region, Integer> f2 = r->{
            int cUp = contactUp.getContact(r); // consider only objects in contact with the top of the parent mask
            if (cUp<=2) return 1;
            cUp = contactUpLR.getContact(r);
            if (verbose) logger.debug("R: {} upper artifact: contact: {}/{}", r.getLabel(), cUp, r.getVoxels().size());
            if (cUp<r.getVoxels().size()/12) return 1;
            double thickness = GeometricalMeasurements.getThickness(r);
            if (verbose) logger.debug("R: {} upper artifact: thickness: {}/{}", r.getLabel(), thickness, thicknessLimitRemove);
            if (thickness<thicknessLimitRemove) return -1;
            if (thickness>=thicknessLimitKeep) return 1;
            return 0;
        };
        return r->{
            int r1 = f1.apply(r);
            if (r1==-1 || r1==0) return r1;
            return f2.apply(r);
        };
    }
    
    @Override
    protected RegionPopulation localThreshold(Image input, RegionPopulation pop, SegmentedObject parent, int structureIdx, boolean callFromSplit) {
        if (pop.getRegions().isEmpty()) return pop;
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                double dilRadius = callFromSplit ? 0 : dilateRadius.getValue().doubleValue();
                Image smooth, edgeMap;
                if (adjustContoursOnRaw.getSelected()) {
                    smooth = smoothScale.getValue().doubleValue() < 1 ? parent.getRawImage(structureIdx) : ImageFeatures.gaussianSmooth(parent.getRawImage(structureIdx), smoothScale.getValue().doubleValue(), false);
                    edgeMap = StandardDeviation.filter(parent.getRawImage(structureIdx), parent.getMask(), 3, 1, smoothScale.getValue().doubleValue(), 1, false);
                } else {
                    smooth = input;
                    edgeMap = this.edgeDetector.getWsMap(input, parent.getMask());
                }
                Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
                if (imageDisp != null && stores.get(parent).isExpertMode()) { //| (callFromSplit && splitVerbose)
                    imageDisp.accept(smooth.setName("Local Threshold intensity map"));
                    imageDisp.accept(edgeMap.setName("Local Threshold edge map"));
                }
                ImageMask mask = parent.getMask();
                // different local threshold for middle part of upper cell when touches borders
                boolean differentialLF = false;
                if (upperCellCorrection.getSelected()) {
                    Region upperCell = pop.getRegions().stream().min(Comparator.comparingInt(r -> r.getBounds().yMin())).get();
                    if (upperCell.getBounds().yMin() <= maxYCoordinate.getValue().intValue()) {
                        differentialLF = true;
                        double yLim = upperCell.getGeomCenter(false).get(1) + upperCell.getBounds().sizeY() / 3.0;
                        pop.localThresholdEdges(smooth, edgeMap, localThresholdFactor.getValue().doubleValue(), false, false, dilRadius, mask, v -> v.y < yLim); // local threshold for lower cells & half lower part of cell
                        if (stores != null) { //|| (callFromSplit && splitVerbose)
                            logger.debug("y lim: {}", yLim);
                        }
                        pop.localThresholdEdges(smooth, edgeMap, upperCellLocalThresholdFactor.getValue().doubleValue(), false, false, dilRadius, mask, v -> v.y > yLim); // local threshold for half upper part of 1st cell
                    }
                }
                if (!differentialLF) pop.localThresholdEdges(smooth, edgeMap, localThresholdFactor.getValue().doubleValue(), false, false, dilRadius, mask, null);
                pop.smoothRegions(2, true, mask);
                return pop;
            default:
                return pop;
        }
    }
    
    @Override public RegionPopulation splitObject(SegmentedObject parent, int structureIdx, Region object) {
        Image input = parent.getPreFilteredImage(structureIdx);
        if (input==null) throw new IllegalArgumentException("No prefiltered image set");
        ImageInteger mask = object.isAbsoluteLandMark() ? object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox()) :object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox().resetOffset()); // extend mask to get the same size as the image
        if (splitAndMerge==null || !parent.equals(currentParent)) {
            currentParent = parent;
            splitAndMerge = initializeSplitAndMerge(parent, structureIdx,parent.getMask());
        }
        if (splitVerbose) splitAndMerge.setTestMode(i->Core.showImage(i));
        if (splitMethod.getSelectedEnum().equals(SPLIT_METHOD.MIN_WIDTH)) splitAndMerge.setInterfaceValue(i->-(double)i.getVoxels().size()); // algorithm:  split  @ smallest interface
        RegionPopulation res = splitAndMerge.splitAndMerge(mask, MIN_SIZE_PROPAGATION, splitAndMerge.objectNumberLimitCondition(2));
        setInterfaceValue(input, splitAndMerge); // for interface value computation
        //res =  localThreshold(input, res, parent, structureIdx, true); 
        if (object.isAbsoluteLandMark()) res.translate(parent.getBounds(), true);
        if (res.getRegions().size()>2) RegionCluster.mergeUntil(res, 2, 0); // merge most connected until 2 objects remain
        res.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);
        return res;
    }
    // track parametrization
    @Override
    public TrackConfigurer<BacteriaPhaseContrast> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Set<SegmentedObject> voidMC = getVoidMicrochannels(structureIdx, parentTrack);
        double[] thlds = getTrackThresholds(parentTrack, structureIdx, voidMC);
        return (p, s) -> {
            if (voidMC.contains(p)) s.isVoid=true; 
            s.globalBackgroundLevel = 0; // used in SplitAndMergeHessian -> influence on split threshold parameter & correction cost in tracker
            s.lowerThld= thlds[0];
            s.upperThld = thlds[1]; // was otsu
            s.filterThld = thlds[1]; // was mean over otsu
        };
    }

    protected double[] getTrackThresholds(List<SegmentedObject> parentTrack, int structureIdx, Set<SegmentedObject> voidMC) {
        if (voidMC.size()==parentTrack.size()) return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        // 1) get global otsu thld for images with foreground
        //double globalThld = getGlobalOtsuThreshold(parentTrack.stream().filter(p->!voidMC.contains(p)), structureIdx);
        Map<Image, ImageMask> imageMapMask = parentTrack.stream().collect(Collectors.toMap(p->p.getPreFilteredImage(structureIdx), p->p.getMask() )); 
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(imageMapMask, true).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        double globalThld = this.foreThresholder.instanciatePlugin().runThresholderHisto(histo);
        //estimate a minimal threshold : middle point between mean value under global threshold and global threshold
        double mean = histo.getValueFromIdx(histo.getMeanIdx(0, (int)histo.getIdxFromValue(globalThld)));
        double minThreshold = (mean+globalThld)/2.0;
        double meanUp = histo.getValueFromIdx(histo.getMeanIdx((int)histo.getIdxFromValue(globalThld), histo.data.length));
        double maxThreshold = (meanUp+globalThld)/2.0;
        logger.debug("bacteria phase segmentation: {} global threshold on images with foreground: global thld: {}, thresholds: [{};{}]", parentTrack.get(0), globalThld, minThreshold, maxThreshold);
        return new double[]{minThreshold, globalThld, maxThreshold}; 
    }
    @Override 
    protected double getGlobalThreshold(List<SegmentedObject> parent, int structureIdx) {
        return getGlobalOtsuThreshold(parent.stream(), structureIdx);
    }

    @Override
    protected void displayAttributes() {
        Core.userLog("Lower Threshold: "+this.lowerThld);
        logger.info("Lower Threshold: "+this.lowerThld);
        Core.userLog("Upper Threshold: "+this.upperThld);
        logger.info("Upper Threshold: {}", upperThld);
        Core.userLog("Upper Threshold2: "+this.filterThld);
        logger.info("Upper Threshold2: {}", filterThld);
    }
}
