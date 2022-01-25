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

import bacmman.test_utils.TestUtils;
import static bacmman.test_utils.TestUtils.logger;
import bacmman.configuration.parameters.NumberParameter;

import static bacmman.data_structure.ProcessingTest.createDummyImagesTC;
import bacmman.configuration.experiment.ChannelImage;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;

import bacmman.image.BlankMask;
import bacmman.image.ImageByte;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bacmman.plugins.PluginFactory;
import bacmman.plugins.Segmenter;
import bacmman.plugins.plugins.processing_pipeline.SegmentThenTrack;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.dummy_plugins.DummySegmenter;
import static bacmman.utils.Utils.toStringArray;

/**
 *
 * @author Jean Ollion
 */
public class TestDataStructure {
    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
    

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
    public void StructureObjectTestStore() throws IOException {
        MasterDAO db = generateDB(MasterDAOFactory.DAOType.DBMap);
        Experiment xp = new Experiment("test");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        xp.setOutputDirectory(testFolder.newFolder("testDB").getAbsolutePath());
        db.setExperiment(xp);
        String f = "test";
        SegmentedObject r = new SegmentedObject(0, new BlankMask(1, 2, 3, 0, 0, 0, 1, 1), db.getDao(f));
        SegmentedObject r2 = new SegmentedObject(1, new BlankMask(1, 2, 3, 0, 0, 0, 1, 1), db.getDao(f));
        SegmentedObject r3 = new SegmentedObject(2, new BlankMask(1, 2, 3, 0, 0, 0, 1, 1), db.getDao(f));
        r.setTrackLinks(r2, true, true);
        r2.setTrackLinks(r3, true, true);
        //r2.setPreviousInTrack(r, true);
        //r3.setPreviousInTrack(r2, true);
        db.getDao(f).store(r);
        db.getDao(f).store(r2);
        db.getDao(f).store(r3);
        r2 = db.getDao(f).getById(null, -1, r2.getFrame(), r2.getId());
        r = db.getDao(f).getById(null, -1, r.getFrame(), r.getId());
        assertTrue("r2 retrieved", r!=null);
        assertEquals("r unique instanciation", r, r2.getPrevious());
        assertEquals("xp unique instanciation", r.getExperiment(), r2.getExperiment());
        db.getDao(f).clearCache();
        r2 = db.getDao(f).getById(null, -1, r2.getFrame(), r2.getId());
        assertTrue("r2 retrieved", r2!=null);
        assertEquals("r retrieved 2", "test", r2.getPositionName());
        //assertEquals("r previous ", r.getId(), r2.getPrevious().getId()); // not lazy anymore
        
        assertEquals("r unique instanciation query from fieldName & time point", r2, db.getDao(f).getRoot(1));
    }

    @Test
    public void StructureObjectTest() throws IOException {
        MasterDAO db = generateDB(MasterDAOFactory.DAOType.DBMap);
        
        // set-up experiment structure
        Experiment xp = new Experiment("test");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        File base = testFolder.newFolder("testDB");
        xp.setPath(Paths.get(base.getAbsolutePath()));
        xp.setOutputDirectory(new File(base, "Output").getAbsolutePath());
        ChannelImage image = new ChannelImage("ChannelImage");
        xp.getChannelImages().insert(image);
        xp.getStructures().removeAllElements();
        Structure microChannel = new Structure("MicroChannel", -1, 0);
        Structure bacteries = new Structure("Bacteries", 0, 0);
        bacteries.setParentStructure(0);
        xp.getStructures().insert(microChannel, bacteries);
        String fieldName = "field1";

        // set-up processing scheme
        PluginFactory.findPlugins("bacmman.dummy_plugins");
        PluginFactory.findPlugins("bacmman.plugins.plugins");
        microChannel.setProcessingPipeline(new SegmentThenTrack(new DummySegmenter(true, 2), new ObjectIdxTracker()));
        bacteries.setProcessingPipeline(new SegmentThenTrack(new DummySegmenter(true, 3), new ObjectIdxTracker()));
        Segmenter seg = ((SegmentThenTrack)microChannel.getProcessingScheme()).getSegmenter();
        assertTrue("segmenter set", seg instanceof DummySegmenter);
        assertEquals("segmenter set (2)", 2, ((NumberParameter)seg.getParameters()[0]).getValue().intValue());
        
        // set up fields
        ImageByte[][] images = createDummyImagesTC(50, 50, 1, 3, 1);
        images[0][0].setPixel(12, 12, 0, 2);
        File folder = testFolder.newFolder("TestInputImagesStructureObject");
        ImageWriter.writeToFile(folder.getAbsolutePath(), fieldName, ImageFormat.OMETIF, images);
        Processor.importFiles(xp, true, null, folder.getAbsolutePath());
        //save to db
        
        MasterDAO.deleteObjectsAndSelectionAndXP(db);
        db.setExperiment(xp);
        ObjectDAO dao = db.getDao(fieldName);
        
        try {
            Processor.preProcessImages(db, 0.5);
        } catch (Exception ex) {
            logger.debug("", ex);
        }
        List<SegmentedObject> rootTrack = xp.getPosition(0).createRootObjects(dao);
        assertEquals("root object creation: number of objects", 3, rootTrack.size());
        Processor.processAndTrackStructures(db, true);
        dao.clearCache();

        SegmentedObject rootFetch = dao.getRoot(0);
        assertTrue("root fetch", rootFetch!=null);
        rootTrack = dao.getTrack(rootFetch);
        for (int t = 0; t<rootTrack.size(); ++t) {
            //root[t]=dao.getById(root.get(t).getId());
            for (int sIdx : xp.experimentStructure.getStructuresInHierarchicalOrderAsArray()) {
                rootTrack.get(t).getChildren(xp.experimentStructure.getParentObjectClassIdx(sIdx)).forEach(parent -> {
                    parent.setChildren(dao.getChildren(parent, sIdx), sIdx);
                });
            }
        }

        for (int t = 1; t<rootTrack.size(); ++t) {
            TestUtils.logger.trace("root track: {}->{} / expected: {} / actual: {}", t-1, t, rootTrack.get(t), rootTrack.get(t-1).getNext());
            assertEquals("root track:"+(t-1)+"->"+t, rootTrack.get(t), rootTrack.get(t-1).getNext());
            assertEquals("root track:"+(t)+"->"+(t-1), rootTrack.get(t-1), rootTrack.get(t).getPrevious());
        }
        SegmentedObject[][] microChannels = new SegmentedObject[rootTrack.size()][];
        assertEquals("number of track heads for microchannels", 2, dao.getTrackHeads(rootTrack.get(0), 0).size());
        for (SegmentedObject mcTh : dao.getTrackHeads(rootTrack.get(0), 0)) {
            TestUtils.logger.debug("mc TH: {}  parent: {}", mcTh, mcTh.getParent());
        }
        for (int t = 0; t<rootTrack.size(); ++t) microChannels[t] = rootTrack.get(t).getChildren(0).toArray(l -> new SegmentedObject[l]);
        for (int t = 0; t<rootTrack.size(); ++t) assertEquals("number of microchannels @t:"+t, 2, microChannels[t].length);
        for (int i = 0; i<microChannels[0].length; ++i) {
            for (int t = 1; t<rootTrack.size(); ++t) {
                assertEquals("mc:"+i+" trackHead:"+t, microChannels[0][i].getId(),  microChannels[t][i].getTrackHeadId());
                assertEquals("mc:"+i+" parenttrackHead:"+t, rootTrack.get(0).getId(),  microChannels[t][i].getParentTrackHeadId());
            }
        }
        for (int i = 0; i<microChannels[0].length; ++i) {
            assertEquals("number of track heads for bacteries @ mc:"+i, 3, dao.getTrackHeads(microChannels[0][i], 1).size());
            SegmentedObject[][] bactos = new SegmentedObject[rootTrack.size()][];
            for (int t = 0; t<rootTrack.size(); ++t) {
                bactos[t] = microChannels[t][i].getChildren(1).toArray(l -> new SegmentedObject[l]);
                TestUtils.logger.debug("parent: {}, children: {}, trackHead: {}", microChannels[t][i], toStringArray(bactos[t], o->o.toString()+"/"+o.getId()), toStringArray(bactos[t], o->o.getTrackHead().toString()+"/"+o.getTrackHeadId()));
            }
            for (int t = 0; t<rootTrack.size(); ++t) assertEquals("number of bacteries @t:"+t+" @mc:"+i, 3, bactos[t].length);
            for (int b = 0; b<bactos[0].length; ++b) {
                for (int t = 1; t<rootTrack.size(); ++t) {
                    assertEquals("mc:"+i+ " bact:"+b+" trackHead:"+t, bactos[0][i].getId(),  bactos[t][i].getTrackHeadId());
                    assertEquals("mc:"+i+ " bact:"+b+" parenttrackHead:"+t, microChannels[0][i].getId(),  bactos[t][i].getParentTrackHeadId());
                }
            }
        }
    }
}
