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
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class ObjectBox<T extends ObjectDAO<Long>> extends PersistentMasterDAOImpl<Long, T, ObjectBoxSelectionDAO> {
    static final Logger logger = LoggerFactory.getLogger(ObjectBox.class);

    public ObjectBox(String dbName, String datasetDir, SegmentedObjectAccessor accessor) {
        super(dbName, datasetDir,
                (mDAO, positionName, outputDir, readOnly) -> (T)new ObjectBoxDAO(mDAO, positionName, outputDir, readOnly),
                ObjectBoxSelectionDAO::new,
                accessor);
    }

    @Override
    public boolean containsDatabase(String outputPath) {
        try {
            List<Path> positions = Files.list(Paths.get(outputPath)).filter(p -> !p.getFileName().toString().equals("Selections")).collect(Collectors.toList());
            for (Path pos : positions) {
                Path so = pos.resolve("objectbox");
                if (Files.exists(so) && Files.list(so).map(p -> p.getFileName().toString()).anyMatch(n -> n.startsWith("objects_") && Character.isDigit(n.charAt(n.length()-1)))) return true;
            }
        } catch (Exception e) { }
        return false;
    }

}
