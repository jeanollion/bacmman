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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.plugins.PluginFactory;

/**
 *
 * @author Jean Ollion
 */
public class TaskRunner {
    public static final Logger logger = LoggerFactory.getLogger(TaskRunner.class);
    
    public static void main(String[] args) {
        PluginFactory.findPlugins("bacmman.plugins.plugins");
        
        //List<Task> tasks = extractMeasurementOnFluoXP(true, true, true);
        //List<Task> tasks = runOnuncorrectedFluoXP();
        //List<Task> tasks = getFastTrackTasks();
        List<Task> tasks = getTasks();
        //for (Task t : tasks) t.isValid();
        for (Task t : tasks) if (t.isValid()) t.runTask();
        logger.info("All tasks performed! See errors below:");
        for (Task t : tasks) t.printErrors();
    }
    public static List<Task> getFluoTasks() {
        List<Task> tasks = new ArrayList<Task>() {{
            add(new Task("boa_fluo151127_test").setActions(false, true, true, false).setPositions(0).setStructures(1));
        }};
        return tasks;
    }
    public static List<Task> getFastTrackTasks() {
        List<Task> tasks = new ArrayList<Task>() {{
            //add(new Task("boa_fluo170207_150ms").setActions(false, false, false, true).addExtractMeasurementDir("/data/Images/MutationDynamics/170207", 1).addExtractMeasurementDir("/data/Images/MutationDynamics/170207", 2));
            add(new Task("boa_fluo170207_150ms").setActions(false, true, true, true).setStructures(2).addExtractMeasurementDir("/data/Images/MutationDynamics/170207", 1).addExtractMeasurementDir("/data/Images/MutationDynamics/170207", 2));
            //add(new Task("boa_fluo170117_GammeMutTrackStab").setAllActions().addExtractMeasurementDir("/data/Images/MutationDynamics/170117GammeMutTrack", 1).addExtractMeasurementDir("/data/Images/MutationDynamics/170117GammeMutTrack", 2));
            //add(new Task("boa_fluo170117_GammeMutTrack").setActions(false, false, false, true).addExtractMeasurementDir("/data/Images/MutationDynamics/170117GammeMutTrack", 1).addExtractMeasurementDir("/data/Images/MutationDynamics/170117GammeMutTrack", 2));
        }};
        return tasks;
    }
    
    public static List<Task> getTasks() {
        List<Task> tasks = new ArrayList<Task>() {{
            add(new Task("fluo170515_MutS").setActions(false, true, true, true).setGenerateTrackImages(true).addExtractMeasurementDir(null, 1).addExtractMeasurementDir(null, 2));
            add(new Task("fluo170517_MutH").setActions(false, true, true, true).setGenerateTrackImages(true).addExtractMeasurementDir(null, 1).addExtractMeasurementDir(null, 2));
            add(new Task("fluo160408_MutH").setActions(false, true, true, true).setGenerateTrackImages(true).addExtractMeasurementDir(null, 1).addExtractMeasurementDir(null, 2));
            add(new Task("fluo151127").setActions(false, false, false, true).setGenerateTrackImages(true).addExtractMeasurementDir(null, 1).addExtractMeasurementDir(null, 2));
            add(new Task("fluo160501").setActions(false, false, false, true).setGenerateTrackImages(true).addExtractMeasurementDir(null, 1).addExtractMeasurementDir(null, 2));
            add(new Task("fluo160428").setActions(false, false, false, true).setGenerateTrackImages(true).addExtractMeasurementDir(null, 1).addExtractMeasurementDir(null, 2));
            //add(new Task("boa_phase150324mutH").setActions(false, true, true, true).addExtractMeasurementDir("/data/Images/Phase/150324_6300_mutH/", 1).addExtractMeasurementDir("/data/Images/Phase/150324_6300_mutH/", 0));
            //add(new Task("boa_phase141107wt").setActions(false, true, true, true).addExtractMeasurementDir("/data/Images/Phase/141107_mg6300_wt/", 1).addExtractMeasurementDir("/data/Images/Phase/141107_mg6300_wt/", 0));
            //add(new Task("boa_phase150616wt").setActions(false, true, true, true).addExtractMeasurementDir("/data/Images/Phase/150616_6300_wt/", 1).addExtractMeasurementDir("/data/Images/Phase/150616_6300_wt/", 0));
            //add(new Task("boa_phase150324mutH").setActions(false, true, true, true).setStructures(1).addExtractMeasurementDir("/data/Images/Phase/150324_6300_mutH/", 1).addExtractMeasurementDir("/data/Images/Phase/150324_6300_mutH/", 0));
            //add(new Task("boa_phase141107wt").setActions(false, true, true, true).setStructures(1).addExtractMeasurementDir("/data/Images/Phase/141107_mg6300_wt/", 1).addExtractMeasurementDir("/data/Images/Phase/141107_mg6300_wt/", 0));
            //add(new Task("boa_phase150616wt").setActions(false, true, true, true).setStructures(1).addExtractMeasurementDir("/data/Images/Phase/150616_6300_wt/", 1).addExtractMeasurementDir("/data/Images/Phase/150616_6300_wt/", 0));
        }};
        return tasks;
    }
    
    public static List<Task> extractMeasurementOnFluoXP(boolean runMeas, boolean corr, boolean uncorr) {
        List<Task> tasks = new ArrayList<Task>() {{
            if (corr) {
                add(new Task("fluo151127").setActions(false, false, false, runMeas).setPositions(0, 1, 2, 3).addExtractMeasurementDir("/data/Images/Fluo/fluo151127", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo151127", 2));
                add(new Task("fluo160428").setActions(false, false, false, runMeas).setPositions(0, 1, 23, 2, 3, 4, 5).addExtractMeasurementDir("/data/Images/Fluo/fluo160428", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160428", 2));
                add(new Task("fluo160501").setActions(false, false, false, runMeas).setPositions(0, 1, 3).addExtractMeasurementDir("/data/Images/Fluo/fluo160501", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160501", 2));
            }
            if (uncorr) {
                add(new Task("fluo151127").setActions(false, false, false, runMeas).unsetPositions(0, 1, 2, 3).addExtractMeasurementDir("/data/Images/Fluo/fluo151127/uncorrectedData", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo151127/uncorrectedData", 2));
                add(new Task("fluo160428").setActions(false, false, false, runMeas).unsetPositions(0, 1, 23, 2, 3, 4, 5).addExtractMeasurementDir("/data/Images/Fluo/fluo160428/uncorrectedData", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160428/uncorrectedData", 2));
                add(new Task("fluo160501").setActions(false, false, false, runMeas).unsetPositions(0, 1, 3).addExtractMeasurementDir("/data/Images/Fluo/fluo160501/uncorrectedData", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160501/uncorrectedData", 2));
            }
        }};
        return tasks;
    }
    public static List<Task> runOnuncorrectedFluoXP() {
        List<Task> tasks = new ArrayList<Task>() {{
            //add(new Task("fluo151127").setActions(false, false, false, true).unsetPositions(0, 1, 2, 3).addExtractMeasurementDir("/data/Images/Fluo/fluo151127/uncorrectedData", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo151127/uncorrectedData", 2));
            add(new Task("fluo160428").setAllActions().unsetPositions(0, 1, 23, 2, 3, 4, 5).addExtractMeasurementDir("/data/Images/Fluo/fluo160428/uncorrectedData", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160428/uncorrectedData", 2));
            add(new Task("fluo160428").setActions(false, false, false, true).setPositions(0, 1, 23, 2, 3, 4, 5).addExtractMeasurementDir("/data/Images/Fluo/fluo160428", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160428", 2));
            add(new Task("fluo160501").setAllActions().unsetPositions(0, 1, 3).addExtractMeasurementDir("/data/Images/Fluo/fluo160501/uncorrectedData", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160501/uncorrectedData", 2));
            add(new Task("fluo160501").setActions(false, false, false, true).setPositions(0, 1, 3).addExtractMeasurementDir("/data/Images/Fluo/fluo160501", 1).addExtractMeasurementDir("/data/Images/Fluo/fluo160501", 2));
        }};
        return tasks;
    }
    
}
