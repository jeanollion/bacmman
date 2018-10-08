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

import bacmman.data_structure.Processor;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.Voxel;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.ImageLabeller;
import bacmman.image.ImageMask;
import bacmman.image.ImageProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;

/**
 *
 * @author Jean Ollion
 */
public class MultiScaleWatershedTransform {
    final protected TreeSet<Voxel> heap;
    final protected Spot[] spots; // map label -> spot (spots[0]==null)
    protected int spotNumber;
    final protected Image[] watershedMaps;
    final protected ImageInteger segmentedMap;
    final protected ImageMask mask; // can be ref2D
    final boolean is3D;
    final boolean decreasingPropagation;
    boolean lowConnectivity;
    PropagationCriterion propagationCriterion;
    FusionCriterion fusionCriterion;
    
    public static RegionPopulation[] watershed(Image[] watershedMaps, ImageMask mask, List<Region>[] regionalExtrema, boolean decreasingPropagation, PropagationCriterion propagationCriterion, FusionCriterion fusionCriterion) {
        MultiScaleWatershedTransform wt = new MultiScaleWatershedTransform(watershedMaps, mask, regionalExtrema, decreasingPropagation, propagationCriterion, fusionCriterion);
        wt.run();
        return wt.getObjectPopulation();
    }
    
    public static RegionPopulation[] watershed(Image[] watershedMaps, ImageMask mask, ImageMask[] seeds, boolean decreasingPropagation) {
        if (watershedMaps.length!=seeds.length) throw new IllegalArgumentException("seeds and watershed maps should be within same scale-space");
        return watershed(watershedMaps, mask, getRegionalExtrema(seeds), decreasingPropagation, null, null);
    }
    public static RegionPopulation[] watershed(Image[] watershedMaps, ImageMask mask, ImageMask[] seeds, boolean decreasingPropagation, PropagationCriterion propagationCriterion, FusionCriterion fusionCriterion) {
        return watershed(watershedMaps, mask, getRegionalExtrema(seeds), decreasingPropagation, propagationCriterion, fusionCriterion);
    }
    
    private static List<Region>[] getRegionalExtrema(ImageMask[] seeds) {
        List[] res = new List[seeds.length];
        for (int i = 0; i<res.length; ++i) res[i] = ImageLabeller.labelImageList(seeds[i]);
        return (List<Region>[]) res;
    }
    
    public MultiScaleWatershedTransform(Image[] watershedMaps, ImageMask mask, List<Region>[] regionalExtrema, boolean decreasingPropagation, PropagationCriterion propagationCriterion, FusionCriterion fusionCriterion) {
        if (watershedMaps.length!= regionalExtrema.length) throw new IllegalArgumentException("Watershed maps should have same number of planes as seeds");
        if (!Image.sameSize(Arrays.asList(watershedMaps))) throw new IllegalArgumentException("WatershedMaps should be of same dimensions");
        if (mask==null) mask=new BlankMask( watershedMaps[0]);
        this.decreasingPropagation = decreasingPropagation;
        heap = decreasingPropagation ? new TreeSet<>(Voxel.getInvertedComparator()) : new TreeSet<>(Voxel.getComparator());
        this.mask=mask;
        this.watershedMaps=watershedMaps;
        spotNumber = 0;
        for (List<Region> l : regionalExtrema) spotNumber += l.size();
        spots = new Spot[spotNumber+1];
        segmentedMap = ImageInteger.createEmptyLabelImage("segmentationMap", spots.length, watershedMaps[0]);
        int spotIdx = 1;
        for (int i = 0; i<regionalExtrema.length; ++i) {
            for (Region o : regionalExtrema[i]) {
                spots[spotIdx] = new Spot(spotIdx, i, o.getVoxels());
                ++spotIdx;
            }
        }
        Processor.logger.trace("watershed transform: number of seeds: {} segmented map type: {}", spotNumber, segmentedMap.getClass().getSimpleName());
        is3D=mask.sizeZ()>1;   
        if (propagationCriterion==null) setPropagationCriterion(new DefaultPropagationCriterion());
        else setPropagationCriterion(propagationCriterion);
        if (fusionCriterion==null) setFusionCriterion(new DefaultFusionCriterion());
        else setFusionCriterion(fusionCriterion);
    }
    
    public MultiScaleWatershedTransform setFusionCriterion(FusionCriterion fusionCriterion) {
        this.fusionCriterion=fusionCriterion;
        fusionCriterion.setUp(this);
        return this;
    }
    
    public MultiScaleWatershedTransform setPropagationCriterion(PropagationCriterion propagationCriterion) {
        this.propagationCriterion=propagationCriterion;
        propagationCriterion.setUp(this);
        return this;
    }
    public MultiScaleWatershedTransform setLowConnectivity(boolean lowConnectivity) {
        this.lowConnectivity = lowConnectivity;
        return this;
    }
    public void run() {
        
        double rad = lowConnectivity ? 1 : 1.5;
        EllipsoidalNeighborhood neigh = segmentedMap.sizeZ()>1?new EllipsoidalNeighborhood(rad, rad, true) : new EllipsoidalNeighborhood(rad, true);
        
        for (Spot s : spots) {
            if (s!=null) {
                for (Voxel v : s.voxels) {
                    for (int i = 0; i<neigh.getSize(); ++i) {
                        Voxel n = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], v.z+neigh.dz[i]) ;
                        if (segmentedMap.contains(n.x, n.y, n.z) && mask.insideMask(n.x, n.y, n.z)) {
                            n.value = watershedMaps[s.scale].getPixel(n.x, n.y, n.z);
                            heap.add(n);
                        }
                    }
                }
            }
        }
        Score score = generateScore();
        List<Voxel> nextProp  = new ArrayList<>(neigh.getSize());
        Set<Integer> surroundingLabels = fusionCriterion==null || fusionCriterion instanceof DefaultFusionCriterion ? null : new HashSet<>(neigh.getSize());
        while (!heap.isEmpty()) {
            //Voxel v = heap.poll();
            Voxel v = heap.pollFirst();
            if (segmentedMap.getPixelInt(v.x, v.y, v.z)>0) continue;
            score.setUp(v);
            for (int i = 0; i<neigh.getSize(); ++i) {
                Voxel n = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], v.z+neigh.dz[i]) ;
                if (segmentedMap.contains(n.x, n.y, n.z) && mask.insideMask(n.x, n.y, n.z)) {
                    int nextLabel = segmentedMap.getPixelInt(n.x, n.y, n.z);
                    if (nextLabel>0) {
                        if (surroundingLabels!=null) surroundingLabels.add(nextLabel);
                        score.add(n, nextLabel);
                    } else nextProp.add(n);
                }
            }
            int currentLabel = score.getLabel();
            spots[currentLabel].addVox(v);
            // check propagation criterion
            for (Voxel n : nextProp) {
                n.value = watershedMaps[spots[currentLabel].scale].getPixel(n.x, n.y, n.z);
                if (propagationCriterion.continuePropagation(v, n)) { // check if voxel already in set
                    if (!heap.contains(n)) heap.add(n); // if already present in set -> was accessed from lower value -> priority
                }
            }
            nextProp.clear();
            // check fusion criterion
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
    
    private Score generateScore() {
        return new MaxDiffWsMap();
    }
    private interface Score {
        public abstract void setUp(Voxel center);
        public abstract void add(Voxel v, int label);
        public abstract int getLabel();
    }
    private class MaxDiffWsMap implements Score {
        Voxel center;
        double curDiff = -Double.MAX_VALUE;
        int curLabel;
        @Override
        public void add(Voxel v, int label) {
            //double diff=!decreasingPropagation ? watershedMap.getPixel(v.x, v.y, v.z) : -watershedMap.getPixel(v.x, v.y, v.z);
            double diff = Math.abs(watershedMaps[spots[label].scale].getPixel(v.x, v.y, v.z)-watershedMaps[spots[label].scale].getPixel(center.x, center.y, center.z)); // compare @ same scale
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
            this.center = center;
            curDiff = -Double.MAX_VALUE; // reset
            curLabel=0;
        }
    }
    
    /*
    public void run() {
        for (Spot s : spots) {
            if (s!=null) for (Voxel v : s.voxels) heap.add(v);
        }
        EllipsoidalNeighborhood neigh = segmentedMap.getSizeZ()>1?new EllipsoidalNeighborhood(1.5, 1.5, true) : new EllipsoidalNeighborhood(1.5, true);
        while (!heap.isEmpty()) {
            Voxel v = heap.pollFirst();
            Spot currentSpot = spots[segmentedMap.getPixelInt(v.x, v.y, v.z)];
            Voxel next;
            for (int i = 0; i<neigh.getSize(); ++i) {
                next = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], v.z+neigh.dz[i]);
                //logger.trace("voxel: {} next: {}, mask contains: {}, insideMask: {}",v, next, mask.contains(next.x, next.y, next.getZ()) , mask.insideMask(next.x, next.y, next.getZ()));
                if (segmentedMap.contains(next.x, next.y, next.z) && mask.insideMask(next.x, next.y, next.z)) currentSpot=propagate(currentSpot,v, next);
            }
        }
    }
        
    protected Spot propagate(Spot currentSpot, Voxel currentVoxel, Voxel nextVox) { /// nextVox.value = 0 at this step
        int label = segmentedMap.getPixelInt(nextVox.x, nextVox.y, nextVox.z);
        if (label!=0) {
            if (label!=currentSpot.label) {
                Spot s2 = spots[label];
                if (fusionCriterion.checkFusionCriteria(currentSpot, s2, currentVoxel)) return currentSpot.fusion(s2);
                else heap.remove(nextVox); // FIXME ??et dans les autres directions?
            }
        } else {
            nextVox.value=watershedMaps[currentSpot.scale].getPixel(nextVox.x, nextVox.y, nextVox.z);
            if (propagationCriterion.continuePropagation(currentVoxel, nextVox)) {
                currentSpot.addVox(nextVox);
                heap.add(nextVox);
            }
        }
        return currentSpot;
    }
    */
    
    public ImageInteger getLabelImage() {return segmentedMap;}
    
    public RegionPopulation[] getObjectPopulation() {
        RegionPopulation[] res = new RegionPopulation[this.watershedMaps.length];
        ArrayList<Region>[] objects = new ArrayList[watershedMaps.length];
        for (int i = 0; i<watershedMaps.length; ++i) objects[i] = new ArrayList<>();
        int label = 1;
        for (Spot s : spots) if (s!=null) objects[s.scale].add(s.toRegion(label++));
        for (int i = 0; i<watershedMaps.length; ++i) res[i] = new RegionPopulation(objects[i], segmentedMap);
        return res;
    }
    public static RegionPopulation combine(RegionPopulation[] pops, ImageProperties ip) {
        ArrayList<Region> allObjects = new ArrayList<>();
        for (int i = 0; i<pops.length; ++i) allObjects.addAll(pops[i].getRegions());
        return new RegionPopulation(allObjects, ip);
    }
    
    protected class Spot {
        public Set<Voxel> voxels;
        int label;
        int scale;
        //Voxel seed;
        /*public Spot(int label, Voxel seed) {
            this.label=label;
            this.voxels=new ArrayList<Voxel>();
            voxels.add(seed);
            seed.value=watershedMaps.getPixel(seed.x, seed.y, seed.getZ());
            heap.add(seed);
            this.seed=seed;
            segmentedMap.setPixel(seed.x, seed.y, seed.getZ(), label);
        }*/
        public Spot(int label, int scale, Set<Voxel> voxels) {
            this.label=label;
            this.scale=scale;
            this.voxels=voxels;
            for (Voxel v :voxels) {
                v.value=watershedMaps[scale].getPixel(v.x, v.y, v.z);
                heap.add(v);
                segmentedMap.setPixel(v.x, v.y, v.z, label);
            }
            //this.seed=seeds.get(0);
            //logger.debug("spot: {} seed size: {} seed {}",label, seeds.size(), seed);
            
        }
        
        public void setLabel(int label) {
            this.label=label;
            for (Voxel v : voxels) segmentedMap.setPixel(v.x, v.y, v.z, label);
        }

        public Spot fusion(Spot spot) {
            if (spot.label<label) return spot.fusion(this);
            spots[spot.label]=null;
            spotNumber--;
            spot.setLabel(label);
            this.voxels.addAll(spot.voxels); // pas besoin de check si voxels.contains(v) car les spots ne se recouvrent pas            //update seed: lowest seedIntensity
            //if (watershedMaps.getPixel(seed.x, seed.y, seed.getZ())>watershedMaps.getPixel(spot.seed.x, spot.seed.y, spot.seed.getZ())) seed=spot.seed;
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
        public void setUp(MultiScaleWatershedTransform instance);
        public boolean continuePropagation(Voxel currentVox, Voxel nextVox);
    }
    public static class DefaultPropagationCriterion implements PropagationCriterion {
        @Override public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            return true;
        }
        @Override public void setUp(MultiScaleWatershedTransform instance) {}
    }
    public static class MonotonalPropagation implements PropagationCriterion {
        boolean decreasingPropagation;
        @Override public void setUp(MultiScaleWatershedTransform instance) {
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
        @Override public void setUp(MultiScaleWatershedTransform instance) {}
        @Override public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            return stopWhenInferior?image.getPixel(nextVox.x, nextVox.y, nextVox.z)>threshold:image.getPixel(nextVox.x, nextVox.y, nextVox.z)<threshold;
        }
    }
    public static class ThresholdPropagationOnWatershedMap implements PropagationCriterion {
        boolean stopWhenInferior;
        double threshold;
        @Override public void setUp(MultiScaleWatershedTransform instance) {
            setStopWhenInferior(instance.decreasingPropagation);
        }
        public ThresholdPropagationOnWatershedMap setStopWhenInferior(boolean stopWhenInferior) {
            this.stopWhenInferior=stopWhenInferior;
            return this;
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
        @Override public void setUp(MultiScaleWatershedTransform instance) {
            for (PropagationCriterion c : criteria) c.setUp(instance);
        }
        public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
            for (PropagationCriterion p : criteria) if (!p.continuePropagation(currentVox, nextVox)) return false;
            return true;
        }
    }
    
    public interface FusionCriterion {
        public void setUp(MultiScaleWatershedTransform instance);
        public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel);
    }
    public static class DefaultFusionCriterion implements FusionCriterion {
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
            return false;
        }
        @Override public void setUp(MultiScaleWatershedTransform instance) {}
    }
    public static class SizeFusionCriterion implements FusionCriterion {
        int minimumSize;
        public SizeFusionCriterion(int minimumSize) {
            this.minimumSize=minimumSize;
        }
        @Override public void setUp(MultiScaleWatershedTransform instance) {}
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
            return s1.voxels.size()<=minimumSize || s2.voxels.size()<=minimumSize;
        }
    }
    public static class NumberFusionCriterion implements FusionCriterion {
        int numberOfSpots;
        MultiScaleWatershedTransform instance;
        public NumberFusionCriterion(int minNumberOfSpots) {
            this.numberOfSpots=minNumberOfSpots;
        }
        @Override public void setUp(MultiScaleWatershedTransform instance) {this.instance=instance;}
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
            return instance.spotNumber>numberOfSpots;
        }
    }
    public static class MultipleFusionCriteria implements FusionCriterion {
        FusionCriterion[] criteria;
        public MultipleFusionCriteria(FusionCriterion... criteria) {
            this.criteria=criteria;
        } 
        @Override public void setUp(MultiScaleWatershedTransform instance) {
            for (FusionCriterion f : criteria) f.setUp(instance);
        }
        @Override public boolean checkFusionCriteria(Spot s1, Spot s2, Voxel currentVoxel) {
            if (criteria.length==0) return false;
            for (FusionCriterion c : criteria) if (!c.checkFusionCriteria(s1, s2, currentVoxel)) return false;
            return true;
        }
    }
}
