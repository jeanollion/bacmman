package bacmman.plugins;

import bacmman.configuration.parameters.Parameter;

import java.util.function.Consumer;

public interface ParameterChangeCallback<T> {
    T setParameterChangeCallback(Consumer<Parameter> parameterChangeCallBack);
    Consumer<Parameter> getParameterChangeCallback();
}
