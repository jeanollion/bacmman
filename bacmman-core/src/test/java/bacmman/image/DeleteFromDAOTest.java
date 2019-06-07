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
package bacmman.image;

import static bacmman.test_utils.GenerateSyntheticData.generateImages;
import static bacmman.test_utils.TestUtils.logger;

import bacmman.data_structure.*;
import bacmman.core.Task;
import bacmman.configuration.experiment.ChannelImage;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.Processor.MEASUREMENT_MODE;
import bacmman.data_structure.dao.BasicMasterDAO;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import bacmman.utils.Utils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bacmman.plugins.PluginFactory;
import bacmman.plugins.plugins.measurements.ObjectInclusionCount;
import bacmman.plugins.plugins.processing_pipeline.SegmentThenTrack;
import bacmman.plugins.plugins.segmenters.SimpleThresholder;
import bacmman.plugins.plugins.thresholders.ConstantValue;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;


/**
 *
 * @author Jean Ollion
 */
public class DeleteFromDAOTest {
    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();

    /*public static void main(String[] args) {
        DeleteFromDAOTest t = new DeleteFromDAOTest();
        try {
            t.testFolder.create();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            //t.testDeleteAndRelabel();
            t.deleteTestBasic();
            //t.deleteTestDBMap();
            //t.testDeleteMass();
        } catch(Throwable e) {
            logger.error("error", e);
        }

    }*/

    private MasterDAO generateDB(MasterDAOFactory.DAOType type) {
        String dir = "";
        try {
            dir = testFolder.newFolder().getAbsolutePath();
        } catch (Exception e) {
            logger.error("could not create folder:", e);
        }
        MasterDAO dao = MasterDAOFactory.createDAO("testdb", dir, type);
        dao.setConfigurationReadOnly(false);
        dao.lockPositions();
        return dao;
    }
    

    @Test
    public void deleteTestBasic() throws IOException{
        MasterDAO dao = generateDB(MasterDAOFactory.DAOType.Basic);
        deleteTest(dao);
        // probleme store: concurent modification: les children sont déjà set lorsque store est apellée... ajouter un test?
    }
    @Test
    public void deleteTestDBMap() throws IOException{
        MasterDAO dao = generateDB(MasterDAOFactory.DAOType.DBMap);
        deleteTest(dao);
    }


    public void deleteTest(MasterDAO masterDAO) throws IOException {
        String prefix = "DAO type: "+masterDAO.getClass().getSimpleName()+"; ";
        // generate XP
        Experiment xp = new Experiment("test");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        ChannelImage cMic = new ChannelImage("ChannelImageMicroChannel");
        xp.getChannelImages().insert(cMic);
        ChannelImage cBact = new ChannelImage("ChannelImageBact");
        xp.getChannelImages().insert(cBact);
        xp.getStructures().removeAllElements();
        Structure microChannel = new Structure("MicroChannel", -1, 0);
        Structure bacteria = new Structure("Bacteries", 0, 1);
        xp.getStructures().insert(microChannel, bacteria);

        // processing chains
        PluginFactory.findPlugins("bacmman.plugins.plugins");
        microChannel.setProcessingPipeline(new SegmentThenTrack(new SimpleThresholder(new ConstantValue(1)), new ObjectIdxTracker()));
        bacteria.setProcessingPipeline(new SegmentThenTrack(new SimpleThresholder(new ConstantValue(1)), new ObjectIdxTracker()));

        // set up I/O directory & create fields
        File inputImage = testFolder.newFolder();
        generateImages("field1", inputImage.getAbsolutePath(), 1, 2, 1);
        generateImages("field11", inputImage.getAbsolutePath(), 1, 2, 1);
        generateImages("field2", inputImage.getAbsolutePath(), 1, 2, 1);
        generateImages("field3", inputImage.getAbsolutePath(), 1, 2, 1);
        generateImages("field4", inputImage.getAbsolutePath(), 1, 2, 1);
        Processor.importFiles(xp, true, null, inputImage.getAbsolutePath());
        assertEquals("number fields", 5, xp.getPositionCount());
        File outputDir = testFolder.newFolder();
        //File outputDir = new File("/tmp/testJava");
        //outputDir.mkdir();
        xp.setPath(outputDir.getParentFile().toPath());
        xp.setOutputDirectory(outputDir.getAbsolutePath());
        // save to db
        masterDAO.setExperiment(xp);
        long t0 = System.currentTimeMillis();
        // process
        assertEquals("number of files before preProcess", 0, countFiles(outputDir));
        try {
            Processor.preProcessImages(masterDAO);
        } catch (Exception ex) {
            logger.error("error while pre-processing", ex);
            assertTrue("failed to preProcess images", false);
        }
        assertEquals("number of files after preProcess",10, countFiles(outputDir));

        Processor.processAndTrackStructures(masterDAO, true);
        //for (String p : xp.getPositionsAsString()) Processor.processAndTrackStructures(masterDAO.getDao(p), true, false, 0,1);


        xp.addMeasurement(new ObjectInclusionCount(1, 1, 50));
        Processor.performMeasurements(masterDAO, MEASUREMENT_MODE.ERASE_ALL, null);

        SegmentedObject r = masterDAO.getDao("field1").getRoot(0);
        SegmentedObject ob = r.getChildren(0).findFirst().get();
        SegmentedObject ob2 = r.getChildren(1).findFirst().get();
        logger.debug("object {}, {}, 2D?{}, meas {}", ob.getBounds(), ob.getRegion().getVoxels(), ob.is2D(), ob.getMeasurements().getValues());
        logger.debug("object2 {}, {}, 2D? {}", ob2.getBounds(), ob2.getRegion().getVoxels(), ob2.is2D());


        ObjectDAO dao = masterDAO.getDao("field1");
        ObjectDAO dao11 = masterDAO.getDao("field11");
        SegmentedObject root = dao.getRoots().get(0);
        SegmentedObject mc = root.getChildren( 0).findFirst().get();
        assertEquals(prefix+"number of stored objects ", 15, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements ", 5, countObjects(masterDAO, Measurements.class));
        assertTrue(prefix+"object retrieved: ", mc.getRegion().getVoxels().size()>=1);
        logger.debug("before delete children");
        dao.deleteChildren(mc, 1);
        assertEquals(prefix+"number of objects after delete children", 14, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements after delete children", 4, countObjects(masterDAO, Measurements.class));
        logger.debug("before delete ");
        dao.delete(root, true, false, false);
        assertEquals(prefix+"number of objects after delete root", 12, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements after delete root", 4, countObjects(masterDAO, Measurements.class));
        logger.debug("before delete children2");
        dao11.deleteChildren(dao11.getRoots().get(0), 0);
        assertEquals(prefix+"number of objects after delete root's children", 10, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements after delete root's children", 3, countObjects(masterDAO, Measurements.class));
        logger.debug("before delete all 1");
        dao11.deleteAllObjects();
        assertEquals(prefix+"number of objects after delete all objects", 9, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements after delete all objects", 3, countObjects(masterDAO, Measurements.class));
        logger.debug("before delete all objects 2");
        masterDAO.getDao("field2").deleteAllObjects();
        assertEquals(prefix+"number of objects after delete field", 6, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements after delete field", 2, countObjects(masterDAO, Measurements.class));
        logger.debug("before delete by structureIdx");
        masterDAO.getDao("field3").deleteObjectsByStructureIdx(0);
        assertEquals(prefix+"number of objects after delete by structureIdx", 4, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements after by structureIdx", 1, countObjects(masterDAO, Measurements.class));        
        masterDAO.deleteAllObjects();
        assertEquals(prefix+"number of files after delete all", 0, countObjects(masterDAO, SegmentedObject.class));
        assertEquals(prefix+"number of measurements after delete all", 0, countObjects(masterDAO, Measurements.class));
        long t2 = System.currentTimeMillis();
    }
    
    @Test
    public void testDeleteMass() throws IOException {
        MasterDAO db = generateDB(MasterDAOFactory.DAOType.DBMap);
        if (db==null) logger.error("db null!!!");
        String f = "testField";
        int[] count = new int[]{10, 10, 10};
        Experiment xp = new Experiment("");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        xp.setOutputDirectory(testFolder.newFolder().getAbsolutePath());
        xp.createPosition(f);
        xp.getStructures().insert(new Structure("S0", -1, 0), new Structure("Sub1", 0, 0), new Structure("Sub2",1, 0));
        db.setExperiment(xp);
        SegmentedObjectAccessor accessor = getAccessor();
        ObjectDAO dao = db.getDao(f);
        SegmentedObject root = getAccessor().createRoot(0, new BlankMask(1, 1, 1), dao);
        Region o = new Region(new BlankMask(1, 1, 1), 1, false);
        List<SegmentedObject> s0 = new ArrayList<SegmentedObject>();
        for (int i = 0; i<count[0]; ++i) {
            SegmentedObject oi = new SegmentedObject(0, 0, i, o, root);
            s0.add(oi);
            List<SegmentedObject> s1 = new ArrayList<SegmentedObject>(count[1]);
            for (int j = 0; j<count[1]; ++j) {
                SegmentedObject oj = new SegmentedObject(0, 1, j, o, oi);
                s1.add(oj);
                List<SegmentedObject> s2 = new ArrayList<SegmentedObject>(count[2]);
                for (int k = 0; k<count[2]; ++k) {
                    SegmentedObject ok = new SegmentedObject(0, 2, k, o, oj);
                    s2.add(ok);
                    ok.getMeasurements().setValue("test", k);
                }
                accessor.setChildren(oj, s2, 2);
                oj.getMeasurements().setValue("test", j);
            }
            accessor.setChildren(oi, s1, 1);
            oi.getMeasurements().setValue("test", i);
        }
        accessor.setChildren(root, s0, 0);
        
        int n = 1;
        dao.store(root);
        assertEquals("store root ", 1, countObjects(db, SegmentedObject.class) );
        for (int i = 0; i<=2; ++i) { 
            dao.store(root.getChildren(i).collect(Collectors.toList()));
            n+=root.getChildren(i).count();

        }
        assertEquals("store children count structureObjects", n, countObjects(db, SegmentedObject.class) );
        assertEquals("store count measurements", n-1, countObjects(db, Measurements.class));
        dao.clearCache();
        root = dao.getRoots().get(0);
        n =1;
        for (int i = 0; i<=2; ++i) {
            n*=count[i];
            List<SegmentedObject> ci = root.getChildren(i).collect(Collectors.toList());;
            assertEquals("retrieve children : s:"+i, n, ci.size());
        }
        dao.delete(root.getChildren(0).collect(Collectors.toList()), true, true, true);
        assertEquals("delete children count structureObjects", 1, countObjects(db, SegmentedObject.class));
        assertEquals("delete count measurements", 0, countObjects(db, Measurements.class));
    }
    
    @Test
    public void testDeleteAndRelabel() throws IOException {
        MasterDAO db = generateDB(MasterDAOFactory.DAOType.DBMap);
        if (db==null) logger.error("db null!!");
        String f = "testField";
        
        Experiment xp = new Experiment("");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        xp.setOutputDirectory(this.testFolder.newFolder().getPath());
        xp.getStructures().insert(new Structure("MicroChannel", -1, 0));
        xp.createPosition(f);
        db.setExperiment(xp);
        
        ObjectDAO dao = db.getDao(f);

        SegmentedObject root = getAccessor().createRoot(0, new BlankMask(1, 1, 1), dao);
        Region o = new Region(new BlankMask(1, 1, 1), 1, false);
        final SegmentedObject c1 = new SegmentedObject(0, 0, 0, o, root);
        final SegmentedObject c2 = new SegmentedObject(0, 0, 1, o, root);
        final SegmentedObject c3 = new SegmentedObject(0, 0, 2, o, root);
        final SegmentedObject c4 = new SegmentedObject(0, 0, 3, o, root);
        final SegmentedObject c5 = new SegmentedObject(0, 0, 4, o, root);
        List<SegmentedObject> children = new ArrayList<SegmentedObject>(){{add(c1);add(c2);add(c3);add(c4);add(c5);}};
        assertEquals("dao cleared", 0, countObjects(db, SegmentedObject.class));
        dao.store(root);
        assertEquals("root stored", 1, countObjects(db, SegmentedObject.class));
        dao.store(children);
        dao.clearCache();
        root = dao.getRoots().get(0);
        children = root.getChildren(0).collect(Collectors.toList());
        assertEquals("retrieve children", 5, children.size());
        List<SegmentedObject> toDelete = new ArrayList(children.subList(1, 3));
        dao.delete(toDelete, true, true, true);
        assertEquals("delete list, from parent", 3, root.getChildren(0).count());
        assertEquals("delete list, relabel", 1, root.getChildren(0).collect(Collectors.toList()).get(1).getIdx());
        dao.clearCache();
        root = dao.getRoots().get(0);
        assertEquals("delete list, relabel stored", 1, root.getChildren(0).collect(Collectors.toList()).get(1).getIdx());
        
        // test with single object
        dao.delete(root.getChildren(0).findFirst().get(), true, true, true);
        assertEquals("delete single, from parent", 2, root.getChildren(0).count());
        assertEquals("delete single, index relabeled from parents?", 0, root.getChildren(0).collect(Collectors.toList()).get(0).getIdx());
        dao.clearCache();
        root = dao.getRoots().get(0);
        assertEquals("delete single, relabel stored", 0, root.getChildren(0).collect(Collectors.toList()).get(0).getIdx());
    }
    
    private static int countObjects(MasterDAO db, Class clazz) {
        if (db instanceof BasicMasterDAO) {
            ArrayList<SegmentedObject> allObjects = new ArrayList<SegmentedObject>();
            for (String f : db.getExperiment().getPositionsAsString()) {
                List<SegmentedObject> rootTrack = db.getDao(f).getRoots();
                allObjects.addAll(rootTrack);
                for (int s = 0; s<db.getExperiment().getStructureCount(); ++s) {
                    for (SegmentedObject r : rootTrack) {
                        allObjects.addAll(r.getChildren(s).collect(Collectors.toList()));
                    }
                }
            }
            if (clazz == SegmentedObject.class) {
                 return allObjects.size();
            } else if (clazz == Measurements.class) {
                return (int)allObjects.stream().filter(o->!o.getMeasurements().getValues().isEmpty()).count();
            }
        } else {
            db.clearCache();

            if (SegmentedObject.class.equals(clazz)) {
                ArrayList<SegmentedObject> allObjects = new ArrayList<>();
                for (String p : db.getExperiment().getPositionsAsString()) {
                    ObjectDAO dao = db.getDao(p);
                    for (int s = -1; s<db.getExperiment().getStructureCount(); ++s) {
                        allObjects.addAll(SegmentedObjectUtils.getAllObjectsAsStream(dao, s).collect(Collectors.toSet()));
                    }
                }
                return allObjects.size();
            }
            else if (Measurements.class.equals(clazz)) {
                int count = 0;
                for (String p : db.getExperiment().getPositionsAsString()) {
                    ObjectDAO dao = db.getDao(p);
                    for (int s = -1; s<db.getExperiment().getStructureCount(); ++s) {
                       count += dao.getMeasurements(s).size();
                    }
                }
                return count;
            }
        }
        
        return -1;
    }
    
    private static int countFiles(File dir) {
        if (dir.isFile()) return 1;
        else {
            int count=0;
            for (File f : dir.listFiles((dir1, name) -> !name.equals(".lock"))) count+=countFiles(f);
            return count;
        }
    }
    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
