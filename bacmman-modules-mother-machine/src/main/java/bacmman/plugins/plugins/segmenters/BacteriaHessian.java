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
import bacmman.plugins.*;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.EDT;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.RegionFactory;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.neighborhood.DisplacementNeighborhood;
import bacmman.processing.split_merge.SplitAndMergeHessian;
import bacmman.utils.ArrayUtil;
import ij.process.AutoThresholder;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.segmenters.EdgeDetector.valueFunction;


/**
 * Bacteria segmentation within microchannels, for phas images
 * @author Jean Ollion
 */
public abstract class BacteriaHessian<T extends BacteriaHessian> extends SegmenterSplitAndMergeHessian implements TrackConfigurable<T>, ManualSegmenter { //implements DevPlugin {
    public enum CONTOUR_ADJUSTMENT_METHOD {LOCAL_THLD_W_EDGE}
    PluginParameter<ThresholderHisto> foreThresholder = new PluginParameter<>("Threshold", ThresholderHisto.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setHint("Threshold for foreground region selection, use depend on the method. Computed on the whole parent-track track.");

    NumberParameter mergeThreshold = new BoundedNumberParameter("Merge Threshold", 4, 0.3, 0, null).setEmphasized(true).setHint("After partitioning regions A and B are merged if mean(hessian) @ interface(A-B) / mean(intensity) @ interface(A-B)  is inferior to (this parameter). <br />Configuration Hint: Tune the value using intermediate image <em>Interface Values before merge by Hessian</em>, interface with a value over this threshold will not be merged. The chosen value should be set so that cells are not merged with background but each cell should not be over-segmented. Result of merging is shown in the image <em>Region values after merge partition</em>");

    protected NumberParameter localThresholdFactor = new BoundedNumberParameter("Local Threshold Factor", 2, 0.75, 0, null).setEmphasized(true).setHint("Factory for local threhsolding to fit edges. A lower value yield in smaller cells. <br />For each region a local threshold T is computed as the mean of intensity within the region weighed by the edge values - standard-deviation of intensity * this factor (edges as defined by <em>Edge Map</em>. Each pixel of the cell contour is eliminated if its intensity is smaller than T. In this case, the same procedure is applied to the neighboring pixels, until all pixels in the contour have an intensity higher than  T");

    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethod = new EnumChoiceParameter<>("Contour Adjustment", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE, true).setHint("Method for contour adjustment");
    ConditionalParameter contourAdjustmentCond = new ConditionalParameter(contourAdjustmentMethod).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE.toString(), localThresholdFactor);


    enum SPLIT_METHOD {MIN_WIDTH, HESSIAN};
    EnumChoiceParameter<SPLIT_METHOD> splitMethod = new EnumChoiceParameter<>("Split method", SPLIT_METHOD.values(), SPLIT_METHOD.HESSIAN, false).setHint("Method for splitting objects (manual correction or tracker with local correction): MIN_WIDTH: splits at the interface of minimal width. Hessian: splits at the interface of maximal hessian value");

    // attributes parametrized during track parametrization
    double filterThld=Double.NaN;
    final String operationSequenceHint = "<ol><li>Partition of the whole microchannel using seeded watershed algorithm on maximal hessian eigenvalue transform</li>"
            +"<li>Merging of partition using a criterion on hessian value at interface see hint of parameter <em>Merge Threshold</em></li>"
            +"<li>Local Threshold of regions to fit contour on <em>Edge Map<em>, see hint of <em>Local Threshold factor</em> for details</li>"
            +"<li>Region of intensity inferior to <em>Threshold</em> are erased</li>"
            +"<li>Foreground is split by applying a watershed transform on the maximal hessian Eigen value, regions are then merged, using a criterion described in <em>Split Threshold</em> parameter</li>"
            +"</ol>";

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, hessianScale, mergeThreshold, edgeMap, foreThresholder, splitThreshold, contourAdjustmentCond, splitMethod};
    }
    public BacteriaHessian() {
        this.hessianScale.setValue(2);
        this.splitThreshold.setValue(0.13);
        //localThresholdFactor.setHint("Factor defining the local threshold. <br />Lower value of this factor will yield in smaller cells. <br />Threshold = mean_w - sigma_w * (this factor), <br />with mean_w = weigthed mean of raw pahse image weighted by edge image, sigma_w = sigma weighted by edge image. Refer to images: <em>Local Threshold edge map</em> and <em>Local Threshold intensity map</em>");
        //localThresholdFactor.setValue(1);
        
    }
    abstract boolean rawHasDarkBackground();
    @Override public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        if (isVoid) return null;
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);

        if (splitAndMerge==null || !parent.equals(currentParent)) {
            currentParent = parent;
            splitAndMerge = initializeSplitAndMerge(parent, objectClassIdx, parent.getMask());
        }
        // step 1: partition the whole image with hessian and merge using criterion on hessian value
        RegionPopulation pop = splitAndMerge.split(parent.getMask(), 5); // partition the whole parent mask
        if (stores!=null) {
            imageDisp.accept(splitAndMerge.getHessian().setName("Hessian"));
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after partitioning"));
            imageDisp.accept(splitAndMerge.drawInterfaceValues(pop).setName("Interface values after partitioning (HINT: use to set merge threshold)"));
        }

        pop = splitAndMerge.merge(pop, null);

        if (stores!=null)  imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after merge partitions"));

        // step 2 adjust regions to edges
        Image smooth = ImageFeatures.gaussianSmooth(parent.getPreFilteredImage(objectClassIdx), 2, false);
        // hessian "edges"
        pop.localThresholdEdges(smooth, splitAndMerge.getWatershedMap(), 0, true, false, 0, parent.getMask(), null);
        //if (stores!=null)  imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after local threshold: hessian"));
        Image edges = this.edgeMap.filter(parent.getPreFilteredImage(objectClassIdx), parent.getMask());
        // edges of pre-filtered images
        localThreshold(pop, edges, smooth, parent.getRawImage(objectClassIdx), parent.getMask());
        if (stores != null) {
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after local threshold: prefiltered images"));
            imageDisp.accept(edges.setName("Edge map for local threshold"));
        }
        if (!Double.isNaN(filterThld)) {
            Map<Region, Double> values = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(parent.getPreFilteredImage(objectClassIdx))));
            pop.filter(r->values.get(r)>=filterThld);
            if (pop.getRegions().isEmpty()) return pop;
        }
        pop = splitAndMerge.split(pop.getLabelMap(), 0); // partition the foreground mask
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
                    logger.debug("Threshold: {}", filterThld);
                }
            });
        }
        return pop;
    }

    
    @Override public SplitAndMergeHessian initializeSplitAndMerge(SegmentedObject parent, int structureIdx, ImageMask foregroundMask) {
        SplitAndMergeHessian sam = super.initializeSplitAndMerge(parent, structureIdx, foregroundMask);
        sam.setThreshold(this.mergeThreshold.getValue().doubleValue());
        Image input = parent.getPreFilteredImage(structureIdx);
        setInterfaceValue(input, sam);
        return sam;
    }
    abstract void setInterfaceValue(Image input, SplitAndMergeHessian sam);


    protected RegionPopulation localThreshold(RegionPopulation pop, Image edgeMap, Image preFilteredSmoothedIntensity, Image rawIntensity, ImageMask mask) {
        if (pop.getRegions().isEmpty()) return pop;
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                pop.localThresholdEdges(preFilteredSmoothedIntensity, edgeMap, localThresholdFactor.getValue().doubleValue(), true, false, 0, mask, null);
                pop.smoothRegions(2, true, mask);
                return pop;
            /*case LOCAL_THLD_EDGE:
                Function<Region, Double> thldFct = r-> { // median intensity value on contours after fit to edges
                    Region dup = r.duplicate(false);
                    dup.erodeContoursEdge(edgeMap, preFilteredSmoothedIntensity, true);
                    return ArrayUtil.median(dup.getContour().stream().mapToDouble(v->preFilteredSmoothedIntensity.getPixel(v.x,v.y,v.z)).toArray());
                };
                return pop.localThreshold(preFilteredSmoothedIntensity, thldFct, true, false, 0, null);
            */
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

    protected EdgeDetector initEdgeDetector(SegmentedObject parent, int structureIdx) {
        EdgeDetector seg = new EdgeDetector().setIsDarkBackground(true);
        seg.minSizePropagation.setValue(0);
        seg.setPreFilters(edgeMap.get());
        seg.setThrehsoldingMethod(EdgeDetector.THLD_METHOD.NO_THRESHOLDING);
        seg.seedRadius.setValue(1.5);
        return seg;
    }

    private boolean verboseManualSeg;
    @Override public void setManualSegmentationVerboseMode(boolean verbose) {
        this.verboseManualSeg=verbose;
    }
    @Override public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<int[]> seedsXYZ) {

        List<Region> seedObjects = RegionFactory.createSeedObjectsFromSeeds(seedsXYZ, input.sizeZ()==1, input.getScaleXY(), input.getScaleZ());
        EdgeDetector seg = initEdgeDetector(parent, objectClassIdx);
        Image wsMap = seg.getWsMap(input, parent.getMask());

        // in order to avoid creation of objects with only few pixels in case sees is in the border of an object -> replace seeds with pixel of higher intensity value & lower gradient value
        // search radius should be ~ scale of watershed map.
        double searchRadius = 4;
        DisplacementNeighborhood n = Filters.getNeighborhood(searchRadius, wsMap);
        seedObjects.forEach(so -> {
            Voxel seed = so.getVoxels().iterator().next();
            double wsVal0 = wsMap.getPixel(seed.x, seed.y, seed.z);
            double inVal0= input.getPixel(seed.x, seed.y, seed.z);
            Voxel newSeed = n.stream(seed, segmentationMask, false).map(v-> {
                double wsVal = wsMap.getPixel(v.x, v.y, v.z);
                if (wsVal>=wsVal0) return null;
                double inVal = input.getPixel(v.x, v.y, v.z);
                if (inVal<=inVal0) return null;
                v.value = (float) ((inVal - inVal0) * (wsVal0 - wsVal));
                return v;
            }).filter(v->v!=null).max(Voxel.getComparator()).orElse(null);
            if (newSeed!=null) {
                if (verboseManualSeg) logger.debug("will move seed: dx:{} dy:{}, dz:{}", newSeed.x-seed.x, newSeed.y-seed.y, newSeed.z-seed.z);
                so.getVoxels().clear();
                so.getVoxels().add(newSeed);
                so.clearMask();
            }
        });

        ImageInteger seedMap = Filters.localExtrema(wsMap, null, false, segmentationMask, Filters.getNeighborhood(1.5, input));
        RegionPopulation watershedSeeds = new RegionPopulation(seedMap, false);
        // TODO optional: replace seeds with the nearest seed with highest intensity -> match algorithm.

        // among remaining seeds -> choose those close to border of parent mask & far from foreground seeds
        Image distanceToBorder = EDT.transform(segmentationMask, true, 1, parent.getMask().sizeZ()>1?parent.getScaleZ()/parent.getScaleXY():1, true);
        Function<Region, Voxel> objectToVoxelMapper = o -> o.getVoxels().iterator().next();
        watershedSeeds.filter(o -> {
            Voxel s = objectToVoxelMapper.apply(o);
            if (distanceToBorder.getPixel(s.x, s.y, s.z)>2) return false; // background seeds should be close enough to border of parent mask
            return seedObjects.stream().map(objectToVoxelMapper).mapToDouble(v->v.getDistance(s)).min().orElse(Double.POSITIVE_INFINITY)>3; // background seeds should be far away from foreground seeds
        });
        watershedSeeds.addObjects(seedObjects, true);
        seg.setSeedMap(watershedSeeds.getLabelMap());
        RegionPopulation pop = seg.run(input, segmentationMask);
        if (verboseManualSeg) Core.showImage(pop.getLabelMap().setName("Partition"));
        if (verboseManualSeg) logger.debug("before filter seeds: {} objects", pop.getRegions().size());
        pop.filter(o-> seedObjects.stream().map(so->so.getVoxels().iterator().next()).anyMatch(v -> o.contains(v))); // keep only objects that contain seeds
        if (verboseManualSeg) logger.debug("after filter seeds: {} objects", pop.getRegions().size());
        pop.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);

        if (verboseManualSeg) {
            //Image manualSeeds = new ImageByte("seeds from: "+input.getName(), input);
            //for (int[] seed : seedsXYZ) manualSeeds.setPixel(seed[0], seed[1], seed[2], 1);
            Core.showImage(watershedSeeds.getLabelMap().setName("Watershed Seeds (background + manual)"));
            Core.showImage(wsMap.setName("Watershed Map"));

            Core.showImage(TypeConverter.toCommonImageType(segmentationMask).setName("Mask"));
            Core.showImage(pop.getLabelMap().setName("segmented from: "+input.getName()));
        }

        return pop;
    }
}
