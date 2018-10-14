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
import bacmman.data_structure.Voxel;
import bacmman.image.*;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.pre_filters.ImageFeature;
import bacmman.plugins.plugins.pre_filters.Sigma;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.ImageFeatures;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.processing.split_merge.SplitAndMergeHessian;
import bacmman.processing.split_merge.SplitAndMergeRegionCriterion;
import ij.process.AutoThresholder;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.plugins.plugins.segmenters.EdgeDetector.valueFunction;


/**
 * Bacteria segmentation within microchannels, for phas images
 * @author Jean Ollion
 */
public class BacteriaPhaseContrastHessian extends SegmenterSplitAndMergeHessian implements Hint, TrackConfigurable<BacteriaPhaseContrastHessian> { //implements DevPlugin {
    public enum CONTOUR_ADJUSTMENT_METHOD {LOCAL_THLD_W_EDGE}
    PluginParameter<ThresholderHisto> foreThresholder = new PluginParameter<>("Threshold", ThresholderHisto.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setEmphasized(true).setHint("Threshold for foreground region selection, use depend on the method. Computed on the whole parent-track track.");

    NumberParameter mergeThreshold = new BoundedNumberParameter("Merge Threshold", 4, 0.3, 0, null).setEmphasized(true).setHint("After partitioning regions are merged if sum(hessian)|interface / sum(raw intensity)|interface < (this parameter). <br />Lower value splits more.  <br />Configuration Hint: Tune the value using intermediate image <em>Interface Values before merge by Hessian</em>, interface with a value over this threshold will not be merged");

    protected NumberParameter localThresholdFactor = new BoundedNumberParameter("Local Threshold Factor", 2, 0.75, 0, null);

    BooleanParameter upperCellCorrection = new BooleanParameter("Upper Cell Correction", false).setHint("If true: when the upper cell is touching the top of the microchannel, a different local threshold factor is applied to the upper half of the cell");
    NumberParameter upperCellLocalThresholdFactor = new BoundedNumberParameter("Upper cell local threshold factor", 2, 2, 0, null).setHint("Local Threshold factor applied to the upper part of the cell");
    NumberParameter maxYCoordinate = new BoundedNumberParameter("Max yMin coordinate of upper cell", 0, 5, 0, null);
    ConditionalParameter cond = new ConditionalParameter(upperCellCorrection).setActionParameters("true", upperCellLocalThresholdFactor, maxYCoordinate);
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethod = new EnumChoiceParameter<>("Contour Adjustment", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE, true).setHint("Method for contour adjustment after segmentation");
    ConditionalParameter contourAdjustmentCond = new ConditionalParameter(contourAdjustmentMethod).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE.toString(), localThresholdFactor, cond);
    NumberParameter minSize = new BoundedNumberParameter("Minimum Region Size", 0, 200, 10, null).setHint("Minimum Object Size in voxels. <br />After split and merge using hessian: regions under this size will be merged by the adjacent region that has the lowest interface value, and if this value is under 2 * <em>Split Threshold</em>");
    enum SPLIT_METHOD {MIN_WIDTH, HESSIAN};
    EnumChoiceParameter<SPLIT_METHOD> splitMethod = new EnumChoiceParameter<>("Split method", SPLIT_METHOD.values(), SPLIT_METHOD.HESSIAN, false).setHint("Method for splitting objects (manual correction or tracker with local correction): MIN_WIDTH: splits at the interface of minimal width. Hessian: splits at the interface of maximal hessian value");

    // attributes parametrized during track parametrization
    double lowerThld = Double.NaN, upperThld = Double.NaN, filterThld=Double.NaN;
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, hessianScale, mergeThreshold, edgeMap, foreThresholder, splitThreshold, minSize , contourAdjustmentCond, splitMethod};
    }
    public BacteriaPhaseContrastHessian() {
        this.hessianScale.setValue(2);
        this.splitThreshold.setValue(0.13);
        //localThresholdFactor.setHint("Factor defining the local threshold. <br />Lower value of this factor will yield in smaller cells. <br />Threshold = mean_w - sigma_w * (this factor), <br />with mean_w = weigthed mean of raw pahse image weighted by edge image, sigma_w = sigma weighted by edge image. Refer to images: <em>Local Threshold edge map</em> and <em>Local Threshold intensity map</em>");
        //localThresholdFactor.setValue(1);
        
    }
    public BacteriaPhaseContrastHessian setMinSize(int minSize) {
        this.minSize.setValue(minSize);
        return this;
    }

    @Override public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        if (isVoid) return null;
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);

        if (splitAndMerge==null || !parent.equals(currentParent)) {
            currentParent = parent;
            splitAndMerge = initializeSplitAndMerge(parent, objectClassIdx, parent.getMask());
        }
        // step 1: partition the whole image with hessian and merge using criterion on hessian value
        RegionPopulation pop = splitAndMerge.split(parent.getMask(), 0); // partition the whole parent mask
        if (stores!=null) {
            imageDisp.accept(splitAndMerge.getHessian().setName("Hessian"));
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after partitioning"));
            imageDisp.accept(splitAndMerge.drawInterfaceValues(pop).setName("Interface values after partitioning (HINT: use to set merge threshol)"));
        }

        // here : need either to filter border artifacts either to avoid merging


        pop = splitAndMerge.merge(pop, null);

        if (stores!=null)  {
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after merge partitions"));
        }

        // step 2 adjust regions to edges
        // hessian "edges"
        pop.localThresholdEdges(parent.getPreFilteredImage(objectClassIdx), splitAndMerge.getWatershedMap(), 0, true, false, 0, parent.getMask(), null);
        if (stores!=null)  {
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after local threshold: hessian"));
        }

        Image edges = this.edgeMap.filter(parent.getPreFilteredImage(objectClassIdx), parent.getMask());
        Image smooth = ImageFeatures.gaussianSmooth(parent.getPreFilteredImage(objectClassIdx), 2, false);
        // edges of pre-filtered images
        localThreshold(pop, edges, smooth, parent.getMask(), true);
        if (stores != null) {
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after local threshold: prefiltered images"));
        }

        if (!Double.isNaN(filterThld)) {
            Map<Region, Double> values = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(parent.getPreFilteredImage(objectClassIdx))));
            pop.filter(r->values.get(r)>=filterThld);
            if (pop.getRegions().isEmpty()) return pop;
        }

        //pop = localThreshold(input, pop, parent, objectClassIdx, false);


        // step 3 second round of split/merging
        pop = splitAndMerge.split(pop.getLabelMap(), 0); // partition the whole parent mask
        splitAndMerge.setThreshold(this.splitThreshold.getValue().doubleValue());
        if (stores!=null) {
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after split by hessian"));
            imageDisp.accept(splitAndMerge.drawInterfaceValues(pop).setName("Interface values after split by hessian: (HINT: use to set split threshold)"));
        }
        pop = splitAndMerge.merge(pop, null);

        pop.filter(new RegionPopulation.Thickness().setX(2).setY(2)); // remove thin objects
        //res.filter(new RegionPopulation.Size().setMin(minSize.getValue().intValue())); // remove small objects

        if (stores!=null)  {
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after merge and remove thin objects"));
        }

        pop.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);

        if (stores!=null) {
            int pSIdx = parent.getExperimentStructure().getParentObjectClassIdx(objectClassIdx);
            stores.get(parent).addMisc("Display Thresholds", l->{
                if (l.stream().map(o->o.getParent(pSIdx)).anyMatch(o->o==parent)) {
                    //displayAttributes();
                }
            });
        }
        return pop;
    }
    final private String toolTip = "<b>Bacteria segmentation within microchannels</b><br />"
            + "This algorithm is designed to work on inverted (foreground is bright) and normalized phase-contrast images, filtered with the Track-pre-filter: \"SubtractBackgroundMicrochannels\"<br />"
            + "<ol><li>Background partition selection can include filtering of High-intensity background objects resulting from border-effects & phase contrast imaging. See <em>Filter border Artefacts</em></li>"
            + "<li>Split/Merge criterion is value of hessian at interface between to regions normalized by the mean value of the pre-filtered image within all segmented regions</li>"
            + "<li>Local threshold step is performed on the raw images with a different value described in the <em>local threshold factor</em> parameter</li></ol>";
    
    @Override public String getHintText() {return toolTip;}
    
    @Override public SplitAndMergeHessian initializeSplitAndMerge(SegmentedObject parent, int structureIdx, ImageMask foregroundMask) {
        SplitAndMergeHessian sam = super.initializeSplitAndMerge(parent, structureIdx, foregroundMask);
        sam.setThreshold(this.mergeThreshold.getValue().doubleValue());
        Image input = parent.getPreFilteredImage(structureIdx);
        setInterfaceValue(input, sam);
        return sam;
    }
    void setInterfaceValue(Image input, SplitAndMergeHessian sam) {
        sam.setInterfaceValue(i-> {
            Collection<Voxel> voxels = i.getVoxels();
            if (voxels.isEmpty()) return Double.NaN;
            else {
                Image hessian = sam.getHessian();
                double val  =  voxels.stream().mapToDouble(v->hessian.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                // normalize using mean value (compare with max of mean or max of median
                double mean = Stream.concat(i.getE1().getVoxels().stream(), i.getE2().getVoxels().stream()).mapToDouble(v->(double)input.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                val/=mean;
                return val;
            }
        });
    }
    


    protected RegionPopulation filterRegionsAfterMergeByHessian(SegmentedObject parent, int structureIdx, RegionPopulation pop) {
        if (stores!=null) stores.get(parent).addIntermediateImage("values after merge by hessian", EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(structureIdx)).setName("Region Values before merge by Hessian"));

        if (stores!=null) stores.get(parent).addIntermediateImage("values after edge fit on hessian", EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(structureIdx)).setName("Region Values before merge by Hessian"));
        if (pop.getRegions().isEmpty()) return pop;        
        // filter low value regions

        // merge small objects with the object with the smallest edge
        /*int minSize = this.minSize.getValue().intValue();
        SplitAndMergeHessian sm= new SplitAndMergeHessian(parent.getPreFilteredImage(structureIdx), splitThreshold.getValue().doubleValue()*2, hessianScale.getValue().doubleValue(), globalBackgroundLevel);
        sm.setHessian(splitAndMerge.getHessian());
        sm.addForbidFusion(i -> i.getE1().size()>minSize && i.getE2().size()>minSize);
        setInterfaceValue(parent.getPreFilteredImage(structureIdx), sm);
        sm.merge(pop, null);
        */
        return pop;
    }

    

    protected RegionPopulation localThreshold(RegionPopulation pop, Image edgeMap, Image smooth, ImageMask mask, boolean darkBackground) {
        if (pop.getRegions().isEmpty()) return pop;
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                // different local threshold for middle part of upper cell when touches borders
                boolean differentialLF = false;
                if (upperCellCorrection.getSelected()) {
                    Region upperCell = pop.getRegions().stream().min(Comparator.comparingInt(r -> r.getBounds().yMin())).get();
                    if (upperCell.getBounds().yMin() <= maxYCoordinate.getValue().intValue()) {
                        differentialLF = true;
                        double yLim = upperCell.getGeomCenter(false).get(1) + upperCell.getBounds().sizeY() / 3.0;
                        pop.localThresholdEdges(smooth, edgeMap, localThresholdFactor.getValue().doubleValue(), darkBackground, false, 0, mask, v -> v.y < yLim); // local threshold for lower cells & half lower part of cell
                        if (stores != null) { //|| (callFromSplit && splitVerbose)
                            logger.debug("y lim: {}", yLim);
                        }
                        pop.localThresholdEdges(smooth, edgeMap, upperCellLocalThresholdFactor.getValue().doubleValue(), darkBackground, false, 0, mask, v -> v.y > yLim); // local threshold for half upper part of 1st cell
                    }
                }
                if (!differentialLF) pop.localThresholdEdges(smooth, edgeMap, localThresholdFactor.getValue().doubleValue(), darkBackground, false, 0, mask, null);
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
        splitAndMerge.setTestMode(TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent));
        if (splitMethod.getSelectedEnum().equals(SPLIT_METHOD.MIN_WIDTH)) splitAndMerge.setInterfaceValue(i->-(double)i.getVoxels().size()); // algorithm:  split  @ smallest interface
        RegionPopulation res = splitAndMerge.splitAndMerge(mask, 20, splitAndMerge.objectNumberLimitCondition(2));
        setInterfaceValue(input, splitAndMerge); // for interface value computation
        //res =  localThreshold(input, res, parent, structureIdx, true); 
        if (object.isAbsoluteLandMark()) res.translate(parent.getBounds(), true);
        if (res.getRegions().size()>2) RegionCluster.mergeUntil(res, 2, 0); // merge most connected until 2 objects remain
        res.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);
        return res;
    }
    // track parametrization
    @Override
    public TrackConfigurer<BacteriaPhaseContrastHessian> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Set<SegmentedObject> voidMC = getVoidMicrochannels(structureIdx, parentTrack);
        double[] thlds = getTrackThresholds(parentTrack, structureIdx, voidMC);
        return (p, s) -> {
            if (voidMC.contains(p)) s.isVoid=true; 
            s.globalBackgroundLevel = 0; // was 0. use in SplitAndMergeHessian -> should be minimal value
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
        logger.debug("bacteria phase segmentation: {} global threshold on images with forground: global thld: {}, thresholds: [{};{}]", parentTrack.get(0), globalThld, minThreshold, maxThreshold);
        return new double[]{minThreshold, globalThld, maxThreshold}; 
    }
    @Override 
    protected double getGlobalThreshold(List<SegmentedObject> parent, int structureIdx) {
        return getGlobalOtsuThreshold(parent.stream(), structureIdx);
    }

}
