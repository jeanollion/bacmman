package bacmman.configuration.parameters;

public interface PythonConfiguration {
    public static String toSnakeCase(String name) {
        name = name.replace(' ', '_');
        name = name.replace('-', '_');
        return name.toLowerCase();
    }

    Object getPythonConfiguration(); // return JSONArray or JSONObject passed to python
    String getPythonConfigurationKey();
}
