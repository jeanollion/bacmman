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
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import bacmman.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class ExperimentSearchUtils {
    private static final Logger logger = LoggerFactory.getLogger(ExperimentSearchUtils.class);

    public static Path getExistingConfigFile(Path dir) {
        Path res = dir.resolve("config.json");
        if (Files.exists(res)) return res;
        // legacy path
        res = dir.resolve(dir.getFileName() + "_config.json");
        if (Files.exists(res)) return res;
        try {
            return Files.list(dir).filter(p -> p.getFileName().toString().endsWith("_config.json")).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static void filter(List<String> list, String prefix) {
        Iterator<String> it = list.iterator();
        while(it.hasNext()) {
            if (!it.next().startsWith(prefix)) it.remove();
        }
    }

    public static String searchForLocalDir(String dbName) {
        String defPath = PropertyUtils.get(PropertyUtils.LOCAL_DATA_PATH);
        Path d = null;
        if (defPath!=null) d = searchLocalDirForDB(Paths.get(defPath), dbName);
        //logger.debug("searching db: {} in path: {}, res: {}", dbName, defPath, d );
        if (d==null) {
            for (String path : PropertyUtils.getStrings(PropertyUtils.LOCAL_DATA_PATH)) {
                if (path.equals(defPath)) continue;
                d = searchLocalDirForDB(Paths.get(path), dbName);
                //logger.debug("searching db: {} in path: {}, res: {}", dbName, path, d );
                if (d!=null) return d.toString();
            }
        } else return d.toString();
        return null;
    }

    public static Path searchLocalDirForDB(Path dir, String dbName) {
        Pair<Path, String> relpathAndName = convertRelPathToFilename(dir, dbName);
        dbName = relpathAndName.value;
        Path configDir = searchConfigDir(dir, dbName, 2);
        if (configDir!=null) return configDir;
        else if (relpathAndName.key == null) return searchConfigDir(dir.getParent(), dbName, 2);
        else return null;
    }

    public static void processDir(Path directory, Consumer<Path> processConfigDir, Consumer<Path> processSubDir) {
        try {
            Files.list(directory).forEach(sub -> {
                if (isConfigDir(sub)) processConfigDir.accept(sub);
                else if (Files.isDirectory(sub)) processSubDir.accept(sub);
            });
        } catch (IOException e) {

        }
    }

    public static String removeLeadingSeparator(String path) {
        if (path==null) return path;
        String sep = FileSystems.getDefault().getSeparator();
        while (path.startsWith(sep)) path = path.substring(sep.length());
        if (path.isEmpty()) return null;
        return path;
    }

    private static Pair<Path, String> splitNameAndRelpath(String relPath) {
        relPath = removeLeadingSeparator(relPath);
        Path p = Paths.get(relPath);
        String fileName = p.getFileName().toString();
        Path path = p.getParent()==null? null: p.getParent();
        return new Pair<>(path, fileName);
    }

    protected static Pair<Path, String> convertRelPathToFilename(Path basePath, String relPath) {
        Pair<Path, String> split = splitNameAndRelpath(relPath);
        if (basePath==null) return split;
        if (split.key==null) {
            split.key = basePath;
            return split;
        }
        split.key = basePath.resolve(split.key);
        return split;
    }

    public static Path searchConfigDir(Path path, String dirName, int recLevels) {
        if (path==null || Files.exists(path)) return null;
        if (Files.isDirectory(path)) return searchConfigDir(new ArrayList<Path>(1){{add(path);}}, dirName, recLevels, 0);
        else return null;
    }

    public static boolean isConfigDir(Path p) {
        try {
            //logger.debug("isConfigDir: {} -> {}", p, Files.list(p).anyMatch(f -> f.getFileName().toString().endsWith("config.json") && Files.isRegularFile(f)));
            return Files.list(p).anyMatch(f -> {
                if (!Files.isRegularFile(f)) return false;
                String fn = f.getFileName().toString();
                return fn.equals("config.json") || fn.endsWith("_config.json");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static Predicate<Path> isConfigDir(String name) {
        return p -> {
            if (!p.getFileName().toString().equals(name)) return false;
            return isConfigDir(p);
        };
    }

    private static Path searchConfigDir(List<Path> pathCandidates, String dirName, int recLevels, int currentLevel) {
        for (Path cand : pathCandidates) {
            try {
                Path dir = Files.list(cand).filter(isConfigDir(dirName)).findFirst().orElse(null);
                if (dir != null) return dir;
            } catch (IOException e) {

            }
        }
        if (currentLevel==recLevels) return null;
        // list next candidates
        pathCandidates = pathCandidates.stream().flatMap(cand -> {
            try {
                return Files.list(cand).filter(Files::isDirectory);
            } catch (IOException e) {
                return Stream.empty();
            }
        }).collect(Collectors.toList());
        return searchConfigDir(pathCandidates, dirName, recLevels, currentLevel+1);
    }

}
