package bacmman.core;

import bacmman.ui.logger.ProgressDisplay;

import java.util.function.Consumer;

/**
 * Counts tasks and sub-tasks and forwards progress to a {@link ProgressDisplay}:
 * <ul>
 *     <li>overall progress = (finished tasks + fraction of the current task) / number of tasks</li>
 *     <li>progress of the current task = (finished sub-tasks + fraction of the current sub-task) / number of sub-tasks</li>
 * </ul>
 * The fraction of the current sub-task is reported through {@link #subProgress(int)}.
 * Displayed progress never goes backward: the progress of the current task only increases until the next task starts (see {@link Fraction}),
 * and overall progress only increases until it is explicitly reset by {@link #setTaskNumber(int)}, {@link #setProgress(int)} or {@link #setRunning(boolean)}
 * (e.g. when {@link #incrementTaskNumber(int)} adds tasks during the run, it stays still until the actual progress catches up).
 * Values are only forwarded when the displayed percentage or message changes.
 */
public class ProgressTracker implements ProgressCallback {
    private final ProgressDisplay ui;
    private int taskNumber, taskCounter;
    private final Fraction currentTask = new Fraction();
    private double overall; // overall fraction done, never decreases until reset
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
        overall = 0;
        newTask();
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
        currentTask.setSubtaskNumber(number);
        subTaskMessage = null;
        subProgressMessage = null;
        update();
    }

    @Override
    public synchronized void incrementSubTask() {
        currentTask.incrementSubTask();
        subProgressMessage = null;
        update();
    }

    @Override
    public synchronized void incrementProgress() {
        ++taskCounter;
        newTask();
        update();
    }

    /**
     * Explicitly sets the number of finished tasks: this can make overall progress go backward
     */
    @Override
    public synchronized void setProgress(int i) {
        taskCounter = i;
        overall = 0;
        newTask();
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
        return currentTask.newHandle(steps, this, this::update, message -> {
            subProgressMessage = message;
            update();
        });
    }

    @Override
    public void log(String message) {
        if (ui != null) ui.setMessage(message);
    }

    @Override
    public void setRunning(boolean running) {
        if (ui != null) ui.setRunning(running);
        synchronized (this) { // the display is reset when running state changes
            overall = 0;
            lastProgress = -1;
            lastSubProgress = -1;
            lastProgressText = null;
            lastSubText = null;
        }
    }

    private void newTask() {
        currentTask.reset();
        subTaskMessage = null;
        subProgressMessage = null;
        lastSubProgress = -1; // a new task starts: its progress starts from 0
    }

    private void update() {
        double taskFraction = currentTask.getValue();
        if (taskNumber > 0) overall = Math.max(overall, Math.min(1, (taskCounter + taskFraction) / taskNumber));
        if (ui == null) return;
        int progress = toPercent(overall);
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
        String subText = Fraction.join(subTaskMessage, subProgressMessage);
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
        String task = taskNumber > 0 ? "Task " + Math.min(taskCounter + 1, taskNumber) + "/" + taskNumber : null;
        return Fraction.join(task, progressMessage);
    }

    /**
     * Fraction of a unit of work that is done, in [0, 1]. The value never decreases until {@link #reset()}.
     * <ul>
     *     <li>The work can be divided into sub-tasks ({@link #setSubtaskNumber(int)}, {@link #incrementSubTask()}). Setting a new number of sub-tasks divides the remaining work: what is already done is kept.</li>
     *     <li>Progress inside the current sub-task is reported by {@link ProgressCallback.SubProgress} handles. Several handles can be created in the same sub-task (e.g. a track pre-filter then a segmenter that both predict): each new handle divides what remains of the sub-task.</li>
     * </ul>
     * Not thread-safe: the owner synchronizes on the lock given to {@link #newHandle(int, Object, Runnable, Consumer)}.
     */
    static class Fraction {
        private double base; // value when the current division into sub-tasks was set
        private int subTaskNumber, subTaskCounter;
        private double subTaskFraction; // progress within the current sub-task, in [0, 1]
        private int generation; // incremented each time the current sub-task changes, to invalidate handles
        private double value;

        void reset() {
            base = 0;
            value = 0;
            subTaskNumber = 0;
            subTaskCounter = 0;
            newSubTask();
        }

        void setSubtaskNumber(int number) {
            base = value;
            subTaskNumber = Math.max(0, number);
            subTaskCounter = 0;
            newSubTask();
        }

        void incrementSubTask() {
            ++subTaskCounter;
            newSubTask();
            update();
        }

        /**
         * Marks the whole work as done
         */
        void complete() {
            value = 1;
        }

        double getValue() {
            return value;
        }

        private void newSubTask() {
            subTaskFraction = 0;
            ++generation;
        }

        private void update() {
            double inner = subTaskNumber > 0 ? Math.min(1, (subTaskCounter + subTaskFraction) / subTaskNumber) : subTaskFraction;
            value = Math.max(value, base + (1 - base) * inner);
        }

        /**
         * @param steps number of steps the handle is divided into
         * @param lock object the owner synchronizes on
         * @param onUpdate called (holding the lock) after the value may have changed
         * @param onMessage called (holding the lock) when the handle sets a message
         * @return handle that reports progress within the current sub-task. It has no effect once the current sub-task has changed
         */
        ProgressCallback.SubProgress newHandle(int steps, Object lock, Runnable onUpdate, Consumer<String> onMessage) {
            final int handleGeneration = generation;
            final double start = subTaskFraction; // a new handle divides what remains of the sub-task
            final double total = Math.max(1, steps);
            return new ProgressCallback.SubProgress() {
                double count = 0;
                @Override
                public void increment(double n) {
                    if (!(n > 0)) return; // also ignores NaN
                    synchronized (lock) {
                        if (handleGeneration != generation) return;
                        count += n;
                        subTaskFraction = Math.max(subTaskFraction, start + (1 - start) * Math.min(1, count / total));
                        update();
                        onUpdate.run();
                    }
                }

                @Override
                public void setMessage(String message) {
                    synchronized (lock) {
                        if (handleGeneration != generation) return;
                        onMessage.accept(message);
                    }
                }
            };
        }

        static String join(String... messages) {
            StringBuilder sb = new StringBuilder();
            for (String message : messages) {
                if (message == null || message.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(message);
            }
            return sb.toString();
        }
    }
}
