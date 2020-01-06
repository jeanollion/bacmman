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

import bacmman.ui.logger.ProgressLogger;

/**
 *
 * @author Jean Ollion
 */
public interface ProgressCallback {
    public void incrementTaskNumber(int subtask);
    void setSubtaskNumber(int number);
    void incrementSubTask();
    public void incrementProgress();
    public void log(String message);
    public static ProgressCallback get(ProgressLogger ui, int taskNumber) {
        ProgressCallback pcb = get(ui);
        pcb.incrementTaskNumber(taskNumber);
        return pcb;
    }
    public static ProgressCallback get(ProgressLogger ui) {
        ProgressCallback pcb = new ProgressCallback(){
            double progress = 0;
            double taskCount = 0;
            double subTaskNumber = 0;
            double subTaskCount = 0;
            @Override
            public void incrementTaskNumber(int subtask) {
                taskCount+=subtask;
            }

            @Override
            public void setSubtaskNumber(int number) {
                subTaskNumber = number;
                subTaskCount = 0;
            }

            @Override
            public synchronized void incrementSubTask() {
                ++subTaskCount;
                if (taskCount>0) ui.setProgress((int)(100 * ((progress+subTaskCount/subTaskNumber)/taskCount)));
            }

            @Override
            public synchronized void incrementProgress() {
                progress++;
                subTaskCount = 0;
                if (taskCount>0) ui.setProgress((int)(100 * (progress/taskCount)));
            }
            @Override
            public void log(String message) {
                ui.setMessage(message);
            }
        };
        return pcb;
    }
}
