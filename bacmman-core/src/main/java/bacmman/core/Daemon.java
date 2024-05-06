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

import bacmman.ui.logger.FileProgressLogger;
import bacmman.ui.logger.ProgressLogger;
import com.google.common.io.Files;
import bacmman.core.DefaultWorker.WorkerTask;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import org.json.simple.JSONObject;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import org.json.simple.parser.ParseException;

/**
 *
 * @author Jean Ollion
 */
public class Daemon {
    ProgressLogger ui;
    FileProgressLogger logUI;
    String watchDir;
    File parsedJobDir, errorDir, logDir;
    boolean watching, running;
    long idleTime = 10000;
    DefaultWorker w;
    final Queue<Task> jobQueue = new LinkedList<>();
    final Map<Task, File> taskFileNameMap = new HashMap<>();
    final Map<File, Boolean> fileNameErrorMap = new HashMap<>();
    final Set<File> oneJobHasBeenRun = new HashSet<>();
    public Daemon(ProgressLogger ui) {
        this.ui=ui;
    }

    public void terminateWatch() {
        watching = false;
        ui.setMessage("Stop Watching");
    }
    public void stopAfterNextJob() {
        running = false;
        watching = false;
        ui.setMessage("Stop Running");
    }
    public void terminateNow() {
        if (w!=null) w.cancelSilently();
    }
    public void watchDirectory(String dir) {
        File wD = new File(dir);
        if (!wD.isDirectory()) {
            ui.setMessage("Cannot set watch directory: "+dir);
            return;
        }
        File pd = Paths.get(dir, "ParsedJobs").toFile();
        File ed = Paths.get(dir, "Errors").toFile();
        File ld = Paths.get(dir, "Logs").toFile();
        pd.mkdirs();
        ed.mkdirs();
        ld.mkdirs();
        if (!pd.isDirectory() || !ed.isDirectory() || !ld.isDirectory()) {
            ui.setMessage("Cannot create sub directories within directory: "+dir);
            return;
        }
        watchDir=dir;
        parsedJobDir=pd;
        errorDir=ed;
        logDir=ld;
        watching = true;
        ui.setMessage("Setting watch directory: "+dir);
        WorkerTask wt = new WorkerTask() {
            @Override
            public String run(int i) {
                while(running) {
                    Task t = jobQueue.poll();
                    if (t!=null) {
                        logUI.setAppend(!oneJobHasBeenRun.contains(taskFileNameMap.get(t)));
                        logUI.setLogFile(Paths.get(logDir.getAbsolutePath(), taskFileNameMap.get(t).getName().replace(".json", ".txt")).toString());
                        t.publishMemoryUsage("");
                        ui.setMessage("Running Job: "+t+" remaining jobs: "+jobQueue.size());
                        t.runTask(0.5);
                        t.publishErrors();
                        if (!t.errors.isEmpty()) fileNameErrorMap.put(taskFileNameMap.get(t), true);
                        logUI.setLogFile(null);
                        oneJobHasBeenRun.add(taskFileNameMap.get(t));
                        taskFileNameMap.remove(t);
                        moveFiles();
                    }
                    else if (watching) {
                        addFiles(watchDir);
                        while(watching && jobQueue.isEmpty()) {
                            try {
                                Thread.sleep(idleTime);
                            } catch (InterruptedException ex) {}
                            addFiles(watchDir);
                        }
                    } else running=false;
                }
                return "";
            }
        };
        running = true;
        w = DefaultWorker.execute(wt, 1, null);
    }
    public void addFiles(String dir) {
        File[] subF = new File(dir).listFiles(f->f.isFile() && f.getName().endsWith(".json"));
        if (subF==null || subF.length==0) {
            //ui.setMessage("no job found in directory: "+dir);
            return;
        }
        for (File f : subF) {
            List<String> jobs = FileIO.readFromFile(f.getAbsolutePath(), s->s, s->ui.setMessage("Error while reading file: "+f));
            boolean error= false;
            for (String s : jobs) {
                JSONObject o = null;
                try {
                    o = JSONUtils.parse(s);
                    Task t = new Task().fromJSON(o).setUI(ui);
                    if (t.isValid()) {
                        jobQueue.add(t);
                        taskFileNameMap.put(t, f);
                    } else {
                        error=true;
                        ui.setMessage("Invalid task: "+f);
                        t.printErrorsTo(ui);
                    }
                } catch (ParseException e) {
                    ui.setMessage("Error: could not parse task: "+ e.toString());
                    error = true;
                }
                fileNameErrorMap.put(f, error);
            }
        }
        ui.setMessage(jobQueue.size()+ " new jobs found in directory: "+dir);
    }
    private void moveFiles() {
        Iterator<Entry<File, Boolean>> it = fileNameErrorMap.entrySet().iterator();
        while(it.hasNext()) {
            Entry<File, Boolean> e = it.next();
            if (!taskFileNameMap.containsValue(e.getKey())) { // all jobs are done
                try { // move file to subfolder so that it is not scanned again
                    Files.move(e.getKey(), Paths.get((e.getValue()?errorDir.getAbsolutePath():parsedJobDir.getAbsolutePath()),e.getKey().getName()).toFile());
                } catch (IOException ex) {
                    ui.setMessage("Cannot move file: "+e.getKey().getName() + " "+ ex.getMessage());
                }
            }
        }
        
    }
}
