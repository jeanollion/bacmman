package bacmman.plugins;

import bacmman.configuration.parameters.Parameter;
import org.json.simple.JSONArray;

import java.util.List;

public interface PluginWithLegacyInitialization {
    void legacyInit(JSONArray parameters);
}
