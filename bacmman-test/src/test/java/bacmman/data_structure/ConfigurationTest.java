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
package bacmman.data_structure;

import bacmman.configuration.experiment.ChannelImage;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.dao.MasterDAO;

import static bacmman.test_utils.TestUtils.logger;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import bacmman.dummy_plugins.DummySegmenter;
import bacmman.plugins.PluginFactory;
import bacmman.plugins.plugins.processing_pipeline.SegmentThenTrack;
import bacmman.plugins.plugins.trackers.ObjectOrderTracker;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author Jean Ollion
 */
public class ConfigurationTest {
    static {MasterDAOFactory.findModules("bacmman.data_structure.dao");}
    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
    Experiment xp;
    Structure s0, s1, s2, s3, s4, s5, s6;
    @Before
    public void setUp() {
        s0 = new Structure("StructureIdx0", -1, 0);
        s1 = new Structure("StructureIdx1", 0, 0);
        s2 = new Structure("StructureIdx2", 0, 0);
        s3 = new Structure("StructureIdx3", 1, 0);
        s4 = new Structure("StructureIdx4", -1, 0);
        s5 = new Structure("StructureIdx5", 3, 0);
        s6 = new Structure("StructureIdx6", 3, 0);
        xp = new Experiment("test XP", s0, s1, s2, s3, s4, s5, s6);
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        /*
        root
        -s0
        --s1
        ---s3
        ----s5
        ----s6
        --s2
        -s4
        */
    }
    
    @Test
    public void testHierarchicalStructureOrder() {
        assertEquals("Structure 2", s2, xp.getStructure(2));
        
        assertEquals("Hierarchical order s0:", 0, xp.experimentStructure.getHierachicalOrder(0));
        assertEquals("Hierarchical order s1:", 1, xp.experimentStructure.getHierachicalOrder(1));
        assertEquals("Hierarchical order s2:", 1, xp.experimentStructure.getHierachicalOrder(2));
        assertEquals("Hierarchical order s3:", 2, xp.experimentStructure.getHierachicalOrder(3));
        assertEquals("Hierarchical order s4:", 0, xp.experimentStructure.getHierachicalOrder(4));
        assertEquals("Hierarchical order s5:", 3, xp.experimentStructure.getHierachicalOrder(5));
        assertEquals("Hierarchical order s5:", 3, xp.experimentStructure.getHierachicalOrder(6));
        
        int[][] orders = xp.experimentStructure.getStructuresInHierarchicalOrder();
        assertArrayEquals("orders 0:", new int[]{0, 4}, orders[0]);
        assertArrayEquals("orders 1:", new int[]{1, 2}, orders[1]);
        assertArrayEquals("orders 2:", new int[]{3}, orders[2]);
        assertArrayEquals("orders 3:", new int[]{5,6}, orders[3]);
    }
    
    @Test
    public void testPathToStructure() {
        assertArrayEquals("path to structure 0->6", new int[]{1, 3, 6}, xp.experimentStructure.getPathToStructure(0, 6));
        assertArrayEquals("path to structure 2->6", new int[]{}, xp.experimentStructure.getPathToStructure(2, 6)); // 6 is not an indirect child of 2
        assertArrayEquals("path to root 6", new int[]{0, 1, 3, 6}, xp.experimentStructure.getPathToRoot(6));
    }
    
    @Test
    public void testGetChildren() {
        assertArrayEquals("getChildren 3", new int[]{5, 6}, xp.experimentStructure.getAllDirectChildStructuresAsArray(3));
        assertArrayEquals("getChildren 2", new int[]{}, xp.experimentStructure.getAllDirectChildStructuresAsArray(2));
        assertArrayEquals("getAllChildren 0", new int[]{1, 2, 3, 5, 6}, xp.experimentStructure.getAllChildStructures(0));
    }
    @Test
    public void testStoreSimpleConfigMapDB() {
        testStoreSimpleConfig("MapDB");
    }

    @Test
    public void testStoreCompleteConfigMapDB() {
        testStoreCompleteConfig("MapDB");
    }

    @Test
    public void testStoreSimpleConfigObjectBox() {
        testStoreSimpleConfig("ObjectBox");
    }
    @Test
    public void testStoreCompleteConfigObjectBox() {
        testStoreCompleteConfig("ObjectBox");
    }

    public void testStoreSimpleConfig(String dbType) {
        MasterDAO db = generateDB(dbType);
        MasterDAO.deleteObjectsAndSelectionAndXP(db);
        Experiment xp = new Experiment("test xp");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        int idx = xp.getStructureCount();
        xp.getStructures().insert(xp.getStructures().createChildInstance("structureTest"));
        db.setExperiment(xp, true);
        db.clearCache(true, true, true);
        xp = db.getExperiment();
        assertEquals("structure nb", idx+1, xp.getStructureCount());
        assertEquals("structure name", "structureTest", xp.getStructure(idx).getName());
        assertTrue("xp init postLoad", xp.getChildCount()>0);

    }

    private MasterDAO generateDB(String type) {
        String dir = "";
        try {
            dir = testFolder.newFolder().getAbsolutePath();
        } catch (Exception e) {
            logger.error("could not create folder:", e);
        }
        MasterDAO dao = MasterDAOFactory.getDAO("testdb", dir, type);
        dao.setConfigurationReadOnly(false);
        dao.lockPositions();
        return dao;
    }

    public void testStoreCompleteConfig(String dbType) {
        MasterDAO db = generateDB(dbType);

        MasterDAO.deleteObjectsAndSelectionAndXP(db);

        // set-up experiment structure
        Experiment xp = new Experiment("test");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        ChannelImage image = xp.getChannelImages().createChildInstance();
        xp.getChannelImages().insert(image);
        Structure microChannel = xp.getStructures().createChildInstance("MicroChannel");
        Structure bacteries = xp.getStructures().createChildInstance("Bacteries");
        xp.getStructures().insert(microChannel);
        bacteries.setParentStructure(0);
        int idx = xp.getStructureCount();

        // set-up processing & tracking 
        PluginFactory.findPlugins("bacmman.dummy_plugins");
        microChannel.setProcessingPipeline(new SegmentThenTrack(new DummySegmenter(true, 2), new ObjectOrderTracker()));
        bacteries.setProcessingPipeline(new SegmentThenTrack(new DummySegmenter(true, 3), new ObjectOrderTracker()));
        

        db.setExperiment(xp, true);
        db.clearCache(true, true, true);
        xp = db.getExperiment();

        assertEquals("structure nb", idx, xp.getStructureCount());
        assertTrue("xp init postLoad", xp.getChildCount()>0);
    }
    
    
}
