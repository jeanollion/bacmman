package bacmman.data_structure;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.plugins.DLengine;
import bacmman.utils.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class DLengineProvider {
    List<DLengine> engines = new ArrayList<>();
    public synchronized DLengine getEngine(Class engineClass, List<Parameter> parameters, Supplier<DLengine> engineFactory) {
        DLengine engine = engines.stream().filter(e -> e.getClass().equals(engineClass) && (ParameterUtils.sameContent(Arrays.asList(e.getParameters()), parameters))).findFirst().orElse(null);
        if (engine==null) {
            engine = engineFactory.get();
            if (engine!=null) engines.add(engine);
        }
        return engine;
    }
}
