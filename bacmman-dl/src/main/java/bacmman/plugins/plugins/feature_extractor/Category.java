package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.configuration.parameters.SelectionParameter;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.image.Image;
import bacmman.image.ImageShort;
import bacmman.plugins.FeatureExtractorConfigurable;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Category implements FeatureExtractorConfigurable, Hint {
    SelectionParameter selection = new SelectionParameter("Categories", true).addValidationFunction(sel -> {
        BooleanParameter addDef = ParameterUtils.getParameterFromSiblings(BooleanParameter.class, sel, null);
        if (!addDef.getSelected()) return sel.getSelectedSelections().count() > 1;
        else return true;
    });
    BooleanParameter addDefaultCategory = new BooleanParameter("Add Default Category", true).setHint("If true, all objects that are in no selection are assigned to category 0, and categories associated to selections start from index one. One object cannot belong to 2 selected selections<br/>If false, there must be at least 2 selected selections and all objects must belong to one and only one selection");

    public Category() {

    }

    public Category(List<String> categories, boolean addDefaultCategory) {
        this.selection.setSelectedItems(categories.toArray(new String[0]));
        this.addDefaultCategory.setSelected(addDefaultCategory);
    }

    int maxObjectIdx = -1;

    @Override
    public void configure(Stream<SegmentedObject> parentTrack, int objectClassIdx) {
        maxObjectIdx = parentTrack.mapToInt(p -> p.getChildren(objectClassIdx).mapToInt(SegmentedObject::getIdx).max().orElse(0)).max().orElse(0);
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        if (maxObjectIdx <0) throw new RuntimeException("Feature not configured");
        ImageShort res=new ImageShort("category", maxObjectIdx + 1, 1, 1);
        List<Selection> selections = selection.getSelectedSelections().collect(Collectors.toList());
        parent.getChildren(objectClassIdx).sorted().forEach(c -> res.setPixel( c.getIdx(), 0, 0, getCategory(c, selections)));
        return res;
    }

    protected int getCategory(SegmentedObject o, List<Selection> selections) {
        if (addDefaultCategory.getSelected()) {
            int l = 0;
            for (int i = 0; i<selections.size(); ++i) {
                if (selections.get(i).contains(o)) {
                    if (l != 0) throw new RuntimeException("Object "+o+ "belongs to category:" +l+ " and "+i+1);
                    l = i + 1;
                }
            }
            return l;
        } else {
            int l = -1;
            for (int i = 0; i<selections.size(); ++i) {
                if (selections.get(i).contains(o)) {
                    if (l != -1) throw new RuntimeException("Object "+o+ "belongs to category:" +l+ " and "+i);
                    l = i;
                }
            }
            if (l == -1) throw new RuntimeException("Object "+o+ "doesn't belongs to any category");
            return l;
        }
    }

    @Override
    public InterpolatorFactory interpolation() {
        return null;
    }

    @Override
    public String defaultName() {
        return "category";
    }

    @Override
    public Task.ExtractZAxis getExtractZDim() {
        return null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{selection, addDefaultCategory};
    }

    @Override
    public String getHintText() {
        return "Extract Category as an array of dimension (N,), N begin the number of objects. <br/>Category of object of label L is located at index L-1. ";
    }

}
