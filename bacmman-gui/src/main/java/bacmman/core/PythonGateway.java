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

import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.ui.GUI;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import bacmman.ui.gui.objects.DatasetTree;
import bacmman.ui.gui.selection.SelectionUtils;
import bacmman.ui.logger.ExperimentSearchUtils;
import bacmman.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import py4j.CallbackClient;
import py4j.GatewayServer;

import javax.net.ServerSocketFactory;
import javax.swing.*;

/**
 *
 * @author Jean Ollion
 */
public class PythonGateway {
    public static final Logger logger = LoggerFactory.getLogger(PythonGateway.class);
    GatewayServer server;
    final int port, pythonPort;
    final String address;
    public PythonGateway(int port, int pythonPort, String address) {
        this.port=port;
        this.pythonPort=pythonPort;
        this.address=address;
    }
    
    public void startGateway() {
        try {
            server = new GatewayServer(this, port, address(), 0, 0, null, new CallbackClient(pythonPort, address()), ServerSocketFactory.getDefault());
            server.start();
            logger.info("Python Gateway started : port: {} python port: {} address: {}", port, pythonPort, address);
        } catch(Exception e) {
            logger.error("Error with Python Gateway: binding with python will not be available", e);
            if (GUI.hasInstance()) GUI.log("Could not start Python Gateway: binding with python will not be available. Error: "+e.getMessage());
        }
    }

    public void stopGateway() {
        if (server!=null) server.shutdown();
    }

    public InetAddress address() {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            logger.error("Invalid python gateway address. Localhost will be used", e);
            return GatewayServer.defaultAddress();
        }
    }

    public void setExperimentToGUI(String xpName) {
        GUI.getInstance().openDataset(xpName, null, false);
    }

    /**
     * Save a selection to the dataset {@param dbName}
     * @param dbName name of the dataset (or path relative to the working directory if @param dbPath is null)
     * @param dbPath path of the dataset
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
    public void saveCurrentSelection(String dbName, String dbPath, int objectClassIdx, String selectionName, List<String> ids, List<String> positions, boolean showObjects, boolean showTracks, boolean open, boolean openWholeSelection, int objectClassIdxDisplay, int interactiveObjectClassIdx) {
        logger.debug("saveCurrentSelection: db: {} path: {}, oc: {}, sel: {}, ids: {}, pos: {}", dbName, dbPath, objectClassIdx, selectionName, ids.size(), positions.size());
        if (ids.isEmpty()) return;
        if (ids.size()!=positions.size()) throw new IllegalArgumentException("idx & position lists should be of same size "+ids.size() +" vs "+ positions.size());
        if (selectionName.isEmpty()) selectionName=null;
        HashMapGetCreate<String, List<String>> idsByPosition = new HashMapGetCreate<>(ids.size(), new HashMapGetCreate.ListFactory<>());
        for (int i = 0; i<ids.size(); ++i) idsByPosition.getAndCreateIfNecessary(positions.get(i)).add(ids.get(i));
        Selection res = Selection.generateSelection(selectionName, objectClassIdx, idsByPosition);
        logger.info("Generating selection: size: {} ({})", positions.size(), res.count());
        SwingUtilities.invokeLater(() -> {
            // get relative path:
            String workingDir = GUI.getInstance().getWorkingDirectory();
            String dbPathCorr = dbPath == null ? workingDir : dbPath;
            String dbRelPath;
            try {
                dbRelPath = DatasetTree.getRelPathFromNameAndDir(dbName, dbPathCorr, workingDir);
            } catch (RuntimeException e) {
                logger.error("Error saving selection", e);
                return;
            }
            boolean dbOpen;
            if (GUI.getDBConnection() != null) {
                String curDBRelPath = DatasetTree.getRelPathFromNameAndDir(GUI.getDBConnection().getDBName(), GUI.getDBConnection().getDatasetDir().toString(), workingDir);
                dbOpen = curDBRelPath.equals(workingDir);
            } else dbOpen = false;
            if (GUI.getDBConnection() == null) {
                logger.info("Opening dataset {}....", dbRelPath);
                GUI.getInstance().openDataset(dbRelPath, null, false);
                if (GUI.getDBConnection() != null) {
                    dbOpen = true;
                    try {
                        logger.info("Selection tab....");
                        GUI.getInstance().setSelectedTab(3);
                        logger.info("Tab selected");
                    } catch (Exception e) {

                    }
                }
            }
            MasterDAO db = dbOpen ? GUI.getDBConnection() : MasterDAOFactory.getDAO(dbName, dbPathCorr);
            if (db == null) {
                logger.error("Could not find dataset: {} in {}", dbName, dbPathCorr);
                return;
            }
            db.setConfigurationReadOnly(false);
            if (db.isConfigurationReadOnly()) {
                String outputFile = Paths.get(GUI.getDBConnection().getExperiment().getOutputDirectory(), "Selections", res.getName() + ".csv").toString();
                FileIO.writeToFile(outputFile, new ArrayList<Selection>() {{
                    add(res);
                }}, s -> s.toJSONEntry().toString());
                logger.debug("Could not open dataset {} in write mode: selection was save to file: {}", dbRelPath, outputFile);
                return;
            }
            db.getSelectionDAO().store(res);
            if (dbOpen) {
                logger.info("pop sels..");
                GUI.getInstance().populateSelections();
                logger.debug("all selections: {}", GUI.getInstance().getSelections().stream().map(Selection::getName).toArray());
                Selection savedSel = GUI.getInstance().getSelections().stream().filter(s -> s.getName().equals(res.getName())).findFirst().orElse(null);
                if (savedSel == null) throw new IllegalArgumentException("selection could not be saved");
                savedSel.setIsDisplayingObjects(showObjects);
                savedSel.setIsDisplayingTracks(showTracks);
                savedSel.setHighlightingTracks(true);
                savedSel.setNavigate(true);
                GUI.getInstance().getSelections().stream().filter(s -> !s.getName().equals(res.getName())).forEach(s -> s.setNavigate(false));
                if (openWholeSelection) {
                    // limit to 200 objects
                    if (ids.size() > 200) throw new IllegalArgumentException("too many objects in selection");
                    int channelIdx = GUI.getDBConnection().getExperiment().experimentStructure.getChannelIdx(objectClassIdxDisplay);
                    SelectionUtils.displaySelection(savedSel, -2, channelIdx);
                } else if (open) {
                    GUI.getInstance().navigateToNextObjects(true, null, false, objectClassIdxDisplay, interactiveObjectClassIdx < 0);
                }
                if (interactiveObjectClassIdx >= 0)
                    GUI.getInstance().setInteractiveStructureIdx(interactiveObjectClassIdx);
            } else {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
            }
        });
    }

    public void testConnection(String message) {
        GUI.getInstance().setMessage(message);
    }
}
