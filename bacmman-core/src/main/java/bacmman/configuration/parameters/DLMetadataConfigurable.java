package bacmman.configuration.parameters;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.github.gist.DLModelMetadata;
import bacmman.plugins.Plugin;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Predicate;

public interface DLMetadataConfigurable {
    Logger logger = LoggerFactory.getLogger(DLMetadataConfigurable.class);
    void configureFromMetadata(DLModelMetadata metadata);
    static void configure(DLModelMetadata metadata, Parameter... parameters) {
        configure(metadata, Arrays.asList(parameters));
    }
    static void configure(DLModelMetadata metadata, Collection<Parameter> parameters, Parameter... exclude) {
        Predicate<Parameter> excludeTest = p -> {
            for (Parameter pp : exclude) if (p.equals(pp)) return true;
            return false;
        };
        for (Parameter param : parameters) {
            if (excludeTest.test(param)) continue;
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
    static void configureParentsAndSiblings(DLModelMetadata metadata, Parameter parameter) {
        if (parameter.getParent() instanceof ContainerParameter) {
            ContainerParameter parent = ((ContainerParameter)parameter.getParent());
            if (parent instanceof ListParameter || (parent instanceof PluginParameter && ( ProcessingPipeline.class.equals(((PluginParameter)parent).getPluginType()) || Transformation.class.equals(((PluginParameter)parent).getPluginType()))  ) || parent instanceof Structure || parent instanceof Experiment) return;
            configure(metadata, parent.getChildren(), parameter);
            configure(metadata, parent);
            configureParentsAndSiblings(metadata, parent);
        }
    }
}
