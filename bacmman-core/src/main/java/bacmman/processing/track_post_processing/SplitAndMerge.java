package bacmman.processing.track_post_processing;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.utils.Triplet;

import java.util.List;

public interface SplitAndMerge {
    double computeMergeCost(List<SegmentedObject> toMerge);
    Triplet<Region, Region, Double> computeSplitCost(SegmentedObject toSplit);
}
