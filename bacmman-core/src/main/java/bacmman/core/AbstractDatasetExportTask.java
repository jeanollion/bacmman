package bacmman.core;

import bacmman.utils.MultipleException;
import bacmman.utils.Pair;
import org.json.simple.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Export of a dataset to a HDF5 file, used to train deep learning models.
 * An export is one task, its steps are reported as sub-tasks.
 * Export is performed by bacmman.py_dataset.ExtractDatasetUtil (bacmman-dl module), called by reflection to avoid a dependency.
 * @param <T> type of the task
 */
public abstract class AbstractDatasetExportTask<T extends AbstractDatasetExportTask<T>> extends AbstractTask<T> {
    static final String EXPORT_UTIL_CLASS = "bacmman.py_dataset.ExtractDatasetUtil";
    String outputFile;

    protected AbstractDatasetExportTask() {
        super();
    }

    protected AbstractDatasetExportTask(bacmman.data_structure.dao.MasterDAO db) {
        super(db);
    }

    protected AbstractDatasetExportTask(String dbName, String dir) {
        super(dbName, dir);
    }

    public String getOutputFile() {
        return outputFile;
    }

    /**
     * @return true if the exported samples are time-lapse sequences
     */
    public boolean isTimelapse() {
        return false;
    }

    /**
     * @return name of the export, used in messages
     */
    protected abstract String getExportName();

    /**
     * @return name of the method of ExtractDatasetUtil that performs the export. It takes this task as sole argument
     */
    protected abstract String getExportMethodName();

    /**
     * @return positions read by the export
     */
    protected abstract Collection<String> getExportedPositions();

    /**
     * Checks the parameters of the export, adds errors to {@link #errors}. Dataset is initialized.
     */
    protected abstract void checkParameters();

    @Override
    public boolean isValid() {
        try {
            initDB();
        } catch (RuntimeException e) {
            errors.addExceptions(new Pair<>("DB could not be initialized", e));
            return false;
        }
        if (db == null || db.getExperiment() == null) {
            errors.addExceptions(new Pair<>(getDBName(), new Exception("DB: " + getDBName() + " not found")));
            printErrors();
            return false;
        }
        if (outputFile == null || outputFile.isEmpty()) errors.addExceptions(new Pair<>(getDBName(), new IllegalArgumentException("No output file")));
        checkParameters();
        for (Pair<String, Throwable> e : errors.getExceptions()) publish("Invalid Task Error @" + e.key + " " + (e.value == null ? "null" : e.value.toString()));
        return errors.isEmpty();
    }

    @Override
    public int countSubtasks() {
        return 1;
    }

    @Override
    public void runTask() {
        publish("Run task: " + this);
        initDB();
        if (progress == null) {
            progress = new ProgressTracker(ui);
            progress.setTaskNumber(countSubtasks());
        }
        String[] positions = getExportedPositions().toArray(new String[0]);
        db.lockPositions(positions);
        try {
            Class<?> clazz = Class.forName(EXPORT_UTIL_CLASS);
            Method m = clazz.getMethod(getExportMethodName(), getClass());
            m.invoke(null, this);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            errors.addExceptions(new Pair<>(getExportName(), new RuntimeException("Could not export dataset: missing bacmman-dl module", e)));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof MultipleException) errors.addExceptions(((MultipleException) cause).getExceptions());
            else errors.addExceptions(new Pair<>(getExportName(), cause));
        } catch (Throwable e) {
            errors.addExceptions(new Pair<>(getExportName(), e));
        } finally {
            if (ownDB) {
                db.unlockPositions(positions);
                db.unlockConfiguration();
                db.clearCache(true, true, true);
                db = null;
            } else {
                for (String position : positions) db.clearCache(position);
            }
            clearSelections();
        }
    }

    protected JSONObject initJSONEntry() {
        JSONObject res = super.initJSONEntry();
        if (outputFile != null) res.put("outputFile", outputFile);
        res.put("compression", compression);
        return res;
    }

    /**
     * @param data JSON entry of the task
     * @param legacy JSON object of the export parameters, in the format of the former {@link ProcessingTask} that included dataset export
     */
    protected void initCommonFromJSONEntry(JSONObject data, JSONObject legacy) {
        initDirFromJSONEntry(data);
        JSONObject params = legacy != null ? legacy : data;
        outputFile = (String) params.get("outputFile");
        if (params.containsKey("compression")) compression = ((Number) params.get("compression")).intValue();
    }

    /**
     * @return value of key, or of legacy key if key is absent
     */
    static Object get(JSONObject o, String key, String legacyKey) {
        if (o.containsKey(key)) return o.get(key);
        return o.get(legacyKey);
    }
}
