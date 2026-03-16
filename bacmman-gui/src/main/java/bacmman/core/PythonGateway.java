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

import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.io.File;

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
        String add = null;
        try {
            add="localhost".equals(address) ? Utils.getExternalIP() : address;
        } catch (SocketException e) {
            add = "0.0.0.0";
        }
        this.address = add == null ? "0.0.0.0" : add; // 0.0.0.0 listen on all network devices
    }

    public List<UnaryPair<String>> getEnv(boolean docker) {
        List<UnaryPair<String>> res = new ArrayList<>();
        res.add(new UnaryPair<>("PYBACMMAN_PORT", String.valueOf(port)));
        res.add(new UnaryPair<>("PYBACMMAN_PYPROXYPORT", String.valueOf(pythonPort)));
        if (docker && (address.equals("0.0.0.0"))) {
            if (Utils.isWindows() || Utils.isMac())
                res.add(new UnaryPair<>("PYBACMMAN_ADDRESS", "host.docker.internal"));
            else {
                try {
                    String ip = Utils.getExternalIP();
                    if (ip != null) res.add(new UnaryPair<>("PYBACMMAN_ADDRESS", ip));
                } catch (SocketException e) {

                }
            }
        } else {
            res.add(new UnaryPair<>("PYBACMMAN_ADDRESS", address));
        }
        return res;
    }

    public List<UnaryPair<Integer>> getPorts() {
        List<UnaryPair<Integer>> res = new ArrayList<>();
        res.add(new UnaryPair<>(port, port));
        res.add(new UnaryPair<>(pythonPort, pythonPort));
        return res;
    }

    public void startGateway() {
        try {
            server = new GatewayServer(this, port, address(), 0, 0, null, new CallbackClient(pythonPort, address()), ServerSocketFactory.getDefault());
            server.start();
            if (GUI.hasInstance()) GUI.log("Python Gateway started : port: "+port+" python port: "+pythonPort+ " address: "+address);
            logger.debug("Python Gateway started : port: {} python port: {} address: {}", port, pythonPort, address);
        } catch(Exception e) {
            logger.debug("Error with Python Gateway: binding with python will not be available", e);
            if (GUI.hasInstance()) GUI.log("Could not start Python Gateway: binding with python will not be available. \nAnother python gateway may be running on those ports, try to change ports from the menu Misc>Python Gateway");
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
        java.util.Random r = new java.util.Random();
        r.nextDouble();
        logger.debug("saveCurrentSelection: db: {} path: {}, oc: {}, sel: {}, ids: {}, pos: {}", dbName, dbPath, objectClassIdx, selectionName, ids.size(), positions.size());
        if (dbName == null && dbPath == null) logger.error("neither dbPath nor dbPath is provided");
        if (ids.isEmpty()) return;
        if (ids.size()!=positions.size()) throw new IllegalArgumentException("idx & position lists should be of same size "+ids.size() +" vs "+ positions.size());
        if (selectionName.isEmpty()) selectionName=null;
        HashMapGetCreate<String, List<String>> idsByPosition = new HashMapGetCreate<>(ids.size(), new HashMapGetCreate.ListFactory<>());
        for (int i = 0; i<ids.size(); ++i) idsByPosition.getAndCreateIfNecessary(positions.get(i)).add(ids.get(i));
        Selection res = Selection.generateSelection(selectionName, objectClassIdx, idsByPosition);
        logger.info("Generating selection: size: {} ({})", positions.size(), res.count());
        SwingUtilities.invokeLater(() -> {
            if (GUI.getInstance() == null) {
                logger.error("BACMMAN is not open");
                return;
            }
            String workingDir = GUI.getInstance().getWorkingDirectory();
            Path path = resolveDBPath(dbName, dbPath, workingDir);
            if (path == null || !Files.isDirectory(path)) {
                logger.error("Could not find dataset: dbName: {} dbPath: {} working dir: {}", dbName, dbPath, workingDir);
                return;
            }
            boolean dbOpen;
            if (GUI.getDBConnection() != null) {
                logger.debug("test DB open: curPath: {} targetPath: {}", GUI.getDBConnection().getDatasetDir().toString(), path);
                dbOpen = GUI.getDBConnection().getDatasetDir().equals(path);
            } else dbOpen = false;
            if (GUI.getDBConnection() == null) {
                logger.info("Opening dataset {}...", path);
                Path wdPath = Paths.get(workingDir);
                if (path.startsWith(wdPath)) { // open db if contained in working directory
                    GUI.getInstance().openDataset(wdPath.relativize(path).toString(), workingDir, false);
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
            }
            MasterDAO db = null;
            try {
                db = dbOpen ? GUI.getDBConnection() : MasterDAOFactory.getDAO(path);
            } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                     IllegalAccessException e) {
                logger.error("Could not instantiate database "+path, e);
                return;
            }
            if (db == null) {
                logger.error("Could not find dataset: {}", path);
                return;
            }
            db.setConfigurationReadOnly(false);
            if (db.isConfigurationReadOnly() || db.getSelectionDAO()==null) {
                String outputFile = Paths.get(db.getExperiment().getOutputDirectory(), "Selections", res.getName() + ".json").toString();
                FileIO.writeToFile(outputFile, new ArrayList<Selection>() {{
                    add(res);
                }}, s -> s.toJSONEntry().toString());
                logger.debug("Could not open dataset {} in write mode: selection was saved to file: {}", path, outputFile);
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

    protected static Path resolveDBPath(String dbName, String dbDir, String workingDir) {
        // make sure working directory is a parent directory
        if (dbDir != null ) {
            dbDir = Paths.get(dbDir).toAbsolutePath().toString();
            workingDir = Paths.get(workingDir).toAbsolutePath().toString();
            if (!dbDir.startsWith(workingDir)) {
                File dbFile = new File(dbDir);
                if (!dbFile.isDirectory() || !dbFile.getParentFile().isDirectory()) return null;
                workingDir = null;
            }
        } else dbDir = workingDir;
        Path dbPath = Paths.get(dbDir);
        if (dbName != null) { // dbName may be a relative path
            if (dbDir.endsWith(dbName)) return dbPath;
            if (ExperimentSearchUtils.isConfigDir(dbPath)) return dbPath; // directory has a configuration file
            return ExperimentSearchUtils.searchConfigDir(dbPath, dbName, 2);
        } else return dbPath;
    }
}
