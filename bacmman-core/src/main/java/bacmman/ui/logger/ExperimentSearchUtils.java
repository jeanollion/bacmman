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

import bacmman.ui.PropertyUtils;
import bacmman.core.ProgressCallback;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import bacmman.utils.Pair;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class ExperimentSearchUtils {
    
    
    
    public static void filter(List<String> list, String prefix) {
        Iterator<String> it = list.iterator();
        while(it.hasNext()) {
            if (!it.next().startsWith(prefix)) it.remove();
        }
    }
    public static String removePrefix(String name, String prefix) {
        if (prefix == null || prefix.isEmpty()) return name;
        while (name.startsWith(prefix)) name= name.substring(prefix.length());
        return name;
    }
    public static String addPrefix(String name, String prefix) {
        if (name==null) return null;
        if (!name.startsWith(prefix)) name= prefix+name;
        return name;
    }
    public static String searchForLocalDir(String dbName) {
        String defPath = PropertyUtils.get(PropertyUtils.LOCAL_DATA_PATH);
        String d = null;
        if (defPath!=null) d = searchLocalDirForDB(dbName, defPath);
        //logger.debug("searching db: {} in path: {}, res: {}", dbName, defPath, d );
        if (d==null) {
            for (String path : PropertyUtils.getStrings(PropertyUtils.LOCAL_DATA_PATH)) {
                if (path.equals(defPath)) continue;
                d = searchLocalDirForDB(dbName, path);
                //logger.debug("searching db: {} in path: {}, res: {}", dbName, path, d );
                if (d!=null) return d;
            }
        }
        return d;
    }
    public static String searchLocalDirForDB(String dbName, String dir) {
        File config = Utils.search(dir, dbName+"_config.json", 2);
        if (config!=null) return config.getParent();
        else {
            config = Utils.search(new File(dir).getParent(), dbName+"_config.json", 2);
            if (config!=null) return config.getParent();
            else return null;
        }
    }
    public static boolean dbRelPathMatch(String dbRelPathToTest, String refDir, String refDbName) {
        String refPath = Paths.get(refDir).resolve(refDbName).toString();
        return refPath.endsWith(dbRelPathToTest);
    }
    public static Map<String, File> listExperiments(String path, boolean excludeDuplicated, ProgressCallback pcb) {
        File f = new File(path);
        Map<String, File> configs = new HashMap<>();
        Set<Pair<String, File>> dup = new HashSet<>();
        if (f.isDirectory()) { // only in directories included in path
            File[] sub = f.listFiles(File::isDirectory);
            if (sub != null) for (File subF : sub) addConfig(subF, configs, dup);
        } 
        if (!dup.isEmpty()) {
            for (Pair<String, File> p : dup) {
                if (excludeDuplicated) configs.remove(p.key);
                if (pcb!=null) pcb.log("Duplicated Experiment: "+p.key +"@:"+p.value+ (excludeDuplicated?" will not be listed":" only one will be listed"));
            }
        }
        return configs;
    }
    public static boolean addConfig(File f, Map<String, File> configs, Set<Pair<String, File>> duplicated) {
        File[] dbs = f.listFiles(subF -> subF.isFile() && subF.getName().endsWith("_config.json"));
        if (dbs==null || dbs.length==0) return false;
        for (File c : dbs) addConfigFile(c, configs, duplicated);
        return true;
    }
    private static void addConfigFile(File c, Map<String, File> configs, Set<Pair<String, File>> duplicated) {
        String dbName = removeConfig(c.getName());
        if (configs.containsKey(dbName)) {
            duplicated.add(new Pair<>(dbName, c.getParentFile()));
            duplicated.add(new Pair<>(dbName, configs.get(dbName)));
        } else configs.put(dbName, c.getParentFile());
    }

    private static String removeConfig(String name) {
        return name.substring(0, name.indexOf("_config.json"));
    }
    static long minMem = 2000000000;
    public static void checkMemoryAndFlushIfNecessary(String... exceptPositions) {
        long freeMem= Runtime.getRuntime().freeMemory();
        long usedMem = Runtime.getRuntime().totalMemory();
        long totalMem = freeMem + usedMem;
        if (freeMem<minMem || usedMem>2*minMem) {
            
        }
    }

}
