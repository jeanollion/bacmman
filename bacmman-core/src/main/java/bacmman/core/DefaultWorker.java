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

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.List;
import javax.swing.SwingWorker;
import bacmman.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class DefaultWorker extends SwingWorker<Integer, String>{
    private static final Logger logger = LoggerFactory.getLogger(DefaultWorker.class);
    protected final WorkerTask task;
    protected Runnable endOfWork;
    protected int[] taskIdx;
    protected ProgressLogger gui;
    public static DefaultWorker execute(WorkerTask t, int maxTaskIdx) {
        return execute(t, maxTaskIdx, Core.getProgressLogger());
    }
    public static DefaultWorker execute(WorkerTask t, int maxTaskIdx, ProgressLogger gui) {
        DefaultWorker res = new DefaultWorker(t, maxTaskIdx, gui);
        res.execute();
        return res;
    }
    public static void executeInForeground(WorkerTask t, int maxTaskIdx) {
        for (int i =0; i<maxTaskIdx; ++i) t.run(i);
    }
    public DefaultWorker(WorkerTask task, int maxTaskIdx, ProgressLogger gui) {
        this.task=task;
        this.gui=gui;
        taskIdx = ArrayUtil.generateIntegerArray(0, maxTaskIdx);
        if (gui!=null) {
            addPropertyChangeListener(evt -> {
                if ("progress".equals(evt.getPropertyName())) {
                    int progress = (Integer) evt.getNewValue();
                    gui.setProgress(progress);
                }
            });
        }
    }
    @Override
    protected Integer doInBackground() throws Exception {

        int count = 0;
        if (gui!=null) {
            logger.debug("Set running true");
            gui.setRunning(true);
        }
        for (int i : taskIdx) {
            String message = task.run(i);
            if (message!=null&&!"".equals(message)) publish(message);
            setProgress(100 * (++count) / taskIdx.length);
        }
        if (gui!=null) {
            logger.debug("Set running false");
            gui.setRunning(false);
        }
        return count;
    }
    
    @Override
    protected void process(List<String> strings) {
        if (gui!=null) {
            for (String s : strings) gui.setMessage(s);
        } 
    }

    @Override 
    public void done() {
        try {
            int count = get();
            logger.debug("worker task executed: {}/{}", count,  taskIdx.length);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (this.endOfWork!=null) endOfWork.run();
        setProgress(0);
        if (gui!=null) {
            gui.setMessage("End of Jobs");
            gui.setRunning(false);
        } //else System.out.println("No GUI. End of JOBS");
    }
    public DefaultWorker setEndOfWork(Runnable endOfWork) {
        this.endOfWork=endOfWork;
        return this;
    }
    public DefaultWorker appendEndOfWork(Runnable end) {
        Runnable oldEnd = this.endOfWork;
        this.endOfWork = () -> {
            if (oldEnd!=null) oldEnd.run();
            if (end!=null) end.run();
        };
        return this;
    }
    public static interface WorkerTask {
        public String run(int i);
    }
}
