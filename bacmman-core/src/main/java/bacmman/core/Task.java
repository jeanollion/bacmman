package bacmman.core;

import bacmman.ui.logger.ProgressDisplay;
import org.json.simple.JSONObject;

import java.lang.reflect.InvocationTargetException;
import bacmman.ui.logger.FileProgressLogger;
import bacmman.ui.logger.MultiProgressDisplay;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public interface Task<T extends Task<T>> {
    boolean isValid();
    void printErrorsTo(ProgressDisplay ui);
    void setUI(ProgressDisplay ui);
    int countSubtasks();
    /**
     * @param progress progress tracker shared by all tasks run together
     */
    void setProgressTracker(ProgressTracker progress);
    void initDB();
    void clearDB();
    String getDir();
    default void setPreprocessingMemoryThreshold(double preProcessingMemoryThreshold) {}
    void runTask();
    default void done() {}
    void publishErrors();
    JSONObject toJSONEntry();
    void initFromJSONEntry(JSONObject data);
    T duplicate();
    /**
     * Entries saved by former versions may describe several tasks, they are split:
     * processing and export actions were performed by the same task, and dataset exports were performed by the processing task.
     * @return the tasks described by the JSON entry
     */
    static List<Task> createTasksFromJSONEntry(JSONObject data) {
        List<Task> res = new ArrayList<>(2);
        if (data.containsKey("class")) {
            String className = (String)data.get("class");
            try {
                Class<?> clazz = Class.forName(className);
                Task t = (Task) clazz.getDeclaredConstructor().newInstance();
                t.initFromJSONEntry(data);
                res.add(t);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else if (data.containsKey(DatasetExportTask.LEGACY_KEY)) {
            DatasetExportTask t = new DatasetExportTask();
            t.initFromJSONEntry(data);
            res.add(t);
        } else if (data.containsKey(RawDatasetExportTask.LEGACY_KEY)) {
            RawDatasetExportTask t = new RawDatasetExportTask();
            t.initFromJSONEntry(data);
            res.add(t);
        } else { // processing task, which may contain export actions in former versions
            ProcessingTask processing = new ProcessingTask();
            processing.initFromJSONEntry(data);
            boolean export = ExportTask.containsExport(data);
            if (processing.hasProcessingAction() || !export) res.add(processing);
            if (export) {
                ExportTask t = new ExportTask();
                t.initFromJSONEntry(data);
                res.add(t);
            }
        }
        return res;
    }

    // execution of tasks
    static boolean printStackTraceElement(String stackTraceElement) {
        //return true;
        return !stackTraceElement.startsWith("java.util.")&&!stackTraceElement.startsWith("java.lang.")&&!stackTraceElement.startsWith("java.security.")
                &&!stackTraceElement.startsWith("java.awt.")&&!stackTraceElement.startsWith("java.lang.")
                &&!stackTraceElement.startsWith("sun.reflect.")&&!stackTraceElement.startsWith("javax.swing.")
                &&!stackTraceElement.startsWith("bacmman.core.")&&!stackTraceElement.startsWith("bacmman.utils.");
    }

    static void executeTasksInForeground(List<Task> tasks, ProgressDisplay ui, double preProcessingMemoryThreshold) {
        int totalSubtasks = 0;
        for (Task t : tasks) {
            LoggerFactory.getLogger(Task.class).debug("checking task: {}", t);
            if (!t.isValid()) {
                t.clearDB();
                if (ui!=null) ui.setMessage("Invalid task: "+t.toString());
                t.printErrorsTo(ui);
                return;
            }
            t.setUI(ui);
            totalSubtasks+=t.countSubtasks();
            t.clearDB();
        }
        if (ui!=null) ui.setMessage("Total subTasks: "+totalSubtasks);
        ProgressTracker progress = new ProgressTracker(ui);
        progress.setTaskNumber(totalSubtasks);
        for (Task t : tasks) t.setProgressTracker(progress);
        for (int i = 0; i<tasks.size(); ++i) {
            //if (ui!=null && i==0) ui.setRunning(true);
            tasks.get(i).initDB();
            final int ii = i;
            Consumer<FileProgressLogger> setLF = l->{if (l.getLogFile()==null) l.setLogFile(Paths.get(tasks.get(ii).getDir(),"Log.txt").toString());};
            Consumer<FileProgressLogger> unsetLF = l->l.setLogFile(null);
            if (ui instanceof MultiProgressDisplay) ((MultiProgressDisplay)ui).applyToLogUserInterfaces(setLF);
            else if (ui instanceof FileProgressLogger) setLF.accept((FileProgressLogger)ui);
            tasks.get(i).setPreprocessingMemoryThreshold(preProcessingMemoryThreshold);
            tasks.get(i).runTask(); // clears cache +  unlock if !keepdb
            tasks.get(i).done();
            if (ui instanceof MultiProgressDisplay) ((MultiProgressDisplay)ui).applyToLogUserInterfaces(unsetLF);
            else if (ui instanceof FileProgressLogger) unsetLF.accept((FileProgressLogger)ui);

            if (ui!=null && i==tasks.size()-1) {
                if (tasks.size()>1) {
                    for (Task t : tasks) t.publishErrors();
                }
            }
        }
    }
    static void executeTasks(List<Task> tasks, ProgressDisplay ui, double preProcessingMemoryThreshold, Runnable... endOfWork) {
        int totalSubtasks = 0;
        for (Task t : tasks) {
            LoggerFactory.getLogger(Task.class).debug("checking task: {}", t);
            if (!t.isValid()) {
                t.clearDB();
                if (ui!=null) ui.setMessage("Invalid task: "+t.toString());
                t.printErrorsTo(ui);
                return;
            }
            t.setUI(ui);
            int c = t.countSubtasks();
            totalSubtasks+=c;
            t.clearDB();
        }
        LoggerFactory.getLogger(Task.class).debug("all tasks are valid");
        if (ui!=null) ui.setMessage("Total subTasks: "+totalSubtasks);
        ProgressTracker progress = new ProgressTracker(ui);
        progress.setTaskNumber(totalSubtasks);
        for (Task t : tasks) t.setProgressTracker(progress);
        DefaultWorker.execute(i -> {
            //if (ui!=null && i==0) ui.setRunning(true);
            tasks.get(i).initDB();
            Consumer<FileProgressLogger> setLF = l->{if (l.getLogFile()==null) l.setLogFile(Paths.get(tasks.get(i).getDir(),"Log.txt").toString());};
            Consumer<FileProgressLogger> unsetLF = l->l.setLogFile(null);
            if (ui instanceof MultiProgressDisplay) ((MultiProgressDisplay)ui).applyToLogUserInterfaces(setLF);
            else if (ui instanceof FileProgressLogger) setLF.accept((FileProgressLogger)ui);
            tasks.get(i).setPreprocessingMemoryThreshold(preProcessingMemoryThreshold);
            tasks.get(i).runTask(); // clears cache +  unlock if !keepdb
            tasks.get(i).done();
            if (ui instanceof MultiProgressDisplay) ((MultiProgressDisplay)ui).applyToLogUserInterfaces(unsetLF);
            else if (ui instanceof FileProgressLogger) unsetLF.accept((FileProgressLogger)ui);
            
            if (ui!=null && i==tasks.size()-1) {
                ui.setRunning(false);
                if (tasks.size()>1) {
                    for (Task t : tasks) t.publishErrors();
                }
            }
            return "";
        }, tasks.size(), Core.getProgressLogger(), false).setEndOfWork(
                ()->{for (Runnable r : endOfWork) r.run();});
    }
    static void executeTaskInForeground(Task t, ProgressDisplay ui, double preProcessingMemoryThreshold) {
        executeTasksInForeground(new ArrayList<Task>(1){{add(t);}}, ui, preProcessingMemoryThreshold);
    }
    static void executeTask(Task t, ProgressDisplay ui, double preProcessingMemoryThreshold, Runnable... endOfWork) {
        executeTasks(new ArrayList<Task>(1){{add(t);}}, ui, preProcessingMemoryThreshold, endOfWork);
    }

}
