package bacmman.plugins.plugins.segmenters;

import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.processing.clustering.ClusterCollection;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

public class SplitAndMergeEDM extends SplitAndMerge<SplitAndMergeEDM.Interface> {
    public final static Logger logger = LoggerFactory.getLogger(SplitAndMergeEDM.class);
    final Image edm;
    public double splitThresholdValue;
    boolean intensityIsdy;
    Function<SplitAndMergeEDM.Interface, Double> interfaceValue;

    public SplitAndMergeEDM(Image edm, Image intensityMap, double splitThreshold, boolean normalizeEdgeValues) {
        super(intensityMap);
        this.edm = edm;
        splitThresholdValue=splitThreshold;
        setInterfaceValue(0.5, normalizeEdgeValues);
    }
    public SplitAndMergeEDM setIntensityIsdy(boolean intensityIsdy) {
        this.intensityIsdy = intensityIsdy;
        return this;
    }

    public BiFunction<? super SplitAndMergeEDM.Interface, ? super SplitAndMergeEDM.Interface, Integer> compareMethod=null;

    public Image drawInterfaceValues(RegionPopulation pop) {
        return RegionCluster.drawInterfaceValues(new RegionCluster<>(pop, true, getFactory()), i->{i.updateInterface(); return i.value;});
    }
    public SplitAndMergeEDM setThresholdValue(double splitThreshold) {
        this.splitThresholdValue = splitThreshold;
        return this;
    }
    public SplitAndMergeEDM setInterfaceValue(double quantile, boolean normalizeEdgeValues) {
        interfaceValue = i-> {
            if (i.getVoxels().isEmpty()) {
                return Double.NaN;
            } else {
                int size = i.getVoxels().size() + i.getDuplicatedVoxels().size();
                double val = ArrayUtil.quantile(Stream.concat(i.getVoxels().stream(), i.getDuplicatedVoxels().stream()).mapToDouble(v -> edm.getPixel(v.x, v.y, v.z)).sorted(), size, 1);
                if (true) {// normalize by mean edm value
                    double sum = BasicMeasurements.getSum(i.getE1(), edm) + BasicMeasurements.getSum(i.getE2(), edm);
                    double mean = sum / (i.getE1().size() + i.getE2().size());
                    val = val / mean;
                }
                return 1/val;
            }
        };
        return this;
    }
    public SplitAndMergeEDM setInterfaceValue(Function<SplitAndMergeEDM.Interface, Double> interfaceValue) {
        this.interfaceValue=interfaceValue;
        return this;
    }
    @Override public Image getWatershedMap() {
        return edm;
    }
    @Override
    public Image getSeedCreationMap() {
        return edm;
    }

    @Override
    protected ClusterCollection.InterfaceFactory<Region, SplitAndMergeEDM.Interface> createFactory() {
        return (Region e1, Region e2) -> new SplitAndMergeEDM.Interface(e1, e2);
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


        @Override
        public void performFusion() {
            double newMean = Double.NaN;
            if (SplitAndMergeEDM.this.getMeanValues().containsKey(getE1()) || SplitAndMergeEDM.this.getMeanValues().containsKey(getE2())) {
                double dy1 = SplitAndMergeEDM.this.getMeanValues().get(getE1());
                double dy2 = SplitAndMergeEDM.this.getMeanValues().get(getE1());
                double s1 = getE1().size();
                double s2 = getE1().size();
                newMean = (dy1 * s1 + dy2 * s2) / (s1 + s2);
            }
            SplitAndMergeEDM.this.regionChanged.accept(e1);
            SplitAndMergeEDM.this.regionChanged.accept(e2);
            super.performFusion();
            if (!Double.isNaN(newMean)) SplitAndMergeEDM.this.getMeanValues().put(e1, newMean);
        }

        @Override
        public void updateInterface() {
            value = interfaceValue.apply(this);
        }

        @Override
        public void fusionInterface(SplitAndMergeEDM.Interface otherInterface, Comparator<? super Region> elementComparator) {
            //fusionInterfaceSetElements(otherInterface, elementComparator);
            SplitAndMergeEDM.Interface other = otherInterface;
            voxels.addAll(other.voxels);
            duplicatedVoxels.addAll(other.duplicatedVoxels);
            value = Double.NaN;// updateSortValue will be called afterwards
        }

        @Override
        public boolean checkFusion() {
            if (addTestImage != null)
                logger.debug("check fusion: {}+{}, size: {}, value: {}, threhsold: {}, fusion: {}", e1.getLabel(), e2.getLabel(), voxels.size(), value, splitThresholdValue, value < splitThresholdValue);
            if (voxels.size() + duplicatedVoxels.size() <= 2 && Math.min(getE1().size(), getE2().size()) > 10)
                return false;
            boolean fusion = value < splitThresholdValue;
            if (!intensityIsdy || !fusion) return fusion;
            // use dy information from both regions: if dy is very different forbid fusion
            double dy1  = SplitAndMergeEDM.this.getMeanValues().get(getE1());
            double dy2  = SplitAndMergeEDM.this.getMedianValues().get(getE2());
            double ddy = Math.abs(dy1 - dy2);
            double ddyIfDiv = getE1().getGeomCenter(false).dist(getE2().getGeomCenter(false));
            if (ddy>ddyIfDiv * 0.75) return false; // TODO tune this parameter
            else return true;
        }

        @Override
        public void addPair(Voxel v1, Voxel v2) {
            if (foregroundMask != null && !foregroundMask.contains(v1.x, v1.y, v1.z)) duplicatedVoxels.add(v2);
            else voxels.add(v1);
            voxels.add(v2);
        }

        @Override
        public int compareTo(Interface t) {
            int c = compareMethod != null ? compareMethod.apply(this, t) : Double.compare(value, t.value); // small edges first
            if (c == 0)
                return super.compareElements(t, RegionCluster.regionComparator); // consitency with equals method
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
            return "Interface: " + e1.getLabel() + "+" + e2.getLabel() + " sortValue: " + value;
        }
    }
}