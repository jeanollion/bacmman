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

import bacmman.core.Core;
import bacmman.data_structure.MyObjectBox;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SelectionBox;
import bacmman.data_structure.SelectionBox_;
import bacmman.utils.FileIO;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.BoxStoreBuilder;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class ObjectBoxSelectionDAO implements SelectionDAO {
    static final Logger logger = LoggerFactory.getLogger(ObjectBoxSelectionDAO.class);
    final Path dir;
    final MasterDAO<?, ?> mDAO;
    protected final Map<String, SelectionBox> nameCache = new HashMap<>();
    protected final boolean readOnly;

    protected BoxStore store;
    protected Box<SelectionBox> box;
    public ObjectBoxSelectionDAO(MasterDAO<?, ?> mDAO, String dir, boolean readOnly) {
        this.mDAO=mDAO;
        this.dir = Paths.get(dir, "Selections");
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.readOnly=readOnly;
    }

    protected BoxStore getStore() {
        if (store == null) {
            synchronized (this) {
                if (store == null) {
                    if (!Files.exists(dir)) {
                        try {
                            Files.createDirectories(dir);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    if (readOnly) { // object box cannot create a readonly database de novo
                        Path dbDir = dir.resolve("objectbox");
                        try {
                            if (!Files.exists(dbDir) || !Files.list(dbDir).findAny().isPresent()) return null;
                        } catch (IOException e) {
                            return null;
                        }
                    }
                    BoxStoreBuilder objectBuilder = MyObjectBox.builder().baseDirectory(dir.toFile()).name("objectbox");
                    if (readOnly) objectBuilder.readOnly();
                    store = objectBuilder.build();
                }
            }
        }
        return store;
    }
    protected Box<SelectionBox> getBox() {
        if (box == null) {
            synchronized (this) {
                if (box == null) {
                    BoxStore store = getStore();
                    if (store != null) box = store.boxFor(SelectionBox.class);
                }
            }
        }
        return box;
    }
    @Override
    public synchronized Selection getOrCreate(String name, boolean clearIfExisting) {
        if (nameCache.isEmpty()) retrieveAllSelections();
        SelectionBox res = nameCache.get(name);
        if (res==null) {
            res = new SelectionBox(new Selection(name, mDAO));
            nameCache.put(name, res);
            if (!readOnly) getBox().put(res);
        } else if (clearIfExisting) {
            res.getSelection(mDAO).clear();
            res.setJsonContent(null);
            if (!readOnly) getBox().put(res);
        }
        return res.getSelection(mDAO);
    }
    
    private void retrieveAllSelections() {
        nameCache.clear();
        if (getBox()!=null) {
            getBox().getAll().forEach(sb -> {
                nameCache.put(sb.getName(), sb);
            });
        }
        // local files
        File dirFile = dir.toFile();
        File[] files = dirFile.listFiles((f, n)-> n.endsWith(".txt")||n.endsWith(".json")||n.endsWith(".csv"));
        if (files == null) return;
        List<SelectionBox> toStore = new ArrayList<>();
        for (File f : files) {
            List<Selection> sels;
            try {
                sels = SelectionDAO.readFile(f);
            } catch (IOException e) {
                logger.error("Error reading selection file: "+f, e);
                continue;
            }
            List<Selection> unreadSel = new ArrayList<>();
            for (Selection s : sels) {
                s.setMasterDAO(mDAO);
                if (nameCache.containsKey(s.getName())) {
                    Boolean prompt = Core.userPrompt("Selection "+s.getName()+ " found in file: "+f.getName()+ " will overwrite existing selection in database. Proceed ?");
                    if (prompt==null || prompt) {
                        if (prompt == null) logger.info("Selection: {} found in file: {} will overwrite existing selection", s.getName(), f.getAbsolutePath());
                        // copy metadata
                        SelectionBox sb = nameCache.get(s.getName());
                        Selection source = sb.getSelection(mDAO);
                        s.setHighlightingTracks(source.isHighlightingTracks());
                        s.setColor(source.getColor());
                        s.setIsDisplayingObjects(source.isDisplayingObjects());
                        s.setIsDisplayingTracks(source.isHighlightingTracks());
                        sb.updateSelection(s);
                        toStore.add(sb);
                    } else {
                        unreadSel.add(s);
                    }
                } else {
                    SelectionBox sb = new SelectionBox(0, s.getName(), s.getObjectClassIdx(), s.toJSONEntry().toJSONString());
                    nameCache.put(s.getName(), sb);
                    toStore.add(sb);
                }
                if (!readOnly) {
                    f.delete();
                    if (!unreadSel.isEmpty()) {
                        FileIO.writeToFile(f.getAbsolutePath().replace(".csv", ".json"), unreadSel, ss->ss.toJSONEntry().toJSONString());
                    }
                }
            }
        }
        if (!readOnly && !toStore.isEmpty()) getBox().put(toStore);
    }
    @Override
    public synchronized List<Selection> getSelections() {
        retrieveAllSelections();
        List<Selection> res = nameCache.values().stream().map(s->s.getSelection(mDAO)).sorted().collect(Collectors.toList());
        return res;
    }

    @Override
    public synchronized void clearSelectionCache(String... positions) {
        nameCache.values().forEach(sb -> sb.freeMemoryForPositions(positions));
    }

    @Override
    public synchronized void store(Selection s) {
        SelectionBox sb = nameCache.get(s.getName());
        if (sb == null) {
            sb = new SelectionBox(s);
            nameCache.put(s.getName(), sb);
        } else sb.updateSelection(s);
        s.setMasterDAO(this.mDAO);
        if (readOnly) {
            logger.warn("Cannot store selection: {} for dataset: {} in read only mode. ", s, dir);
            return;
        }
        getBox().put(sb);
        //logger.debug("stored selection: {} id = {}", s.getName(), sb.getId());
    }

    @Override
    public synchronized void delete(String name) {
        SelectionBox sb = nameCache.remove(name);
        if (readOnly) return;
        if (sb!=null) getBox().remove(sb.getId());
        else getBox().query(SelectionBox_.name.equal(name)).build().remove();
    }

    @Override
    public synchronized void delete(Selection o) {
        delete(o.getName());
    }

    @Override
    public synchronized void deleteAllObjects() {
        nameCache.clear();
        if (readOnly) return;
        getBox().removeAll();
    }
    @Override
    public synchronized void erase() {
        clearCache();
        logger.debug("erasing selections read only: {}", readOnly);
        if (readOnly) return;
        Utils.deleteDirectory(dir.resolve("objectbox").toFile());
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    public synchronized void compact(boolean commit) {

    }
    private synchronized void close(boolean commit) {
        if (store == null ) return;
        if (!store.isClosed()) {
            if (box!=null) box.closeThreadResources();
            store.close();
        }
        store = null;
        box = null;
    }

    @Override
    public void clearCache() {
        this.nameCache.clear();
        close(true);
    }
}
