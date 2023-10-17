package bacmman.configuration.parameters;

import java.util.function.BiConsumer;

public interface ParameterWithLegacyInitialization<P, V> {
    void legacyInit();
    Parameter[] getLegacyParameters();
    P setLegacyParameter(BiConsumer<Parameter[], P> setValue, Parameter... p);

    /**
     * When parameter cannot be initialized, this value is used as default. Useful when parametrization of a module has changed.
     * @param value default value
     * @return this parameter for convenience
     */
    P setLegacyInitializationValue(V value);
}
