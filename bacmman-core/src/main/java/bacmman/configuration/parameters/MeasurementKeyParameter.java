package bacmman.configuration.parameters;

import bacmman.configuration.experiment.Experiment;
import bacmman.measurement.MeasurementKeyObject;

public class MeasurementKeyParameter extends ParameterImpl<MeasurementKeyParameter> implements ChoosableParameter<MeasurementKeyParameter>, Listenable<MeasurementKeyParameter> {
    protected String selectedKey;
    protected int objectClassIdx;

    protected MeasurementKeyParameter(String name, int objectClassIdx) {
        super(name);
        this.objectClassIdx = objectClassIdx;
    }

    @Override
    public void setSelectedItem(String item) {
        this.selectedKey = item;
        fireListeners();
    }

    @Override
    public String[] getChoiceList() {
        Experiment xp = ParameterUtils.getExperiment(this);
        if (xp==null) return new String[0];
        return xp.getAllMeasurementNamesByStructureIdx(MeasurementKeyObject.class, objectClassIdx).get(objectClassIdx);
    }

    @Override
    public int getSelectedIndex() {
        if (selectedKey == null) return -1;
        else {
            String[] choice =getChoiceList();
            for (int i = 0; i<choice.length; ++i) {
                if (selectedKey.equals(choice[i])) return i;
            }
            return -1;
        }
    }

    public String getSelectedKey() {
        return selectedKey;
    }

    @Override
    public boolean isAllowNoSelection() {
        return false;
    }

    @Override
    public String getNoSelectionString() {
        return "No Measurement Selected";
    }

    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof MeasurementKeyParameter) {
            selectedKey = ((MeasurementKeyParameter) other).getSelectedKey();
        }
    }

    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof MeasurementKeyParameter) {
            if (selectedKey == null ) return ((MeasurementKeyParameter) other).getSelectedKey()==null;
            else return selectedKey.equals(((MeasurementKeyParameter) other).getSelectedKey());
        } else return false;
    }

    @Override
    public Object toJSONEntry() {
        return selectedKey;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry instanceof String) this.selectedKey = (String)jsonEntry;
    }
}
