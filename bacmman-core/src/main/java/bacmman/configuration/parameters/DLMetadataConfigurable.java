package bacmman.configuration.parameters;

import bacmman.github.gist.DLModelMetadata;
import bacmman.plugins.Plugin;

import java.util.Arrays;
import java.util.Collection;

public interface DLMetadataConfigurable {
    void configureFromMetadata(DLModelMetadata metadata);
    static void configure(DLModelMetadata metadata, Parameter... parameters) {
        configure(metadata, Arrays.asList(parameters));
    }
    static void configure(DLModelMetadata metadata, Collection<Parameter> parameters) {
        for (Parameter param : parameters) {
            if (param instanceof DLMetadataConfigurable) ((DLMetadataConfigurable)param).configureFromMetadata(metadata);
            if (param instanceof PluginParameter) {
                PluginParameter pp = ((PluginParameter)param);
                Plugin p = pp.instantiatePlugin();
                if (p instanceof DLMetadataConfigurable) {
                    ((DLMetadataConfigurable)p).configureFromMetadata(metadata);
                    ParameterUtils.setContent(pp.getParameters(), Arrays.asList(p.getParameters()));
                }
            }
        }
    }
}
