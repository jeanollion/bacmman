package bacmman.plugins;

import bacmman.configuration.parameters.Parameter;

import java.util.function.Consumer;

public interface ParameterChangeCallback<T> {
    T addParameterChangeCallback(Consumer<Parameter> parameterChangeCallBack);
    boolean removeParameterChangeCallback(Consumer<Parameter> parameterChangeCallBack);
}
