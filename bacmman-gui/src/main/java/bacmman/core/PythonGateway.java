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
    
    public void saveCurrentSelection(String dbName, int structureIdx, String selectionName, List<String> ids, List<String> positions, boolean showObjects, boolean showTracks, boolean open, boolean openWholeSelection, int structureDisplay, int interactiveStructure) {
        if (ids.isEmpty()) return;
        if (ids.size()!=positions.size()) throw new IllegalArgumentException("idx & position lists should be of same size "+ids.size() +" vs "+ positions.size());
        if (selectionName.length()==0) selectionName=null;
        HashMapGetCreate<String, List<String>> idsByPosition = new HashMapGetCreate<>(ids.size(), new HashMapGetCreate.ListFactory());
        for (int i = 0; i<ids.size(); ++i) idsByPosition.getAndCreateIfNecessary(positions.get(i)).add(ids.get(i));
        Selection res = Selection.generateSelection(selectionName, structureIdx, idsByPosition);
        logger.info("Generating selection: size: {} ({})", positions.size(), res.count());
        res.setIsDisplayingObjects(showObjects);
        res.setIsDisplayingTracks(showTracks);
        res.setHighlightingTracks(true);
        
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
        logger.info("sel sel..");
        GUI.getInstance().setSelectedSelection(res);
        if (openWholeSelection) {
            SelectionUtils.displaySelection(res, -2, structureDisplay);
        } else {
            GUI.getInstance().navigateToNextObjects(true, false, structureDisplay, interactiveStructure<0);
        }
        if (interactiveStructure>=0) GUI.getInstance().setInteractiveStructureIdx(interactiveStructure);
    }
    
}
