package bacmman.configuration.parameters;

import bacmman.plugins.Plugin;

public class SimplePluginParameterList<T extends Plugin> extends PluginParameterList<T, SimplePluginParameterList<T>> {
    boolean allowNoSelection;
    public SimplePluginParameterList(String name, String childLabel, Class<T> childClass, boolean allowNoSelection) {
        super(name, childLabel, childClass, allowNoSelection);
        this.allowNoSelection=allowNoSelection;
    }
    public SimplePluginParameterList(String name, String childLabel, Class<T> childClass, T childInstance, boolean allowNoSelection) {
        super(name, childLabel, childClass, childInstance, allowNoSelection);
        this.allowNoSelection=allowNoSelection;
    }

    @Override
    public SimplePluginParameterList<T> duplicate() {
        SimplePluginParameterList dup = new SimplePluginParameterList(name, childLabel, childClass, allowNoSelection);
        dup.setContentFrom(this);
        return dup;
    }
}
