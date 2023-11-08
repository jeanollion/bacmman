package bacmman.configuration.parameters;

import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.Collection;

public interface PythonConfiguration {
    public static String toSnakeCase(String name) {
        name = name.replace(' ', '_');
        name = name.replace('-', '_');
        return name.toLowerCase();
    }

    Object getPythonConfiguration(); // return JSONArray or JSONObject passed to python
    String getPythonConfigurationKey();

    static void putParameters(Parameter[] parameters, JSONObject container) {
        putParameters(Arrays.asList(parameters), container);
    }
    static void putParameters(Collection<Parameter> parameters, JSONObject container) {
        for (Parameter p : parameters) {
            if (p instanceof PythonConfiguration) {
                String key = ((PythonConfiguration)p).getPythonConfigurationKey();
                if (key == null) key = toSnakeCase(p.getName());
                Object conf = ((PythonConfiguration)p).getPythonConfiguration();
                if (conf!=null) container.put(key, conf);
            } else {
                Object conf = p.toJSONEntry();
                if (conf!=null) container.put(toSnakeCase(p.getName()), conf);
            }
        }

    }
}
