/*
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.core;

import bacmman.ui.logger.ProgressDisplay;

/**
 * Handle given to code that performs work, to report its progress. Counting is done by {@link ProgressTracker}, which forwards percentages and messages to a {@link ProgressDisplay}.
 * <ul>
 *     <li>Task level: a fixed number of tasks ({@link #incrementTaskNumber(int)}, {@link #incrementProgress()}). Displayed as overall progress.</li>
 *     <li>Sub-task level: the current task is divided into sub-tasks ({@link #setSubtaskNumber(int)}, {@link #incrementSubTask()}). Displayed as the progress of the current task.</li>
 *     <li>Inside a sub-task, a plugin can report finer progress with {@link #subProgress(int)}. Plugins that do not receive a callback get it from {@link ProgressContext#subProgress(int)}.</li>
 * </ul>
 * @author Jean Ollion
 */
public interface ProgressCallback {
    void incrementTaskNumber(int subtask);
    void setTaskNumber(int number);
    void setSubtaskNumber(int number);
    void incrementSubTask();
    void incrementProgress();
    void log(String message);
    void setProgress(int i);
    int getTaskNumber();
    void setRunning(boolean running);

    /**
     * @param message short description of the current task, displayed next to the overall progress
     */
    default void setProgressMessage(String message) {}

    /**
     * @param message short description of the current sub-task, displayed next to the progress of the current task
     */
    default void setSubTaskMessage(String message) {}

    /**
     * Progress inside the current sub-task (or inside the current task if no sub-task number is set).
     * The handle is only valid until the next call to {@link #incrementSubTask()}, {@link #setSubtaskNumber(int)} or {@link #incrementProgress()}; afterwards it has no effect.
     * @param steps number of steps the current sub-task is divided into
     * @return handle to report the steps
     */
    default SubProgress subProgress(int steps) {
        return SubProgress.NO_OP;
    }

    interface SubProgress {
        /**
         * @param steps number of steps performed since last call. Can be fractional, e.g. when a step is divided into batches
         */
        void increment(double steps);
        default void increment(int steps) {
            increment((double)steps);
        }
        default void increment() {
            increment(1);
        }
        /**
         * @param message short description of what is being done, displayed next to the progress of the current task
         */
        void setMessage(String message);

        SubProgress NO_OP = new SubProgress() {
            @Override public void increment(double steps) {}
            @Override public void setMessage(String message) {}
        };
    }

    static ProgressCallback get(ProgressDisplay ui, int taskNumber) {
        ProgressCallback pcb = get(ui);
        pcb.incrementTaskNumber(taskNumber);
        return pcb;
    }

    static ProgressCallback get(ProgressDisplay ui) {
        return new ProgressTracker(ui);
    }
}
