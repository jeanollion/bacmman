package bacmman.core;

import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.JSONSerializable;
import org.json.simple.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.util.stream.Stream;

public interface TaskI<T extends TaskI<T>> {
    boolean isValid();
    void printErrorsTo(ProgressLogger ui);
    void setUI(ProgressLogger ui);
    int countSubtasks();
    void setTaskCounter(int[] counter);
    void initDB();
    String getDir();
    default void setPreprocessingMemoryThreshold(double preProcessingMemoryThreshold) {}
    void runTask();
    default void done() {}
    void publishErrors();
    JSONObject toJSONEntry();
    void initFromJSONEntry(JSONObject data);
    T duplicate();
    static TaskI createFromJSONEntry(JSONObject data) {
        if (data.containsKey("class")) {
            String className = (String)data.get("class");
            try {
                Class<?> clazz = Class.forName(className);
                TaskI res = (TaskI) clazz.getDeclaredConstructor().newInstance();
                res.initFromJSONEntry(data);
                return res;
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else { // most common task type
            Task res = new Task();
            res.initFromJSONEntry(data);
            return res;
        }
    }
}
