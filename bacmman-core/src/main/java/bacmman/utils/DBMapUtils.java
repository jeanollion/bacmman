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
package bacmman.utils;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;

import bacmman.data_structure.dao.DBMapObjectDAO;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;
import org.mapdb.serializer.SerializerCompressionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class DBMapUtils {
    public static int startSize=2, incrementSize=2;
    public static final Logger logger = LoggerFactory.getLogger(DBMapUtils.class);
    public static DB createFileDB(String path, boolean readOnly, boolean safeMode) { //   https://mapdb.org/book/performance/
        //logger.debug("creating file db: {}, is dir: {}, exists: {}", path, new File(path).isDirectory(),new File(path).exists());
        DBMaker.Maker m = DBMaker.fileDB(path)
                .allocateStartSize( startSize * 1024*1024)
                .allocateIncrement(incrementSize * 1024*1024)
                .closeOnJvmShutdown();
        if (!Utils.isWindows()) {
            m = m.fileMmapEnableIfSupported() // Only enable mmap on supported platforms
                .fileMmapPreclearDisable()   // Make mmap file faster
                .cleanerHackEnable() // Unmap (release resources) file when its closed. //That can cause JVM crash if file is accessed after it was unmapped
                .concurrencyScale(8); // TODO as option ?
        } else {
            m = m.fileChannelEnable();
        }

        if (readOnly) m=m.fileLockDisable().readOnly();
        else if (safeMode) m=m.transactionEnable();
        DB db = m.make();
        //db.getStore().fileLoad(); //optionally preload file content into disk cache
        return db;
    }
    public static HTreeMap<String, String> createHTreeMap(DB db, String key) {
        try {
            return db.hashMap(key, Serializer.STRING, new SerializerCompressionWrapper<>(Serializer.STRING)).createOrOpen();
        } catch (UnsupportedOperationException e) { // read-only case
            return null;
        }
    }
    public static HTreeMap<Integer, Object> createFrameIndexHTreeMap(DB db, String key) {
        try {
            return db.hashMap(key, Serializer.INTEGER, db.getDefaultSerializer()).createOrOpen();
        } catch (UnsupportedOperationException e) { // read-only case
            return null;
        }
    }
    public static <K, V> Set<Entry<K, V>> getEntrySet(HTreeMap<K, V> map) {
        if (map==null) return Collections.EMPTY_SET; // read-only case
        return map.getEntries(); 
    }
    public static <V> Collection<V> getValues(HTreeMap<?, V> map) {
        if (map==null) return Collections.EMPTY_SET;
        return map.getValues(); 
    }
    public static Iterable<String> getNames(DB db) {
        return db.getAllNames(); 
    }
    public static boolean contains(DB db, String key) {
        if (db==null) return false;
        for (String s : db.getAllNames()) if (s.equals(key)) return true;
        return false;
    }
    public static void deleteDBFile(String path) {
        new File(path).delete();
    }
}
