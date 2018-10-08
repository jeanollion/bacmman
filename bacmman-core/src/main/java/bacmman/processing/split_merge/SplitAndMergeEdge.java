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
import bacmman.processing.clustering.ClusterCollection;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;
import bacmman.measurement.BasicMeasurements;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import static bacmman.plugins.Plugin.logger;

import bacmman.utils.ArrayUtil;

import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SplitAndMergeEdge extends SplitAndMerge<SplitAndMergeEdge.Interface> {
    final Image edge;
    public double splitThresholdValue;
    Function<Interface, Double> interfaceValue;

    public SplitAndMergeEdge(Image edgeMap, Image intensityMap, double splitThreshold, boolean normalizeEdgeValues) {
        super(intensityMap);
        this.edge = edgeMap;
        splitThresholdValue=splitThreshold;
        setInterfaceValue(0.5, normalizeEdgeValues);
    }

    public BiFunction<? super Interface, ? super Interface, Integer> compareMethod=null;
    public Image drawInterfaceValues(RegionPopulation pop) {
        return RegionCluster.drawInterfaceValues(new RegionCluster<>(pop, true, getFactory()), i->{i.updateInterface(); return i.value;});
    }
    public SplitAndMergeEdge setThresholdValue(double splitThreshold) {
        this.splitThresholdValue = splitThreshold;
        return this;
    }
    public SplitAndMergeEdge setInterfaceValue(double quantile, boolean normalizeEdgeValues) {
        interfaceValue = i-> {
            if (i.getVoxels().isEmpty()) {
                return Double.NaN;
            } else {
                int size = i.voxels.size()+i.duplicatedVoxels.size();
                double val= ArrayUtil.quantile(Stream.concat(i.voxels.stream(), i.duplicatedVoxels.stream()).mapToDouble(v->edge.getPixel(v.x, v.y, v.z)).sorted(), size, quantile);
                if (normalizeEdgeValues) {// normalize by intensity (mean better than median, better than mean @ edge)
                    double sum = BasicMeasurements.getSum(i.getE1(), intensityMap)+BasicMeasurements.getSum(i.getE2(), intensityMap);
                    double mean = sum /(double)(i.getE1().size()+i.getE2().size());
                    val= val/mean;
                }
                return val;
            }
        };
        return this;
    }
    public SplitAndMergeEdge setInterfaceValue(Function<Interface, Double> interfaceValue) {
        this.interfaceValue=interfaceValue;
        return this;
    }
    @Override public Image getWatershedMap() {
        return edge;
    }
    @Override
    public Image getSeedCreationMap() {
        return edge;
    }
    
    @Override
    protected ClusterCollection.InterfaceFactory<Region, Interface> createFactory() {
        return (Region e1, Region e2) -> new Interface(e1, e2);
    }
    
    public class Interface extends InterfaceRegionImpl<Interface> implements RegionCluster.InterfaceVoxels<Interface> {
        public double value;
        Set<Voxel> voxels;
        Set<Voxel> duplicatedVoxels; // to allow duplicate values from background
        public Interface(Region e1, Region e2) {
            super(e1, e2);
            voxels = new HashSet<>();
            duplicatedVoxels = new HashSet<>();
        }
        @Override public void performFusion() {
            SplitAndMergeEdge.this.regionChanged.accept(e1);
            SplitAndMergeEdge.this.regionChanged.accept(e2);
            super.performFusion();
        }
        @Override public void updateInterface() {
            value = interfaceValue.apply(this);
        }

        @Override 
        public void fusionInterface(Interface otherInterface, Comparator<? super Region> elementComparator) {
            //fusionInterfaceSetElements(otherInterface, elementComparator);
            Interface other = otherInterface;
            voxels.addAll(other.voxels); 
            duplicatedVoxels.addAll(other.duplicatedVoxels);
            value = Double.NaN;// updateSortValue will be called afterwards
        }

        @Override
        public boolean checkFusion() {
            if (addTestImage!=null) logger.debug("check fusion: {}+{}, size: {}, value: {}, threhsold: {}, fusion: {}", e1.getLabel(), e2.getLabel(), voxels.size(), value, splitThresholdValue, value<splitThresholdValue);
            if (voxels.size()+duplicatedVoxels.size()<=2 && Math.min(getE1().size(), getE2().size())>10) return false;
            return value<splitThresholdValue;
        }

        @Override
        public void addPair(Voxel v1, Voxel v2) {
            if (foregroundMask!=null && !foregroundMask.contains(v1.x, v1.y, v1.z)) duplicatedVoxels.add(v2);
            else  voxels.add(v1);
            voxels.add(v2);
        }

        @Override
        public int compareTo(Interface t) {
            int c = compareMethod!=null ? compareMethod.apply(this, t) : Double.compare(value, t.value); // small edges first
            if (c==0) return super.compareElements(t, RegionCluster.regionComparator); // consitency with equals method
            else return c;
        }
        @Override
        public Collection<Voxel> getVoxels() {
            return voxels;
        }
        public Collection<Voxel> getDuplicatedVoxels() {
            return duplicatedVoxels;
        }
        @Override
        public String toString() {
            return "Interface: " + e1.getLabel()+"+"+e2.getLabel()+ " sortValue: "+value;
        } 
    }
}
