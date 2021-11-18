package bacmman.configuration.parameters;

import bacmman.utils.Pair;

import java.util.function.Function;

public interface ParameterWithLegacyInitialization<P, V> {
    void legacyInit();
    Parameter getLegacyParameter();
    P setLegacyParameter(Parameter p, Function<Parameter, V> setValue);
    P setLegacyInitializationValue(V value);
}
