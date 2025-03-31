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

import bacmman.ui.logger.ConsoleProgressLogger;
import bacmman.core.Daemon;
import bacmman.core.Task;
import ij.plugin.PlugIn;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import bacmman.plugins.PluginFactory;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class Console implements PlugIn {
    static final String WATCH_DIR_KEY = "watch_dir";
    Daemon d;
    ConsoleProgressLogger ui = new ConsoleProgressLogger();
    public static void main(String[] args) {
        PluginFactory.findPlugins("bacmman.plugins.plugins");
        Logger root = (Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        /*String arg = "";
        Iterator<String> it = Arrays.asList(args).iterator();
        while(it.hasNext()) {
            String n = it.next();
            arg+=n+(it.hasNext()?";":"");
        }*/
        new Console().run(args.length==0 ? null : args[0]);
    }
    
    @Override
    public void run(String args) {
        ui.setMessage("BOA Shell version: "+Utils.getVersion(this));
        while(!promptCommand()){};
        //if (args==null || args.length()==0) return;
        /*Collection<Task> jobs;
        Function<String, Task> parser = s->new Task().setUI(ui).fromJSON(JSONUtils.parse(s));
        if (args.endsWith(".txt")|args.endsWith(".json")) { // open text file
            jobs = FileIO.readFromFile(args, parser); // TODO -> read JSON File en utilisant content handler de JSON simple
        } else { // directly parse command
            String[] tasksS = args.split("\n");
            String[] tasksS2 = args.split(";");
            if (tasksS2.length>tasksS.length) tasksS=tasksS2;
            jobs = new HashSet<>(tasksS.length);
            for (String s : tasksS) jobs.add(parser.apply(s));
        }
        runJobs(jobs);*/
    }
    public void runJobs(Collection<Task> jobs) {
        if (jobs==null || jobs.isEmpty()) return;
        int count = 0;
        for (Task t : jobs) {
            if (t==null) {
                ui.setMessage("Error: job "+count+" could not be parsed");
                return;
            }
            if (!t.isValid()) {
                ui.setMessage("Error: job: "+t.toString()+" is not valid" + (t.getDB()==null?"db null": (t.getDB().getExperiment()==null? "xp null":"")));
                return;
            }
            ++count;
        }
        System.out.println(">Will execute: "+jobs.size()+" jobs");
        for (Task t : jobs) {
            ui.setMessage("Running: "+t.toString());
            t.setPreprocessingMemoryThreshold(0.5);
            t.runTask();
            t.flush(false);
        }
        int errorCount = 0;
        for (Task t: jobs) errorCount+=t.getErrors().size();
        ui.setMessage("All jobs finished. Errors: "+errorCount);
        for (Task t: jobs) t.printErrors();
    }
    
    private boolean promptCommand() {
        ui.setMessage("Current Watch Dir: "+PropertyUtils.get(WATCH_DIR_KEY, "NONE"));
        String c = prompt("Type \"R\" to run daemon \"S\" to set watch directory \"q\" to exit shell:");
        if (c.equals("S")) {
           String dir = this.prompt("Type watch directory");
           if (dir!=null && new File(dir).isDirectory()) {
               ui.setMessage("Setting watch directory to: "+dir);
               PropertyUtils.set(WATCH_DIR_KEY, dir);
           }
           return false;
        } else if (c.equals("R")) {
            runDaemon();
            return false;
        } else if (c.equals("q")) {
            if (d!=null) {
                d.stopAfterNextJob();
            }
            return true;
        } else {
            return false;
        }
    }
    private void runDaemon() {
        String watchDir = PropertyUtils.get(WATCH_DIR_KEY);
        if (watchDir==null || !new File(watchDir).isDirectory()) {
            ui.setMessage("No watch Dir found "+watchDir);
            return;
        } else {
            ui.setMessage("Watch Directory: "+watchDir);
        }
        d = new Daemon(ui);
        d.watchDirectory(watchDir);
    }
    
    private String prompt(String promptInstruction) {
        if (promptInstruction!=null && promptInstruction.length()>0) ui.setMessage(promptInstruction);
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        try {
           return(br.readLine());
        } catch (IOException ioe) {
           ui.setMessage("IO error trying to read command!");
        }
        return "";
    }
    
    private boolean promptBool(String instruction, boolean def) {
        String p = prompt(instruction+(def?" [Y/n]:":" [y/N]:"));
        if ("Y".equals(p)||"y".equals(p)) return true;
        else if ("N".equals(p)||"n".equals(p)) return true;
        else return def;
    }

}
