package bacmman.core;

/**
 * Holds the progress callback of the work running on the current thread, so that plugins can report progress without it being passed through every call.
 * It is set by the caller (e.g. {@link bacmman.data_structure.Processor} around the processing of a parent track).
 * Plugins should read it once on the calling thread: threads of parallel streams do not see it.
 */
public final class ProgressContext {
    private static final ThreadLocal<ProgressCallback> CURRENT = new ThreadLocal<>();

    private ProgressContext() {}

    /**
     * @return progress callback of the current thread, or null if none is set
     */
    public static ProgressCallback get() {
        return CURRENT.get();
    }

    /**
     * @param pcb progress callback for the current thread (null clears it)
     * @return the previous callback, to be restored with this method
     */
    public static ProgressCallback set(ProgressCallback pcb) {
        ProgressCallback previous = CURRENT.get();
        if (pcb == null) CURRENT.remove();
        else CURRENT.set(pcb);
        return previous;
    }

    /**
     * Runs {@code action} with {@code pcb} as the current progress callback, then restores the previous one.
     */
    public static void run(ProgressCallback pcb, Runnable action) {
        ProgressCallback previous = set(pcb);
        try {
            action.run();
        } finally {
            set(previous);
        }
    }

    /**
     * Calls {@code action} with {@code pcb} as the current progress callback (null: no callback), then restores the previous one.
     */
    public static <T> T call(ProgressCallback pcb, java.util.function.Supplier<T> action) {
        ProgressCallback previous = set(pcb);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /**
     * Runs {@code action} with a slice of {@code parent} as the current progress callback: progress reported by the action (e.g. through {@link #subProgress(int)}) only advances this slice, and the slice is completed when the action ends.
     * Use it to divide a sub-task into parts that each run code reporting its own progress, so that progress does not restart for each part.
     * @param parent handle the slice belongs to, e.g. obtained with {@link #subProgress(int)}
     * @param steps number of steps of {@code parent} the slice represents
     * @param label displayed before the messages of the action (can be null)
     */
    public static void runSlice(ProgressCallback.SubProgress parent, double steps, String label, Runnable action) {
        Slice slice = new Slice(parent, steps, label, CURRENT.get());
        try {
            run(slice, action);
        } finally {
            slice.finish();
        }
    }

    /**
     * Divides the current sub-task into {@code number} stages of equal weight, each run with {@link Stages#run(String, Runnable)}: progress reported within a stage only advances this stage.
     * Use it when a sub-task runs several steps that can each report progress (e.g. track pre-filters, segmentation, tracking, track post-filters), so that the first step that reports does not fill the whole sub-task.
     * @param number number of stages. When there is a single stage (or no progress callback for the current thread), stages are run directly
     */
    public static Stages stages(int number) {
        return new Stages(number > 1 && CURRENT.get() != null ? subProgress(number) : null);
    }

    public static final class Stages {
        private final ProgressCallback.SubProgress progress;

        private Stages(ProgressCallback.SubProgress progress) {
            this.progress = progress;
        }

        /**
         * Runs a stage on the current thread
         * @param label displayed before the messages of the stage (can be null)
         */
        public void run(String label, Runnable action) {
            if (progress == null) action.run();
            else runSlice(progress, 1, label, action);
        }
    }

    /**
     * @param steps number of steps the current sub-task is divided into
     * @return handle to report progress within the current sub-task. Does nothing if no progress callback is set for the current thread
     */
    public static ProgressCallback.SubProgress subProgress(int steps) {
        ProgressCallback pcb = CURRENT.get();
        return pcb == null ? ProgressCallback.SubProgress.NO_OP : pcb.subProgress(steps);
    }

    /**
     * Progress callback that represents a slice of a {@link ProgressCallback.SubProgress} handle, so that a sub-task can be divided into parts that each run code reporting its own progress.
     * E.g. a tracker that runs a segmenter on each of N tracks within the same parent track: each run is a slice, and the progress the segmenter reports with {@link ProgressContext#subProgress(int)} only advances its own slice.
     * Within the slice, sub-tasks and sub-progress handles behave as in {@link ProgressTracker} (see {@link ProgressTracker.Fraction}), and progress never goes backward.
     * Task-level calls ({@link #incrementProgress()}, {@link #setProgress(int)}, {@link #setTaskNumber(int)}, {@link #incrementTaskNumber(int)}) belong to the top-level task and are ignored.
     * Created by {@link #runSlice(ProgressCallback.SubProgress, double, String, Runnable)}.
     */
    private static class Slice implements ProgressCallback {
        private final SubProgress parent;
        private final double steps;
        private final String label;
        private final ProgressCallback logTarget;
        private final ProgressTracker.Fraction fraction = new ProgressTracker.Fraction();
        private double reported; // fraction of the slice already reported to the parent
        private String subTaskMessage, subProgressMessage, lastMessage;

        /**
         * @param parent handle the slice belongs to
         * @param steps number of steps of the parent handle the slice represents
         * @param label displayed before the messages of the slice (can be null)
         * @param logTarget receives the log messages (can be null)
         */
        Slice(SubProgress parent, double steps, String label, ProgressCallback logTarget) {
            this.parent = parent == null ? SubProgress.NO_OP : parent;
            this.steps = Math.max(0, steps);
            this.label = label;
            this.logTarget = logTarget;
            updateMessage();
        }

        /**
         * Reports the remaining part of the slice to the parent handle
         */
        synchronized void finish() {
            fraction.complete();
            update();
        }

        @Override
        public synchronized void setSubtaskNumber(int number) {
            fraction.setSubtaskNumber(number);
            subTaskMessage = null;
            subProgressMessage = null;
            updateMessage();
        }

        @Override
        public synchronized void incrementSubTask() {
            fraction.incrementSubTask();
            subProgressMessage = null;
            update();
            updateMessage();
        }

        @Override
        public synchronized void setSubTaskMessage(String message) {
            subTaskMessage = message;
            subProgressMessage = null;
            updateMessage();
        }

        @Override
        public void setProgressMessage(String message) {
            setSubTaskMessage(message);
        }

        @Override
        public synchronized SubProgress subProgress(int steps) {
            return fraction.newHandle(steps, this, this::update, message -> {
                subProgressMessage = message;
                updateMessage();
            });
        }

        @Override
        public void log(String message) {
            if (logTarget != null) logTarget.log(message);
        }

        // task level: belongs to the top-level task
        @Override public void incrementTaskNumber(int subtask) {}
        @Override public void setTaskNumber(int number) {}
        @Override public void incrementProgress() {}
        @Override public void setProgress(int i) {}
        @Override public int getTaskNumber() {return 1;}
        @Override public void setRunning(boolean running) {}

        private void update() {
            double value = fraction.getValue();
            if (value > reported) {
                double delta = value - reported;
                reported = value;
                parent.increment(delta * steps);
            }
        }

        private void updateMessage() {
            String message = ProgressTracker.Fraction.join(label, subTaskMessage, subProgressMessage);
            if (!message.equals(lastMessage)) {
                lastMessage = message;
                parent.setMessage(message);
            }
        }
    }
}
