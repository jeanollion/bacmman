package bacmman.configuration.parameters;

import bacmman.image.Image;
import bacmman.image.PrimitiveType;
import bacmman.utils.Utils;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public interface PythonConfiguration {
    static String toSnakeCase(String name) {
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
    static String imageToPythonString(Image image) { // z, y, x order
        StringBuilder sb = new StringBuilder();
        BiConsumer<Integer, Integer> appendLine = (y, z) -> {
            sb.append('[');
            for (int x = 0; x<image.sizeX(); ++x) {
                if (image instanceof PrimitiveType.FloatType) sb.append(image.getPixel(x, y, z));
                else sb.append((int)image.getPixel(x, y, z));
                if (x<image.sizeX()-1) sb.append(',');
            }
            sb.append(']');
        };
        Consumer<Integer> appendPlane = z -> {
            if (image.sizeY()==1 && image.sizeZ()==1) appendLine.accept(0, z);
            else {
                sb.append('[');
                for (int y = 0; y<image.sizeY();++y) {
                    appendLine.accept(y, z);
                    if (y<image.sizeY()-1) sb.append(',');
                }
                sb.append(']');
            }
        };
        if (image.sizeZ() == 1) appendPlane.accept(0);
        else {
            sb.append('[');
            for (int z = 0; z<image.sizeZ();++z) {
                appendPlane.accept(z);
                if (z<image.sizeZ()-1) sb.append(',');
            }
            sb.append(']');
        }
        return sb.toString();
    }

}
