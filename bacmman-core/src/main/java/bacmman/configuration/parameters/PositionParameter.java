package bacmman.configuration.parameters;

import bacmman.configuration.experiment.Experiment;


public class PositionParameter extends AbstractChoiceParameterMultiple<String, PositionParameter> {

    public PositionParameter(String name, boolean allowNoSelection, boolean allowMultipleSelection) {
        super(name, s->s, allowNoSelection, allowMultipleSelection);
    }

    @Override
    public String[] getChoiceList() {
        if (getXP()==null) return new String[0];
        return getXP().getPositionsAsString();
    }
    protected Experiment getXP() {
        return ParameterUtils.getExperiment(this);
    }
    public String[] getSelectedPosition(boolean returnAllIfNoneSelected) {
        if (returnAllIfNoneSelected && selectedItems.length == 0) return getChoiceList();
        return getSelectedItems();
    }
}
