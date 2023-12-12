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
package bacmman.data_structure.dao;

import bacmman.data_structure.Selection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class MapDBSelectionDAO implements SelectionDAO {
    static final Logger logger = LoggerFactory.getLogger(MapDBSelectionDAO.class);
    final Path dir;
    final MasterDAO<?, ?> mDAO;
    DB db;
    HTreeMap<String, String> dbMap;
    private final Map<String, Selection> idCache = new HashMap<>();
    private final boolean readOnly;
    public MapDBSelectionDAO(MasterDAO<?, ?> mDAO, String dir, boolean readOnly) {
        this.mDAO=mDAO;
        this.dir = Paths.get(dir, "Selections");
        this.dir.toFile().mkdirs();
        this.readOnly=readOnly;
        makeDB();
    }
    private synchronized void makeDB() {
        String selectionFile = getSelectionFile();
        if (readOnly && !new File(selectionFile).exists()) return;
        //logger.debug("making db @ {} (parent exists: {}), read only: {}", getSelectionFile(), new File(getSelectionFile()).getParentFile().exists(), readOnly);
        db = MapDBUtils.createFileDB(getSelectionFile(), readOnly, false);
        dbMap = MapDBUtils.createHTreeMap(db, "selections");
    }
    
    private String getSelectionFile() {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return dir.resolve("selections.db").toString();
    }
    @Override
    public synchronized Selection getOrCreate(String name, boolean clearIfExisting) {
        if (idCache.isEmpty()) retrieveAllSelections();
        Selection res = idCache.get(name);
        if (res==null) {
            res = new Selection(name, mDAO);
            idCache.put(name, res);
        } else if (clearIfExisting) {
            res.clear();
            // TODO: commit ?
        }
        return res;
    }
    
    private void retrieveAllSelections() {
        idCache.clear();
        if (db==null || db.isClosed()) makeDB();
        for (String s : MapDBUtils.getValues(dbMap)) {
            Selection sel = JSONUtils.parse(Selection.class, s);
            sel.setMasterDAO(mDAO);
            idCache.put(sel.getName(), sel);
        }
        // local files
        File dirFile = dir.toFile();
        File[] files = dirFile.listFiles((f, n)-> n.endsWith(".txt")||n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                List<Selection> sels = FileIO.readFromFile(f.getAbsolutePath(), s -> JSONUtils.parse(Selection.class, s), s -> logger.error("Error while converting json file content: {} -> content :{}", f, s));
                for (Selection s : sels) {
                    s.setMasterDAO(mDAO);
                    if (idCache.containsKey(s.getName())) {
                        logger.info("Selection: {} found in file: {} will be overwritten in local database", s.getName(), f.getAbsolutePath());
                        // copy metadata
                        Selection source = idCache.get(s.getName());
                        s.setHighlightingTracks(source.isHighlightingTracks());
                        s.setColor(source.getColor());
                        s.setIsDisplayingObjects(source.isDisplayingObjects());
                        s.setIsDisplayingTracks(source.isHighlightingTracks());
                    }
                    idCache.put(s.getName(), s);
                    store(s);
                    if (!readOnly) f.delete();
                }
            }
        }
    }
    @Override
    public synchronized List<Selection> getSelections() {
        retrieveAllSelections();
        List<Selection> res = new ArrayList<>(idCache.values());
        Collections.sort(res);
        return res;
    }

    @Override
    public synchronized void store(Selection s) {
        idCache.put(s.getName(), s);
        s.setMasterDAO(this.mDAO);
        if (readOnly) {
            logger.warn("Cannot store selection: {} for dataset: {} in read only mode. ", s, dir);
            return;
        }
        if (db==null || db.isClosed()) makeDB();
        this.dbMap.put(s.getName(), JSONUtils.serialize(s));
        db.commit();
    }

    @Override
    public synchronized void delete(String id) {
        idCache.remove(id);
        if (readOnly) return;
        if (db==null || db.isClosed()) makeDB();
        dbMap.remove(id);
        db.commit();
    }

    @Override
    public synchronized void delete(Selection o) {
        delete(o.getName());
    }

    @Override
    public synchronized void deleteAllObjects() {
        idCache.clear();
        if (db!=null) {
            db.close();
            db = null;
            dbMap = null;
        }
        if (readOnly) return;
        MapDBUtils.deleteDBFile(getSelectionFile());
    }

    @Override
    public void erase() {
        deleteAllObjects();
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    public synchronized void compact(boolean commit) {
        if (readOnly) return;
        if (db==null || db.isClosed()) makeDB();
        if (commit) this.db.commit();
        //this.db.compact();
    }
    private synchronized void close(boolean commit) {
        if (db==null || db.isClosed()) return;
        if (commit && !readOnly) this.db.commit();
        this.db.close();
    }

    @Override
    public void clearCache() {
        this.idCache.clear();
        close(true);
    }
}
