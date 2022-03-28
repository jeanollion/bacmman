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
import bacmman.plugins.Plugin;
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
import static bacmman.processing.split_merge.SplitAndMerge.INTERFACE_VALUE.CENTER;
import static bacmman.processing.split_merge.SplitAndMerge.INTERFACE_VALUE.MEDIAN;

import bacmman.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SplitAndMergeEdge extends SplitAndMerge<SplitAndMergeEdge.Interface> {
    public final static Logger logger = LoggerFactory.getLogger(SplitAndMergeEdge.class);
    final Image edge;
    public double splitThresholdValue;
    Function<Interface, Double> interfaceValue;
    boolean seedsOnEdgeMap = true;
    boolean watershedOnEdgeMap = true;
    public SplitAndMergeEdge(Image edgeMap, Image intensityMap, double splitThreshold, boolean normalizeEdgeValues, INTERFACE_VALUE mode) {
        super(intensityMap);
        this.edge = edgeMap;
        splitThresholdValue=splitThreshold;
        setInterfaceValue(0.5, mode, normalizeEdgeValues);
    }

    public SplitAndMergeEdge(Image edgeMap, Image intensityMap, double splitThreshold, boolean normalizeEdgeValues) {
        this(edgeMap, intensityMap, splitThreshold, normalizeEdgeValues, MEDIAN);
    }

    public BiFunction<? super Interface, ? super Interface, Integer> compareMethod=null;

    public Image drawInterfaceValues(RegionPopulation pop) {
        return RegionCluster.drawInterfaceValues(new RegionCluster<>(pop, true, getFactory()), i->{i.updateInterface(); return i.value;});
    }
    public SplitAndMergeEdge setThresholdValue(double splitThreshold) {
        this.splitThresholdValue = splitThreshold;
        return this;
    }
    public SplitAndMergeEdge setInterfaceValue(double quantile, INTERFACE_VALUE mode, boolean normalizeEdgeValues) {
        interfaceValue = i-> {
            if (i.getVoxels().isEmpty()) {
                return Double.NaN;
            } else {
                double val;
                switch (mode) {
                    case MEDIAN:
                    default: {
                        int size = i.voxels.size()+i.duplicatedVoxels.size();
                        val= ArrayUtil.quantile(Stream.concat(i.voxels.stream(), i.duplicatedVoxels.stream()).mapToDouble(v->edge.getPixel(v.x, v.y, v.z)).sorted(), size, quantile);
                        break;
                    }
                    case CENTER: {
                        float[] center = new float[3];
                        Stream.concat(i.getVoxels().stream(), i.getDuplicatedVoxels().stream()).forEach(v -> {
                            center[0] += v.x;
                            center[1] += v.y;
                            center[2] += v.z;
                        });
                        double count = i.getVoxels().size() + i.getDuplicatedVoxels().size();
                        center[0]/=count;
                        center[1]/=count;
                        center[2]/=count;
                        val = edge.getPixel(center[0], center[1], center[2]);
                        break;
                    }
                }

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
        return watershedOnEdgeMap?edge:intensityMap;
    }
    @Override public Image getSeedCreationMap() {
        return seedsOnEdgeMap?edge:intensityMap;
    }

    public SplitAndMergeEdge seedsOnEdgeMap(boolean seedsOnEdgeMap) {
        this.seedsOnEdgeMap = seedsOnEdgeMap;
        return this;
    }
    public SplitAndMergeEdge watershedOnEdgeMap(boolean watershedOnEdgeMap) {
        this.watershedOnEdgeMap = watershedOnEdgeMap;
        return this;
    }
    @Override
    protected ClusterCollection.InterfaceFactory<Region, Interface> createFactory() {
        return Interface::new;
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
            boolean fusion = value<splitThresholdValue;
            if (!fusion) return false;
            return checkFusionCriteria(this);
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

        @Override
        public double getValue() {
            return value;
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
