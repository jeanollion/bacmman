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
package bacmman.ui.logger;

/**
 * Output side of progress reporting: displays messages and progress values (GUI, console, file...).
 * Code that performs work should not call this directly but use a {@link bacmman.core.ProgressCallback}, which does the counting.
 * Progress has two levels: the overall progress of all tasks, and the progress of the current task (sub-progress).
 * @author Jean Ollion
 */
public interface ProgressLogger {
    /**
     * @param i overall progress in percent
     */
    void setProgress(int i);
    void setMessage(String message);
    void setRunning(boolean running);
    boolean isGUI();

    /**
     * @param text short description of the overall progress (e.g. current step), displayed next to the overall progress
     */
    default void setProgressMessage(String text) {}

    /**
     * @param i progress of the current task, in percent
     */
    default void setSubProgress(int i) {}

    /**
     * @param text short description of the current task progress, displayed next to the sub-progress
     */
    default void setSubMessage(String text) {}
}
