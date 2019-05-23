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

import bacmman.configuration.experiment.Experiment;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.DBMapObjectDAO;
import bacmman.data_structure.dao.ImageDAO;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static bacmman.utils.JSONUtils.parse;
import static bacmman.utils.JSONUtils.serialize;

/**
 *
 * @author Jean Ollion
 */
public class ImportExportJSON {
    public static final Logger logger = LoggerFactory.getLogger(ImportExportJSON.class);
    public static void writeObjects(FileIO.ZipWriter writer, ObjectDAO dao, ProgressCallback pcb) {
        List<SegmentedObject> roots=dao.getRoots();
        if (roots.isEmpty()) return;
        List<SegmentedObject> allObjects = new ArrayList<>();
        allObjects.addAll(roots);
        for (int sIdx = 0; sIdx<dao.getExperiment().getStructureCount(); ++sIdx) {
            SegmentedObjectUtils.getAllObjectsAsStream(dao, sIdx).forEachOrdered(o->allObjects.add(o));
        }
        if (pcb!=null) pcb.log(allObjects.size()+"# objects found");
        writer.write(dao.getPositionName()+"/objects.txt", allObjects, o -> serialize(o));
        allObjects.removeIf(o -> o.getMeasurements().getValues().isEmpty());
        if (pcb!=null) pcb.log(allObjects.size()+"# measurements found");
        writer.write(dao.getPositionName()+"/measurements.txt", allObjects, o -> serialize(o.getMeasurements()));
    }
    public static void exportPreProcessedImages(FileIO.ZipWriter writer, ObjectDAO dao) {
        int ch = dao.getExperiment().getChannelImageCount(true);
        int fr = dao.getExperiment().getPosition(dao.getPositionName()).getFrameNumber(false);
        String dir = dao.getPositionName()+"/Images/";
        ImageDAO iDao = dao.getExperiment().getImageDAO();
        for (int c = 0; c<ch; ++c) {
            for (int f = 0; f<fr; ++f) {
                InputStream is = iDao.openPreProcessedImageAsStream(c, f, dao.getPositionName());
                if (is!=null) writer.appendFile(dir+f+"_"+c, is); //closes is
            }
        }
        // todo check all exported
    }
    private static Set<Triplet<SegmentedObject,Integer, Integer>> listAllTrackImages(ObjectDAO dao) {
        Set<Triplet<SegmentedObject,Integer, Integer>> res = new HashSet<>();
        for (int sIdx = 0; sIdx<dao.getExperiment().getStructureCount(); ++sIdx) {
            List<Integer> direct = dao.getExperiment().experimentStructure.getAllDirectChildStructures(sIdx);
            direct = Utils.transform(direct, s->dao.getExperiment().getChannelImageIdx(s));
            Utils.removeDuplicates(direct, false);
            if (direct.isEmpty()) continue;
            Set<SegmentedObject> ths = SegmentedObjectUtils.getAllObjectsAsStream(dao, sIdx).filter(o->o.isTrackHead()).collect(Collectors.toSet());
            logger.debug("exporting track images: structure: {}, child structures: {}, th: {}", sIdx, direct, ths.size());
            for (int childCIdx : direct) {
                for (SegmentedObject th : ths) {
                    res.add(new Triplet(th, sIdx, childCIdx));
                }
            }
        }
        return res;
    }
    public static void exportTrackImages(FileIO.ZipWriter writer, ObjectDAO dao) {
        ImageDAO iDao = dao.getExperiment().getImageDAO();
        for (Triplet<SegmentedObject, Integer, Integer> p : listAllTrackImages(dao)) {
            InputStream is = iDao.openTrackImageAsStream(p.v1, p.v3);
            if (is!=null) writer.appendFile(dao.getPositionName()+"/TrackImages_"+p.v2+"/"+ Selection.indicesString(p.v1)+"_"+p.v3, is);
        }
    }
    public static String importTrackImages(FileIO.ZipReader reader, ObjectDAO dao) {
        ImageDAO iDao = dao.getExperiment().getImageDAO();
        Set<Triplet<SegmentedObject,Integer, Integer>> missingTrackImages = new HashSet<>();
        for (Triplet<SegmentedObject, Integer, Integer> p : listAllTrackImages(dao)) {
            String file = dao.getPositionName()+"/TrackImages_"+p.v2+"/"+Selection.indicesString(p.v1)+"_"+p.v3;
            InputStream is = reader.readFile(file);
            if (is!=null) iDao.writeTrackImage(p.v1, p.v3, is);
            else missingTrackImages.add(p);
        }
        if (!missingTrackImages.isEmpty()) {
            logger.info("trackImages Import @position: {} missing trackImages: {}", dao.getPositionName(), missingTrackImages);
            String message = "TrackImages Import @position: "+dao.getPositionName()+" missing trackImages: "+Utils.toStringList(missingTrackImages, t->t.v1);
            return message;
        }
        return null;
    }
    public static void importPreProcessedImages(FileIO.ZipReader reader, ObjectDAO dao) {
        String dir = dao.getPositionName()+"/Images/";
        ImageDAO iDao = dao.getExperiment().getImageDAO();
        String pos = dao.getPositionName();
        List<String> files = reader.listsubFiles(dir);
        logger.debug("pos: {}, images: {}", pos, Utils.toStringList(files));
        for (String f : files) {
            File file = new File(f);
            String[] fc = file.getName().split("_");
            int frame = Integer.parseInt(fc[0]);
            int channel = Integer.parseInt(fc[1]);
            InputStream is = reader.readFile(f);
            if (is!=null) {
                //logger.debug("read images: f={}, c={} pos: {}", frame, channel, pos);
                iDao.writePreProcessedImage(is, channel, frame, pos);
            }
        }
        // todo check all imported
    }
    public static void importObjects(FileIO.ZipReader reader, ObjectDAO dao) {
        logger.debug("reading objects..");
        List<SegmentedObject> allObjects = reader.readObjects(dao.getPositionName()+"/objects.txt", o->parse(SegmentedObject.class, o));
        logger.debug("{} objets read", allObjects.size());
        List<Measurements> allMeas = reader.readObjects(dao.getPositionName()+"/measurements.txt", o->new Measurements(parse(o), dao.getPositionName()));
        logger.debug("{} measurements read", allObjects.size());
        Map<String, SegmentedObject> objectsById = new HashMap<>(allObjects.size());
        
        List<SegmentedObject> roots = new ArrayList<>();
        Iterator<SegmentedObject> it = allObjects.iterator();
        while(it.hasNext()) {
            SegmentedObject n = it.next();
            if (n.isRoot()) {
                roots.add(n);
                it.remove();
            }
        }
        
        for (SegmentedObject o : allObjects) objectsById.put(o.getId(), o);
        for (SegmentedObject o : roots) objectsById.put(o.getId(), o);
        SegmentedObjectUtils.setRelatives(objectsById, true, false); // avoiding calls to dao getById when storing measurements: set parents
        SegmentedObjectAccessor accessor = dao.getMasterDAO().getAccess();
        for (Measurements m : allMeas) {
            SegmentedObject o = objectsById.get(m.getId());
            if (o!=null) accessor.setMeasurements(o, m);
        }
        logger.debug("storing roots");
        dao.store(roots);
        logger.debug("storing other objects");
        dao.store(allObjects);
        logger.debug("storing measurements");
        dao.upsertMeasurements(allObjects);
        if (dao instanceof DBMapObjectDAO) ((DBMapObjectDAO)dao).compactDBs(true);
    }
    
    public static <T extends JSONSerializable> List<T> readObjects(String path, Class<T> clazz) {
        return FileIO.readFromFile(path, s-> parse(clazz, s));
    }
    
    public static void exportPositions(FileIO.ZipWriter w, MasterDAO dao, boolean objects, boolean preProcessedImages, boolean trackImages, ProgressCallback pcb) {exportPositions(w, dao, objects, preProcessedImages, trackImages, null, pcb);}
    public static void exportPositions(FileIO.ZipWriter w, MasterDAO dao, boolean objects, boolean preProcessedImages, boolean trackImages, List<String> positions, ProgressCallback pcb) {
        if (!w.isValid()) return;
        if (positions==null) positions = Arrays.asList(dao.getExperiment().getPositionsAsString());
        int count = 0;
        //if (pcb!=null) pcb.incrementTaskNumber(positions.size());
        for (String p : positions) {
            count++;
            logger.info("Exporting: {}/{}", count, positions.size());
            if (pcb!=null) pcb.log("Exporting position: "+p+ " ("+count+"/"+positions.size()+")");
            ObjectDAO oDAO = dao.getDao(p);
            if (objects) {
                writeObjects(w, oDAO, pcb);
                logger.info("objects exported");
            }
            if (preProcessedImages) {
                logger.info("Writing pp images");
                exportPreProcessedImages(w, oDAO);
            }
            if (trackImages) {
                logger.info("Writing track images");
                exportTrackImages(w, oDAO);
            }
            oDAO.clearCache();
            if (pcb!=null) pcb.incrementProgress();
            if (pcb!=null) pcb.log("Position: "+p+" exported!");
        }
        logger.info("Exporting position done!");
    }
    public static void exportConfig(FileIO.ZipWriter w, MasterDAO dao) {
        if (!w.isValid()) return;
        w.write("config.json", new ArrayList<Experiment>(1){{add(dao.getExperiment());}}, o->JSONUtils.serialize(o));
    }
    
    public static void exportSelections(FileIO.ZipWriter w, MasterDAO dao) {
        if (!w.isValid()) return;
        if (dao.getSelectionDAO()!=null) w.write("selections.json", dao.getSelectionDAO().getSelections(), o -> JSONUtils.serialize(o));
    }
    public static Experiment readConfig(File f) {
        if (f.getName().endsWith(".json")||f.getName().endsWith(".txt")) {
            List<Experiment> xp = FileIO.readFromFile(f.getAbsolutePath(), o->JSONUtils.parse(Experiment.class, o));
            if (xp.size()==1) return xp.get(0);
        } else if (f.getName().endsWith(".zip")) {
            FileIO.ZipReader r = new FileIO.ZipReader(f.getAbsolutePath());
            if (r.valid()) {
                List<Experiment> xp = r.readObjects("config.json", o->JSONUtils.parse(Experiment.class, o));
                if (xp.size()==1) return xp.get(0);
            }
        }
        return null;
    }
    public static void importConfigurationFromFile(String path, MasterDAO dao, boolean structures, boolean preProcessingTemplate, ProgressCallback pcb) {
        File f = new File(path);
        if (f.getName().endsWith(".json")||f.getName().endsWith(".txt")) { //FIJI allows only to upload .txt
            List<Experiment> xp = FileIO.readFromFile(path, o->JSONUtils.parse(Experiment.class, o));
            if (xp.size()==1) {
                Experiment source = xp.get(0);
                if (structures && source.getStructureCount()!=dao.getExperiment().getStructureCount()) {
                    if (pcb!=null) pcb.log("Configuration file should have same object class number. Source has: {}"+source.getStructureCount()+" instead of "+dao.getExperiment().getStructureCount());
                    logger.error("Configuration file should have same object class number. Source has: {} instead of {}", source.getStructureCount(), dao.getExperiment().getStructureCount());
                    return;
                }
                // set structures
                if (structures) dao.getExperiment().getStructures().setContentFrom(source.getStructures());
                // set preprocessing template
                if (preProcessingTemplate) dao.getExperiment().getPreProcessingTemplate().setContentFrom(source.getPreProcessingTemplate());
                // set measurements
                dao.getExperiment().getMeasurements().setContentFrom(source.getMeasurements());
                // set other import image
                dao.getExperiment().getImportMethodParameter().setContentFrom(source.getImportMethodParameter());
                // set detection channels
                dao.getExperiment().getChannelImages().setContentFrom(source.getChannelImages());
                dao.getExperiment().getChannelImagesDuplicated().setContentFrom(source.getChannelImagesDuplicated());
                dao.getExperiment().getBestFocusPlaneParameter().setContentFrom(source.getBestFocusPlaneParameter());
                dao.updateExperiment();
                logger.debug("Dataset: {} from file: {} set to db: {}", dao.getExperiment().getName(), path, dao.getDBName());
            }
            
        }
    }
    public static void importFromFile(String path, MasterDAO dao, boolean config, boolean selections, boolean objects, boolean preProcessedImages, boolean trackImages, ProgressCallback pcb) {
        File f = new File(path);
        if (f.getName().endsWith(".json")||f.getName().endsWith(".txt")) {
            if (config) {
                dao.setConfigurationReadOnly(false);
                if (dao.isConfigurationReadOnly()) {
                    if (pcb!=null) pcb.log("Cannot import configuration: experiment is in read only");
                    return;
                }
                List<Experiment> xp = FileIO.readFromFile(path, o->JSONUtils.parse(Experiment.class, o));
                if (xp.size()==1) {
                    xp.get(0).setOutputDirectory(dao.getDir()+File.separator+"Output");
                    xp.get(0).setOutputImageDirectory(xp.get(0).getOutputDirectory());
                    xp.get(0).getPositionParameter().removeAllElements();
                    dao.setExperiment(xp.get(0));
                    logger.debug("Dataset: {} from file: {} set to db: {}", dao.getExperiment().getName(), path, dao.getDBName());
                    dao.clearCache(); // avoid lock issues
                }
            }
        } else if (f.getName().endsWith(".zip")) importFromZip(path, dao, config, selections, objects, preProcessedImages, trackImages, pcb);
    }
    
    public static boolean importFromZip(String path, MasterDAO dao, boolean config, boolean selections, boolean objects, boolean preProcessedImages, boolean trackImages, ProgressCallback pcb) {
        FileIO.ZipReader r = new FileIO.ZipReader(path);
        boolean ok = true;
        if (r.valid()) {
            if (config) { 
                dao.setConfigurationReadOnly(false);
                if (dao.isConfigurationReadOnly()) {
                    if (pcb!=null) pcb.log("Cannot import configuration: dataset is in read only");
                    ok = false;
                } else {
                    Experiment xp = r.readFirstObject("config.json", o->JSONUtils.parse(Experiment.class, o));
                    if (xp!=null) {
                        xp.getPositionParameter().removeAllElements();
                        if (dao.getDir()!=null) {
                            xp.setOutputDirectory(dao.getDir()+File.separator+"Output");
                            xp.setOutputImageDirectory(xp.getOutputDirectory());
                        }
                        dao.setExperiment(xp);
                        logger.debug("XP: {} from file: {} set to db: {}", dao.getExperiment().getName(), path, dao.getDBName());
                    } else {
                        ok = false;
                    }
                }
            }
            if (objects || preProcessedImages || trackImages) {
                Collection<String> dirs = r.listRootDirectories();
                dirs = new ArrayList<>(dirs);
                Collections.sort((List)dirs);
                logger.info("directories: {}", dirs);
                if (pcb!=null) {
                    pcb.incrementTaskNumber(dirs.size());
                    pcb.log("positions: "+dirs.size());
                }
                int count = 0;
                dao.lockPositions(dirs.toArray(new String[dirs.size()]));
                for (String position : dirs) {
                    count++;
                    if (pcb!=null) pcb.log("Importing: Position: "+position + " ("+ count+"/"+dirs.size()+")");
                    ObjectDAO oDAO = dao.getDao(position);
                    if (oDAO.isReadOnly()) {
                        if (pcb!=null) pcb.log("Cannot import position: "+position+" (cannot be locked)");
                        ok = false;
                    } else {
                        try {
                            if (objects) {
                                logger.debug("deleting all objects");
                                oDAO.deleteAllObjects();
                                logger.debug("all objects deleted");
                                importObjects(r, oDAO);
                            }
                            if (preProcessedImages) importPreProcessedImages(r, oDAO);
                            if (trackImages) {
                                String importTI = importTrackImages(r, oDAO);
                                if (importTI!=null && pcb!=null) pcb.log(importTI);
                                else if (pcb!=null) pcb.log("Import track images ok");
                            }
                        } catch (Exception e) {
                            if (pcb!=null) pcb.log("Error! xp could not be undumped! "+e.getMessage());
                            e.printStackTrace();
                            dao.deleteExperiment();
                            throw e;
                        }
                        oDAO.clearCache();
                        if (pcb!=null) pcb.incrementProgress();
                        if (pcb!=null) pcb.log("Position: "+position+" imported!");
                    }
                }
            }
            if (selections) {
                dao.setConfigurationReadOnly(false);
                if (dao.isConfigurationReadOnly()) {
                    if (pcb!=null) pcb.log("Cannot import selection: dataset is in read only");
                    ok = false;
                } else {
                    logger.debug("importing selections....");
                    List<Selection> sels = r.readObjects("selections.json", o->JSONUtils.parse(Selection.class, o));
                    logger.debug("selections: {}", sels.size());
                    if (sels.size()>0 && dao.getSelectionDAO()!=null) {
                        for (Selection s: sels ) if (dao.getSelectionDAO()!=null) dao.getSelectionDAO().store(s);
                        logger.debug("Stored: #{} selections from file: {} set to db: {}", sels.size(), path, dao.getDBName());
                    }
                }
            }
            r.close();
            return ok;
        } else return false;
    }
    
}
