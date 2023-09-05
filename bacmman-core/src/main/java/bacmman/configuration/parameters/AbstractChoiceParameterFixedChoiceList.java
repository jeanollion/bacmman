package bacmman.configuration.parameters;

import java.util.function.Function;

public abstract class AbstractChoiceParameterFixedChoiceList<V, P extends AbstractChoiceParameterFixedChoiceList<V, P>> extends AbstractChoiceParameter<V, P> {
    protected String[] listChoice;
    public AbstractChoiceParameterFixedChoiceList(String name, String[] listChoice, String selectedItem, Function<String, V> mapper, boolean allowNoSelection) {
        super(name, null, mapper, allowNoSelection); // set listChoice before call set selected Item
        this.listChoice=listChoice;
        if (selectedItem!=null) this.setSelectedItem(selectedItem);
    }
    @Override
    public String[] getChoiceList() {return listChoice;}
}
