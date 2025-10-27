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

import bacmman.configuration.experiment.Experiment;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

import bacmman.core.Core;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.ui.logger.ProgressLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class MapDB<T extends ObjectDAO<String>> extends PersistentMasterDAOImpl<String, T, MapDBSelectionDAO> {
    static final Logger logger = LoggerFactory.getLogger(MapDB.class);

    public MapDB(Path dir, SegmentedObjectAccessor accessor) {
        super(dir,
                (mDAO, positionName, outputDir, readOnly) -> (T)new MapDBObjectDAO(mDAO, positionName, outputDir, readOnly),
                MapDBSelectionDAO::new,
                accessor);
    }

    @Override
    public boolean containsDatabase(Path outputPath) {
        return MasterDAOFactory.containsMapDBDatabase(outputPath);
    }

}
