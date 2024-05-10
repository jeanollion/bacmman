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
package bacmman.processing.watershed;

import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.Voxel;
import bacmman.data_structure.CoordCollection;
import bacmman.data_structure.SortedCoordSet;
import bacmman.image.*;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.GeometricalFeature;
import bacmman.processing.Filters;
import bacmman.processing.ImageLabeller;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.ToLongBiFunction;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class WatershedTransform {
    public final static Logger logger = LoggerFactory.getLogger(WatershedTransform.class);
    public enum PropagationType {DIRECT, NORMAL};
    public static class WatershedConfiguration {
        boolean lowConnectivity;
        TrackSeedFunction trackSeedFunction;
        boolean decreasingPropagation;
        PropagationCriterion propagationCriterion;
        FusionCriterion fusionCriterion;
        PropagationType prop = PropagationType.NORMAL;
        public WatershedConfiguration() {}
        public WatershedConfiguration propagationCriterion(PropagationCriterion... c) {
            if (c.length==1) this.propagationCriterion = c[0];
            else if (c.length>1) this.propagationCriterion = new MultiplePropagationCriteria(c);
            return this;
        }
        public WatershedConfiguration propagation(PropagationType prop) {
            this.prop = prop;
            return this;
        }
        public WatershedConfiguration fusionCriterion(FusionCriterion c) {
            this.fusionCriterion = c;
            return this;
        }
        public WatershedConfiguration lowConectivity(boolean lowConnectivity) {
            this.lowConnectivity = lowConnectivity;
            return this;
        }
        public WatershedConfiguration decreasingPropagation(boolean decreasingPropagation) {
            this.decreasingPropagation = decreasingPropagation;
            return this;
        }

        public WatershedConfiguration setTrackSeeds(TrackSeedFunction trackSeedFunction) {
            this.trackSeedFunction = trackSeedFunction;
            return this;
        }
    }
    final protected SortedCoordSet heap;

    final protected Map<Integer, Spot> spots;
    final protected Image watershedMap;
    final protected ImageInteger segmentedMap;
    final protected ImageMask mask;
    final boolean is3D;
    public final boolean decreasingPropagation;
    protected boolean lowConnectivity = false;
    PropagationCriterion propagationCriterion;
    FusionCriterion fusionCriterion;
    protected TrackSeedFunction trackSeedFunction;
    PropagationType prop;
    public SortedCoordSet getHeap() {return heap;}
    public Map<Integer, Spot> getSpots() {return spots;}
    public static List<Region> duplicateSeeds(List<Region> seeds) {
        List<Region> res = new ArrayList<>(seeds.size());
        for (Region o : seeds) res.add(new Region(new HashSet<>(o.getVoxels()), o.getLabel(), o.is2D(), o.getScaleXY(), o.getScaleZ()));
        return res;
    }
    public static List<Region> createSeeds(List<Voxel> seeds, boolean is2D, double scaleXY, double scaleZ) {
        List<Region> res = new ArrayList<>(seeds.size());
        int label = 1;
        for (Voxel v : seeds) res.add(new Region(new HashSet<Voxel>(){{add(v);}}, label++, is2D, scaleXY, scaleZ));
        return res;
    }
    public static RegionPopulation watershed(Image watershedMap, ImageMask mask, WatershedConfiguration config) {
        ImageByte seeds = Filters.localExtrema(watershedMap, null, config.decreasingPropagation, mask, Filters.getNeighborhood(1.5, 1.5, watershedMap), false);
        //new IJImageDisplayer().showImage(seeds.setName("seeds"));
        return watershed(watershedMap, mask, seeds, config);
    }
    public static RegionPopulation watershed(Image watershedMap, ImageMask mask, ImageMask seeds, WatershedConfiguration config) {
        return watershed(watershedMap, mask, Arrays.asList(ImageLabeller.labelImage(seeds)), config);
    }
    /**
     *
     * @param watershedMap
     * @param mask
     * @param regionalExtrema CONTAINED REGION WILL BE MODIFIED
     * @param  config watershed configuration
     * @return
     */
    public static RegionPopulation watershed(Image watershedMap, ImageMask mask, List<Region> regionalExtrema, WatershedConfiguration config) {
        WatershedTransform wt = new WatershedTransform(watershedMap, mask, regionalExtrema, config);
        wt.run();
        return wt.getRegionPopulation();
    }

    /**
     *
     * @param watershedMap
     * @param mask
     * @param regionalExtrema CONTAINED REGION WILL BE MODIFIED
     * @param config watershed configuration
     */
    public WatershedTransform(Image watershedMap, ImageMask mask, List<Region> regionalExtrema, WatershedConfiguration config) {
        if (mask==null) mask=new BlankMask( watershedMap);
        if (config ==null) config = new WatershedConfiguration(); // default config
        this.decreasingPropagation = config.decreasingPropagation;
        this.lowConnectivity = config.lowConnectivity;
        this.prop = config.prop;
        heap = SortedCoordSet.create(watershedMap, decreasingPropagation);
        this.mask=mask;
        this.watershedMap=watershedMap;
        spots = new HashMap<>(regionalExtrema.size()+1);
        segmentedMap = ImageInteger.createEmptyLabelImage("segmentationMap", regionalExtrema.size()+1, watershedMap);
        if (config.trackSeedFunction!=null) setTrackSeedFunction(config.trackSeedFunction);
        is3D=watershedMap.sizeZ()>1;
        if (config.propagationCriterion==null) setPropagationCriterion(new DefaultPropagationCriterion());
        else setPropagationCriterion(config.propagationCriterion);
        if (config.fusionCriterion==null) setFusionCriterion(new DefaultFusionCriterion());
        else setFusionCriterion(config.fusionCriterion);

        // create spots
        for (int i = 0; i<regionalExtrema.size(); ++i) spots.put(i+1, new Spot(i+1, regionalExtrema.get(i).getVoxels())); // do modify seed objects
        //logger.debug("watershed transform: number of seeds: {} Segmented map type: {}, decreasing prop: {}", regionalExtrema.size(), segmentedMap.getClass().getSimpleName(), decreasingPropagation);
    }

    public WatershedTransform setFusionCriterion(FusionCriterion fusionCriterion) {
        this.fusionCriterion=fusionCriterion;
        fusionCriterion.setUp(this);
        return this;
    }
    
    public WatershedTransform setPropagationCriterion(PropagationCriterion propagationCriterion) {
        this.propagationCriterion=propagationCriterion;
        propagationCriterion.setUp(this);
        return this;
    }
    public WatershedTransform setTrackSeedFunction(TrackSeedFunction trackSeedFunction) {
        this.trackSeedFunction=trackSeedFunction;
        trackSeedFunction.setUp(this);
        return this;
    }
    public void run() {
        if (null == prop) throw new IllegalArgumentException("Unknown propagation type");
        else switch (prop) {
            case NORMAL:
                runNormal();
                break;
            case DIRECT:
                runDirectSegmentation();
                break;
            default:
                throw new IllegalArgumentException("Unknown propagation type");
        }
    }
    public void runNormal() {
        double rad = lowConnectivity ? 1 : 1.5;
        EllipsoidalNeighborhood neigh = watershedMap.sizeZ()>1?new EllipsoidalNeighborhood(rad, rad, true) : new EllipsoidalNeighborhood(rad, true);
        for (Spot s : spots.values()) { // initialize with direct neighbors of spots
            s.voxels.stream()
                .filter(c->heap.insideMask(mask, c))
                .forEach( c -> {
                    IntStream.range(0, neigh.getSize())
                        .filter(nidx -> heap.insideBounds(c, neigh.dx[nidx], neigh.dy[nidx], neigh.dz[nidx]))
                        .mapToLong(nidx -> heap.translate(c, neigh.dx[nidx], neigh.dy[nidx], neigh.dz[nidx]))
                        .filter(n -> !heap.insideMask(segmentedMap, n))
                        .filter(n -> heap.insideMask(mask, n))
                        .filter(n -> propagationCriterion.continuePropagation(c, n))
                        .forEach(heap::add);
                    }
                );


        }
        Score score = generateScore();
        CoordCollection nextProp = CoordCollection.create(heap.sizeX(), heap.sizeY(), heap.sizeZ());
        Set<Integer> surroundingLabels = fusionCriterion==null || fusionCriterion instanceof DefaultFusionCriterion ? null : new HashSet<>(neigh.getSize());
        //logger.debug("fusion crit: {} surr. label null ? {}", fusionCriterion==null ? "null" : fusionCriterion.getClass(), surroundingLabels==null);
        //logger.debug("Start loop with {} pixels, {}", heap.size(), ((CoordSet.SortedCoordSet2DList)heap).log(watershedMap));
        long count = 0;
        while (!heap.isEmpty()) {
            long c = heap.pollFirst();
            if (heap.insideMask(segmentedMap, c)) continue; //already segmented
            score.setUp(c);
            for (int i = 0; i<neigh.getSize(); ++i) { // check all neighbors
                if (!heap.insideBounds(c, neigh.dx[i], neigh.dy[i], neigh.dz[i])) continue;
                long n = heap.translate(c, neigh.dx[i], neigh.dy[i], neigh.dz[i]);
                if (!heap.insideMask(mask, n)) continue;
                int nextLabel = heap.getPixelInt(segmentedMap, n);
                if (nextLabel>0) { // if already segmented
                    if (surroundingLabels!=null) surroundingLabels.add(nextLabel); // add to surrounding labels for fusion criterion
                    score.add(n, nextLabel); // add candidate spot for segmentation
                } else { // else -> add to propagation heap
                    nextProp.add(n);
                }
            }

            int currentLabel = score.getLabel();
            //logger.debug("coord: {}, nextprop: {}, label: {} surr labels: {}", heap.parse(c), nextProp.size(), currentLabel, surroundingLabels);
            if (spots.get(currentLabel)==null) {
                logger.error("Error: label: {} has no spot", currentLabel);
            }
            spots.get(currentLabel).addVox(c);
            // check propagation criterion
            nextProp.removeIf(n->!propagationCriterion.continuePropagation(c, n));
            heap.addAll(nextProp);
            nextProp.clear();
            // check fusion criterion for all surrounding labels
            if (surroundingLabels!=null) {
                surroundingLabels.remove(currentLabel);
                if (!surroundingLabels.isEmpty()) {
                    Spot currentSpot = spots.get(currentLabel);
                    for (int otherLabel : surroundingLabels) {
                        if (fusionCriterion.checkFusionCriteria(currentSpot, spots.get(otherLabel), c)) {
                            currentSpot = currentSpot.fusion(spots.get(otherLabel));
                        }
                    }
                    surroundingLabels.clear();
                }
            }
        }
    }

    public void runDirectSegmentation() {
        for (Spot s : spots.values()) if (s!=null) heap.addAll(s.voxels); // initialize with seeds
        double rad = lowConnectivity ? 1 : 1.5;
        EllipsoidalNeighborhood neigh = watershedMap.sizeZ()>1?new EllipsoidalNeighborhood(rad, rad, true) : new EllipsoidalNeighborhood(rad, true);
        while (!heap.isEmpty()) {
            long c = heap.pollFirst();
            Spot currentSpot = spots.get(heap.getPixelInt(segmentedMap, c));
            if (currentSpot ==null) {
                logger.error("spot null @ v={} label: {}", heap.parse(c, null), heap.getPixelInt(segmentedMap, c));
                throw new RuntimeException("NULL SPOT");
            }
            for (int i = 0; i<neigh.getSize(); ++i) {
                if (!heap.insideBounds(c, neigh.dx[i], neigh.dy[i], neigh.dz[i])) continue;
                long n = heap.translate(c, neigh.dx[i], neigh.dy[i], neigh.dz[i]);
                if (!heap.insideMask(mask, n)) continue;
                int nextLabel = heap.getPixelInt(segmentedMap, n);
                if (nextLabel==currentSpot.label) continue;
                else if (nextLabel>0) {
                    if (fusionCriterion.checkFusionCriteria(currentSpot, spots.get(nextLabel), n)) {
                        currentSpot = currentSpot.fusion( spots.get(nextLabel));
                    }
                } else {
                    if (propagationCriterion.continuePropagation(c, n)){
                        currentSpot.addVox(n);
                        heap.add(n);
                    }
                }
            }
        }
    }
    private Score generateScore() {
        return new CountMinDiffWsMap();
    }
    private interface Score {
        void setUp(long center);
        void add(long c, int label);
        int getLabel();
    }
    private class CountMinDiffWsMap implements Score {
        double centerV = 0;
        Map<Integer, Integer> countMap = new HashMap<>(8);
        Map<Integer, Double> diffMap = new HashMap<>(8);
        @Override
        public void add(long c, int label) {
            countMap.put(label, countMap.getOrDefault(label, 0)+1);
            double diff = Math.abs(centerV - heap.getPixel(watershedMap, c));
            if (diff<diffMap.getOrDefault(label, Double.POSITIVE_INFINITY)) {
                diffMap.put(label, diff);
            }
        }
        @Override
        public int getLabel() {
            // max of counts
            if (countMap.isEmpty()) return 0;
            else if (countMap.size()==1) return countMap.entrySet().iterator().next().getKey();
            Map.Entry<Integer, Integer> maxLabel = null;
            for (Map.Entry<Integer, Integer> e : Utils.entriesSortedByValues(countMap, true)) {
                if (maxLabel==null) maxLabel = e;
                else if (maxLabel.getValue().equals(e.getValue())) { // same number of neighbors -> minimize diff
                    if (diffMap.get(e.getKey())<diffMap.get(maxLabel.getKey())) maxLabel = e;
                } else break; // no equal
            }
            return maxLabel.getKey();
        }
        @Override
        public void setUp(long center) {
            centerV = heap.getPixel(watershedMap, center);
            countMap.clear();
            diffMap.clear();
        }
    }

    
    
    public Image getWatershedMap() {
        return watershedMap;
    }
    
    public ImageInteger getLabelImage() {return segmentedMap;}
    /**
     * 
     * @return Result of watershed transform as RegionPopulation, relative landmark to the watershedMap
     */
    public RegionPopulation getRegionPopulation() {
        //int nb = 0;
        //for (Spot s : wt.spots) if (s!=null) nb++;
        ArrayList<Region> res = new ArrayList<>(spots.size());
        int label = 1;
        for (Spot s : spots.values()) if (s!=null) res.add(s.toRegion(label++));
        return new RegionPopulation(res, new BlankMask(watershedMap).resetOffset()).setConnectivity(lowConnectivity);
    }

    public class Spot {
        public CoordCollection voxels;
        int label;
        public long seedCoord;
        public Spot(int label, Collection<Voxel> seeds) {
            this.label=label;
            this.voxels= CoordCollection.create(heap.sizeX(), heap.sizeY(), heap.sizeZ());
            if (trackSeedFunction!=null) { // set seed
                if (voxels.size() == 1) seedCoord = voxels.stream().iterator().next();
                else {
                    double[] center = new double[3];
                    for (Voxel v : seeds) {
                        center[0] += v.x;
                        center[1] += v.y;
                        center[2] += v.z;
                    }
                    double s = seeds.size();
                    seedCoord = getHeap().toCoord((int)Math.round(center[0] / s), (int)Math.round(center[1] / s), (int)Math.round(center[2] / s));
                }
            }
            seeds.stream().mapToLong(v -> heap.toCoord(v.x, v.y, v.z)).forEach(l -> this.voxels.add(l));
            for (Voxel v : seeds) {
                segmentedMap.setPixel(v.x, v.y, v.z, label);
            }
        }
        
        public void setLabel(int label) {
            this.label=label;
            voxels.stream().forEach(l -> heap.setPixel(segmentedMap, l, label));
        }

        public Spot fusion(Spot spot) {
            if (spot.label<label) return spot.fusion(this);
            //logger.debug("fusion: {}+{}", this.label, spot.label);
            spots.remove(spot.label);
            spot.setLabel(label);
            this.voxels.addAll(spot.voxels); // pas besoin de check si voxels.contains(v) car les spots ne se recouvrent pas            //update seed: lowest seedIntensity
            if (trackSeedFunction!=null) {
                seedCoord = trackSeedFunction.getSeed(this, spot);
            }
            return this;
        }
        
        public void addVox(long v) {
            int value = heap.getPixelInt(segmentedMap, v);
            if (value!=0) throw new IllegalArgumentException("Voxel already segmented");
            voxels.add(v);
            heap.setPixel(segmentedMap, v, label);
        }
        
        public Region toRegion(int label) {
            return new Region(voxels.getMask(mask.getScaleXY(), mask.getScaleZ()), label, heap.sizeZ()==1);
        }
        
        public double getQuality() {
            DoubleStream s = voxels.stream(watershedMap);
            if (decreasingPropagation) {
                return s.max().orElse(Double.NEGATIVE_INFINITY);
            } else {
                return -s.min().orElse(Double.POSITIVE_INFINITY);
            }
        }
        
    }
    public interface PropagationCriterion {
        public void setUp(WatershedTransform instance);
        public boolean continuePropagation(long currentVox, long nextVox);
    }
    public static class DefaultPropagationCriterion implements PropagationCriterion {
        @Override public boolean continuePropagation(long currentVox, long nextVox) {
            return true;
        }
        @Override public void setUp(WatershedTransform instance) {}
    }
    public static class MonotonalPropagation implements PropagationCriterion {
        boolean decreasingPropagation;
        WatershedTransform instance;
        @Override public void setUp(WatershedTransform instance) {
            this.instance = instance;
            setPropagationDirection(instance.decreasingPropagation);
        }
        public MonotonalPropagation setPropagationDirection(boolean decreasingPropagation) {
            this.decreasingPropagation=decreasingPropagation;
            return this;
        }
        @Override public boolean continuePropagation(long currentVox, long nextVox) {
            if (decreasingPropagation) return (instance.heap.getPixel(instance.watershedMap, nextVox)<=instance.heap.getPixel(instance.watershedMap, currentVox));
            else return (instance.heap.getPixel(instance.watershedMap, nextVox)>=instance.heap.getPixel(instance.watershedMap, currentVox));
        }
    }
    public static class ThresholdPropagation implements PropagationCriterion {
        Image image;
        double threshold;
        boolean stopWhenInferior;
        WatershedTransform instance;
        public ThresholdPropagation(Image image, double threshold, boolean stopWhenInferior) {
            this.image=image;
            this.threshold=threshold;
            this.stopWhenInferior=stopWhenInferior;
        }
        @Override public void setUp(WatershedTransform instance) {this.instance=instance;}
        @Override public boolean continuePropagation(long currentVox, long nextVox) {
            return stopWhenInferior?instance.heap.getPixel(image, nextVox)>threshold:instance.heap.getPixel(image, nextVox)<threshold;
        }
    }
    public static class LocalThresholdPropagation implements PropagationCriterion {
        Image image;
        Map<Integer, Double> thresholdMap;
        boolean stopWhenInferior;
        WatershedTransform wst;
        public LocalThresholdPropagation(Image image, Map<Integer, Double> thresholdMap, boolean stopWhenInferior) {
            this.image=image;
            this.thresholdMap=thresholdMap;
            this.stopWhenInferior=stopWhenInferior;
        }
        @Override public void setUp(WatershedTransform instance) {
            this.wst = instance;
        }
        @Override public boolean continuePropagation(long currentVox, long nextVox) {
            double threshold = thresholdMap.get(wst.heap.getPixelInt(wst.segmentedMap,currentVox));
            return stopWhenInferior?wst.heap.getPixel(image,nextVox)>threshold:wst.heap.getPixel(image,nextVox)<threshold;
        }
    }
    public static class ThresholdPropagationOnWatershedMap implements PropagationCriterion {
        boolean stopWhenInferior;
        double threshold;
        WatershedTransform wst;
        @Override public void setUp(WatershedTransform instance) {
            stopWhenInferior = instance.decreasingPropagation;
            this.wst = instance;
        }
        public ThresholdPropagationOnWatershedMap(double threshold) {
            this.threshold=threshold;
        }
        public boolean continuePropagation(long currentVox, long nextVox) {
            return stopWhenInferior?wst.heap.getPixel(wst.watershedMap, nextVox)>threshold:wst.heap.getPixel(wst.watershedMap, nextVox)<threshold;
        }
        
    }
    public static class MultiplePropagationCriteria implements PropagationCriterion {
        PropagationCriterion[] criteria;
        public MultiplePropagationCriteria(PropagationCriterion... criteria) {
            this.criteria=criteria;
        } 
        @Override public void setUp(WatershedTransform instance) {
            for (PropagationCriterion c : criteria) c.setUp(instance);
        }
        public boolean continuePropagation(long currentVox, long nextVox) {
            for (PropagationCriterion p : criteria) if (!p.continuePropagation(currentVox, nextVox)) return false;
            return true;
        }
    }
    
    public interface FusionCriterion {
        public void setUp(WatershedTransform instance);
        public boolean checkFusionCriteria(Spot s1, Spot s2, long currentVoxel);
    }
    public static class DefaultFusionCriterion implements FusionCriterion {
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, long currentVoxel) {
            return false;
        }
        @Override public void setUp(WatershedTransform instance) {}
    }
    public static class SizeFusionCriterion implements FusionCriterion {
        int minimumSize;
        public SizeFusionCriterion(int minimumSize) {
            this.minimumSize=minimumSize;
        }
        @Override public void setUp(WatershedTransform instance) {}
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, long currentVoxel) {
            return s1.voxels.size()<=minimumSize || s2.voxels.size()<=minimumSize;
        }
    }
    public static class ThresholdFusionOnWatershedMap implements FusionCriterion {
        double threshold;
        WatershedTransform instance;
        public ThresholdFusionOnWatershedMap(double threshold) {
            this.threshold=threshold;
        }
        @Override public void setUp(WatershedTransform instance) {this.instance=instance;}
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, long currentVoxel) {
            return instance.decreasingPropagation ? instance.heap.getPixel(instance.watershedMap, currentVoxel)>threshold : instance.heap.getPixel(instance.watershedMap, currentVoxel)<threshold;
        }
    }
    public static class NumberFusionCriterion implements FusionCriterion {
        int numberOfSpots;
        WatershedTransform instance;
        public NumberFusionCriterion(int minNumberOfSpots) {
            this.numberOfSpots=minNumberOfSpots;
        }
        @Override public void setUp(WatershedTransform instance) {this.instance=instance;}
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, long currentVoxel) {
            return instance.spots.size()>numberOfSpots;
        }
    }
    public static class MultipleFusionCriteriaAnd implements FusionCriterion {
        FusionCriterion[] criteria;
        public MultipleFusionCriteriaAnd(FusionCriterion... criteria) {
            this.criteria=criteria;
        } 
        @Override public void setUp(WatershedTransform instance) {
            for (FusionCriterion f : criteria) f.setUp(instance);
        }
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, long currentVoxel) {
            if (criteria.length==0) return false;
            for (FusionCriterion c : criteria) if (!c.checkFusionCriteria(s1, s2, currentVoxel)) return false;
            return true;
        }
    }
    public static class MultipleFusionCriteriaOr implements FusionCriterion {
        FusionCriterion[] criteria;
        public MultipleFusionCriteriaOr(FusionCriterion... criteria) {
            this.criteria=criteria;
        } 
        @Override public void setUp(WatershedTransform instance) {
            for (FusionCriterion f : criteria) f.setUp(instance);
        }
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, long currentVoxel) {
            if (criteria.length==0) return false;
            for (FusionCriterion c : criteria) if (c.checkFusionCriteria(s1, s2, currentVoxel)) return true;
            return false;
        }
    }
    public interface TrackSeedFunction {
        void setUp(WatershedTransform instance);
        long getSeed(Spot s1, Spot s2);
    }
    public static TrackSeedFunction getIntensityTrackSeedFunction(Image image, boolean keepLowest) {
        return new TrackSeedFunction() {
            WatershedTransform instance;
            @Override
            public void setUp(WatershedTransform instance) {
                this.instance=instance;
            }

            @Override
            public long getSeed(Spot s1, Spot s2) {
                double v1 = instance.getHeap().getPixel(image, s1.seedCoord);
                double v2 = instance.getHeap().getPixel(image, s2.seedCoord);
                if (keepLowest) {
                    return v1<=v2 ? s1.seedCoord:s2.seedCoord;
                } else {
                    return v1<=v2 ? s2.seedCoord:s1.seedCoord;
                }
            }
        };
    }

}
