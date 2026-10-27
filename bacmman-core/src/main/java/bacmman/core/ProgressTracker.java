package bacmman.core;

import bacmman.ui.logger.ProgressDisplay;

/**
 * Counts tasks and sub-tasks and forwards progress to a {@link ProgressDisplay}:
 * <ul>
 *     <li>overall progress = (finished tasks + fraction of the current task) / number of tasks</li>
 *     <li>progress of the current task = (finished sub-tasks + fraction of the current sub-task) / number of sub-tasks</li>
 * </ul>
 * The fraction of the current sub-task is reported through {@link #subProgress(int)}.
 * Values are only forwarded when the displayed percentage or message changes.
 */
public class ProgressTracker implements ProgressCallback {
    private final ProgressDisplay ui;
    private int taskNumber, taskCounter;
    private int subTaskNumber, subTaskCounter;
    private double subTaskFraction; // progress within the current sub-task, in [0, 1]
    private int subTaskGeneration; // incremented each time the current sub-task changes, to invalidate SubProgress handles
    private String progressMessage, subTaskMessage, subProgressMessage;
    private int lastProgress = -1, lastSubProgress = -1;
    private String lastProgressText, lastSubText;

    public ProgressTracker(ProgressDisplay ui) {
        this.ui = ui;
    }

    public ProgressDisplay getUI() {
        return ui;
    }

    @Override
    public synchronized void incrementTaskNumber(int subtask) {
        taskNumber += subtask;
        update();
    }

    @Override
    public synchronized void setTaskNumber(int number) {
        taskNumber = number;
        taskCounter = 0;
        resetSubTasks();
        update();
    }

    @Override
    public synchronized int getTaskNumber() {
        return taskNumber;
    }

    public synchronized int getTaskCounter() {
        return taskCounter;
    }

    @Override
    public synchronized void setSubtaskNumber(int number) {
        subTaskNumber = Math.max(0, number);
        subTaskCounter = 0;
        subTaskMessage = null;
        newSubTask();
        update();
    }

    @Override
    public synchronized void incrementSubTask() {
        ++subTaskCounter;
        newSubTask();
        update();
    }

    @Override
    public synchronized void incrementProgress() {
        ++taskCounter;
        resetSubTasks();
        update();
    }

    @Override
    public synchronized void setProgress(int i) {
        taskCounter = i;
        resetSubTasks();
        update();
    }

    @Override
    public synchronized void setProgressMessage(String message) {
        progressMessage = message;
        update();
    }

    @Override
    public synchronized void setSubTaskMessage(String message) {
        subTaskMessage = message;
        subProgressMessage = null;
        update();
    }

    @Override
    public synchronized SubProgress subProgress(int steps) {
        final int generation = subTaskGeneration;
        final double total = Math.max(1, steps);
        return new SubProgress() {
            double count = 0;
            @Override
            public void increment(double n) {
                synchronized (ProgressTracker.this) {
                    if (generation != subTaskGeneration) return;
                    count += n;
                    subTaskFraction = Math.min(1, count / total);
                    update();
                }
            }

            @Override
            public void setMessage(String message) {
                synchronized (ProgressTracker.this) {
                    if (generation != subTaskGeneration) return;
                    subProgressMessage = message;
                    update();
                }
            }
        };
    }

    @Override
    public void log(String message) {
        if (ui != null) ui.setMessage(message);
    }

    @Override
    public void setRunning(boolean running) {
        if (ui != null) ui.setRunning(running);
        synchronized (this) { // the display is reset when running state changes
            lastProgress = -1;
            lastSubProgress = -1;
            lastProgressText = null;
            lastSubText = null;
        }
    }

    private void resetSubTasks() {
        subTaskNumber = 0;
        subTaskCounter = 0;
        subTaskMessage = null;
        newSubTask();
    }

    private void newSubTask() {
        subTaskFraction = 0;
        subProgressMessage = null;
        ++subTaskGeneration;
    }

    /**
     * @return fraction of the current task that is done, in [0, 1]
     */
    private double currentTaskFraction() {
        if (subTaskNumber > 0) return Math.min(1, (subTaskCounter + subTaskFraction) / subTaskNumber);
        else return subTaskFraction;
    }

    private void update() {
        if (ui == null) return;
        double taskFraction = currentTaskFraction();
        int progress = taskNumber > 0 ? toPercent((taskCounter + taskFraction) / taskNumber) : 0;
        int subProgress = toPercent(taskFraction);
        if (progress != lastProgress) {
            lastProgress = progress;
            ui.setProgress(progress);
        }
        if (subProgress != lastSubProgress) {
            lastSubProgress = subProgress;
            ui.setSubProgress(subProgress);
        }
        String progressText = getProgressText();
        if (!progressText.equals(lastProgressText)) {
            lastProgressText = progressText;
            ui.setProgressMessage(progressText);
        }
        String subText = getSubText();
        if (!subText.equals(lastSubText)) {
            lastSubText = subText;
            ui.setSubMessage(subText);
        }
    }

    /**
     * @return percentage, rounded down. A small tolerance absorbs rounding errors of fractional increments that sum to a whole (e.g. 7 x 1/7)
     */
    private static int toPercent(double fraction) {
        return (int) Math.min(100, Math.floor(100 * fraction + 1e-6));
    }

    private String getProgressText() {
        StringBuilder sb = new StringBuilder();
        if (taskNumber > 0) sb.append("Task ").append(Math.min(taskCounter + 1, taskNumber)).append("/").append(taskNumber);
        append(sb, progressMessage);
        return sb.toString();
    }

    private String getSubText() {
        StringBuilder sb = new StringBuilder();
        append(sb, subTaskMessage);
        append(sb, subProgressMessage);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String message) {
        if (message == null || message.isEmpty()) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(message);
    }
}
