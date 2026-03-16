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

import static bacmman.test_utils.TestUtils.logger;
import bacmman.configuration.experiment.ChannelImage;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;

import static bacmman.data_structure.SegmentedObjectUtils.setTrackLinks;
import bacmman.image.BlankMask;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static junit.framework.Assert.assertEquals;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class TestTrackStructure {
    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
    static {MasterDAOFactory.findModules("bacmman.data_structure.dao");}
    @Test
    public void testTrackStructureMapDB() throws IOException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        testTrackStructure("MapDB");
    }

    @Test
    public void testTrackStructureObjectBox() throws IOException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        testTrackStructure("ObjectBox");
    }

    /*public static void main(String[] args) {
        TestTrackStructure t = new TestTrackStructure();
        try {
            t.testFolder.create();
        } catch (IOException e) {
            e.printStackTrace();
        }
        t.testTrackStructure();
    }*/
    
    public void testTrackStructure(String daoType) throws IOException, InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException {
        MasterDAO masterDAO = MasterDAOFactory.getDAO(testFolder.newFolder("testTrack").toPath(), daoType);
        masterDAO.setConfigurationReadOnly(false);
        Experiment xp = new Experiment("test");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        //xp.setOutputImageDirectory("/data/Images/Test/");
        xp.setOutputDirectory(testFolder.newFolder("testTrackOuput").getAbsolutePath());
        ChannelImage image = new ChannelImage("ChannelImage");
        xp.getChannelImages().insert(image);
        Structure microChannel = new Structure("MicroChannel", -1, 0);
        Structure bacteries = new Structure("Bacteries", 0, 0);
        xp.getStructures().insert(microChannel, bacteries);
        bacteries.setParentStructure(0);
        xp.createPosition("field1");
        masterDAO.setExperiment(xp, true);
        ObjectDAO dao = masterDAO.getDao("field1");
        SegmentedObject[] rootT = new SegmentedObject[5];
        for (int i = 0; i<rootT.length; ++i) rootT[i] = new SegmentedObject(i, new BlankMask(1, 1, 1), dao);
        
        setTrackLinks(Arrays.asList(rootT));
        dao.store(Arrays.asList(rootT));
        SegmentedObject[] mcT = new SegmentedObject[5];
        for (int i = 0; i<mcT.length; ++i) mcT[i] = new SegmentedObject(i, 0, 0, new Region(new BlankMask( 1, 1, 1), 1, false), rootT[i]);
        setTrackLinks(Arrays.asList(mcT));
        dao.store(Arrays.asList(mcT));
        SegmentedObject[][] bTM = new SegmentedObject[5][3];
        for (int t = 0; t<bTM.length; ++t) {
            for (int j = 0; j<3; ++j) bTM[t][j] = new SegmentedObject(t, 1, j, new Region(new BlankMask( 1, 1, 1), j+1, false), mcT[t]);
            //dao.storeLater(bTM[i]);
        }
        for (int i= 1; i<mcT.length; ++i) {
            bTM[i-1][0].setTrackLinks(bTM[i][0], true, true);
            //bTM[i][0].setPreviousInTrack(bTM[i-1][0], false);
        }
        bTM[0][0].setTrackLinks(bTM[1][1], true, false);
        //bTM[1][1].setPreviousInTrack(bTM[0][0], true);
        for (int i= 2; i<mcT.length; ++i) {
            bTM[i-1][1].setTrackLinks(bTM[i][1], true, true);
            //bTM[i][1].setPreviousInTrack(bTM[i-1][1], false);
        }
        bTM[2][1].setTrackLinks(bTM[3][2], true, false);
        //bTM[3][2].setPreviousInTrack(bTM[2][1], true); 
        bTM[3][2].setTrackLinks(bTM[4][2], true, true);
        //bTM[4][2].setPreviousInTrack(bTM[3][2], false);
        bTM[0][1].setTrackLinks(bTM[1][2], true, true);
        //bTM[1][2].setPreviousInTrack(bTM[0][1], false); 
        bTM[1][2].setTrackLinks(bTM[2][2], true, true);
        //bTM[2][2].setPreviousInTrack(bTM[1][2], false);
        /*
        0.0->4
        -1->4
        --3->4
        1.0->2
        2.0
        */
        for (int i = 0; i<bTM.length; ++i) dao.store(Arrays.asList(bTM[i]));
        dao.clearCache();
        // retrive tracks head for microChannels
        List<SegmentedObject> mcHeads = dao.getTrackHeads(rootT[0], 0);
        
        assertEquals("number of heads for microChannels", 1, mcHeads.size());
        //assertEquals("head is in idCache", mcHeads.get(0), dao.getFromCache(mcHeads.get(0).getId()));
        assertEquals("head for microChannel", mcT[0].getId(), mcHeads.get(0).getId());
        assertEquals("head for microChannel (unique instanciation)", dao.getById(mcT[0].getStructureIdx(), mcT[0].getId(), mcT[0].getFrame(), mcT[0].getParentTrackHeadId()), mcHeads.get(0));

        // retrieve microChannel track
        List<SegmentedObject> mcTrack = dao.getTrack(mcHeads.get(0));
        assertEquals("number of elements in microChannel track", 5, mcTrack.size());
        for (int i = 0; i<mcTrack.size(); ++i) assertEquals("microChannel track element: "+i, mcT[i].getId(), mcTrack.get(i).getId());
        assertEquals("head of microChannel track (unique instanciation)", mcHeads.get(0), mcTrack.get(0));
        for (int i = 0; i<mcTrack.size(); ++i) assertEquals("microChannel track element: "+i+ " unique instanciation", dao.getById(mcT[i].getStructureIdx(), mcT[i].getId(), mcT[i].getFrame(), mcT[i].getParentTrackHeadId()), mcTrack.get(i));

        // retrive tracks head for bacteries
        List<SegmentedObject> bHeadsRetrive = dao.getTrackHeads(mcT[0], 1);
        List<SegmentedObject> bHeads = new ArrayList<SegmentedObject>(5){{add(bTM[0][0]);add(bTM[0][1]);add(bTM[0][2]);add(bTM[1][1]);add(bTM[3][2]);}};
        logger.debug("retrived bacts: {}", Utils.toStringList(bHeadsRetrive, b->b.toString()));
        assertEquals("number of heads for bacteries", 5, bHeadsRetrive.size());
        for (int i = 0; i<bHeads.size(); ++i) {
            logger.debug("compare: {} and {}",bHeads.get(i), bHeadsRetrive.get(i) );
            assertEquals("head for bacteries :"+i, bHeads.get(i).getId(), bHeadsRetrive.get(i).getId());
        }
        assertEquals("head for bacteries (0, unique instanciation)", dao.getById(bTM[0][0].getStructureIdx(), bTM[0][0].getId(), bTM[0][0].getFrame(), bTM[0][0].getParentTrackHeadId()), bHeadsRetrive.get(0));

        // retrieve bacteries track
        List<SegmentedObject> bTrack0 = dao.getTrack(bHeadsRetrive.get(0));
        assertEquals("number of elements in bacteries track (0)", 5, bTrack0.size());
        for (int i = 0; i<mcTrack.size(); ++i) assertEquals("bacteries track element: "+i, bTM[i][0].getId(), bTrack0.get(i).getId());
        assertEquals("head of bacteria track (unique instanciation)", bHeadsRetrive.get(0), bTrack0.get(0));
        for (int i = 0; i<mcTrack.size(); ++i) assertEquals("bacteries track element: "+i+ " unique instanciation", dao.getById(bTM[i][0].getStructureIdx(), bTM[i][0].getId(), bTM[i][0].getFrame(), bTM[i][0].getParentTrackHeadId()), bTrack0.get(i));

        masterDAO.clearCache(true, true, true);
        
    }

}
