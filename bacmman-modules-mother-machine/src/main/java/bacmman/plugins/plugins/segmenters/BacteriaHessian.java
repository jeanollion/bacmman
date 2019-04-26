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
import bacmman.utils.geom.Point;
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

    NumberParameter mergeThreshold = new BoundedNumberParameter("Merge Threshold", 4, 0.3, 0, null).setEmphasized(true).setHint("After partitioning regions A and B are merged if mean(hessian) @ interface(A-B) / mean(intensity) @ interface(A-B)  is inferior to (this parameter). <br />Configuration Hint: Tune the value using intermediate image <em>Split cells: Interface values</em>, interface with a value over this threshold will not be merged. The chosen value should be set so that cells are not merged with background but each cell should not be over-segmented. Result of merging is shown in the image <em>Region values after merge partition</em>");

    protected NumberParameter localThresholdFactorHess = new BoundedNumberParameter("Local Threshold Factor (Hessian)", 2, 0, 0, null).setEmphasized(true).setHint("Factor for local thresholding to fit hessian. A lower value results in smaller cells. <br />For each region a local threshold T is computed as the mean of intensity within the region weighed by the hessian values - standard-deviation of intensity * this threshold. Each pixel of the cell contour is eliminated if its intensity is smaller than T. In this case, the same procedure is applied to the neighboring pixels, until all pixels in the contour have an intensity higher than  T");
    BooleanParameter upperCellCorrectionHess = new BooleanParameter("Upper Cell Correction (Hessian)", false).setHint("If true: when the upper cell is touching the top of the microchannel, a different local threshold factor is applied to the upper half of the cell");
    NumberParameter upperCellLocalThresholdFactorHess = new BoundedNumberParameter("Upper cell local threshold factor", 2, 2, 0, null).setHint("Local Threshold factor applied to the upper part of the cell");
    NumberParameter maxYCoordinateHess = new BoundedNumberParameter("Max yMin coordinate of upper cell", 0, 5, 0, null);
    ConditionalParameter upperCellCorrectionCondHess = new ConditionalParameter(upperCellCorrectionHess).setActionParameters("true", upperCellLocalThresholdFactorHess, maxYCoordinateHess);
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethodHess = new EnumChoiceParameter<>("Contour Adjustment (hessian)", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE, true).setHint("Method for contour adjustment using hessian");
    ConditionalParameter contourAdjustmentCondHess = new ConditionalParameter(contourAdjustmentMethodHess).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE.toString(), localThresholdFactorHess, upperCellCorrectionCondHess);

    BooleanParameter upperCellCorrection = new BooleanParameter("Upper Cell Correction", false).setHint("If true: when the upper cell is touching the top of the microchannel, a different local threshold factor is applied to the upper half of the cell");
    NumberParameter upperCellLocalThresholdFactor = new BoundedNumberParameter("Upper cell local threshold factor", 2, 2, 0, null).setHint("Local Threshold factor applied to the upper part of the cell");
    NumberParameter maxYCoordinate = new BoundedNumberParameter("Max yMin coordinate of upper cell", 0, 5, 0, null);
    ConditionalParameter upperCellCorrectionCond = new ConditionalParameter(upperCellCorrection).setActionParameters("true", upperCellLocalThresholdFactor, maxYCoordinate);
    protected NumberParameter localThresholdFactor = new BoundedNumberParameter("Local Threshold Factor", 2, 0.75, 0, null).setEmphasized(true).setHint("Factor for local thresholding to fit edges. A lower value results in smaller cells. <br />For each region a local threshold T is computed as the mean of intensity within the region weighed by the edge values - standard-deviation of intensity * this threshold (edges as defined by <em>Edge Map</em>). Each pixel of the cell contour is eliminated if its intensity is smaller than T. In this case, the same procedure is applied to the neighboring pixels, until all pixels in the contour have an intensity higher than  T");
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethod = new EnumChoiceParameter<>("Contour Adjustment", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE, true).setHint("Method for contour adjustment");
    ConditionalParameter contourAdjustmentCond = new ConditionalParameter(contourAdjustmentMethod).setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_W_EDGE.toString(), localThresholdFactor, upperCellCorrectionCond);


    enum SPLIT_METHOD {MIN_WIDTH, HESSIAN};
    EnumChoiceParameter<SPLIT_METHOD> splitMethod = new EnumChoiceParameter<>("Split method", SPLIT_METHOD.values(), SPLIT_METHOD.HESSIAN, false).setHint("Method for splitting objects (manual correction or tracker with local correction): MIN_WIDTH: splits at the interface of minimal width. Hessian: splits at the interface of maximal hessian value");

    // attributes parametrized during track parametrization
    double filterThld=Double.NaN;
    final String operationSequenceHint = "<ol><li>Partitioning of the whole microchannel using seeded watershed algorithm on maximal hessian eigenvalue transform</li>"
            +"<li>Merging of regions using a criterion on hessian value at interface see hint of parameter <em>Merge Threshold</em></li>"
            +"<li>Local Threshold of regions <em>Hessian</em>, see hint of <em>Contour Adjustment (hessian)</em> and sub-parameters for details</li>"
            +"<li>Local Threshold of regions to fit contour on <em>Edge Map</em>, see hint of <em>Contour Adjustment</em> and sub-parameters for details</li>"
            +"<li>Region of intensity inferior to <em>Threshold</em> are erased</li>"
            +"<li>Foreground is split by applying a watershed transform on the maximal hessian Eigen value, regions are then merged, using a criterion described in <em>Split Threshold</em> parameter</li>"
            +"</ol>";

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{vcThldForVoidMC, hessianScale, mergeThreshold, edgeMap, foreThresholder, splitThreshold, contourAdjustmentCondHess, contourAdjustmentCond, splitMethod};
    }
    public BacteriaHessian() {
        this.hessianScale.setValue(2);
        this.splitThreshold.setValue(0.13);
        //localThresholdFactor.setHint("Factor defining the local threshold. <br />Lower value of this threshold will results in smaller cells. <br />Threshold = mean_w - sigma_w * (this threshold), <br />with mean_w = weigthed mean of raw pahse image weighted by edge image, sigma_w = sigma weighted by edge image. Refer to images: <em>Contour adjustment: edge map</em> and <em>Contour adjustment: intensity map</em>");
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
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Foreground detection: Region values after partitioning"));
            imageDisp.accept(splitAndMerge.drawInterfaceValues(pop).setName("Interface values after partitioning (HINT: use to set merge threshold)"));
        }

        pop = splitAndMerge.merge(pop, null);

        if (stores!=null)  imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, input).setName("Region Values after merge regions"));

        // step 2 adjust regions to edges
        localThreshold(pop, parent, objectClassIdx);

        if (!Double.isNaN(filterThld)) {
            Map<Region, Double> values = pop.getRegions().stream().collect(Collectors.toMap(o->o, valueFunction(parent.getPreFilteredImage(objectClassIdx))));
            pop.filter(r->values.get(r)>=filterThld);
            if (pop.getRegions().isEmpty()) return pop;
        }
        pop = splitAndMerge.split(pop.getLabelMap(), 20); // partition the foreground mask
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

    protected void localThresholdWEdge(RegionPopulation pop, Image edgeMap, Image intensity, ImageMask mask, boolean darkBck, boolean upperCellCorrection, int upperCellCorrectionMinYCoord, double factor, double factorUpperCell) {
        // different local threshold for middle part of upper cell when touches borders
        boolean differentialLF = false;
        if (upperCellCorrection) {
            Region upperCell = pop.getRegions().stream().min(Comparator.comparingInt(r -> r.getBounds().yMin())).get();
            if (upperCell.getBounds().yMin() <= upperCellCorrectionMinYCoord) {
                differentialLF = true;
                double yLim = upperCell.getGeomCenter(false).get(1); // + upperCell.getBounds().sizeY() / 3.0; // local threshold for half upper part of 1st cell
                pop.localThresholdEdges(intensity, edgeMap, factorUpperCell, darkBck, false, 0, mask, v -> v.y < yLim);
                if (stores != null) { //|| (callFromSplit && splitVerbose)
                    logger.debug("y lim: {}", yLim);
                }
                pop.localThresholdEdges(intensity, edgeMap, factor, darkBck, false, 0, mask, v -> v.y > yLim);  // local threshold for lower cells & half lower part of cell
            }
        }
        if (!differentialLF) pop.localThresholdEdges(intensity, edgeMap, factor, darkBck, false, 0, mask, null);
        pop.smoothRegions(2, true, mask);
    }

    protected void localThreshold(RegionPopulation pop, SegmentedObject parent, int objectClassIdx) {
        if (pop.getRegions().isEmpty()) return;
        Image smooth = ImageFeatures.gaussianSmooth(parent.getPreFilteredImage(objectClassIdx), 2, false);
        switch(contourAdjustmentMethodHess.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                localThresholdWEdge(pop, splitAndMerge.getWatershedMap(), smooth, parent.getMask(), true, this.upperCellCorrectionHess.getSelected(), maxYCoordinateHess.getValue().intValue(), this.localThresholdFactorHess.getValue().doubleValue(), upperCellLocalThresholdFactorHess.getValue().doubleValue());
                break;
            default:
        }
        if (contourAdjustmentMethod.getSelectedIndex()<0) return;

        Image edges = this.edgeMap.filter(parent.getPreFilteredImage(objectClassIdx), parent.getMask());
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_W_EDGE:
                localThresholdWEdge(pop, edges, smooth, parent.getMask(), true, this.upperCellCorrection.getSelected(), maxYCoordinate.getValue().intValue(), this.localThresholdFactor.getValue().doubleValue(), upperCellLocalThresholdFactor.getValue().doubleValue());
                break;
            default:
        }

        if (stores != null) {
            Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
            imageDisp.accept(EdgeDetector.generateRegionValueMap(pop, parent.getPreFilteredImage(objectClassIdx)).setName("Region Values after local threshold: prefiltered images"));
            imageDisp.accept(edges.setName("Edge map for local threshold"));
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
    @Override public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {

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
