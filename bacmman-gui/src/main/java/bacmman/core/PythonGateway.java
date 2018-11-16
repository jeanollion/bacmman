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

import bacmman.data_structure.Selection;
import bacmman.ui.GUI;

import java.util.List;

import bacmman.ui.gui.selection.SelectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import py4j.GatewayServer;
import bacmman.utils.HashMapGetCreate;

/**
 *
 * @author Jean Ollion
 */
public class PythonGateway {
    public static final Logger logger = LoggerFactory.getLogger(PythonGateway.class);
    GatewayServer server;
    public PythonGateway() {
    }
    
    public void startGateway() {
        try {
        server = new GatewayServer(this);
        server.start();
        } catch(Exception e) {}
    }
    public void setExperimentToGUI(String xpName) {
        GUI.getInstance().openExperiment(xpName, null, false);
    }

    /**
     * Save a selection to the dataset {@param dbName}
     * @param dbName name of the dataset
     * @param objectClassIdx index of the class of the objects contained the selection
     * @param selectionName name of the selection
     * @param ids indices of the objects contained in the selection
     * @param positions position of the objects contained in the selection
     * @param showObjects whether objects should be shown
     * @param showTracks
     * @param open
     * @param openWholeSelection
     * @param objectClassIdxDisplay
     * @param interactiveObjectClassIdx
     */
    public void saveCurrentSelection(String dbName, int objectClassIdx, String selectionName, List<String> ids, List<String> positions, boolean showObjects, boolean showTracks, boolean open, boolean openWholeSelection, int objectClassIdxDisplay, int interactiveObjectClassIdx) {
        if (ids.isEmpty()) return;
        if (ids.size()!=positions.size()) throw new IllegalArgumentException("idx & position lists should be of same size "+ids.size() +" vs "+ positions.size());
        if (selectionName.length()==0) selectionName=null;
        HashMapGetCreate<String, List<String>> idsByPosition = new HashMapGetCreate<>(ids.size(), new HashMapGetCreate.ListFactory());
        for (int i = 0; i<ids.size(); ++i) idsByPosition.getAndCreateIfNecessary(positions.get(i)).add(ids.get(i));
        Selection res = Selection.generateSelection(selectionName, objectClassIdx, idsByPosition);
        logger.info("Generating selection: size: {} ({})", positions.size(), res.count());

        if (GUI.getDBConnection()==null || !GUI.getDBConnection().getDBName().equals(dbName)) {
            if (GUI.getDBConnection()!=null) logger.debug("current xp name : {} vs {}", GUI.getDBConnection().getDBName(), dbName);
            logger.info("Connection to {}....", dbName);
            GUI.getInstance().openExperiment(dbName, null, false);
            logger.info("Selection tab....");
            GUI.getInstance().setSelectedTab(2);
            logger.info("Tab selected");
        }
        GUI.getDBConnection().getSelectionDAO().store(res);
        logger.info("pop sels..");
        GUI.getInstance().populateSelections();
        logger.debug("all selections: {}", GUI.getInstance().getSelections().stream().map(s->s.getName()).toArray());
        Selection savedSel = GUI.getInstance().getSelections().stream().filter(s->s.getName().equals(res.getName())).findFirst().orElse(null);
        if (savedSel==null) throw new IllegalArgumentException("selection could not be saved");
        savedSel.setIsDisplayingObjects(showObjects);
        savedSel.setIsDisplayingTracks(showTracks);
        savedSel.setHighlightingTracks(true);
        savedSel.setNavigate(true);

        if (openWholeSelection) {
            // limit to 200 objects
            if (ids.size()>200) throw new IllegalArgumentException("too many objects in selection");
            SelectionUtils.displaySelection(savedSel, -2, objectClassIdxDisplay);
        } else if (open) {
            GUI.getInstance().navigateToNextObjects(true, false, objectClassIdxDisplay, interactiveObjectClassIdx<0);
        }
        if (interactiveObjectClassIdx>=0) GUI.getInstance().setInteractiveStructureIdx(interactiveObjectClassIdx);
    }
    
}
