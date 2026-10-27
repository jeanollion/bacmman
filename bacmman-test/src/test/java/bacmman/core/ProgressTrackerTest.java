package bacmman.core;

import bacmman.ui.logger.ProgressLogger;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProgressTrackerTest {

    static class RecordingLogger implements ProgressLogger {
        int progress = -1, subProgress = -1;
        String progressMessage, subMessage;
        int progressCalls;
        @Override public void setProgress(int i) {progress = i; ++progressCalls;}
        @Override public void setMessage(String message) {}
        @Override public void setRunning(boolean running) {}
        @Override public boolean isGUI() {return false;}
        @Override public void setProgressMessage(String text) {progressMessage = text;}
        @Override public void setSubProgress(int i) {subProgress = i;}
        @Override public void setSubMessage(String text) {subMessage = text;}
    }

    @Test
    public void tasksAndSubTasks() {
        RecordingLogger ui = new RecordingLogger();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(4);
        assertEquals(0, ui.progress);
        t.setSubtaskNumber(2);
        t.incrementSubTask();
        assertEquals(50, ui.subProgress);
        assertEquals(12, ui.progress); // (0 + 0.5) / 4
        t.incrementProgress();
        assertEquals(25, ui.progress);
        assertEquals(0, ui.subProgress);
        t.setProgress(4);
        assertEquals(100, ui.progress);
        t.incrementProgress(); // more increments than expected tasks: stays at 100
        assertEquals(100, ui.progress);
    }

    @Test
    public void subProgressWithinSubTask() {
        RecordingLogger ui = new RecordingLogger();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        t.setSubtaskNumber(2);
        t.incrementSubTask();
        ProgressCallback.SubProgress sp = t.subProgress(4);
        sp.increment(2);
        assertEquals(75, ui.subProgress); // (1 + 2/4) / 2
        assertEquals(75, ui.progress);
        sp.increment(10); // over-counting is capped to the current sub-task
        assertEquals(100, ui.subProgress);
        t.setSubtaskNumber(3); // new sub-tasks: previous handle is no longer valid
        sp.increment(1);
        sp.setMessage("stale");
        assertEquals(0, ui.subProgress);
        assertEquals("", ui.subMessage);
    }

    @Test
    public void fractionalSubProgress() { // e.g. an engine reporting a prediction of 2 frames (steps) divided into 7 batches of samples
        RecordingLogger ui = new RecordingLogger();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        ProgressCallback.SubProgress sp = t.subProgress(4);
        sp.increment(2); // 2 frames predicted in a single call
        assertEquals(50, ui.subProgress);
        for (int b = 0; b < 7; ++b) sp.increment(2 * 1. / 7);
        assertEquals(100, ui.subProgress);
    }

    @Test
    public void subProgressWithoutSubTasks() {
        RecordingLogger ui = new RecordingLogger();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(2);
        ProgressCallback.SubProgress sp = t.subProgress(10);
        sp.increment(5);
        assertEquals(50, ui.subProgress);
        assertEquals(25, ui.progress);
    }

    @Test
    public void messages() {
        RecordingLogger ui = new RecordingLogger();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(3);
        t.setProgressMessage("Pre-Processing");
        assertEquals("Task 1/3 · Pre-Processing", ui.progressMessage);
        t.setSubtaskNumber(2);
        t.setSubTaskMessage("Parent track 1/2");
        t.subProgress(1).setMessage("predicting");
        assertEquals("Parent track 1/2 · predicting", ui.subMessage);
        t.incrementSubTask(); // clears the message of the finished sub-task
        assertEquals("Parent track 1/2", ui.subMessage);
        t.setProgress(3);
        assertEquals("Task 3/3 · Pre-Processing", ui.progressMessage);
    }

    @Test
    public void updatesOnlyWhenPercentageChanges() {
        RecordingLogger ui = new RecordingLogger();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        int calls = ui.progressCalls;
        ProgressCallback.SubProgress sp = t.subProgress(1000);
        for (int i = 0; i<5; ++i) sp.increment(); // 0.5%: same integer percentage
        assertEquals(calls, ui.progressCalls);
    }

    @Test
    public void context() {
        assertNull(ProgressContext.get());
        assertSame(ProgressCallback.SubProgress.NO_OP, ProgressContext.subProgress(10));
        RecordingLogger ui = new RecordingLogger();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        ProgressContext.run(t, () -> {
            assertSame(t, ProgressContext.get());
            ProgressContext.subProgress(2).increment();
        });
        assertEquals(50, ui.progress);
        assertNull(ProgressContext.get());
    }
}
