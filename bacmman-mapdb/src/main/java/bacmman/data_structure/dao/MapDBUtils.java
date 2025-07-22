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

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;

import bacmman.utils.Utils;
import org.mapdb.*;
import org.mapdb.serializer.SerializerCompressionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class MapDBUtils {
    public static int startSize=2, incrementSize=4, concurrencyScale = 8;
    public static final Logger logger = LoggerFactory.getLogger(MapDBUtils.class);
    public static DB createFileDB(String path, boolean readOnly, boolean safeMode) {
        return createFileDB(path, readOnly, safeMode, false);
    }
    public static DB createFileDB(String path, boolean readOnly, boolean safeMode, boolean checkSumHeaderByPass) { //   https://mapdb.org/book/performance/
        //logger.debug("creating file db: {}, is dir: {}, exists: {}", path, new File(path).isDirectory(),new File(path).exists());
        DBMaker.Maker m = DBMaker.fileDB(path)
                .allocateStartSize( (long)startSize * 1024*1024)
                .allocateIncrement((long)incrementSize * 1024*1024)
                .closeOnJvmShutdown();
        if (checkSumHeaderByPass) m = m.checksumHeaderBypass();
        if (!Utils.isWindows()) {
            m = m.fileMmapEnableIfSupported() // Only enable mmap on supported platforms
                .fileMmapPreclearDisable()   // Make mmap file faster
                .cleanerHackEnable() // Unmap (release resources) file when its closed. //That can cause JVM crash if file is accessed after it was unmapped
                .concurrencyScale(concurrencyScale);
        } else {
            m = m.fileChannelEnable();
        }

        if (readOnly) m=m.fileLockDisable().readOnly();
        else if (safeMode) m=m.transactionEnable();
        try {
            //logger.debug("creating db file: {}", path);
            DB db = m.make();
            //db.getStore().fileLoad(); //optionally preload file content into disk cache
            return db;
        } catch (DBException.DataCorruption e) { // database was corrupted.
            if (!checkSumHeaderByPass) {
                logger.error("Database is corrupted. Will try to open anyway", e);
                return createFileDB(path, readOnly, safeMode, true);
            } else throw e;
        }


    }
    public static HTreeMap<String, String> createHTreeMap(DB db, String key) { //, boolean compressed

        boolean compressed = true;
        try {
            return db.hashMap(key, Serializer.STRING, compressed ? new SerializerCompressionWrapper<>(Serializer.STRING) : Serializer.STRING).valueInline().createOrOpen();
        } catch (UnsupportedOperationException e) { // read-only case
            return null;
        }
    }
    public static HTreeMap<String, Integer> createFrameHTreeMap(DB db, String key) {
        try {
            return db.hashMap(key, Serializer.STRING, Serializer.INTEGER).createOrOpen();
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
