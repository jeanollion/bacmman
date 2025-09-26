package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParentObjectClassParameter;
import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Offset;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.Plugin;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.ToDoubleBiFunction;
import java.util.stream.DoubleStream;

public class OverlapArea implements ObjectFeature {
    final static Logger logger = LoggerFactory.getLogger(OverlapArea.class);

    ParentObjectClassParameter otherOC = new ParentObjectClassParameter("Other object class", -1, -1, false, false)
            .setAutoConfiguration(ParentObjectClassParameter.autoconfigStructureInParentOtherwiseAll()) // used in processing: only parent, used in measurement: all structures are possible
            .setEmphasized(true);
    enum MODE {SUM, MAX}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Overlap mode", MODE.values(), MODE.SUM).setEmphasized(true).setHint("SUM: will sum overlaps with all regions from other object class, MAX will return the maximum overlap");
    enum NORM {NO_NORM, CURRENT_SIZE, OTHER_SIZE, AVERAGE_SIZE}
    EnumChoiceParameter<NORM> norm = new EnumChoiceParameter<>("Normalization", NORM.values(), NORM.CURRENT_SIZE).setEmphasized(true).setHint("OTHER_SIZE :divides overlap value by the size of the other object class.");
    RegionPopulation otherPop;
    Offset offset;
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{otherOC, mode, norm};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        otherPop = parent.getChildRegionPopulation(otherOC.getSelectedClassIdx());
        offset = childPopulation.isAbsoluteLandmark() ? null : parent.getBounds();
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        ToDoubleBiFunction<Region, Region> getNorm;
        switch (norm.getSelectedEnum()) {
            case NO_NORM:
            default:
                getNorm = (r1, r2) -> 1;
                break;
            case CURRENT_SIZE:
                getNorm = (r1, r2) -> r1.size();
                break;
            case OTHER_SIZE:
                getNorm = (r1, r2) -> r2.size();
                break;
            case AVERAGE_SIZE:
                getNorm = (r1, r2) -> 0.5 * (r1.size() + r2.size());
        }
        double[] overlaps = otherPop.getRegions().stream()
                .mapToDouble(r -> r.getOverlapArea(region, null, offset) / getNorm.applyAsDouble(region, r)).toArray();
        switch (mode.getSelectedEnum()) {
            case MAX:
            default:
                return overlaps[ArrayUtil.max(overlaps)];
            case SUM:
                return DoubleStream.of(overlaps).sum();
        }
    }

    @Override
    public String getDefaultName() {
        return "Overlap";
    }
}
