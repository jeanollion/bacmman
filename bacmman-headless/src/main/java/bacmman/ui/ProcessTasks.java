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
package bacmman.ui;

import bacmman.core.Core;
import bacmman.ui.logger.ConsoleProgressLogger;
import bacmman.core.Task;
import bacmman.ui.logger.FileProgressLogger;
import bacmman.ui.logger.MultiProgressLogger;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import java.util.List;
import java.util.function.Function;

import org.json.simple.parser.ParseException;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class ProcessTasks {
    
    public static void main(String[] args) {
        Core.getCore();
        Logger root = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        ConsoleProgressLogger consoleUI = new ConsoleProgressLogger();
        //consoleUI.setMessage("ARGS #"+args.length+" "+Utils.toStringArray(args));
        final ProgressLogger ui;
        if (args.length==0) {
            consoleUI.setMessage("Missing argument: job list file and optionally a log file as second argument");
            return;
        } else if (args.length==1) {
            ui = consoleUI;
        } else if (args.length==2) {
            FileProgressLogger logUI = new FileProgressLogger(true);
            logUI.setLogFile(args[1]);
            consoleUI.setMessage("Setting log file: "+args[1]);
            ui = new MultiProgressLogger(consoleUI, logUI);
        } else {
            consoleUI.setMessage("Too many arguments. Expect only path of job list file and optionally a log file as second argument");
            return;
        }
        ui.setRunning(true);
        //ui.setMessage("BACMMAN version: "+Utils.getVersion(ui));
        Function<String, Task> parser = s-> {
            try {
                Task t = new Task();
                t.setUI(ui);
                t.initFromJSONEntry(JSONUtils.parse(s));
                return t;
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        };
        List<Task> jobs = FileIO.readFromFile(args[0], parser, s->ui.setMessage("Error while parsing task file: "+args[0]));
        ui.setMessage(jobs.size()+" jobs found in file: "+args[0]);
        if (jobs.isEmpty()) return;
        
        int count = 0;
        int subTaskCount = 0;
        for (Task t : jobs) {
            if (t==null) {
                ui.setMessage("Error: job "+count+" could not be parsed");
                return;
            }
            try {
                if (!t.isValid()) {
                    ui.setMessage("Error: job: "+t.toString()+" is not valid" + (t.getDB()==null?"db null": (t.getDB().getExperiment()==null? "xp null":"")));
                    return;
                }
            } catch (Throwable e) {
                t.publishError("Validation", e);
            }
            ++count;
            subTaskCount += t.countSubtasks();
        }
        ui.setMessage(">Will execute: "+jobs.size()+" jobs");
        int[] counter = new int[]{0, subTaskCount};
        for (Task t : jobs) {
            t.setTaskCounter(counter);
            try {
                t.setPreprocessingMemoryThreshold(0.5);
                t.runTask();
            } catch (Throwable e) {
                t.publishError("Task", e);
            } finally {
                t.flush(false);
            }
        }
        int errorCount = 0;
        for (Task t: jobs) errorCount+=t.getErrors().size();
        ui.setMessage("All jobs finished. Errors: "+errorCount);
        for (Task t: jobs) t.publishErrors();
        ui.setRunning(false);
    }

}
