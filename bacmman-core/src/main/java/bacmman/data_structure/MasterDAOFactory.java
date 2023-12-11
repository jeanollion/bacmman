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
package bacmman.data_structure;


import bacmman.configuration.parameters.FileChooser;
import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.dao.*;
import bacmman.data_structure.dao.UUID;
import bacmman.utils.FileIO;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static bacmman.plugins.PluginFactory.getClasses;

/**
 *
 * @author Jean Ollion
 */
public class MasterDAOFactory {
    static final Logger logger = LoggerFactory.getLogger(MasterDAOFactory.class);
    private final static TreeMap<String, Class<PersistentMasterDAO>> NAME_MAP_CLASS = new TreeMap<>();
    private final static Map<Class<PersistentMasterDAO>, String> CLASS_MAP_NAME = new HashMap<>();

    private static String currentType = "DBMap";

    public static Collection<String> getAllTypes() {return NAME_MAP_CLASS.keySet(); }
    public static String getCurrentType() {
        return currentType;
    }

    public static void setCurrentType(String currentType) {
        MasterDAOFactory.currentType = currentType;
    }

    public static MasterDAO getDAO(String dbName, String dir) {
        return getDAO(dbName, dir, currentType);
    }

    public static MasterDAO getDAO(String dbName, String datasetDir, String defaultDAOType) {
        Pair<String, String> correctedPath = Utils.convertRelPathToFilename(datasetDir, dbName);
        dbName = correctedPath.value;
        datasetDir = correctedPath.key;
        String outputPath = extractOutputPath(datasetDir, dbName);
        String daoType = getObjectDAOType(dbName, datasetDir, outputPath, defaultDAOType);
        logger.debug("Extracted output path: {}, detected dao type: {}, default type: {}", outputPath, daoType, defaultDAOType);
        return initDAO(daoType, dbName, datasetDir, new SegmentedObjectAccessor());
    }

    public static MasterDAO initDAO(String moduleName, String dbName, String directory, SegmentedObjectAccessor accessor) {
        if (moduleName == null) {
            return null;
        }
        try {
            MasterDAO res = null;
            if (NAME_MAP_CLASS.containsKey(moduleName)) {
                res = NAME_MAP_CLASS.get(moduleName).getDeclaredConstructor(String.class, String.class, SegmentedObjectAccessor.class).newInstance(dbName, directory, accessor);
            } else {
                if (moduleName.equals("MemoryMasterDAO")) return new MemoryMasterDAO<>(accessor, UUID.generator()).setDatasetDir(Paths.get(directory));
                else if (moduleName.equals("DuplicateMasterDAO")) throw new IllegalArgumentException("Cannot create Duplicate master DAO this way");
                else throw new IllegalArgumentException("Unsupported DAO type: "+moduleName+ " all types: "+NAME_MAP_CLASS.keySet());
            }
            return res;
        } catch (InstantiationException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            logger.debug("get DAO: "+moduleName, ex);
        }
        return null;
    }

    public static void findModules(String packageName) {
        logger.info("looking for plugins in package: {}", packageName);
        try {
            for (Class c : getClasses(packageName)) {
                if (PersistentMasterDAO.class.isAssignableFrom(c) && !Modifier.isAbstract( c.getModifiers()) ) {
                    addPlugin(c.getSimpleName(), c);
                }
            }
        } catch (ClassNotFoundException | IOException ex) {
            logger.warn("find plugins", ex);
        }
        logger.info("DAO found {}", NAME_MAP_CLASS.keySet());
    }
    private static void addPlugin(String alias, Class<PersistentMasterDAO> c) {
        if (alias.isEmpty() || alias.equals(" ")) return;
        if (NAME_MAP_CLASS.containsKey(alias)) {
            Class<PersistentMasterDAO> otherC = NAME_MAP_CLASS.get(c.getSimpleName());
            if (!otherC.equals(c)) {
                logger.warn("Duplicate DAO name: {} & {} (name: {} simpleName: {})", otherC.getName(), c.getName(), alias, c.getSimpleName());
                Core.userLog("Duplicate DAO name: "+otherC.getName()+" & "+c.getName());
            }
        } else {
            if (CLASS_MAP_NAME.containsKey(c)) {
                logger.warn("Duplicate DAO for class: {} -> {} & {}", c, alias, CLASS_MAP_NAME.get(c));
                Core.userLog("Duplicate DAO for class: "+c+" -> "+alias+" & "+ CLASS_MAP_NAME.get(c));
            } else {
                NAME_MAP_CLASS.put(alias, c);
                CLASS_MAP_NAME.put(c, alias);
            }
        }
    }

    protected static String extractOutputPath(String datasetDir, String dbName) {
        Path datasetPath = Paths.get(datasetDir);
        Path configFile = datasetPath.resolve(dbName + "_config.json");
        if (!Files.exists(configFile)) return null;
        String config = FileIO.readFisrtFromFile(configFile.toString(), s->s);
        int i = config.indexOf("outputPath");
        if (i==-1) return null;
        String param = config.substring(config.indexOf(":", i)+1, config.indexOf(",", i));
        Object jsonParam = null;
        try {
            jsonParam = new JSONParser().parse(param);
            FileChooser outputPath = new FileChooser("Output Path").setRefPath(datasetPath);
            outputPath.initFromJSONEntry(jsonParam);
            return outputPath.getFirstSelectedFilePath();
        } catch (ParseException e) {
            return null;
        }
    }

    public static String getObjectDAOType(String dbName, String directory, String outputPath, String defaultType) {
        if (outputPath == null) return defaultType;
        try {
            List<String> types = new ArrayList<>();
            SegmentedObjectAccessor accessor = new SegmentedObjectAccessor();
            Collection<String> dbTypes = getAllTypes();
            for (String type : dbTypes) {
                MasterDAO mDAO = initDAO(type, dbName, directory, accessor);
                if (mDAO instanceof PersistentMasterDAO && ((PersistentMasterDAO)mDAO).containsDatabase(outputPath)) types.add(type);
            }
            logger.debug("Detected DAO types: {}", types);
            if (types.isEmpty()) {
                if (dbTypes.contains("MapDB") && containsMapDBDatabase(outputPath)) throw new RuntimeException("This database is a MapDB database, but MapDB is not installed. From FIJ install bacmman-mapdb update site");
                return defaultType;
            }
            if (types.size()>1) {
                logger.error("Several DAO Types detected: {}", types);
                throw new RuntimeException("Several DAO Types detected");
            }
            return types.get(0);
        } catch (Exception e) {
            logger.debug("Error detecting DAO Type", e);
            return defaultType;
        }
    }

    public static <ID1, ID2>void storeAllObjects(ObjectDAO<ID1> source, ObjectDAO<ID2> destination) {
        int[] ocIdxs= destination.getMasterDAO().getExperiment().experimentStructure.getStructuresInHierarchicalOrderAsArray();
        Map<Object, Object> oldMapNewID = new HashMap<>();
        List<SegmentedObject> root = source.getRoots();
        Map<SegmentedObject, SegmentedObject> sourceMapDupRoot = root.stream().collect(Collectors.toMap(r->r, r -> r.duplicate(destination, null, false, false, false)));
        sourceMapDupRoot.forEach((s, dup) -> oldMapNewID.put(s.getId(), dup.getId()));
        sourceMapDupRoot.forEach((s, dup) -> dup.setLinks(oldMapNewID, s));
        destination.store(sourceMapDupRoot.values());
        Map<Integer, Map<SegmentedObject, SegmentedObject>> ocIdxMapSourceMapDup = new HashMap<>();
        ocIdxMapSourceMapDup.put(-1, sourceMapDupRoot);
        UnaryOperator<SegmentedObject> getDup = o -> ocIdxMapSourceMapDup.get(o.getStructureIdx()).get(o);
        for (int ocIdx : ocIdxs) {
            List<SegmentedObject> objects = SegmentedObjectUtils.getAllChildrenAsStream(root.stream(), ocIdx).collect(Collectors.toList());
            fixTrackHeads(objects); // fix issues that could have happened in early versions of the first database structure
            logger.debug("position: {} coping {} object: from ocIdx={} (first frame {})", destination.getPositionName(), objects.size(), ocIdx, root.isEmpty() ? 0 : root.get(0).getChildren(ocIdx).count());
            Map<SegmentedObject, SegmentedObject> sourceMapDup = objects.stream().collect(Collectors.toMap(o->o, o -> o.duplicate(destination, getDup.apply(o.getParent()), false, false, false)));
            oldMapNewID.clear();
            sourceMapDup.forEach((s, dup) -> oldMapNewID.put(s.getId(), dup.getId()));
            sourceMapDup.forEach((s, dup) -> dup.setLinks(oldMapNewID, s));
            destination.store(sourceMapDup.values());
            ocIdxMapSourceMapDup.put(ocIdx, sourceMapDup);
        }
        source.clearCache();
        destination.compactDBs(true);
        destination.clearCache();
    }

    protected static void fixTrackHeads(List<SegmentedObject> objects) {
        Collections.sort(objects);
        objects.forEach( o -> {
            if (o.getTrackHead() == null || !o.getTrackHead().isTrackHead()) {
                if (o.getPrevious()==null || o.getPrevious().getNext() == null) o.setTrackHead(o, false, true, null);
                else o.setTrackHead(o.getPrevious().getTrackHead(), false, true, null);
            }
        });
    }

    public static boolean isType(MasterDAO db, String targetType) {
        Class<PersistentMasterDAO> targetClass = NAME_MAP_CLASS.get(targetType);
        if (targetClass == null) throw new RuntimeException("Target DAO class not present: "+targetClass);
        return db.getClass().equals(targetClass);
    }

    public static String getType(MasterDAO db) {
        return CLASS_MAP_NAME.get(db.getClass());
    }

    public static MasterDAO ensureDAOType(MasterDAO db, String targetType, ProgressCallback pcb) {
        if (isType(db, targetType)) return db;
        db.unlockConfiguration();
        db.setConfigurationReadOnly(true);
        db.clearCache();
        db.unlockPositions();
        MasterDAO targetDB = initDAO(targetType, db.getDBName(), db.getDatasetDir().toString(), new SegmentedObjectAccessor());
        boolean lockedConfig = targetDB.setConfigurationReadOnly(false);
        boolean lockedPositions = targetDB.lockPositions();
        logger.debug("target db locked config: {}, sel dao: {}, positions: {}", lockedConfig, !targetDB.getSelectionDAO().isReadOnly(), lockedPositions);
        if (!lockedConfig || !lockedPositions || targetDB.getSelectionDAO().isReadOnly()) {
            Core.userLog("Error converting DB: could not lock");
            targetDB.unlockPositions();
            targetDB.unlockConfiguration();
            return null;
        }
        pcb.incrementTaskNumber(targetDB.getExperiment().getPositionsAsString().length + 1 );
        for (String position : targetDB.getExperiment().getPositionsAsString()) {
            if (!db.getDao(position).isEmpty()) storeAllObjects(db.getDao(position), targetDB.getDao(position));
            pcb.incrementProgress();
        }
        for (Selection s : db.getSelectionDAO().getSelections()) {
            logger.debug("storing selection: {}, size: {}", s.getName(), s.getAllElementStrings().size());
            targetDB.getSelectionDAO().store(s);
        }
        targetDB.unlockPositions();
        targetDB.unlockConfiguration();
        db.setConfigurationReadOnly(false);
        boolean lock = db.lockPositions();
        for (String position : db.getExperiment().getPositionsAsString()) {
            db.getDao(position).erase();
        }
        logger.debug("erasing selections");
        db.getSelectionDAO().erase();
        pcb.incrementProgress();
        db.unlockPositions();
        db.unlockConfiguration();
        return targetDB;
    }

    public static boolean containsMapDBDatabase(String outputPath) {
        try {
            List<Path> positions = Files.list(Paths.get(outputPath)).filter(p -> !p.getFileName().toString().equals("Selections")).collect(Collectors.toList());
            for (Path pos : positions) {
                Path segmentedObjects = pos.resolve("segmented_objects");
                if (Files.exists(segmentedObjects) && Files.list(segmentedObjects).map(p -> p.getFileName().toString()).anyMatch(n -> n.startsWith("objects_") && n.endsWith(".db"))) return true;
            }
        } catch (Exception e) { }
        return false;
    }
}
