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

import static bacmman.data_structure.dao.DBMapMasterDAO.logger;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mapdb.DB;
import org.mapdb.HTreeMap;
import bacmman.utils.DBMapUtils;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;

/**
 *
 * @author Jean Ollion
 */
public class DBMapSelectionDAO implements SelectionDAO {
    
    final String dir;
    final DBMapMasterDAO mDAO;
    DB db;
    HTreeMap<String, String> dbMap;
    private final Map<String, Selection> idCache = new HashMap<>();
    private final boolean readOnly;
    public DBMapSelectionDAO(DBMapMasterDAO mDAO, String dir, boolean readOnly) {
        this.mDAO=mDAO;
        this.dir= dir+File.separator+"Selections"+File.separator;
        new File(this.dir).mkdirs();
        this.readOnly=readOnly;
        makeDB();
    }
    private synchronized void makeDB() {
        db = DBMapUtils.createFileDB(getSelectionFile(), readOnly);
        dbMap = DBMapUtils.createHTreeMap(db, "selections");
    }
    
    private String getSelectionFile() {
        return dir+"selections.db";
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
        if (db.isClosed()) makeDB();
        for (String s : DBMapUtils.getValues(dbMap)) {
            Selection sel = JSONUtils.parse(Selection.class, s);
            sel.setMasterDAO(mDAO);
            idCache.put(sel.getName(), sel);
        }
        // local files
        File dirFile = new File(dir);
        for (File f : dirFile.listFiles((f, n)-> n.endsWith(".txt")||n.endsWith(".json"))) {
            List<Selection> sels = FileIO.readFromFile(f.getAbsolutePath(), s -> JSONUtils.parse(Selection.class, s));
            for (Selection s : sels) {
                s.setMasterDAO(mDAO);
                if (idCache.containsKey(s.getName())) {
                    logger.info("Selection: {} found in file: {} will be overwriten in local database", s.getName(), f.getAbsolutePath());
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
        if (readOnly) return;
        s.setMasterDAO(this.mDAO);
        if (db.isClosed()) makeDB();
        this.dbMap.put(s.getName(), JSONUtils.serialize(s));
        db.commit();
    }

    @Override
    public synchronized void delete(String id) {
        idCache.remove(id);
        if (readOnly) return;
        if (db.isClosed()) makeDB();
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
        if (readOnly) return;
        db.close();
        db=null;
        dbMap=null;
        DBMapUtils.deleteDBFile(getSelectionFile());
    }
    public synchronized void compact(boolean commit) {
        if (readOnly) return;
        if (db.isClosed()) makeDB();
        if (commit) this.db.commit();
        //this.db.compact();
    }
    private synchronized void close(boolean commit) {
        if (db.isClosed()) return;
        if (commit && !readOnly) this.db.commit();
        this.db.close();
    }

    @Override
    public void clearCache() {
        this.idCache.clear();
        close(true);
    }
}
