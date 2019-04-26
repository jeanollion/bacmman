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
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.core.Core;
import bacmman.processing.EDT;
import bacmman.processing.Filters;
import bacmman.processing.RegionFactory;
import bacmman.processing.neighborhood.DisplacementNeighborhood;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.TypeConverter;
import bacmman.plugins.ManualSegmenter;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.Hint;
import bacmman.plugins.plugins.pre_filters.ImageFeature;
import bacmman.plugins.plugins.pre_filters.StandardDeviation;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import bacmman.plugins.TrackConfigurable;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public abstract class BacteriaIntensitySegmenter<T extends BacteriaIntensitySegmenter<T>> extends SegmenterSplitAndMergeHessian implements TrackConfigurable<T>, ManualSegmenter, Hint {

    protected final int MIN_SIZE_PROPAGATION = 20; // TODO add as parameter ?
    protected NumberParameter smoothScale = new BoundedNumberParameter("Smooth scale", 1, 2, 0, 5).setHint("Scale (pixels) for gaussian filtering for the local thresholding step");
    protected NumberParameter localThresholdFactor = new BoundedNumberParameter("Local Threshold Factor", 2, 1.25, 0, null).setEmphasized(true)
            .setSimpleHint("Lower value of this threshold will results in smaller cells.<br /><br /><b>This threshold should be calibrated for each new experimental setup</b>");

    //segmentation-related attributes (kept for split and merge methods)
    protected EdgeDetector edgeDetector;

    public BacteriaIntensitySegmenter() {
    }

    public T setSplitThreshold(double splitThreshold) {
        this.splitThreshold.setValue(splitThreshold);
        return (T)this;
    }
    public T setHessianScale(double hessianScale) {
        this.hessianScale.setValue(hessianScale);
        return (T)this;
    }
    public T setSmoothScale(double smoothScale) {
        this.smoothScale.setValue(smoothScale);
        return (T)this;
    }

    public T setLocalThresholdFactor(double localThresholdFactor) {
        this.localThresholdFactor.setValue(localThresholdFactor);
        return (T)this;
    }

    /**
     * Initialize the EdgeDetector that will create a parittion of the image and filter the regions
     * @param parent
     * @param structureIdx
     * @return 
     */
    protected EdgeDetector initEdgeDetector(SegmentedObject parent, int structureIdx) {
        EdgeDetector seg = new EdgeDetector().setIsDarkBackground(true);
        seg.minSizePropagation.setValue(0);
        seg.setPreFilters(edgeMap.get());
        seg.setThrehsoldingMethod(EdgeDetector.THLD_METHOD.NO_THRESHOLDING);
        //seg.setTestMode( TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent));
        return seg;
    }
    @Override public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        if (isVoid) return null;
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);

        if (splitAndMerge==null || !parent.equals(currentParent)) {
            currentParent = parent;
            splitAndMerge = initializeSplitAndMerge(parent, objectClassIdx, parent.getMask());
        }

        edgeDetector = initEdgeDetector(parent, objectClassIdx);
        RegionPopulation splitPop = edgeDetector.runSegmenter(input, objectClassIdx, parent);
        splitPop.smoothRegions(1, true, parent.getMask());
        if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(edgeDetector.getWsMap(parent.getPreFilteredImage(objectClassIdx), parent.getMask()).setName("Foreground detection: edge map"));
        if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(EdgeDetector.generateRegionValueMap(splitPop, parent.getPreFilteredImage(objectClassIdx)).setName("Foreground detection: Region values after partitioning"));
        splitPop = filterRegionsAfterEdgeDetector(parent, objectClassIdx, splitPop);
        if (stores!=null && stores.get(parent).isExpertMode()) imageDisp.accept(EdgeDetector.generateRegionValueMap(splitPop.getImageProperties(), splitPop.getRegions().stream().collect(Collectors.toMap(r->r, r->1d))).setName("Foreground mask"));

        RegionPopulation split = splitAndMerge.split(splitPop.getLabelMap(), MIN_SIZE_PROPAGATION);
        if (stores!=null) {
            imageDisp.accept(splitAndMerge.getHessian().setName("Hessian"));
            
        }
        split = filterRegionsAfterSplitByHessian(parent, objectClassIdx, split);
        if (stores!=null)  {
            //if (stores.get(parent).isExpertMode()) imageDisp.accept(EdgeDetector.generateRegionValueMap(split, input).setName("Split cells: Region values before merge"));
            imageDisp.accept(splitAndMerge.drawInterfaceValues(split).setName("Split cells: Interface values"));
        }
        RegionPopulation res = splitAndMerge.merge(split, null);
        res = filterRegionsAfterMergeByHessian(parent, objectClassIdx, res);
        if (stores!=null && stores.get(parent).isExpertMode())  {
            imageDisp.accept(EdgeDetector.generateRegionValueMap(res, input).setName("Split cells: Region values after merge"));
        }
        res = localThreshold(input, res, parent, objectClassIdx, false);
        res.filter(new RegionPopulation.Thickness().setX(2).setY(2)); // remove thin objects
        //res.filter(new RegionPopulation.Size().setMin(minSize.getValue().intValue())); // remove small objects
        
        res.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);
        
        if (stores!=null) {
            int pSIdx = parent.getExperimentStructure().getParentObjectClassIdx(objectClassIdx);
            stores.get(parent).addMisc("Display Thresholds", l->{
                if (l.stream().map(o->o.getParent(pSIdx)).anyMatch(o->o==parent)) {
                    displayAttributes();
                }
            });
        }
        return res;
    }
    protected abstract void displayAttributes();
    protected abstract RegionPopulation filterRegionsAfterEdgeDetector(SegmentedObject parent, int structureIdx, RegionPopulation pop);
    protected abstract RegionPopulation filterRegionsAfterSplitByHessian(SegmentedObject parent, int structureIdx, RegionPopulation pop);
    protected abstract RegionPopulation filterRegionsAfterMergeByHessian(SegmentedObject parent, int structureIdx, RegionPopulation pop);
    protected abstract RegionPopulation localThreshold(Image input, RegionPopulation pop, SegmentedObject parent, int structureIdx, boolean callFromSplit);

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
        if (verboseManualSeg) Core.showImage(pop.getLabelMap().setName("Regions"));
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
