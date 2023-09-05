package bacmman.configuration.parameters;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.DockerGateway;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.core.Core;
import java.util.function.Predicate;

public class DockerImageParameter extends AbstractChoiceParameter<String, DockerImageParameter> {
    protected String selectedItem;
    protected Predicate<String> filter;
    protected DockerImageParameter(String name, Predicate<String> filter) {
        super(name, null, s->s, false);
        this.filter = filter == null? s->true : filter;
    }

    @Override
    public void setSelectedItem(String item) {
        this.selectedItem = item;
        fireListeners();
    }

    @Override
    public String[] getChoiceList() {
        DockerGateway gateway = Core.getCore().getDockerGateway();
        if (gateway==null) return new String[0];
        return gateway.listImages().filter(filter).toArray(String[]::new);
    }

    @Override
    public String getValue() {
        return getSelectedItem();
    }

    @Override
    public void setValue(String value) {
        this.setSelectedItem(value);
    }
}
