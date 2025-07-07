package bacmman.configuration.parameters;

import java.util.Arrays;
import java.util.List;

public class CategoryParameter extends ConditionalParameterAbstract<Boolean, CategoryParameter> {

    SelectionParameter categorySelection = new SelectionParameter("Categories", true).addValidationFunction(sel -> {
        BooleanParameter addDef = ParameterUtils.getParameterFromSiblings(BooleanParameter.class, sel, null);
        if (!addDef.getSelected()) return sel.getSelectedSelections().count() > 1;
        else return true;
    });
    BooleanParameter addDefaultCategory = new BooleanParameter("Add Default Category", true).setHint("If true, all objects that are in no selection are assigned to category 0, and categories associated to selections start from index one. One object cannot belong to 2 selected selections<br/>If false, there must be at least 2 selected selections and all objects must belong to one and only one selection");


    public CategoryParameter(boolean predictCategory) {
        super(new BooleanParameter("Predict Category", predictCategory));
        setActionParameters(true, categorySelection, addDefaultCategory);
    }

    public boolean getSelected() {
        return action.getValue();
    }

    public List<String> getCategorySelections() {
        return getSelected() ? Arrays.asList(categorySelection.getSelectedItems()) : null;
    }

    public boolean addDefaultCategory() {
        return addDefaultCategory.getSelected();
    }

    public CategoryParameter setSelectionObjectClass(int objectClass) {
        categorySelection.setSelectionObjectClass(objectClass);
        return this;
    }
}
