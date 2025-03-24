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
    void incrementTaskNumber(int subtask);
    void setTaskNumber(int number);
    void setSubtaskNumber(int number);
    void incrementSubTask();
    void incrementProgress();
    void log(String message);
    void setProgress(int i);
    int getTaskNumber();
    void setRunning(boolean running);

    static ProgressCallback get(ProgressLogger ui, int taskNumber) {
        ProgressCallback pcb = get(ui);
        pcb.incrementTaskNumber(taskNumber);
        return pcb;
    }
    static ProgressCallback get(ProgressLogger ui) {
        ProgressCallback pcb = new ProgressCallback(){
            double taskCounter = 0;
            double taskNumber = 0;
            double subTaskNumber = 0;
            double subTaskCounter = 0;

            @Override
            public void setRunning(boolean running) {
                ui.setRunning(running);
            }

            @Override
            public void incrementTaskNumber(int subtask) {
                taskNumber +=subtask;
            }

            @Override
            public int getTaskNumber() {return (int) taskNumber;}

            @Override
            public void setSubtaskNumber(int number) {
                subTaskNumber = number;
                subTaskCounter = 0;
            }

            @Override
            public void setTaskNumber(int number) {
                taskNumber = number;
                taskCounter = 0;
                subTaskCounter = 0;
                subTaskNumber = 0;
            }

            @Override
            public synchronized void incrementSubTask() {
                ++subTaskCounter;
                if (taskNumber >0) ui.setProgress((int)(100 * ((taskCounter + subTaskCounter /subTaskNumber)/ taskNumber)));
            }

            @Override
            public synchronized void incrementProgress() {
                taskCounter++;
                subTaskCounter = 0;
                if (taskNumber >0) ui.setProgress((int)(100 * (taskCounter / taskNumber)));
            }
            @Override
            public synchronized void setProgress(int i) {
                if (taskCounter != i) {
                    taskCounter = i;
                    subTaskCounter = 0;
                    if (taskNumber >0) ui.setProgress((int)(100 * (taskCounter / taskNumber)));
                }
            }
            @Override
            public void log(String message) {
                ui.setMessage(message);
            }
        };
        return pcb;
    }
}
