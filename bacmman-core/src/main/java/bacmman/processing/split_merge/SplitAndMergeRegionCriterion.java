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
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.processing.clustering.ClusterCollection;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;

import java.util.Comparator;
import java.util.function.Function;
import static bacmman.plugins.Plugin.logger;

import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Split & Merge Class with interface value function based on value in whole region
 * @author Jean Ollion
 */
public class SplitAndMergeRegionCriterion extends SplitAndMerge<SplitAndMergeRegionCriterion.Interface> {
    final Image wsMap;
    public final double splitThresholdValue;
    Function<Interface, Double> interfaceValue;
    InterfaceValue method;
    
    public static enum InterfaceValue {MEAN_INTENSITY_IN_REGIONS, DIFF_INTENSITY_BTWN_REGIONS, DIFF_MEDIAN_BTWN_REGIONS, ABSOLUTE_DIFF_MEDIAN_BTWN_REGIONS, ABSOLUTE_DIFF_MEDIAN_BTWN_REGIONS_INV};
    public SplitAndMergeRegionCriterion(Image edgeMap, Image intensityMap, double splitThreshold, InterfaceValue method) {
        super(intensityMap);
        this.method=method;
        this.wsMap = edgeMap;
        splitThresholdValue=splitThreshold;
        switch (method) {
            case MEAN_INTENSITY_IN_REGIONS:
            default:
                interfaceValue = i-> {
                    Predicate<Voxel> filter = i.getE1().getLabel()==0 ? (v-> intensityMap.contains(v.x, v.y, v.z)) : (v->true);
                    return - Stream.concat(i.getE1().getVoxels().stream().filter(filter), i.getE2().getVoxels().stream()).mapToDouble(v->intensityMap.getPixel(v.x, v.y, v.z)).average().orElse(Double.NEGATIVE_INFINITY); // maximal intensity first
                };
                break;
            case DIFF_INTENSITY_BTWN_REGIONS:
                interfaceValue = i -> {
                    Predicate<Voxel> filter = i.getE1().getLabel()==0 ? (v-> intensityMap.contains(v.x, v.y, v.z)) : (v->true);
                    double m1 = i.getE1().getVoxels().stream().filter(filter).mapToDouble(v->intensityMap.getPixel(v.x, v.y, v.z)).average().orElse(Double.NaN);
                    if (Double.isNaN(m1)) return Double.NEGATIVE_INFINITY;
                    double m2 = i.getE2().getVoxels().stream().mapToDouble(v->intensityMap.getPixel(v.x, v.y, v.z)).average().getAsDouble();
                    return Math.abs(m1-m2); // minimal difference first
                };
                break;
            case DIFF_MEDIAN_BTWN_REGIONS:
                interfaceValue = i -> {
                    double v1 = getMedianValues().get(i.getE1());
                    if (Double.isNaN(v1)) return Double.NEGATIVE_INFINITY; // background
                    return Math.abs(v1-getMedianValues().get(i.getE2()));
                };
                break;
            case ABSOLUTE_DIFF_MEDIAN_BTWN_REGIONS: // supposing : foreground values are higher. trying to merge region that can be foreground or background with foreground regions of lesser label. 
                interfaceValue = i -> {
                    double v1 = getMedianValues().get(i.getE1());
                    if (Double.isNaN(v1)) return Double.POSITIVE_INFINITY; // background
                    return v1-getMedianValues().get(i.getE2()); // highest difference of higher label region will be negative
                };
                break;
            case ABSOLUTE_DIFF_MEDIAN_BTWN_REGIONS_INV: //supposing : background values are higher trying to merge region that can be foreground or background with foreground regions of lesser label
                interfaceValue = i -> {
                    double v1 = getMedianValues().get(i.getE1());
                    if (Double.isNaN(v1)) return Double.POSITIVE_INFINITY; // background
                    return getMedianValues().get(i.getE2())-v1; // highest difference of higher label region will be negative
                };
                break;
        }
        
    }
    
    public BiFunction<? super Interface, ? super Interface, Integer> compareMethod=null;
    
    public SplitAndMergeRegionCriterion setInterfaceValue(Function<Interface, Double> interfaceValue) {
        this.interfaceValue=interfaceValue;
        return this;
    }
    @Override public Image getWatershedMap() {
        return wsMap;
    }
    @Override
    public Image getSeedCreationMap() {
        return wsMap;
    }
    
    @Override
    protected ClusterCollection.InterfaceFactory<Region, Interface> createFactory() {
        return (Region e1, Region e2) -> new Interface(e1, e2);
    }
    
    public class Interface extends InterfaceRegionImpl<Interface> {
        public double value;
        public Interface(Region e1, Region e2) {
            super(e1, e2);
        }
        @Override public void performFusion() {
            SplitAndMergeRegionCriterion.this.regionChanged.accept(e1);
            SplitAndMergeRegionCriterion.this.regionChanged.accept(e2);
            super.performFusion();
        }
        @Override public void updateInterface() {
            value = interfaceValue.apply(this);
        }

        @Override 
        public void fusionInterface(Interface otherInterface, Comparator<? super Region> elementComparator) {
            //fusionInterfaceSetElements(otherInterface, elementComparator);
            value = Double.NaN;// updateSortValue will be called afterwards
        }

        @Override
        public boolean checkFusion() {
            if (addTestImage!=null) logger.debug("check fusion: {}+{}, value: {}, threhsold: {}, fusion: {}", e1.getLabel(), e2.getLabel(), value, splitThresholdValue, value<splitThresholdValue);
            boolean fusion = value<splitThresholdValue;
            if (!fusion) return false;
            return checkFusionCriteria(this);
        }

        @Override
        public void addPair(Voxel v1, Voxel v2) {
        }

        @Override
        public int compareTo(Interface t) {
            int c = compareMethod!=null ? compareMethod.apply(this, t) : Double.compare(value, t.value); // small edges first
            if (c==0) return super.compareElements(t, RegionCluster.regionComparator); // consitency with equals method
            else return c;
        }

        @Override
        public String toString() {
            return "Interface: " + e1.getLabel()+"+"+e2.getLabel()+ " sortValue: "+value;
        } 
    }
}
