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

import java.util.List;
import java.util.concurrent.CancellationException;
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
    protected Runnable cancel;
    protected int[] taskIdx;
    protected ProgressLogger progressor;
    protected ProgressCallback pcb;
    long tStart;
    public DefaultWorker setProgressCallBack(ProgressCallback pcb) {
        this.pcb = pcb;
        return this;
    }
    public static DefaultWorker executeSingleTask(Runnable task, ProgressLogger gui) {
        return execute(t  -> {
            task.run();
            return null;
        }, 1, gui);
    }
    public static DefaultWorker execute(WorkerTask t, int maxTaskIdx) {
        return execute(t, maxTaskIdx, Core.getProgressLogger());
    }
    public static DefaultWorker execute(WorkerTask t, int maxTaskIdx, ProgressLogger gui) {
        DefaultWorker res = new DefaultWorker(t, maxTaskIdx, gui);
        res.execute();
        return res;
    }
    public static void executeInForeground(WorkerTask t, int maxTaskIdx) {
        for (int i =0; i<maxTaskIdx; ++i) {
            try {
                t.run(i);
            } catch (Exception e) {
                logger.debug("Error @ task:"+i, e);
                return;
            }
        }
    }
    public DefaultWorker(WorkerTask task, int maxTaskIdx, ProgressLogger progressor) {
        this.task=task;
        this.progressor = progressor;
        taskIdx = ArrayUtil.generateIntegerArray(0, maxTaskIdx);
        if (progressor !=null) {
            addPropertyChangeListener(evt -> {
                if ("progress".equals(evt.getPropertyName())) {
                    int progress = (Integer) evt.getNewValue();
                    progressor.setProgress(progress);
                }
            });
        }
    }
    public void setStartTime() {
        tStart = System.currentTimeMillis();
    }
    @Override
    protected Integer doInBackground() throws Exception {
        int count = 0;
        if (progressor !=null) {
            logger.debug("Set running true");
            progressor.setRunning(true);
        }
        if (pcb!=null) pcb.incrementTaskNumber(taskIdx.length);
        for (int i : taskIdx) {
            String message = task.run(i);
            if (message!=null&&!"".equals(message)) publish(message);
            setProgress(100 * (++count) / taskIdx.length);
            if (pcb!=null) pcb.incrementProgress();
        }
        if (progressor !=null) {
            logger.debug("Set running false");
            progressor.setRunning(false);
        }
        return count;
    }
    
    @Override
    protected void process(List<String> strings) {
        if (progressor !=null) {
            for (String s : strings) progressor.setMessage(s);
        }
        if (pcb !=null) {
            for (String s : strings) pcb.log(s);
        }
    }

    @Override 
    public void done() {
        try {
            int count = get();
            long tEnd = System.currentTimeMillis();
            String timeMsg = tStart>0 ? "in "+(tEnd-tStart)+"ms" : "";
            logger.debug("worker task executed: {}/{} {}", count, taskIdx.length, timeMsg);
        } catch (CancellationException e) {
            logger.debug("Cancelled task", e);
        } catch (Exception e) {
            if (progressor !=null) progressor.setMessage("Error while executing task:" + e.toString());
            logger.debug("Error while executing task", e);
            throw new RuntimeException(e);
        } finally {
            if (this.endOfWork!=null) endOfWork.run();
            setProgress(0);
            if (progressor !=null) {
                //progressor.setMessage("End of Jobs");
                progressor.setRunning(false);
            } //else System.out.println("No GUI. End of JOBS");
        }
    }
    public DefaultWorker setCancel(Runnable cancel) {
        this.cancel=cancel;
        return this;
    }

    public void cancelSilently() {
        if (cancel!=null) cancel.run();
        try {
            cancel(true);
        } catch (CancellationException ignored) {}
    }
    public DefaultWorker setEndOfWork(Runnable endOfWork) {
        this.endOfWork=endOfWork;
        return this;
    }
    public DefaultWorker appendEndOfWork(Runnable end) {
        if (end==null) return this;
        else if (this.endOfWork==null) {
            this.endOfWork = end;
            return this;
        } else {
            Runnable oldEnd = this.endOfWork;
            this.endOfWork = () -> {
                oldEnd.run();
                end.run();
            };
            return this;
        }
    }

    public interface WorkerTask {
        String run(int i) throws Exception;
    }

}
