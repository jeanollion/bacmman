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
     * @param steps number of steps the current sub-task is divided into
     * @return handle to report progress within the current sub-task. Does nothing if no progress callback is set for the current thread
     */
    public static ProgressCallback.SubProgress subProgress(int steps) {
        ProgressCallback pcb = CURRENT.get();
        return pcb == null ? ProgressCallback.SubProgress.NO_OP : pcb.subProgress(steps);
    }
}
