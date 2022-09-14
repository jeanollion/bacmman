package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PostFilterSequence;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBox;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class BinaryOperation implements PostFilter, Hint {
    PostFilterSequence postFilter = new PostFilterSequence("Post-Filters").setEmphasized(true);
    enum GATE {A_PLUS_B, A_MINUS_B, B_MINUS_A}
    EnumChoiceParameter<GATE> gate = new EnumChoiceParameter<>("Operation", GATE.values(), null).setEmphasized(true);

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{postFilter, gate};
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        RegionPopulation post = postFilter.filter(childPopulation.duplicate(), childStructureIdx, parent);
        BiFunction<Region, Region, Region> op;
        switch (gate.getSelectedEnum()) {
            case A_PLUS_B:
            default:
                op = (a, b) -> {a.add(b);return a;};
                break;
            case A_MINUS_B:
                op = (a,b) -> {a.remove(b); return a;};
                break;
            case B_MINUS_A:
                op = (a,b) -> {b.remove(a); return b;};
                break;
        }
        UnaryOperator<Region> getMaxOverlap = r -> {
            Region other = post.getRegions().stream().max(Comparator.comparingInt(rr->BoundingBox.getIntersection(r.getBounds(), rr.getBounds()).getSizeXYZ())).get();
            if (BoundingBox.getIntersection(r.getBounds(), other.getBounds()).getSizeXYZ()==0) return null;
            else return other;
        };
        List<Region> modifiedRegions = childPopulation.getRegions().stream().map(a -> {
            Region b = getMaxOverlap.apply(a);
            if (b == null) {
                if (gate.getSelectedEnum().equals(GATE.B_MINUS_A)) return null;
                else return a;
            } else return op.apply(a, b);
        }).filter(Objects::nonNull).collect(Collectors.toList());
        return new RegionPopulation(modifiedRegions, childPopulation.getImageProperties());
    }

    @Override
    public String getHintText() {
        return "Let A be the object set. Applies a post-filter to A to get a set of transformed objects B. For each object a from A: get b from B so that bounding box overlap is maximal, and replace a by the result of the selected operation between a and b";
    }
}
