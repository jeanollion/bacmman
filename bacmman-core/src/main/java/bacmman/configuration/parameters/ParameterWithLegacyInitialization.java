package bacmman.configuration.parameters;

import java.util.function.BiConsumer;

public interface ParameterWithLegacyInitialization<P, V> {
    /**
     * This method is run when a parameter cannot be initialized, meaning that the parametrization of the module has changed.
     */
    void legacyInit();

    Parameter[] getLegacyParameters();
    /**
     * When a parameter A of a module has been replaced by B, this methods allows to initialize B using the former value of A
     * @param p
     * @param setValue
     * @return
     */
    P setLegacyParameter(BiConsumer<Parameter[], P> setValue, Parameter... p);

    /**
     * When parameter cannot be initialized, this value is used as default. Useful when default value of a parameter has changed.
     * @param value default value
     * @return this parameter for convenience
     */
    P setLegacyInitializationValue(V value);
}
