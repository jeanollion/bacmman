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
package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Experiment;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;

import bacmman.data_structure.SegmentedObjectAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import java.util.HashSet;
import java.util.Set;

import static bacmman.core.Core.*;

/**
 *
 * @author Jean Ollion
 */
public class DBMapMasterDAO implements MasterDAO {
    public static final Logger logger = LoggerFactory.getLogger(DBMapMasterDAO.class);
    protected final Path configDir;
    
    protected final String dbName;
    final HashMap<String, DBMapObjectDAO> DAOs = new HashMap<>();
    final Set<String> positionLock = new HashSet<>();
    protected Experiment xp;
    java.nio.channels.FileLock xpFileLock;
    private FileChannel xpLockChannel;
    RandomAccessFile cfg;
    DBMapSelectionDAO selectionDAO;
    boolean readOnly = true; // default is read only
    private final SegmentedObjectAccessor accessor;

    public DBMapMasterDAO(String dir, String dbName, SegmentedObjectAccessor accessor) {
        if (dir==null) throw new IllegalArgumentException("Invalid directory: "+ dir);
        if (dbName==null) throw new IllegalArgumentException("Invalid DbName: "+ dbName);
        logger.debug("create DBMAPMASTERDAO: dir: {}, dbName: {}", dir, dbName);
        configDir = Paths.get(dir);
        if (!Files.exists(configDir)) {
            try {
                Files.createDirectories(configDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        this.dbName = dbName;
        this.accessor=accessor;
    }

    @Override
    public SegmentedObjectAccessor getAccess() {
        return accessor;
    }

    @Override
    public boolean lockPositions(String... positionNames) {
        if (positionNames==null || positionNames.length==0) {
            if (getExperiment()==null) return false;
            positionNames = getExperiment().getPositionsAsString();
        }
        positionLock.addAll(Arrays.asList(positionNames));
        boolean success = true;
        for (String p : positionNames) {
            DBMapObjectDAO dao = getDao(p);
            success = success && !dao.readOnly;
            if (dao.readOnly) logger.warn("Position: {} could not be locked. Another process already locks it? All changes won't be saved", p);
        }
        return success;
    }
    
    @Override 
    public void unlockPositions(String... positionNames) {
        if (positionNames==null || positionNames.length==0) {
            if (getExperiment()==null) return;
            positionNames = getExperiment().getPositionsAsString();
        }
        this.positionLock.removeAll(Arrays.asList(positionNames));
        for (String p : positionNames) {
            if (this.DAOs.containsKey(p)) {
                this.getDao(p).unlock();
                DAOs.remove(p);
            }
        }
    }

    
    @Override
    public boolean isConfigurationReadOnly() {
        if (cfg==null) this.getExperiment(); // try to get lock 
        return readOnly;
    }
    @Override 
    public boolean setConfigurationReadOnly(boolean readOnly) {
        if (readOnly) {
            this.readOnly=true;
            this.unlockXP();
            return true;
        } else {
            this.readOnly=false;
            this.getExperiment();
            if (xpFileLock!=null) {
                this.readOnly=false;
                return true;
            } else {
                this.readOnly = true;
                return false;
            }
        }
    }
    
    @Override
    public void eraseAll() {
        String outputPath = getExperiment()!=null ? getExperiment().getOutputDirectory() : null;
        String outputImagePath = getExperiment()!=null ? getExperiment().getOutputImageDirectory() : null;
        unlockPositions();
        unlockConfiguration();
        Utils.deleteDirectory(outputPath);
        Utils.deleteDirectory(outputImagePath);
        deleteExperiment();
        configDir.toFile().delete();// deletes XP directory only if void.
    }
    
    private File getConfigFile(String dbName) {
        return configDir.resolve(dbName + "_config.json").toFile();
    }
    
    @Override
    public DBMapObjectDAO getDao(String positionName) {
        DBMapObjectDAO res = this.DAOs.get(positionName);
        if (res==null) {
            String op = getOutputPath();
            if (op==null) throw new RuntimeException("No output path set, cannot create DAO");
            res = new DBMapObjectDAO(this, positionName, op, positionLock.contains(positionName)?false:readOnly);
            //logger.debug("creating DAO: {} position lock: {}, read only: {}", positionName, positionLock.contains(positionName), res.isReadOnly());
            DAOs.put(positionName, res);
        }
        return res;
    }

    @Override
    public String getDBName() {
        return dbName;
    }
    @Override
    public Path getDir() {
        return this.configDir;
    }

    @Override
    public void deleteAllObjects() {
        if (getExperiment()==null) return;
        for (String s : getExperiment().getPositionsAsString()) {
            getDao(s).deleteAllObjects(); // also deletes measurements
        }
    }
    
    @Override
    public void deleteExperiment() {
        if (readOnly) return;
        unlockXP();
        File cfg = getConfigFile(dbName);
        if (cfg.isFile()) cfg.delete();
    }

    private synchronized void accessConfigFileAndlockXP() {
        if (xpFileLock!=null) return;
        try {
            File f = getConfigFile(dbName);
            if (!f.exists()) f.createNewFile();
            cfg = new RandomAccessFile(f, readOnly?"r":"rw");
            if (!readOnly) {
                logger.debug("locking file: {} (cfg null? {})", getConfigFile(dbName), xp==null);
                lock();
            }
            logger.debug("lock at creation: {}, for file: {}", xpFileLock, getConfigFile(dbName));
        } catch (FileNotFoundException ex) {
            logger.debug("no config file found!");
        } catch (OverlappingFileLockException e) {
            logger.debug("file already locked", e);
        } catch (IOException ex) {
            logger.debug("File could not be locked", ex);
        }
    }
    private Path getLockedFilePath() {
        return configDir.resolve(dbName + "_config.json.lock");
    }
    private synchronized boolean lock() {
        try {
            Path p = getLockedFilePath();
            xpLockChannel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            xpFileLock = xpLockChannel.tryLock();
        } catch (IOException|OverlappingFileLockException ex) {
            return false;
        }
        if (xpFileLock==null) {
            if (xpLockChannel!=null) {
                try {
                    xpLockChannel.close();
                } catch (IOException ex) {
                    return false;
                }
            }
            return false;
        } else return true;
    }
    public synchronized void unlock() {
        if (this.xpFileLock!=null) {
            try {
                xpFileLock.release();
                xpFileLock = null;
            } catch (IOException ex) {
                logger.debug("error releasing dao lock", ex);
            }
        }
        if (this.xpLockChannel!=null && xpLockChannel.isOpen()) {
            try {
                xpLockChannel.close();
                xpLockChannel = null;
            } catch (IOException ex) {
                logger.debug("error releasing dao lock channel", ex);
            }
        }
        Path p = getLockedFilePath();
        if (Files.exists(p)) {
            try {
                Files.delete(p);
            } catch (IOException ex) {

            }
        }
    }
    private synchronized void unlockXP() {
        unlock();
        if (cfg!=null) {
            try {
                cfg.close();
                cfg=null;
            } catch (IOException ex) {
                logger.debug("could not close config file", ex);
            }
        }
        
    }
    
    @Override
    public void clearCache() {
        logger.debug("clearing cache...");
        clearCache(true, true , true);
    }
    @Override
    public void unlockConfiguration() {
        unlockXP();
        if (selectionDAO!=null) {
            getSelectionDAO().clearCache();
            this.selectionDAO=null;
        }
        
    }
    @Override 
    public void clearCache(String position) {
        if (getExperiment().getPosition(position)!=null) getExperiment().getPosition(position).flushImages(true, true); // input images
        DBMapObjectDAO dao = DAOs.get(position);
        if (dao!=null) dao.clearCache();
    }
    public synchronized void clearCache(boolean xpDAO, boolean objectDAO, boolean selectionDAO) {
        if (objectDAO) {
            for (DBMapObjectDAO dao : DAOs.values()) clearCache(dao.getPositionName());
        }
        
        if (selectionDAO && this.selectionDAO!=null) {
            getSelectionDAO().clearCache();
            this.selectionDAO=null;
        }
        if (xpDAO) {
            //this.unlockXP();
            this.xp=null;
        }
    }
    
    public void compact() {
        for (String s : getExperiment().getPositionsAsString()) {
            userLog("Compacting position: "+s);
            getDao(s).compactDBs(true);
        }
        if (getSelectionDAO()!=null) getSelectionDAO().compact(true);
    }

    @Override
    public Experiment getExperiment() {
        if (this.xp==null) {
            synchronized(this) {
                if (xp==null) {
                    if (xpFileLock==null) this.accessConfigFileAndlockXP();
                    if (!readOnly && xpFileLock==null) {
                        logger.warn(dbName+ ": Config file could not be locked. Dataset already opened ? Dataset will be opened in ReadOnly mode");
                        readOnly = true;
                        accessConfigFileAndlockXP(); // will try to access the xp file in readonly mode
                    }
                    xp = getXPFromFile();
                    if (xp!=null) {
                        // check output dir & set default if necessary
                        boolean modified = checkOutputDirectories(false);
                        modified = checkOutputDirectories(true) || modified;
                        if (modified) updateExperiment();
                    }
                } else return xp;
            }
        }
        return xp;
    }

    private Experiment getXPFromFile() {
        if (cfg==null) return null;
        String xpString;
        try {
            cfg.seek(0);
            xpString = cfg.readLine();
        } catch (IOException ex) {
            logger.debug("could not read config file: ", ex);
            return null;
        }
        if (xpString==null || xpString.length()==0) return null;
        Experiment xp = new Experiment(dbName).setPath(configDir);
        xp.initFromJSONEntry(JSONUtils.parse(xpString));
        return xp;
    }
    
    public boolean checkOutputDirectories(boolean image) {
        if (xp==null) return false;
        //if (true) return false;
        String outS = image ? xp.getOutputImageDirectory() : xp.getOutputDirectory();
        File out = outS!=null ? new File(outS) : null;
        if (out==null || !out.exists() || !out.isDirectory()) { // look for default output dir and set it up if exists
            out = configDir.resolve("Output").toFile().getAbsoluteFile();
            out.mkdirs();
            if (out.isDirectory()) {
                if (image) xp.setOutputImageDirectory(out.getAbsolutePath());
                else xp.setOutputDirectory(out.getAbsolutePath());
                logger.info("Output {} directory was: {} is now : {}", image? "Image" : "",  outS, out.getAbsolutePath());
                return true;
            }
            logger.debug("default output dir: {}, exists: {}, is Dir: {}", out.getAbsolutePath(), out.exists(), out.isDirectory());
        } 
        if (!out.exists() || !out.isDirectory()) { // warn
            String message = "No "+(image?"Image":"")+" Output Directory Found, Please configure it";
            logger.warn(message);
            userLog(message);
        }
        return false;
    }
    
    
    @Override
    public void updateExperiment() {
        if (xp==null) {
            userLog("Could not update configuration -> configuration NULL ERROR");
            logger.error("Cannot update configuration -> configuration NULL");
        }
        if (readOnly) {
            userLog("Could not update configuration -> READ ONLY");
            logger.error("Cannot update configuration -> READ ONLY");
            return;
        }
        if (this.xpFileLock==null) accessConfigFileAndlockXP();
        updateXPFile();
    }
    private void updateXPFile() {
        logger.debug("Updating configuration file..");
        if (xp!=null && cfg!=null) {
            try {
                FileIO.write(cfg, xp.toJSONEntry().toJSONString(), false);
                if (this.experimentChangedFromFile()) {
                    userLog("Could not save configuration");
                    logger.debug("update not done!");
                    //logger.debug("on file: {}", getXPFromFile().toJSONEntry());
                    //logger.debug("current: {}", xp.toJSONEntry());
                }
                else logger.debug("Update done!");
            } catch (IOException ex) {
                userLog("Could not update configuration: error");
                logger.error("Could not update configuration", ex);
            }
        } else {
            userLog("Could not update configuration -> configuration null ?" + (xp==null)+ " file read error "+(cfg==null));
            logger.error("Could not update configuration -> configuration null ? {} file read error {}", xp==null, cfg==null);
        }
        
        //FileIO.writeToFile(getConfigFile(dbName, false), Arrays.asList(new Experiment[]{xp}), o->o.toJSONEntry().toJSONString());
    }

    @Override
    public void setExperiment(Experiment xp) {
        this.xp=xp;
        if (xp.getPath()==null) this.xp.setPath(configDir);
        updateExperiment();
    }
    
    @Override 
    public boolean experimentChangedFromFile() {
        Experiment xpFile = getXPFromFile();
        if (xpFile==null && xp==null) return false;
        return xpFile==null || !xpFile.sameContent(xp);
    }

    protected String getOutputPath() {
        getExperiment();
        if (xp==null) return null;
        String res = xp.getOutputDirectory();
        if (res==null) return null;
        File f = new File(res);
        if (f.exists() && f.isDirectory()) return res;
        else {
            return null;
        }
        
    }
    
    @Override
    public DBMapSelectionDAO getSelectionDAO() {
        if (this.selectionDAO==null) {
            String op = getOutputPath();
            if (op!=null) {
                selectionDAO = new DBMapSelectionDAO(this, op, isConfigurationReadOnly());
            }
        }
        return selectionDAO;
    }
    
}
