package bacmman.core;

import bacmman.ui.logger.ProgressDisplay;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProgressTrackerTest {

    static class RecordingDisplay implements ProgressDisplay {
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
        RecordingDisplay ui = new RecordingDisplay();
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
        RecordingDisplay ui = new RecordingDisplay();
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
        t.setSubtaskNumber(3); // new sub-tasks divide the remaining work: progress is kept, and previous handle is no longer valid
        sp.increment(1);
        sp.setMessage("stale");
        assertEquals(100, ui.subProgress);
        assertEquals("", ui.subMessage);
    }

    @Test
    public void newSubTaskNumberDividesRemainingWork() {
        RecordingDisplay ui = new RecordingDisplay();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        t.setSubtaskNumber(2);
        t.incrementSubTask();
        assertEquals(50, ui.progress);
        t.setSubtaskNumber(4); // remaining 50% divided in 4
        assertEquals(50, ui.progress);
        t.incrementSubTask();
        assertEquals(62, ui.progress);
    }

    @Test
    public void successiveHandlesNeverGoBackward() { // e.g. a track pre-filter then a segmenter that both report progress in the same sub-task
        RecordingDisplay ui = new RecordingDisplay();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        ProgressCallback.SubProgress sp1 = t.subProgress(2);
        sp1.increment();
        assertEquals(50, ui.progress);
        ProgressCallback.SubProgress sp2 = t.subProgress(2); // divides the remaining 50%
        assertEquals(50, ui.progress);
        sp2.increment();
        assertEquals(75, ui.progress);
        sp1.increment(); // first handle: its value (100% of the sub-task) is above the current one
        assertEquals(100, ui.progress);
        sp2.increment(-5);
        assertEquals(100, ui.progress);
    }

    @Test
    public void addedTasksDoNotMoveOverallProgressBackward() {
        RecordingDisplay ui = new RecordingDisplay();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(2);
        t.incrementProgress();
        assertEquals(50, ui.progress);
        t.incrementTaskNumber(2); // 1/4 = 25% : stays at 50%
        assertEquals(50, ui.progress);
        t.incrementProgress();
        t.incrementProgress();
        assertEquals(75, ui.progress);
    }

    @Test
    public void slices() { // e.g. SegmentAroundAndLink: one slice per reference track within the sub-task of a parent track, each track running a segmenter that reports its progress
        RecordingDisplay ui = new RecordingDisplay();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        t.setSubtaskNumber(2);
        int[] last = new int[]{0};
        ProgressContext.run(t, () -> {
            ProgressCallback.SubProgress sp = ProgressContext.subProgress(4); // 4 frames: tracks of 1 and 3 frames
            ProgressContext.runSlice(sp, 1, "Track 1/2", () -> {
                ProgressCallback.SubProgress seg = ProgressContext.subProgress(1);
                assertEquals("Track 1/2", ui.subMessage);
                seg.setMessage("predicting");
                assertEquals("Track 1/2 · predicting", ui.subMessage);
                seg.increment();
            });
            assertEquals(12, ui.subProgress); // (0 + 1/4) / 2
            last[0] = ui.subProgress;
            ProgressContext.runSlice(sp, 3, "Track 2/2", () -> {
                assertEquals(last[0], ui.subProgress); // does not restart from 0
                ProgressCallback.SubProgress seg = ProgressContext.subProgress(3);
                seg.increment();
                assertEquals(25, ui.progress); // (0 + 2/4) / 2
                ProgressContext.get().incrementProgress(); // task level: ignored in a slice
                ProgressContext.get().setSubtaskNumber(2); // divides the remaining 2 frames
                ProgressContext.get().incrementSubTask();
                assertEquals(37, ui.progress); // (0 + 3/4) / 2
            }); // slice completed when the action ends
            assertEquals(50, ui.subProgress);
        });
        assertNull(ProgressContext.get());
        t.incrementSubTask(); // first sub-task finished
        assertEquals(50, ui.progress);
        t.incrementSubTask();
        assertEquals(100, ui.progress);
    }

    @Test
    public void fractionalSubProgress() { // e.g. an engine reporting a prediction of 2 frames (steps) divided into 7 batches of samples
        RecordingDisplay ui = new RecordingDisplay();
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
        RecordingDisplay ui = new RecordingDisplay();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(2);
        ProgressCallback.SubProgress sp = t.subProgress(10);
        sp.increment(5);
        assertEquals(50, ui.subProgress);
        assertEquals(25, ui.progress);
    }

    @Test
    public void messages() {
        RecordingDisplay ui = new RecordingDisplay();
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
        RecordingDisplay ui = new RecordingDisplay();
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
        RecordingDisplay ui = new RecordingDisplay();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        ProgressContext.run(t, () -> {
            assertSame(t, ProgressContext.get());
            ProgressContext.subProgress(2).increment();
        });
        assertEquals(50, ui.progress);
        assertNull(ProgressContext.get());
    }

    @Test
    public void stages() { // e.g. a pipeline: a track pre-filter then a segmenter configuration that both predict, then segmentation
        RecordingDisplay ui = new RecordingDisplay();
        ProgressTracker t = new ProgressTracker(ui);
        t.setTaskNumber(1);
        ProgressContext.run(t, () -> {
            ProgressContext.Stages stages = ProgressContext.stages(2);
            stages.run("Track pre-filters", () -> {
                ProgressContext.subProgress(2).increment();
                assertEquals(25, ui.progress);
                assertEquals("Track pre-filters", ui.subMessage);
            });
            assertEquals(50, ui.progress); // stage completed
            stages.run("Segmentation", () -> {
                ProgressContext.subProgress(4).increment();
                assertEquals(62, ui.progress);
            });
            assertEquals(100, ui.progress);
        });
        // single stage or no callback: run directly
        int[] runs = new int[1];
        ProgressContext.stages(3).run("x", () -> {
            assertNull(ProgressContext.get());
            ++runs[0];
        });
        ProgressContext.run(t, () -> ProgressContext.stages(1).run("x", () -> {
            assertSame(t, ProgressContext.get());
            ++runs[0];
        }));
        assertEquals(2, runs[0]);
        assertEquals("x", ProgressContext.call(null, () -> "x"));
    }
}
