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
package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.dao.DBMapMasterDAO;
import bacmman.data_structure.dao.DBMapObjectDAO;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.dao.SelectionDAO;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class DbConverter {
    public static final Logger logger = LoggerFactory.getLogger(DbConverter.class);

    
    public static void copy(MasterDAO source, MasterDAO dest, boolean copyXP) {
        if (copyXP) {
            Experiment xp2 = source.getExperiment().duplicate();
            if (dest instanceof DBMapMasterDAO) xp2.setOutputDirectory(dest.getDir().resolve("Output").toString());
            dest.setExperiment(xp2);
        }
        SelectionDAO sourceSelDAO = source.getSelectionDAO();
        if (sourceSelDAO!=null) {
            SelectionDAO destSelDAO = dest.getSelectionDAO();
            for (Selection s : sourceSelDAO.getSelections()) destSelDAO.store(s);
        }
        long readTime = 0;
        long writeTime = 0;
        long objectCount = 0;
        for (String position : source.getExperiment().getPositionsAsString()) {
            ObjectDAO sourceDAO = source.getDao(position);
            ObjectDAO destDAO = dest.getDao(position);
            List<SegmentedObject> roots=sourceDAO.getRoots();
            destDAO.store(roots);
            for (int sIdx = 0; sIdx<source.getExperiment().getStructureCount(); ++sIdx) {
                long tr0 = System.currentTimeMillis();
                Collection<List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(roots, sIdx).values();
                long tr1 = System.currentTimeMillis();
                
                List<SegmentedObject> toWrite = new ArrayList<>();
                for (List<SegmentedObject> list : allTracks) toWrite.addAll(list);
                objectCount+=toWrite.size();
                for (SegmentedObject o : toWrite) {
                    o.getRegion();
                    o.getMeasurements();
                }
                readTime+=tr1-tr0;
                long t0 = System.currentTimeMillis();
                destDAO.store(toWrite);
                destDAO.upsertMeasurements(toWrite);
                long t1 = System.currentTimeMillis();
                writeTime+=t1-t0;
                if (destDAO instanceof DBMapObjectDAO) ((DBMapObjectDAO)destDAO).compactDBs(false);
            }
            logger.debug("xp: {}, current readTime: {} ({}), current write time: {} ({}), total object number: {}", source.getDBName(), readTime, (double)readTime/(double)objectCount, writeTime, (double)writeTime/(double)objectCount, objectCount);
        }
        
    }
}
