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
package bacmman.processing.split_merge;

import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.processing.Filters;
import bacmman.processing.clustering.ClusterCollection;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;
import bacmman.plugins.plugins.trackers.ObjectOrderTracker;
import bacmman.processing.watershed.WatershedTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 *
 * @author Jean Ollion
 * @param <I>
 */
public abstract class SplitAndMerge<I extends InterfaceRegionImpl<I>> { //& RegionCluster.InterfaceVoxels<I>
    final static Logger logger = LoggerFactory.getLogger(SplitAndMerge.class);
    public enum INTERFACE_VALUE { MEDIAN, CENTER }
    protected Consumer<Image> addTestImage;
    protected ClusterCollection.InterfaceFactory<Region, I> factory;
    protected final Map<Region, Double> medianValues;
    protected final Map<Region, Double> meanValues;
    protected Image intensityMap;
    boolean increasingPropagation = true, localMinOnSeedMap=true;
    protected ImageMask foregroundMask;
    protected Consumer<Region> regionChanged;
    protected double seedThreshold = Double.NaN;
    protected List<FusionCriterion<Region, I>> fusionCriteria = new ArrayList<>();
    protected boolean parallel;

    public SplitAndMerge<I> setParallel(boolean parallel) {
        this.parallel=parallel;
        return this;
    }

    public SplitAndMerge<I> setSeedThrehsold(double seedThrehsold) {
        this.seedThreshold=seedThrehsold;
        return this;
    }

    public void setTestMode(Consumer<Image> addTestImage) {
        this.addTestImage = addTestImage;
    }

    public SplitAndMerge(Image intensityMap) {
        this.intensityMap=intensityMap;
        medianValues= InterfaceRegionImpl.getMedianValueMap(intensityMap);
        meanValues= InterfaceRegionImpl.getMeanValueMap(intensityMap);
        regionChanged = r -> {
            medianValues.remove(r);
            meanValues.remove(r);
            for (FusionCriterion<Region, I> crit : fusionCriteria) crit.elementChanged(r);
        };
    }

    /**
     *
     * @param increasingPropagation if true: watershed will be performed with a increasing propagation (e.g. if watershed map represent edges)
     * @param localMinOnSeedMap if true seeds are local minima of the seed map
     * @return this instance for convenience
     */
    public SplitAndMerge<I> setMapsProperties(boolean increasingPropagation, boolean localMinOnSeedMap) {
        this.increasingPropagation =increasingPropagation;
        this.localMinOnSeedMap=localMinOnSeedMap;
        return this;
    }
    /**
     * 
     * @return A map containing median values of a given region within the map {@param intensityMap}, updated when a fusion occurs
     */
    public Map<Region, Double> getMedianValues() {
        return medianValues;
    }
    public Map<Region, Double> getMeanValues() {
        return meanValues;
    }
    public abstract Image drawInterfaceValues(RegionPopulation pop);
    public Image getIntensityMap() {
        return intensityMap;
    }
    /**
     * Allows merging of objects with a background object corresponding to the outter contour of the {@param foregroundMask}, allowing out-of-bound voxels
     * Voxels added to the interface can be out of the bounds of forground mask, a {@link Image#contains(int, int, int) } check should be done in the {@link InterfaceRegionImpl#addPair(Voxel, Voxel)} method
     * @param <T> type of split and merge instance
     * @param foregroundMask mask defining foreground, background is outside is mask 
     * @return same instance for convinience
     */
    public <T extends SplitAndMerge> T allowMergeWithBackground(ImageMask foregroundMask) {
        this.foregroundMask= foregroundMask;
        return (T)this;
    }
    public abstract Image getSeedCreationMap();
    public abstract Image getWatershedMap();
    protected abstract ClusterCollection.InterfaceFactory<Region, I> createFactory();
    public ClusterCollection.InterfaceFactory<Region, I> getFactory() {
        if (factory == null) factory = createFactory();
        return factory;
    }
    Predicate<I> forbidFusion;
    public SplitAndMerge<I> addForbidFusion(Predicate<I> forbidFusion) {
        if (forbidFusion==null) return this;
        if (this.forbidFusion==null) this.forbidFusion = forbidFusion;
        else this.forbidFusion = this.forbidFusion.or(forbidFusion);
        return this;
    }
    public SplitAndMerge<I> setForbidFusion(Predicate<I> forbidFusion) {
        this.forbidFusion = forbidFusion;
        return this;
    }
    public boolean isFusionForbidden(I inter) {
        return forbidFusion != null && forbidFusion.test(inter);
    }

    public void addFusionCriterion(FusionCriterion<Region, I> criterion) {
        fusionCriteria.add(criterion);
    }

    public boolean checkFusionCriteria(I inter) {
        for (FusionCriterion<Region, I> crit : fusionCriteria) {
            if (!crit.checkFusion(inter)) return false;
        }
        return true;
    }
    public void addForbidFusionForegroundBackground(Predicate<Region> isBackground, Predicate<Region> isForeground) {
        this.addForbidFusion(i->{
            if ((i.getE1().getLabel()==0 || isBackground.test(i.getE1())) && isForeground.test(i.getE2())) return true;
            if (isForeground.test(i.getE1()) && isBackground.test(i.getE2())) return true;
            return false;
        });
    }
    /**
     * Fusion is forbidden if difference of median values within regions is superior to {@param thldDiff}
     * @param thldDiff 
     */
    public void addForbidByMedianDifference(double thldDiff) {
        this.addForbidFusion(i->{
            double med1 = medianValues.get(i.getE1());
            double med2 = medianValues.get(i.getE2());
            return Math.abs(med1-med2)>thldDiff;
        });
    }
    
    public Function<RegionCluster<I>, Boolean> objectNumberLimitCondition(int numberOfElementsToKeep) {
        return (RegionCluster<I> c) -> c.elementNumberStopCondition(numberOfElementsToKeep).getAsBoolean();
    }
     /**
     * 
     * @param popWS population to merge according to criterion on hessian value @ interface
     * @param stopCondition condition to stop merging. if not null, criterion will not be checked
     * @return 
     */
    public RegionPopulation merge(RegionPopulation popWS, Function<RegionCluster<I>, Boolean> stopCondition) {
        RegionCluster.verbose=addTestImage!=null; //TODO proper test mode
        RegionCluster<I> c = new RegionCluster<>(popWS, foregroundMask, true, getFactory());
        c.addForbidFusionPredicate(forbidFusion);
        c.mergeSort(stopCondition==null, stopCondition==null ? () -> false : () -> stopCondition.apply(c));
        if (addTestImage!=null) addTestImage.accept(popWS.getLabelMap().duplicate("Split&Merge: regions after merge"));
        return popWS;
    }
    /**
     * 
     * @param segmentationMask
     * @param minSizePropagation
     * @param stopCondition
     * @return 
     */
    public RegionPopulation splitAndMerge(ImageMask segmentationMask, int minSizePropagation, Function<RegionCluster<I>, Boolean> stopCondition) {
        RegionPopulation popWS = split(segmentationMask, minSizePropagation);
        if (addTestImage!=null) {
            addTestImage.accept(getWatershedMap().duplicate("Split&Merge:WSMap"));
            addTestImage.accept(popWS.getLabelMap().duplicate("Split&Merge: seg map after split before merge"));
        }
        if (addTestImage!=null) logger.debug("Split & Merge: number of regions after split: {}", popWS.getRegions().size());
        return merge(popWS, stopCondition);
    }
    public RegionPopulation split(ImageMask segmentationMask, int minSizePropagation) {
        return split(segmentationMask, minSizePropagation, 1.5, 1);
    }
    public RegionPopulation split(ImageMask segmentationMask, int minSizePropagation, double seedRadius) {
        return split(segmentationMask, minSizePropagation, seedRadius, 1);
    }
    public RegionPopulation split(ImageMask segmentationMask, int minSizePropagation, double seedRadius, double seedRadiusZ) {
        ImageByte seeds = Double.isNaN(seedThreshold) ? Filters.localExtrema(getSeedCreationMap(), null, !localMinOnSeedMap, segmentationMask, Filters.getNeighborhood(seedRadius, seedRadiusZ, getSeedCreationMap()), parallel) :
                Filters.localExtrema(getSeedCreationMap(), null, !localMinOnSeedMap, seedThreshold, segmentationMask, Filters.getNeighborhood(seedRadius, seedRadiusZ, getSeedCreationMap()), parallel);
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(!increasingPropagation);
        if (minSizePropagation>1) config.fusionCriterion(new WatershedTransform.SizeFusionCriterion(minSizePropagation));
        RegionPopulation popWS = WatershedTransform.watershed(getWatershedMap(), segmentationMask, seeds, config);
        if (addTestImage!=null) popWS.sortBySpatialOrder(ObjectOrderTracker.IndexingOrder.YXZ);
        return popWS;
    }
    public RegionCluster<I> getInterfaces(RegionPopulation population, boolean lowConnectivity) {
        return new RegionCluster<>(population, lowConnectivity, getFactory());
    }
    public final BiFunction<? super I, ? super I, Integer> compareBySize(boolean largerFirst) {
        return (i1, i2) -> {
            double[] maxMin1 = i1.getE1().size()>i1.getE2().size() ? new double[]{i1.getE1().size(), i1.getE2().size()} : new double[]{i1.getE2().size(), i1.getE1().size()};
            double[] maxMin2 = i2.getE1().size()>i2.getE2().size() ? new double[]{i2.getE1().size(), i2.getE2().size()} : new double[]{i2.getE2().size(), i2.getE1().size()};
            if (largerFirst) {
                int c = Double.compare(maxMin1[0], maxMin2[0]);
                if (c!=0) return -c;
                return Double.compare(maxMin1[1], maxMin2[1]); // smaller first
            } else {
                int c = Double.compare(maxMin1[1], maxMin2[1]);
                if (c!=0) return c;
                return Double.compare(maxMin1[0], maxMin2[0]);
            }  
        };
        /*return (i1, i2) -> {
            int s1 = i1.getE1().getSize() + i1.getE2().getSize();
            int s2 = i2.getE1().getSize() + i2.getE2().getSize();
            return largerFirst ? Integer.compare(s2, s1) : Integer.compare(s1, s2);
        };*/
    }
    public SplitAndMerge addRegionChangedCallBack(Consumer<Region> regionChanged) {
        this.regionChanged = regionChanged.andThen(regionChanged);
        return this;
    }
    
    public BiFunction<? super I, ? super I, Integer> compareByMedianIntensity(boolean highIntensityFirst) {
        return (i1, i2) -> {
            double i11  = medianValues.get(i1.getE1());
            double i12  = medianValues.get(i1.getE2());
            double i21  = medianValues.get(i2.getE1());
            double i22  = medianValues.get(i2.getE2());
            
            if (highIntensityFirst) {
                double min1 = Math.min(i11, i12);
                double min2 = Math.min(i21, i22);
                int c = Double.compare(min1, min2);
                if (c!=0) return -c; // max of mins first
                double max1 = Math.max(i11, i12);
                double max2 = Math.max(i21, i22);
                return -Double.compare(max1, max2); // max of maxs first
            } else {
                double max1 = Math.max(i11, i12);
                double max2 = Math.max(i21, i22);
                int c = Double.compare(max1, max2);
                if (c!=0) return c;
                double min1 = Math.min(i11, i12);
                double min2 = Math.min(i21, i22);
                return Double.compare(min1, min2);
            }
        };
    }
}
