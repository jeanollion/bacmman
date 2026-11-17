package bacmman.core;

import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.ui.logger.ExperimentSearchUtils;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.MultipleException;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Common implementation of tasks run on a dataset: dataset access, error collection, logging and progress reporting.
 * Progress is delegated to the {@link ProgressTracker} shared by all tasks run together.
 * @param <T> type of the task
 */
public abstract class AbstractTask<T extends AbstractTask<T>> implements Task<T>, ProgressCallback {
    private static final Logger logger = LoggerFactory.getLogger(AbstractTask.class);
    String dir;
    volatile MasterDAO db;
    boolean ownDB;
    MultipleException errors = new MultipleException();
    ProgressTracker progress; // shared by all tasks run together
    ProgressDisplay ui;
    int compression = 4; // compression level of exported HDF5 files

    protected AbstractTask() {
        setUI(Core.getProgressLogger());
        ownDB = true;
    }

    protected AbstractTask(MasterDAO db) {
        setUI(Core.getProgressLogger());
        this.db = db;
        this.dir = db.getDatasetDir().toFile().getAbsolutePath();
        ownDB = false;
    }

    protected AbstractTask(String dbName, String dir) {
        this();
        if (dir != null && !"".equals(dir)) this.dir = dir;
        else this.dir = ExperimentSearchUtils.searchForLocalDir(dbName);
    }

    @SuppressWarnings("unchecked")
    protected T self() {
        return (T) this;
    }

    public T setDir(String dir) {
        if (dir != null && dir.equals(this.dir)) return self();
        if (db != null) {
            if (ownDB) {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
            }
            clearSelections();
            this.db = null;
        }
        this.ownDB = false;
        this.dir = dir;
        return self();
    }

    public T setDB(MasterDAO db) {
        if (this.db != null && ownDB) {
            this.db.unlockPositions();
            this.db.unlockConfiguration();
            this.db.clearCache(true, true, true);
        }
        clearSelections();
        this.db = db;
        ownDB = false;
        return self();
    }

    public MasterDAO getDB() {
        initDB();
        return db;
    }

    @Override
    public String getDir() {
        return dir;
    }

    public T setCompression(int compression) {
        this.compression = compression;
        return self();
    }

    public int getCompression() {
        return compression;
    }

    public List<Pair<String, Throwable>> getErrors() {
        return errors.getExceptions();
    }

    @Override
    public void initDB() {
        if (db == null) {
            synchronized (this) {
                if (db == null) {
                    if (dir == null) throw new RuntimeException("XP not found");
                    Path path = Paths.get(dir);
                    if (Files.isDirectory(path)) {
                        try {
                            db = MasterDAOFactory.getDAO(path);
                        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                                 IllegalAccessException e) {
                            throw new RuntimeException("Error instantiating DB: " + dir, e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void clearDB() {
        if (db != null) {
            if (ownDB) {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
                db = null;
            } else db.clearCache(false, false, true);
        }
        clearSelections();
    }

    /**
     * Frees memory used by selections loaded by the task
     */
    public void clearSelections() {}

    protected String getDBName() {
        return db != null ? db.getDBName() : (dir != null ? Paths.get(dir).getFileName().toString() : "");
    }

    public void flush(boolean errors) {
        clearDB();
        if (errors) this.errors.getExceptions().clear();
    }

    // JSON
    /**
     * @return JSON entry with the class of the task (so that it can be created by {@link Task#createTasksFromJSONEntry(JSONObject)}) and the dataset directory
     */
    protected JSONObject initJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("class", getClass().getName());
        if (dir != null) res.put("dir", dir);
        return res;
    }

    protected void initDirFromJSONEntry(JSONObject data) {
        if (data.containsKey("dir")) {
            dir = (String) data.get("dir");
            if (!new File(dir).exists()) dir = null;
        }
    }

    @Override
    public T duplicate() {
        try {
            T res = (T) getClass().getDeclaredConstructor().newInstance();
            res.initFromJSONEntry(toJSONEntry());
            return res;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    // errors and messages
    @Override
    public void setUI(ProgressDisplay ui) {
        if (ui == null) this.ui = null;
        else {
            if (ui.equals(this.ui)) return;
            this.ui = ui;
        }
    }

    public void printErrors() {
        if (!errors.isEmpty()) logger.error("Errors for Task: {}", toString());
        for (Pair<String, ? extends Throwable> e : errors.getExceptions()) logger.error(e.key, e.value);
    }

    @Override
    public void printErrorsTo(ProgressDisplay ui) {
        if (!errors.isEmpty()) ui.setMessage("Errors for Task: " + this);
        for (Pair<String, ? extends Throwable> e : errors.getExceptions()) ui.setMessage(e.key + ": " + e.value);
    }

    @Override
    public void done() {
        clearDB();
        this.publish("Task done.");
        publishErrors();
        this.printErrors();
        this.publish("------------------");
    }

    public void publish(String message) {
        if (ui != null) ui.setMessage(message);
        logger.debug(message);
    }

    public void publishMemoryUsage(String message) {
        publish(message + Utils.getMemoryUsage());
    }

    @Override
    public void publishErrors() {
        errors.unroll();
        this.publish("Errors: " + this.errors.getExceptions().size() + " For JOB: " + this);
        for (Pair<String, ? extends Throwable> e : errors.getExceptions()) publishError(e.key, e.value);
    }

    public void publishError(String localizer, Throwable error) {
        publish("Error @" + localizer + " " + (error == null ? "null" : error.toString()));
        if (error != null) publishError(error);
    }

    public void publishError(Throwable t) {
        Arrays.stream(t.getStackTrace())
                .map(StackTraceElement::toString)
                .filter(Task::printStackTraceElement)
                .forEachOrdered(this::publish);
        if (t.getCause() != null && !t.getCause().equals(t)) {
            publish("caused By");
            publishError(t.getCause());
        }
    }

    // Progress Callback: delegates to the tracker shared by all tasks run together
    @Override
    public void setProgressTracker(ProgressTracker progress) {
        this.progress = progress;
    }

    protected ProgressTracker getProgress() {
        if (progress == null) progress = new ProgressTracker(ui);
        return progress;
    }

    @Override
    public void incrementTaskNumber(int subtask) {
        getProgress().incrementTaskNumber(subtask);
    }

    @Override
    public void incrementProgress() {
        getProgress().incrementProgress();
    }

    @Override
    public void setSubtaskNumber(int number) {
        getProgress().setSubtaskNumber(number);
    }

    @Override
    public void setTaskNumber(int taskNumber) {
        getProgress().setTaskNumber(taskNumber);
    }

    @Override
    public void incrementSubTask() {
        getProgress().incrementSubTask();
    }

    @Override
    public void setProgress(int i) {
        getProgress().setProgress(i);
    }

    @Override
    public int getTaskNumber() {
        return getProgress().getTaskNumber();
    }

    @Override
    public void setProgressMessage(String message) {
        getProgress().setProgressMessage(message);
    }

    @Override
    public void setSubTaskMessage(String message) {
        getProgress().setSubTaskMessage(message);
    }

    @Override
    public SubProgress subProgress(int steps) {
        return getProgress().subProgress(steps);
    }

    @Override
    public void log(String message) {
        publish(message);
    }

    @Override
    public void setRunning(boolean running) {
        if (ui != null) ui.setRunning(running);
    }
}
