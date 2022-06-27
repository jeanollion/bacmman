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

import bacmman.data_structure.*;
import bacmman.processing.Filters;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.image.ImageLabeller;
import bacmman.image.ImageMask;

import java.util.*;

import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class WatershedTransform {
    public final static Logger logger = LoggerFactory.getLogger(WatershedTransform.class);
    public enum PropagationType {DIRECT, NORMAL};
    public static class WatershedConfiguration {
        boolean lowConnectivity;
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
    }
    final protected TreeSet<Voxel> heap;
    final protected Spot[] spots; // map label -> spot (spots[0]==null)
    protected int spotNumber;
    final protected Image watershedMap;
    protected Image priorityMap;
    final protected ImageInteger segmentedMap;
    final protected ImageMask mask;
    final boolean is3D;
    public final boolean decreasingPropagation;
    protected boolean lowConnectivity = false;
    protected boolean computeSpotCenter = false;
    PropagationCriterion propagationCriterion;
    FusionCriterion fusionCriterion;
    PropagationType prop;
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
        ImageByte seeds = Filters.localExtrema(watershedMap, null, config.decreasingPropagation, mask, Filters.getNeighborhood(1.5, 1.5, watershedMap));
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
        heap = decreasingPropagation ? new TreeSet<>(Voxel.getInvertedComparator()) : new TreeSet<>(Voxel.getComparator());
        //heap = decreasingPropagation ? new PriorityQueue<>(Voxel.getInvertedComparator()) : new PriorityQueue<>();
        this.mask=mask;
        this.watershedMap=watershedMap;
        spots = new Spot[regionalExtrema.size()+1];
        spotNumber=regionalExtrema.size();
        segmentedMap = ImageInteger.createEmptyLabelImage("segmentationMap", spots.length, watershedMap);
        for (int i = 0; i<regionalExtrema.size(); ++i) spots[i+1] = new Spot(i+1, regionalExtrema.get(i).getVoxels()); // do modify seed objects
        //logger.debug("watershed transform: number of seeds: {} Segmented map type: {}", regionalExtrema.size(), segmentedMap.getClass().getSimpleName());
        is3D=watershedMap.sizeZ()>1;   
        if (config.propagationCriterion==null) setPropagationCriterion(new DefaultPropagationCriterion());
        else setPropagationCriterion(config.propagationCriterion);
        if (config.fusionCriterion==null) setFusionCriterion(new DefaultFusionCriterion());
        else setFusionCriterion(config.fusionCriterion);
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
    public WatershedTransform setPriorityMap(Image priorityMap) {
        if (!watershedMap.sameDimensions(priorityMap)) throw new IllegalArgumentException("PriorityMap should have same dimensions as watershed map");
        this.priorityMap=priorityMap;
        this.computeSpotCenter = true;
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
        for (Spot s : spots) { // initialize with direct neighbors of spots
            if (s!=null) {
                for (Voxel v : s.voxels) {
                    if (!mask.insideMask(v.x, v.y, v.z)) continue;
                    for (int i = 0; i<neigh.getSize(); ++i) {
                        Voxel n = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], v.z+neigh.dz[i]) ;
                        if (segmentedMap.contains(n.x, n.y, n.z) && mask.insideMask(n.x, n.y, n.z)) {
                            n.value = watershedMap.getPixel(n.x, n.y, n.z);
                            heap.add(n);
                        }
                    }
                }
            }
        }
        Score score = generateScore();
        List<Voxel> nextProp  = new ArrayList<>(neigh.getSize());
        Set<Integer> surroundingLabels = fusionCriterion==null || fusionCriterion instanceof DefaultFusionCriterion ? null : new HashSet<>(neigh.getSize());
        //logger.debug("fusion crit: {} surr. label null ? {}", fusionCriterion==null ? "null" : fusionCriterion.getClass(), surroundingLabels==null);
        while (!heap.isEmpty()) {
            //Voxel v = heap.poll();
            Voxel v = heap.pollFirst();
            /*if (!segmentedMap.contains(v.x, v.y, v.z)) {
                logger.error("OOB voxel in heap: {}, bounds: {}", v, segmentedMap.getBoundingBox().resetOffset());
                throw new RuntimeException("OOB voxel in heap");
            }*/
            if (segmentedMap.getPixelInt(v.x, v.y, v.z)>0) continue; //already segmented
            score.setUp(v);
            for (int i = 0; i<neigh.getSize(); ++i) { // check all neighbors
                Voxel n = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], v.z+neigh.dz[i]) ;
                if (segmentedMap.contains(n.x, n.y, n.z) && mask.insideMask(n.x, n.y, n.z)) {
                    int nextLabel = segmentedMap.getPixelInt(n.x, n.y, n.z);
                    if (nextLabel>0) { // if already segmented
                        if (surroundingLabels!=null) surroundingLabels.add(nextLabel); // add to surrounding labels for fusion criterion
                        score.add(n, nextLabel); // add candidate spot for segmentation
                    } else { // else -> add to propagation heap
                        n.value = watershedMap.getPixel(n.x, n.y, n.z);
                        nextProp.add(n);
                    }
                }
            }
            int currentLabel = score.getLabel();
            if (spots[currentLabel]==null) {
                logger.error("WS error no spot for label: {} voxel: {}. low connectivity: {} Labels: row up: {} {} {} row center: {} {} {} row down: {} {} {}", currentLabel, v, lowConnectivity, v.y>0 && v.x>0?segmentedMap.getPixelInt(v.x-1, v.y-1, v.z):"OOB", v.y>0?segmentedMap.getPixelInt(v.x, v.y-1, v.z):"OOB", v.y>0 && v.x<segmentedMap.sizeX()-1 ? segmentedMap.getPixelInt(v.x+1, v.y-1, v.z) : "OOB", v.x>0?segmentedMap.getPixelInt(v.x-1, v.y, v.z):"00B", segmentedMap.getPixelInt(v.x, v.y, v.z), v.x<segmentedMap.sizeX()?segmentedMap.getPixelInt(v.x+1, v.y, v.z):"OOB", v.x>0&&v.y<segmentedMap.sizeY()-1?segmentedMap.getPixelInt(v.x-1, v.y+1, v.z):"OOB", v.y<segmentedMap.sizeY()-1?segmentedMap.getPixelInt(v.x, v.y+1, v.z):"OOB", v.y<segmentedMap.sizeY()-1 && v.x<segmentedMap.sizeX()-1 ? segmentedMap.getPixelInt(v.x+1, v.y+1, v.z) : "OOB");
                throw new RuntimeException("WS error no spot for label");
            }
            spots[currentLabel].addVox(v);
            // check propagation criterion
            nextProp.removeIf(n->!propagationCriterion.continuePropagation(v, n));
            heap.addAll(nextProp);
            nextProp.clear();
            // check fusion criterion for all surrounding labels
            if (surroundingLabels!=null) {
                surroundingLabels.remove(currentLabel);
                if (!surroundingLabels.isEmpty()) {
                    Spot currentSpot = spots[currentLabel];
                    for (int otherLabel : surroundingLabels) {
                        if (fusionCriterion.checkFusionCriteria(currentSpot, spots[otherLabel], v)) {
                            currentSpot = currentSpot.fusion(spots[otherLabel]);
                        }
                    }
                    surroundingLabels.clear();
                }
            }
        }
    }
    
    
    
    public void runDirectSegmentation() {
        
        for (Spot s : spots) if (s!=null) heap.addAll(s.voxels);
        double rad = lowConnectivity ? 1 : 1.5;
        EllipsoidalNeighborhood neigh = watershedMap.sizeZ()>1?new EllipsoidalNeighborhood(rad, rad, true) : new EllipsoidalNeighborhood(rad, true);
        while (!heap.isEmpty()) {
            //Voxel v = heap.poll();
            Voxel v = heap.pollFirst();
            Spot currentSpot = spots[segmentedMap.getPixelInt(v.x, v.y, v.z)];
            if (currentSpot ==null) logger.error("spot null @ v={} label: {}", v, segmentedMap.getPixelInt(v.x, v.y, v.z));
            for (int i = 0; i<neigh.getSize(); ++i) {
                Voxel n = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], v.z+neigh.dz[i]) ;
                if (segmentedMap.contains(n.x, n.y, n.z) && mask.insideMask(n.x, n.y, n.z)) {
                    int nextLabel = segmentedMap.getPixelInt(n.x, n.y, n.z);
                    if (nextLabel==currentSpot.label) continue;
                    else if (nextLabel>0) {
                        if (fusionCriterion.checkFusionCriteria(currentSpot, spots[nextLabel], n)) {
                            currentSpot = currentSpot.fusion( spots[nextLabel]);
                        }
                    } else {
                        n.value =watershedMap.getPixel(n.x, n.y, n.z);
                        if (propagationCriterion.continuePropagation(v, n)){
                            currentSpot.addVox(n);
                            heap.add(n);
                            
                        }
                    }
                }
            }
        }
    }
    private Score generateScore() {
        if (this.priorityMap!=null) {
            return new MinDistance();
            //return new MaxPriority();
            //return new MaxSeedPriority();
        } 
        //return new MaxDiffWsMap(); // for WS map = EDGE map
        //return new MinDiffWsMap();
        return new CountMinDiffWsMap();
    }
    private interface Score {
        public abstract void setUp(Voxel center);
        public abstract void add(Voxel v, int label);
        public abstract int getLabel();
    }
    private class CountMinDiffWsMap implements Score {
        double centerV = 0;
        Map<Integer, Integer> countMap = new HashMap<>(8);
        Map<Integer, Double> diffMap = new HashMap<>(8);
        @Override
        public void add(Voxel v, int label) {
            countMap.put(label, countMap.getOrDefault(label, 0)+1);
            double diff = Math.abs(centerV - watershedMap.getPixel(v.x, v.y, v.z));
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
        public void setUp(Voxel center) {
            centerV = center.value;
            countMap.clear();
            diffMap.clear();
        }
    }
    private class MinDiffWsMap implements Score {
        double centerV = 0;
        double curDiff = Double.POSITIVE_INFINITY;
        int curLabel;
        @Override
        public void add(Voxel v, int label) {
            //double diff=!decreasingPropagation ? watershedMap.getPixel(v.x, v.y, v.z) : -watershedMap.getPixel(v.x, v.y, v.z);
            double diff = Math.abs(centerV - watershedMap.getPixel(v.x, v.y, v.z));
            if (diff<curDiff) {
                curDiff=diff;
                curLabel = label;
            }
        }
        @Override
        public int getLabel() {
            return curLabel;
        }
        @Override
        public void setUp(Voxel center) {
            centerV = center.value;
            curDiff = Double.POSITIVE_INFINITY; // reset
            curLabel=0;
        }
    }
    private class MaxDiffWsMap implements Score { // assign to adjacent spot with maximal difference in watershed map
        double centerV = 0;
        double curDiff = Double.NEGATIVE_INFINITY; // reset
        int curLabel = 0;
        @Override
        public void add(Voxel v, int label) {
            //double diff=!decreasingPropagation ? watershedMap.getPixel(v.x, v.y, v.z) : -watershedMap.getPixel(v.x, v.y, v.z);
            double diff = Math.abs(watershedMap.getPixel(v.x, v.y, v.z)-centerV);
            if (diff>curDiff) {
                curDiff=diff;
                curLabel = label;
            }
        }
        @Override
        public int getLabel() {
            return curLabel;
        }
        @Override
        public void setUp(Voxel center) {
            centerV = center.value;
            curDiff = Double.NEGATIVE_INFINITY; // reset
            curLabel=0;
        }
    }
    private class MinDiffPriorityMap implements Score {
        Voxel center;
        double centerP;
        double curDiff = Double.MAX_VALUE;;
        int curLabel;
        @Override
        public void add(Voxel v, int label) {
            double diff=Math.abs(priorityMap.getPixel(v.x, v.y, v.z)-centerP) ;
            if (diff<curDiff) {
                curDiff=diff;
                curLabel = label;
            }
        }
        @Override
        public int getLabel() {
            return curLabel;
        }
        @Override
        public void setUp(Voxel center) {
            centerP = priorityMap.getPixel(center.x, center.y, center.z);
            curDiff = Double.MAX_VALUE; // reset
            curLabel=0;
        }
    }
    private class MinDistance implements Score {
        Voxel center;
        double curDistSq;
        int curLabel;
        @Override
        public void add(Voxel v, int label) {
            double d = spots[label].distSq(center);
            if (d<curDistSq) {
                curDistSq = d;
                curLabel = label;
            }
        }
        @Override
        public int getLabel() {
            return curLabel;
        }
        @Override
        public void setUp(Voxel center) {
            this.center=center;
            curDistSq = Double.POSITIVE_INFINITY; // reset
            curLabel=0;
        }
    }
    private class MaxPriority implements Score {
        Voxel center, curVoxel;
        double priority;
        int curLabel;
        HashMapGetCreate<Integer, int[]> count = new HashMapGetCreate<>(i->new int[1]);
        @Override
        public void add(Voxel v, int label) {
            double p = priorityMap.getPixel(v.x, v.y, v.z);
            if (p>priority || (p==priority && spots[curLabel].distSq(center)>spots[label].distSq(center))) {
                priority = p;
                curLabel = label;
                curVoxel = v;
                count.clear();
            } else if (p==priority) { // max count ?
                if (count.isEmpty()) count.getAndCreateIfNecessary(curLabel)[0]++; // add count for previous label
                count.getAndCreateIfNecessary(label)[0]++;
            }
        }
        @Override
        public int getLabel() {
            if (!count.isEmpty()) return Collections.max(count.entrySet(), (e1, e2)->Integer.compare(e1.getValue()[0], e2.getValue()[0])).getKey();
            return curLabel;
        }
        @Override
        public void setUp(Voxel center) {
            this.center=center;
            curVoxel = null;
            priority = Double.NEGATIVE_INFINITY; // reset
            curLabel=0;
            count.clear();
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
        ArrayList<Region> res = new ArrayList<>(spotNumber);
        int label = 1;
        for (Spot s : spots) if (s!=null) res.add(s.toRegion(label++));
        return new RegionPopulation(res, new BlankMask(watershedMap).resetOffset()).setConnectivity(lowConnectivity);
    }
    public Spot[] getSpotArray() {
        return spots;
    }
    public Collection<Voxel> getHeap() {
        return heap;
    }
    
    
    public class Spot {
        public Set<Voxel> voxels;
        int label;
        float priorityValue;
        double[] center;
        public Spot(int label, Collection<Voxel> voxels) {
            this.label=label;
            this.voxels=new HashSet<>(voxels);
            if (computeSpotCenter) center= new double[3];
            for (Voxel v : voxels) {
                v.value=watershedMap.getPixel(v.x, v.y, v.z);
                if (center!=null) {
                    center[0]+=v.x;
                    center[1]+=v.y;
                    center[2]+=v.z;
                }
                if (priorityMap!=null) {
                    float p = priorityMap.getPixel(v.x, v.y, v.z);
                    if (p>priorityValue) priorityValue = p;
                }
                segmentedMap.setPixel(v.x, v.y, v.z, label);
            }
            if (center!=null && voxels.size()>1) {
                center[0]/=voxels.size();
                center[1]/=voxels.size();
                center[2]/=voxels.size();
                logger.debug("spot: {} center: {}", this.label, center);
            }
            
        }
        
        public double distSq(Voxel v) {
            return Math.pow(v.x- center[0], 2)+ Math.pow(v.y-center[1], 2)+Math.pow(v.z-center[2], 2);
        }
        
        public void setLabel(int label) {
            this.label=label;
            for (Voxel v : voxels) segmentedMap.setPixel(v.x, v.y, v.z, label);
        }

        public Spot fusion(Spot spot) {
            if (spot.label<label) return spot.fusion(this);
            //logger.debug("fusion: {}+{}", this.label, spot.label);
            spots[spot.label]=null;
            spotNumber--;
            spot.setLabel(label);
            this.voxels.addAll(spot.voxels); // pas besoin de check si voxels.contains(v) car les spots ne se recouvrent pas            //update seed: lowest seedIntensity
            //if (watershedMap.getPixel(seed.x, seed.y, seed.getZ())>watershedMap.getPixel(spot.seed.x, spot.seed.y, spot.seed.getZ())) seed=spot.seed;
            return this;
        }
        
        public void addVox(Voxel v) {
            if (!voxels.contains(v)) {
                voxels.add(v);
                segmentedMap.setPixel(v.x, v.y, v.z, label);
            }
        }
        
        public Region toRegion(int label) {
            return new Region(voxels, label, segmentedMap.sizeZ()==1, mask.getScaleXY(), mask.getScaleZ()).setQuality(getQuality());
        }
        
        public double getQuality() {
            if (decreasingPropagation) {
                double max = Double.NEGATIVE_INFINITY;
                for (Voxel v: voxels) if (v.value>max) max = v.value;
                return max;
            } else {
                double min = Double.POSITIVE_INFINITY;
                for (Voxel v: voxels) if (v.value<min) min = v.value;
                return -min;
            }
        }
        
    }
    public interface PropagationCriterion {
        public void setUp(WatershedTransform instance);
        public boolean continuePropagation(Voxel currentVox, Voxel nextVox);
    }
    public static class DefaultPropagationCriterion implements PropagationCriterion {
        @Override public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            return true;
        }
        @Override public void setUp(WatershedTransform instance) {}
    }
    public static class MonotonalPropagation implements PropagationCriterion {
        boolean decreasingPropagation;
        @Override public void setUp(WatershedTransform instance) {
            setPropagationDirection(instance.decreasingPropagation);
        }
        public MonotonalPropagation setPropagationDirection(boolean decreasingPropagation) {
            this.decreasingPropagation=decreasingPropagation;
            return this;
        }
        @Override public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            if (decreasingPropagation) return (nextVox.value<=currentVox.value);
            else return (nextVox.value>=currentVox.value);
        }
    }
    public static class ThresholdPropagation implements PropagationCriterion {
        Image image;
        double threshold;
        boolean stopWhenInferior;
        public ThresholdPropagation(Image image, double threshold, boolean stopWhenInferior) {
            this.image=image;
            this.threshold=threshold;
            this.stopWhenInferior=stopWhenInferior;
        }
        @Override public void setUp(WatershedTransform instance) {}
        @Override public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            return stopWhenInferior?image.getPixel(nextVox.x, nextVox.y, nextVox.z)>threshold:image.getPixel(nextVox.x, nextVox.y, nextVox.z)<threshold;
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
        @Override public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            double threshold = thresholdMap.get(wst.segmentedMap.getPixelInt(currentVox.x, currentVox.y, currentVox.z));
            return stopWhenInferior?image.getPixel(nextVox.x, nextVox.y, nextVox.z)>threshold:image.getPixel(nextVox.x, nextVox.y, nextVox.z)<threshold;
        }
    }
    public static class ThresholdPropagationOnWatershedMap implements PropagationCriterion {
        boolean stopWhenInferior;
        double threshold;
        @Override public void setUp(WatershedTransform instance) {
            stopWhenInferior = instance.decreasingPropagation;
        }
        public ThresholdPropagationOnWatershedMap(double threshold) {
            this.threshold=threshold;
        }
        public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            return stopWhenInferior?nextVox.value>threshold:nextVox.value<threshold;
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
        public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            for (PropagationCriterion p : criteria) if (!p.continuePropagation(currentVox, nextVox)) return false;
            return true;
        }
    }
    
    public interface FusionCriterion {
        public void setUp(WatershedTransform instance);
        public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel);
    }
    public static class DefaultFusionCriterion implements FusionCriterion {
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
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
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
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
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
            return instance.decreasingPropagation ? currentVoxel.value>threshold : currentVoxel.value<threshold;
        }
    }
    public static class NumberFusionCriterion implements FusionCriterion {
        int numberOfSpots;
        WatershedTransform instance;
        public NumberFusionCriterion(int minNumberOfSpots) {
            this.numberOfSpots=minNumberOfSpots;
        }
        @Override public void setUp(WatershedTransform instance) {this.instance=instance;}
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
            return instance.spotNumber>numberOfSpots;
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
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
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
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
            if (criteria.length==0) return false;
            for (FusionCriterion c : criteria) if (c.checkFusionCriteria(s1, s2, currentVoxel)) return true;
            return false;
        }
    }
    
    /*public static class PrioriyVoxel extends Voxel {
        float priority;
        public PrioriyVoxel(int x, int y, int z) {
            super(x, y, z);
        }
        public static PrioriyVoxel fromVoxel(Voxel v) {
            PrioriyVoxel res = new PrioriyVoxel(v.x, v.y, v.z);
            res.value=v.value;
            return res;
        }
        @Override
        public int compareTo(Voxel other) {
            if (value < other.value) {
                return -1;
            } else if (value > other.value) {
                return 1;
            } 
            if (other instanceof PrioriyVoxel) { // inverted order: larger value has priority
                PrioriyVoxel otherV = (PrioriyVoxel)other;
                if (priority>otherV.priority) return -1;
                else if (priority<otherV.priority) return 1;
            }
            // consistancy with equals method    
            if (x<other.x) return -1;
            else if (x>other.x) return 1;
            else if (y<other.y) return -1;
            else if (y>other.y) return 1;
            else if (z<other.z) return -1;
            else if (z>other.z) return 1;
            else return 0;
        }
    }*/
}
