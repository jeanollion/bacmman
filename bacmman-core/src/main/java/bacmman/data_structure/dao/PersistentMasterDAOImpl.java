package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.*;

public abstract class PersistentMasterDAOImpl<ID, T extends ObjectDAO<ID>, S extends SelectionDAO> implements PersistentMasterDAO<ID, T> {
    static final Logger logger = LoggerFactory.getLogger(PersistentMasterDAOImpl.class);
    public static int MAX_OPEN_DAO = 5; // ObjectBox limit: limited number of dataset can be open at the same time.
    protected final Path datasetDir;

    protected final String dbName;
    final HashMap<String, T> DAOs = new HashMap<>();
    final LinkedList<T> openDAO = new LinkedList<>();
    final Set<String> positionLock = new HashSet<>();
    protected Experiment xp;
    java.nio.channels.FileLock xpFileLock;
    private FileChannel xpLockChannel;
    RandomAccessFile cfg;
    S selectionDAO;
    boolean readOnly = true; // default is read only
    boolean safeMode;
    private final SegmentedObjectAccessor accessor;
    protected final ObjectDAOFactory<ID, T> factory;
    protected final SelectionDAOFactory<S> selectionDAOFactory;
    @FunctionalInterface public interface ObjectDAOFactory<ID, T extends ObjectDAO<ID>> {
        T makeDAO(MasterDAO<ID, T> mDAO, String positionName, String dir, boolean readOnly);
    }
    @FunctionalInterface public interface SelectionDAOFactory<S> {
        S makeDAO(MasterDAO<?, ?> mDAO, String dir, boolean readOnly);
    }
    public PersistentMasterDAOImpl(String dbName, String datasetDir, ObjectDAOFactory<ID, T> factory, SelectionDAOFactory<S> selectionDAOFactory, SegmentedObjectAccessor accessor) {
        if (datasetDir==null) throw new IllegalArgumentException("Invalid directory: "+ datasetDir);
        if (dbName==null) throw new IllegalArgumentException("Invalid DbName: "+ dbName);
        logger.debug("create persistent master DAO: dir: {}, dbName: {}", datasetDir, dbName);
        this.datasetDir = Paths.get(datasetDir);
        if (!Files.exists(this.datasetDir)) {
            try {
                Files.createDirectories(this.datasetDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        this.dbName = dbName;
        this.accessor=accessor;
        this.factory=factory;
        this.selectionDAOFactory=selectionDAOFactory;
    }

    @Override
    public SegmentedObjectAccessor getAccess() {
        return accessor;
    }

    @Override
    public synchronized boolean lockPositions(String... positionNames) {
        if (positionNames==null || positionNames.length==0) {
            if (getExperiment()==null) return false;
            positionNames = getExperiment().getPositionsAsString();
        }
        positionLock.addAll(Arrays.asList(positionNames));
        boolean success = true;
        for (String p : positionNames) {
            if (DAOs.containsKey(p) && DAOs.get(p).isReadOnly()) { //
                T dao = DAOs.get(p);
                //logger.debug("close dao: {} {} [lock + read only]", p, dao.hashCode());
                Core.getCore().closePosition(p);
                dao.clearCache();
                dao.unlock();
                clearSelectionCache(p);
                DAOs.remove(p);
                openDAO.remove(dao);
            }
            T dao = getDao(p);
            success = success && !dao.isReadOnly();
            if (dao.isReadOnly()) logger.warn("Position: {} could not be locked. Another process already locks it? All changes won't be saved", p);
        }
        return success;
    }

    @Override
    public synchronized void unlockPositions(String... positionNames) {
        if (positionNames==null || positionNames.length==0) {
            if (getExperiment()==null) return;
            positionNames = getExperiment().getPositionsAsString();
        }
        this.positionLock.removeAll(Arrays.asList(positionNames));
        if (safeMode && !openDAO.isEmpty()) { // one commit message for all positions
            Boolean commit = Core.userPrompt("Safe mode is ON. Changes may have been made, commit them?");
            for (T dao : openDAO) {
                if (commit == null || commit) dao.commit();
                else dao.rollback();
            }
        }
        for (String p : positionNames) {
            if (this.DAOs.containsKey(p)) {
                T dao = this.getDao(p);
                //logger.debug("close dao: {} {} [unlock]", p, dao.hashCode());
                Core.getCore().closePosition(p);
                dao.clearCache();
                dao.unlock();
                clearSelectionCache(p);
                DAOs.remove(p);
                openDAO.remove(dao);
            }
        }
    }

    @Override
    public boolean isConfigurationReadOnly() {
        if (cfg==null) accessConfigFileAndLockXP();
        return readOnly;
    }

    @Override
    public boolean setConfigurationReadOnly(boolean readOnly) {
        if (readOnly) {
            if (this.readOnly) return true;
            else {
                this.readOnly = true;
                unlockAndCloseXP();
                clearCache(false, true, true); // selection DAO needs to be re-created
                return true;
            }
        } else {
            if (!this.readOnly) return true;
            this.readOnly=false;
            accessConfigFileAndLockXP();
            if (xpFileLock!=null) {
                this.readOnly=false;
                clearCache(false, true, true); // selection DAO needs to be re-created
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
        boolean del = datasetDir.toFile().delete(); // deletes XP directory only if void.
        logger.debug("deleted config dir: {}", del);
    }

    private File getConfigFile() {
        return datasetDir.resolve(dbName + "_config.json").toFile();
    }

    @Override
    public T getDao(String positionName) {
        T res = this.DAOs.get(positionName);
        if (res==null) {
            synchronized (this) {
                res = this.DAOs.get(positionName);
                if (res == null) {
                    String op = getOutputPath();
                    if (op==null) throw new RuntimeException("No output path set, cannot create DAO");
                    //logger.debug("requesting dao: {} already open: {}", positionName, openDAOs.stream().map(ObjectDAO::getPositionName).collect(Collectors.toList()));
                    if (openDAO.size()>=MAX_OPEN_DAO) {
                        T toClose = openDAO.pollFirst();
                        //logger.debug("close dao: {} {} [open dao limit]", toClose.getPositionName(), toClose.hashCode());
                        Core.getCore().closePosition(toClose.getPositionName());
                        commit(toClose);
                        toClose.clearCache();
                        clearSelectionCache(toClose.getPositionName());
                        openDAO.remove(toClose); // keep dao object in DAOs map but not in openDAO list to avoid creating several time dao's
                    }
                    res = factory.makeDAO(this, positionName, op, !positionLock.contains(positionName) && readOnly);
                    res.setSafeMode(safeMode);
                    //logger.debug("{} creating DAO: {}@{} position lock: {}, read only: {}", hashCode(), res.hashCode(), positionName, positionLock.contains(positionName), res.isReadOnly());
                    DAOs.put(positionName, res);
                    openDAO.addLast(res);
                }
            }
        }
        if (openDAO.isEmpty() || !res.equals(openDAO.getLast())) {
            synchronized (openDAO) { // put in last position
                openDAO.remove(res);
                openDAO.addLast(res);
            }
        }
        return res;
    }

    @Override
    public void commit() {
        openDAO.forEach(ObjectDAO::commit);
    }

    @Override
    public void rollback() {
        openDAO.forEach(ObjectDAO::rollback);
    }

    protected void commit(T dao) {
        if (safeMode && openDAO.contains(dao)) {
            Boolean commit = Core.userPrompt("Safe mode is ON. Changes may have been made on position "+dao.getPositionName()+ " commit them?");
            if (commit == null || commit) dao.commit();
            else dao.rollback();
        } else dao.commit();
    }

    protected void clearSelectionCache(String... positions) {
        getSelectionDAO().clearSelectionCache(positions);
    }

    @Override
    public String getDBName() {
        return dbName;
    }
    @Override
    public Path getDatasetDir() {
        return this.datasetDir;
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
        unlockAndCloseXP();
        File cfg = getConfigFile();
        if (cfg.isFile()) cfg.delete();
    }

    private synchronized void accessConfigFileAndLockXP() {
        if (xpFileLock!=null && cfg!=null) return;
        try {
            boolean lock = false;
            if (!readOnly) {
                logger.debug("locking file: {} (cfg null? {})", getLockedFilePath(), xp==null);
                lock = lock();
            }
            if (!lock) readOnly = true;
            File f = getConfigFile();
            if (!f.exists()) f.createNewFile();
            cfg = new RandomAccessFile(f, readOnly?"r":"rw");
            logger.debug("lock at creation: {}, for file: {}", xpFileLock, getConfigFile());
        } catch (FileNotFoundException ex) {
            logger.debug("no config file found!");
        } catch (OverlappingFileLockException e) {
            logger.debug("file already locked", e);
        } catch (IOException ex) {
            logger.debug("File could not be locked", ex);
        }
    }
    private Path getLockedFilePath() {
        return datasetDir.resolve(dbName + "_config.json.lock");
    }
    private synchronized boolean lock() {
        try {
            Path p = getLockedFilePath();
            xpLockChannel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            xpFileLock = xpLockChannel.tryLock();
            p.toFile().deleteOnExit(); // shutdown hook
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
                logger.debug("error deleting configuration lock", ex);
            }
        }
    }
    private synchronized void unlockAndCloseXP() {
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
    public void unlockConfiguration() {
        unlockAndCloseXP();
        if (selectionDAO!=null) {
            getSelectionDAO().clearCache();
            this.selectionDAO=null;
        }
    }
    @Override
    public synchronized void clearCache(String position) {
        if (getExperiment().getPosition(position)!=null) getExperiment().getPosition(position).freeMemoryImages(true, true); // input images
        T dao = DAOs.get(position);
        if (dao!=null) dao.clearCache();
        if (selectionDAO != null) clearSelectionCache(position);
    }
    @Override public synchronized void clearCache(boolean configuration, boolean selectionDAO, boolean objectDAOs) {
        logger.debug("clearing cache...");
        if (selectionDAO && this.selectionDAO!=null) {
            getSelectionDAO().clearCache();
            this.selectionDAO=null;
        }
        if (objectDAOs) {
            for (T dao : DAOs.values()) {
                clearCache(dao.getPositionName());
            }
        }

        if (configuration) {
            //this.unlockXP();
            this.xp=null;
        }
    }

    public void compact() {
        for (String s : getExperiment().getPositionsAsString()) {
            Core.userLog("Compacting position: "+s);
            getDao(s).compactDBs(true);
        }
        //if (getSelectionDAO()!=null) getSelectionDAO().compact(true);
    }

    @Override
    public Experiment getExperiment() {
        if (this.xp==null) {
            synchronized(this) {
                if (xp==null) {
                    if (!readOnly && xpFileLock==null || cfg==null) this.accessConfigFileAndLockXP();
                    xp = getXPFromFile();
                    if (xp!=null) {
                        // check output dir & set default if necessary
                        boolean modified = checkOutputDirectories(false);
                        modified = checkOutputDirectories(true) || modified;
                        if (modified) storeExperiment();
                        MasterDAO.configureExperiment(this, xp);
                    }
                } else return xp;
            }
        }
        return xp;
    }

    private Experiment getXPFromFile()  {
        if (cfg==null) return null;
        String xpString;
        try {
            cfg.seek(0);
            xpString = cfg.readLine();
        } catch (IOException ex) {
            logger.debug("could not read config file: ", ex);
            return null;
        }
        if (xpString==null || xpString.isEmpty()) return null;
        Experiment xp = new Experiment(dbName).setPath(datasetDir);
        try {
            xp.initFromJSONEntry(JSONUtils.parse(xpString));
        } catch (ParseException e) {
            logger.error("Error parsing configuration file: {}", e.toString());
            if (getLogger()!=null) getLogger().setMessage("Error parsing configuration file: "+ e);
            return null;
        }
        return xp;
    }

    public boolean checkOutputDirectories(boolean image) {
        if (xp==null) return false;
        //if (true) return false;
        String outS = image ? xp.getOutputImageDirectory() : xp.getOutputDirectory();
        File out = outS!=null ? new File(outS) : null;
        if (out==null || !out.exists() || !out.isDirectory()) { // look for default output dir and set it up if exists
            out = datasetDir.resolve("Output").toFile().getAbsoluteFile();
            out.mkdirs();
            if (out.isDirectory()) {
                if (image) xp.setOutputImageDirectory(out.getAbsolutePath());
                else xp.setOutputDirectory(out.getAbsolutePath());
                logger.info("Output {} directory was: {} is now : {}", image? "Image" : "",  outS, out.getAbsolutePath());
                return true;
            }
            logger.debug("default output dir: {}, exists: {}, is Dir: {}", out.getAbsolutePath(), out.exists(), out.isDirectory());
        }
        if (!out.exists() || !out.isDirectory()) { // warn
            String message = "No "+(image?"Image":"")+" Output Directory Found, Please configure it";
            logger.warn(message);
            Core.userLog(message);
        }
        return false;
    }


    @Override
    public void storeExperiment() {
        if (xp==null) {
            Core.userLog("Could not update configuration -> configuration NULL ERROR");
            logger.error("Cannot update configuration -> configuration NULL");
        }
        if (readOnly) {
            Core.userLog("Could not update configuration -> READ ONLY");
            logger.error("Cannot update configuration -> READ ONLY");
            return;
        }
        if (this.xpFileLock==null) accessConfigFileAndLockXP();
        updateXPFile();
    }
    private void updateXPFile() {
        logger.debug("Updating configuration file..");
        if (xp!=null && cfg!=null) {
            try {
                //logger.debug("updating xp: {}", xp.toJSONEntry().toJSONString());
                FileIO.write(cfg, xp.toJSONEntry().toJSONString(), false);
                if (this.experimentChangedFromFile()) {
                    Core.userLog("Could not save configuration");
                    logger.debug("update not done!");
                    //logger.debug("on file: {}", getXPFromFile().toJSONEntry());
                    //logger.debug("current: {}", xp.toJSONEntry());
                } else logger.debug("Update done!");
            } catch (IOException ex) {
                Core.userLog("Could not update configuration: error");
                logger.error("Could not update configuration", ex);
            }
        } else {
            Core.userLog("Could not update configuration -> configuration null ?" + (xp==null)+ " file read error "+(cfg==null));
            logger.error("Could not update configuration -> configuration null ? {} file read error {}", xp==null, cfg==null);
        }

        //FileIO.writeToFile(getConfigFile(dbName, false), Arrays.asList(new Experiment[]{xp}), o->o.toJSONEntry().toJSONString());
    }

    @Override
    public void setExperiment(Experiment xp, boolean store) {
        this.xp=xp;
        MasterDAO.configureExperiment(this, xp);
        if (store) storeExperiment();
    }

    @Override
    public boolean experimentChangedFromFile() {
        Experiment xpFile = getXPFromFile();
        if (xpFile==null && xp==null) return false;
        return xpFile==null || !xpFile.sameContent(xp);
    }

    protected String getOutputPath() {
        if (getExperiment()==null) return null;
        String res = xp.getOutputDirectory();
        if (res==null) return null;
        File f = new File(res);
        if (!f.exists()) f.mkdirs();
        if (f.exists() && f.isDirectory()) return res;
        else {
            logger.debug("output path is not a directory or could not be created: {}", res);
            throw new RuntimeException("Invalid output path");
        }
    }

    @Override
    public S getSelectionDAO() {
        if (this.selectionDAO==null) {
            if (getExperiment()==null) return null;
            String op = getOutputPath();
            if (op==null) {
                logger.debug("No output path. experiment==null? {} output: {}", getExperiment()==null, getExperiment()==null?"null":getExperiment().getOutputDirectory());
                throw new RuntimeException("No output path set, cannot create DAO");
            }
            selectionDAO = selectionDAOFactory.makeDAO(this, op, isConfigurationReadOnly());
        }
        return selectionDAO;
    }
    @Override
    public void setSafeMode(boolean safeMode) {
        if (this.safeMode!=safeMode) {
            this.safeMode = safeMode;
            for (ObjectDAO dao : DAOs.values()) dao.setSafeMode(safeMode);
        }
    }
    @Override
    public boolean getSafeMode() {
        return safeMode;
    }

    ProgressLogger bacmmanLogger;
    @Override
    public PersistentMasterDAOImpl<ID, T, S> setLogger(ProgressLogger logger) {
        bacmmanLogger = logger;
        return this;
    }

    @Override
    public ProgressLogger getLogger() {
        return bacmmanLogger;
    }
}
