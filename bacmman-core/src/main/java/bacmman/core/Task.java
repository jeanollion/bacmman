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

import bacmman.configuration.experiment.PreProcessingChain;
import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.configuration.parameters.MLModelFileParameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.Processor;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.measurement.MeasurementExtractor;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.FeatureExtractor;
import bacmman.ui.logger.ExperimentSearchUtils;
import bacmman.ui.logger.FileProgressLogger;
import bacmman.ui.logger.MultiProgressLogger;
import bacmman.ui.logger.ProgressLogger;

import bacmman.data_structure.Processor.MEASUREMENT_MODE;
import static bacmman.data_structure.Processor.deleteObjects;
import static bacmman.data_structure.Processor.executeProcessingScheme;
import static bacmman.data_structure.Processor.getOrCreateRootTrack;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.*;

import bacmman.utils.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class Task implements TaskI<Task>, ProgressCallback {
        private static final Logger logger = LoggerFactory.getLogger(Task.class);
        String dbName, dir;
        boolean preProcess, segmentAndTrack, trackOnly, measurements, exportPreProcessedImages, exportTrackImages, exportObjects, exportSelections, exportConfig;
        MEASUREMENT_MODE measurementMode = MEASUREMENT_MODE.ERASE_ALL;
        boolean exportData;
        List<Integer> positions;
        int[] structures;
        List<Pair<String, int[]>> extractMeasurementDir = new ArrayList<>();
        MultipleException errors = new MultipleException();
        MasterDAO db;
        boolean ownDB;
        int[] taskCounter;
        double subtaskNumber=0, subtaskCounter =0;
        double preProcessingMemoryThreshold = 0.5;
        ProgressLogger ui;
        String selectionName;


        String extractDSFile, extractRawDSFile;
        List<FeatureExtractor.Feature> extractDSFeatures;
        List<String> extractDSSelections;
        int[] extractDSDimensions;
        int[] extractDSEraseTouchingContoursOC;
        boolean extractDSTracking;
        int extractDSSubsamplingFactor=1;
        int extractDSSubsamplingNumber=0;
        int extractDSSpatialDownsamplingFactor =1;
        SimpleBoundingBox extractDSRawBounds;
        Map<String, List<Integer>> extractDSRawPositionMapFrames;
        int[] extractDSRawChannels;
        int extractDSCompression = 4;
        ExtractZAxisParameter.ExtractZAxisConfig extractRawZAxis = new ExtractZAxisParameter.IMAGE3D();
        boolean extractByPosition;

        @Override
        public JSONObject toJSONEntry() {
            JSONObject res=  new JSONObject();
            res.put("dbName", dbName); 
            if (this.dir!=null) res.put("dir", dir); // put dbPath ?
            if (preProcess) res.put("preProcess", preProcess);
            if (segmentAndTrack) res.put("segmentAndTrack", segmentAndTrack);
            if (trackOnly) res.put("trackOnly", trackOnly);
            if (measurements) {
                res.put("measurements", measurements);
                res.put("measurementMode", measurementMode.toString());
            }
            if (exportPreProcessedImages) res.put("exportPreProcessedImages", exportPreProcessedImages);
            if (exportTrackImages) res.put("exportTrackImages", exportTrackImages);
            if (exportObjects) res.put("exportObjects", exportObjects);
            if (exportSelections) res.put("exportSelections", exportSelections);
            if (exportConfig) res.put("exportConfig", exportConfig);
            if (positions!=null) res.put("positions", JSONUtils.toJSONArray(positions));
            if (structures!=null) res.put("structures", JSONUtils.toJSONArray(structures));
            if (selectionName!=null) res.put("selection", selectionName);
            JSONArray ex = new JSONArray();
            for (Pair<String, int[]> p : extractMeasurementDir) {
                JSONObject o = new JSONObject();
                o.put("dir", p.key);
                o.put("s", JSONUtils.toJSONArray(p.value));
                ex.add(o);
            }
            if (!ex.isEmpty()) res.put("extractMeasurementDir", ex);
            if (extractDSFile!=null && extractDSSelections!=null && !extractDSSelections.isEmpty() && extractDSFeatures !=null && !extractDSFeatures.isEmpty()) {
                JSONObject extractDS = new JSONObject();
                extractDS.put("outputFile", extractDSFile);
                extractDS.put("compression", extractDSCompression);
                JSONArray extractDSSels = new JSONArray();
                for (String s : extractDSSelections) extractDSSels.add(s);
                extractDS.put("selections", extractDSSels);
                extractDS.put("dimensions", JSONUtils.toJSONArray(extractDSDimensions));
                JSONArray extractDSFeats = new JSONArray();
                for (FeatureExtractor.Feature feature: extractDSFeatures) {
                    JSONObject feat = new JSONObject();
                    feat.put("name", feature.getName());
                    feat.put("oc", feature.getObjectClass());
                    PluginParameter<FeatureExtractor> pp = new PluginParameter<>("FE", FeatureExtractor.class, feature.getFeatureExtractor(), false);
                    feat.put("feature", pp.toJSONEntry());
                    String selFilter = feature.getSelectionFilter();
                    if (selFilter!=null) feat.put("selectionFilter", selFilter);
                    extractDSFeats.add(feat);
                }
                extractDS.put("features", extractDSFeats);
                if (extractDSEraseTouchingContoursOC!=null && extractDSEraseTouchingContoursOC.length>0) extractDS.put("eraseTouchingContoursOC", JSONUtils.toJSONArray(extractDSEraseTouchingContoursOC));
                if (extractDSSubsamplingFactor>1) extractDS.put("extractDSSubsamplingFactor", extractDSSubsamplingFactor);
                if (extractDSSubsamplingNumber>1) extractDS.put("extractDSSubsamplingNumber", extractDSSubsamplingNumber);
                if (extractDSSpatialDownsamplingFactor>1) extractDS.put("extractDSSpatialDownsamplingFactor", extractDSSpatialDownsamplingFactor);
                extractDS.put("extractDSTracking", extractDSTracking);
                res.put("extractDataset", extractDS);
            }
            if (extractRawDSFile!=null && extractDSRawPositionMapFrames!=null && !extractDSRawPositionMapFrames.isEmpty() && extractDSRawChannels!=null) {
                JSONObject extractRawDS = new JSONObject();
                extractRawDS.put("outputFile", extractRawDSFile);
                extractRawDS.put("channels", JSONUtils.toJSONArray(extractDSRawChannels));
                extractRawDS.put("compression", extractDSCompression);
                if (extractDSRawBounds!=null) extractRawDS.put("bounds", extractDSRawBounds.toJSONEntry());
                JSONObject pf = new JSONObject();
                for (String p : extractDSRawPositionMapFrames.keySet()) pf.put(p, JSONUtils.toJSONArray(extractDSRawPositionMapFrames.get(p)));
                extractRawDS.put("positionMapFrame", pf);
                extractRawDS.put("extractZAxis", extractRawZAxis.toJSONEntry());
                res.put("extractRawDataset", extractRawDS);
            }
            return res;
        }

        public Task duplicate() {
            Task t = new Task();
            t.initFromJSONEntry(toJSONEntry());
            return t;
        }

        public void initFromJSONEntry(JSONObject data) {
            if (data==null) return;
            this.dbName = (String)data.getOrDefault("dbName", "");
            if (data.containsKey("dir")) {
                dir = (String)data.get("dir");
                if (!new File(dir).exists()) dir=null;
            }
            if (dir==null) dir = ExperimentSearchUtils.searchForLocalDir(dbName);
            this.preProcess = (Boolean)data.getOrDefault("preProcess", false);
            this.segmentAndTrack = (Boolean)data.getOrDefault("segmentAndTrack", false);
            this.trackOnly = (Boolean)data.getOrDefault("trackOnly", false);
            this.measurements = (Boolean)data.getOrDefault("measurements", false);
            this.measurementMode = MEASUREMENT_MODE.valueOf((String)data.getOrDefault("measurementMode", MEASUREMENT_MODE.ERASE_ALL.toString()));
            this.exportPreProcessedImages = (Boolean)data.getOrDefault("exportPreProcessedImages", false);
            this.exportTrackImages = (Boolean)data.getOrDefault("exportTrackImages", false);
            this.exportObjects = (Boolean)data.getOrDefault("exportObjects", false);
            this.exportSelections = (Boolean)data.getOrDefault("exportSelections", false);
            this.exportConfig = (Boolean)data.getOrDefault("exportConfig", false);
            if (exportPreProcessedImages || exportTrackImages || exportObjects || exportSelections || exportConfig) exportData= true;
            if (data.containsKey("selection")) selectionName = (String)data.get("selection");
            if (data.containsKey("positions")) positions = JSONUtils.fromIntArrayToList((JSONArray)data.get("positions"));
            if (data.containsKey("structures")) structures = JSONUtils.fromIntArray((JSONArray)data.get("structures"));
            if (data.containsKey("extractMeasurementDir")) {
                extractMeasurementDir = new ArrayList<>();
                JSONArray ex = (JSONArray)data.get("extractMeasurementDir");
                for (Object o : ex) {
                    JSONObject jo = (JSONObject)(o);
                    extractMeasurementDir.add(new Pair(jo.get("dir"), JSONUtils.fromIntArray((JSONArray)jo.get("s"))));
                }
            }
            if (data.containsKey("extractDataset")) {
                JSONObject extractDS = (JSONObject)data.get("extractDataset");
                extractDSFile = (String)extractDS.get("outputFile");
                extractDSCompression = ((Number)extractDS.get("compression")).intValue();
                JSONArray sels = (JSONArray)extractDS.get("selections");
                extractDSSelections = new ArrayList<>(sels.size());
                for (Object s : sels) extractDSSelections.add((String)s);
                JSONArray feats = (JSONArray)extractDS.get("features");
                extractDSFeatures = new ArrayList<>(feats.size());
                for (Object f : feats) {
                    JSONObject feat = (JSONObject)f;
                    PluginParameter<FeatureExtractor> pp = new PluginParameter<>("FE", FeatureExtractor.class, false);
                    pp.initFromJSONEntry(feat.get("feature"));
                    extractDSFeatures.add(new FeatureExtractor.Feature(
                            (String)feat.get("name"),
                            pp.instantiatePlugin(),
                            ((Number)feat.get("oc")).intValue(),
                            feat.containsKey("selectionFilter")?(String)feat.get("selectionFilter"):null));
                }
                extractDSDimensions = JSONUtils.fromIntArray((JSONArray)extractDS.get("dimensions"));
                if (extractDS.containsKey("eraseTouchingContoursOC")) extractDSEraseTouchingContoursOC = JSONUtils.fromIntArray((JSONArray)extractDS.get("eraseTouchingContoursOC"));
                else extractDSEraseTouchingContoursOC = new int[0];
                if (extractDS.containsKey("extractDSSubsamplingFactor")) extractDSSubsamplingFactor = ((Number)extractDS.get("extractDSSubsamplingFactor")).intValue();
                if (extractDS.containsKey("extractDSSubsamplingNumber")) extractDSSubsamplingNumber = ((Number)extractDS.get("extractDSSubsamplingNumber")).intValue();
                if (extractDS.containsKey("extractDSSpatialDownsamplingFactor")) extractDSSpatialDownsamplingFactor = ((Number)extractDS.get("extractDSSpatialDownsamplingFactor")).intValue();
                if (extractDS.containsKey("extractDSTracking")) extractDSTracking = ((Boolean)extractDS.get("extractDSTracking"));
            }
            if (data.containsKey("extractRawDataset")) {
                JSONObject extractRawDS = (JSONObject)data.get("extractRawDataset");
                extractRawDSFile = (String)extractRawDS.get("outputFile");
                extractDSCompression = ((Number)extractRawDS.get("compression")).intValue();
                extractDSRawChannels = JSONUtils.fromIntArray((JSONArray)extractRawDS.get("channels"));
                if (extractRawDS.containsKey("bounds")) {
                    extractDSRawBounds = new SimpleBoundingBox();
                    extractDSRawBounds.initFromJSONEntry(extractRawDS.get("bounds"));
                }
                extractRawZAxis = ExtractZAxisParameter.getConfigFromJSONEntry(extractRawDS.getOrDefault("extractZAxis", ExtractZAxisParameter.ExtractZAxis.IMAGE3D.toString()));
                JSONObject pf = (JSONObject)extractRawDS.get("positionMapFrame");
                extractDSRawPositionMapFrames = new HashMap<>();
                for (Object k: pf.keySet()) extractDSRawPositionMapFrames.put((String)k, JSONUtils.fromIntArrayToList((JSONArray)pf.get(k)));
            }
            return;
        }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 59 * hash + Objects.hashCode(this.dbName);
        hash = 59 * hash + Objects.hashCode(this.dir);
        hash = 59 * hash + (this.preProcess ? 1 : 0);
        hash = 59 * hash + (this.segmentAndTrack ? 1 : 0);
        hash = 59 * hash + (this.trackOnly ? 1 : 0);
        hash = 59 * hash + (this.measurements ? 1 : 0);
        hash = 59 * hash + (this.exportPreProcessedImages ? 1 : 0);
        hash = 59 * hash + (this.exportTrackImages ? 1 : 0);
        hash = 59 * hash + (this.exportObjects ? 1 : 0);
        hash = 59 * hash + (this.exportSelections ? 1 : 0);
        hash = 59 * hash + (this.exportConfig ? 1 : 0);
        hash = 59 * hash + (this.exportData ? 1 : 0);
        if (selectionName!=null) hash = 59 * hash + selectionName.hashCode();
        hash = 59 * hash + Objects.hashCode(this.positions);
        hash = 59 * hash + Arrays.hashCode(this.structures);
        hash = 59 * hash + Objects.hashCode(this.extractMeasurementDir);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Task other = (Task) obj;
        if (this.preProcess != other.preProcess) {
            return false;
        }
        if (this.segmentAndTrack != other.segmentAndTrack) {
            return false;
        }
        if (this.trackOnly != other.trackOnly) {
            return false;
        }
        if (this.measurements != other.measurements) {
            return false;
        }
        if (this.exportPreProcessedImages != other.exportPreProcessedImages) {
            return false;
        }
        if (this.exportTrackImages != other.exportTrackImages) {
            return false;
        }
        if (this.exportObjects != other.exportObjects) {
            return false;
        }
        if (this.exportSelections != other.exportSelections) {
            return false;
        }
        if (this.exportConfig != other.exportConfig) {
            return false;
        }
        if (this.exportData != other.exportData) {
            return false;
        }
        if (!Objects.equals(this.dbName, other.dbName)) {
            return false;
        }
        if (!Objects.equals(this.dir, other.dir)) {
            return false;
        }
        if (!Objects.equals(this.positions, other.positions)) {
            return false;
        }
        if (!Arrays.equals(this.structures, other.structures)) {
            return false;
        }
        if (!Objects.equals(this.extractMeasurementDir, other.extractMeasurementDir)) {
            return false;
        }
        if (!Objects.equals(this.selectionName, other.selectionName)) {
            return false;
        }
        return true;
    }

    @Override
    public void setUI(ProgressLogger ui) {
        if (ui==null) this.ui=null;
        else {
            if (ui.equals(this.ui)) return;
            this.ui=ui;
        }
    }
    public Task() {
        setUI(Core.getProgressLogger());
        ownDB = true;
    }
    public Task(MasterDAO db) {
        setUI(Core.getProgressLogger());
        this.db=db;
        this.dbName=db.getDBName();
        this.dir=db.getDatasetDir().toFile().getAbsolutePath();
        ownDB = false;
    }
    public Task(String dbName) {
        this(dbName, null);
    }
    public Task(String dbName, String dir) {
        this();
        this.dbName=dbName;
        if (dir!=null && !"".equals(dir)) this.dir=dir;
        else this.dir = ExperimentSearchUtils.searchForLocalDir(dbName);
    }
    public Task setDBName(String dbName) {
        if (dbName!=null && dbName.equals(this.dbName)) return this;
        if (db!=null) {
            if (ownDB) {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
            }
            this.db=null;
        }
        this.ownDB = false;
        this.dbName=dbName;
        return this;
    }
    public Task setDir(String dir) {
        if (dir!=null && dir.equals(this.dir)) return this;
        if (db!=null) {
            if (ownDB) {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
            }
            this.db=null;
        }
        this.ownDB = false;
        this.dir=dir;
        return this;
    }

    public List<Pair<String, Throwable>> getErrors() {return errors.getExceptions();}
    public MasterDAO getDB() {
        initDB();
        return db;
    }

    public Task setDB(MasterDAO db) {
        if (db!=null && ownDB) {
            this.db.unlockPositions();
            this.db.unlockConfiguration();
            this.db.clearCache(true, true, true);
        }
        this.db = db;
        ownDB = false;
        return this;
    }

    public String getDir() {
        return dir;
    }

    public String getExtractDSFile() {
        return extractDSFile;
    }

    public String getExtractRawDSFile() {
        return extractRawDSFile;
    }

    public List<FeatureExtractor.Feature> getExtractDSFeatures() {
        if (db!=null && extractDSFeatures !=null) { // set Experiment as parent in case needed
            extractDSFeatures.stream()
                .flatMap(f-> f.getFeatureExtractor().getParameters()==null? Stream.empty() : Arrays.stream(f.getFeatureExtractor().getParameters()))
                .forEach(p->p.setParent(db.getExperiment()));
        }
        return extractDSFeatures;
    }

    public List<String> getExtractDSSelections() {
        return extractDSSelections;
    }

    public int[] getExtractDSDimensions() {
        return extractDSDimensions;
    }
    public int[] getExtractDSEraseTouchingContoursOC() {
        return extractDSEraseTouchingContoursOC;
    }
    public boolean isExtractDSTracking() {return extractDSTracking;}
    public BoundingBox getExtractRawDSBounds() { return extractDSRawBounds; }
    public ExtractZAxisParameter.ExtractZAxisConfig getExtractRawZAxis() {return extractRawZAxis; }
    public Map<String, List<Integer>> getExtractRawDSFrames() {return extractDSRawPositionMapFrames;}
    public int[] getExtractRawDSChannels() {return extractDSRawChannels;}
    public int getExtractDSCompression() {return extractDSCompression;}

    public int getExtractDSSubsamplingFactor() {
        return extractDSSubsamplingFactor;
    }
    public int getExtractDSSubsamplingNumber() {
        return extractDSSubsamplingNumber<=0 ? extractDSSubsamplingFactor : extractDSSubsamplingNumber;
    }
    public int getExtractDSSpatialDownsamplingFactor() {
        return extractDSSpatialDownsamplingFactor;
    }

    public Task setAllActions() {
        this.preProcess=true;
        this.segmentAndTrack=true;
        this.measurements=true;
        this.trackOnly=false;
        return this;
    }
    public Task setActions(boolean preProcess, boolean segment, boolean track, boolean measurements) {
        this.preProcess=preProcess;
        this.segmentAndTrack=segment;
        if (segmentAndTrack) trackOnly = false;
        else trackOnly = track;
        this.measurements=measurements;
        return this;
    }
    public Task setMeasurementMode(MEASUREMENT_MODE mode) {
        this.measurementMode=mode;
        return this;
    }
    public boolean isPreProcess() {
        return preProcess;
    }

    public boolean isSegmentAndTrack() {
        return segmentAndTrack;
    }

    public boolean isTrackOnly() {
        return trackOnly;
    }

    public boolean isMeasurements() {
        return measurements;
    }

    public Task setExtractDS(String extractDSFile, List<String> extractDSSelections, List<FeatureExtractor.Feature> extractDS, int[] dimensions, int[] eraseTouchingContoursOC, boolean tracking, int spatialDownSamplingFactor, int subsamplingFactor, int subsamplingNumber, int compression) {
        this.extractDSFile = extractDSFile;
        this.extractDSSelections = extractDSSelections;
        this.extractDSFeatures = extractDS;
        this.extractDSDimensions = dimensions;
        this.extractDSEraseTouchingContoursOC = eraseTouchingContoursOC;
        this.extractDSSpatialDownsamplingFactor = spatialDownSamplingFactor;
        this.extractDSSubsamplingFactor = subsamplingFactor;
        this.extractDSSubsamplingNumber = subsamplingNumber;
        this.extractDSCompression = compression;
        this.extractDSTracking = tracking;
        return this;
    }

    public boolean getExtractDSTracking() {
            return extractDSTracking;
    }

    public Task setExtractRawDS(String extractDSFile, int[] channels, SimpleBoundingBox bounds, ExtractZAxisParameter.ExtractZAxisConfig zAxis, Map<String, List<Integer>> positionMapFrames, int compression) {
        this.extractRawDSFile = extractDSFile;
        this.extractDSRawPositionMapFrames = positionMapFrames;
        this.extractDSRawBounds = bounds;
        this.extractDSRawChannels = channels;
        this.extractRawZAxis = zAxis;
        this.extractDSCompression = compression;
        return this;
    }
    public Task setExtractDSCompression(int compression) {
        this.extractDSCompression = compression;
        return this;
    }

    /*public Task setExportData(boolean preProcessedImages, boolean trackImages, boolean objects, boolean config, boolean selections) {
        this.exportPreProcessedImages=preProcessedImages;
        this.exportTrackImages=trackImages;
        this.exportObjects=objects;
        this.exportConfig=config;
        this.exportSelections=selections;
        if (preProcessedImages || trackImages || objects || config || selections) exportData= true;
        return this;
    }*/

    public Task setPositions(int... positions) {
        if (positions!=null && positions.length>0) this.positions=Utils.toList(positions);
        return this;
    }
    public Task unsetPositions(int... positions) {
        initDB();
        if (this.positions==null) this.positions=Utils.toList(ArrayUtil.generateIntegerArray(db.getExperiment().getPositionCount()));
        for (int p : positions) this.positions.remove((Integer)p);
        logger.debug("positions: {} ({})", this.positions, Utils.transform(this.positions, i->db.getExperiment().getPositionsAsString()[i]));
        return this;
    }

    @Override
    public void initDB() {
        if (db==null) {
            synchronized (this) {
                if (db == null) {
                    if (dir==null) throw new RuntimeException("XP not found");
                    if (!"localhost".equals(dir) && new File(dir).exists()) db = MasterDAOFactory.getDAO(dbName, dir);
                }
            }
        }
    }

    public Task setPositions(String... positions) {
        if (positions!=null && positions.length>0) {
            boolean initDB = db==null;
            if (initDB) initDB();
            this.positions=new ArrayList<>(positions.length);
            for (int i = 0; i<positions.length; ++i) this.positions.add(db.getExperiment().getPositionIdx(positions[i]));
            if (initDB) {  // only set to null if no db was set before, to be able to run on GUI db without lock issues
                db.unlockConfiguration();
                db.unlockPositions();
                db.clearCache(true, true, true);
                db=null;
            };
        }
        return this;
    }

    public Task setStructures(int... structures) {
        this.structures=structures;
        Arrays.sort(structures);
        return this;
    }
    public Task setSelection(String selectionName) {
        this.selectionName = selectionName;
        return this;
    }

    public Task addExtractMeasurementDir(String dir, int... extractStructures) {
        if (extractStructures==null || extractStructures.length==0) {
            ensurePositionAndObjectClasses(false, true);
            for (int s : structures) this.extractMeasurementDir.add(new Pair<>(dir, new int[]{s}));
        } else this.extractMeasurementDir.add(new Pair<>(dir, extractStructures));
        if (extractMeasurementDir.stream().noneMatch(p-> p.key.equals(dir) && Arrays.stream(p.value).anyMatch(s->s==-1))) {
            this.extractMeasurementDir.add(new Pair<>(dir, new int[]{-1}));
        }
        return this;
    }

    public void setExtractByPosition(boolean extractByPosition) {
        this.extractByPosition = extractByPosition;
    }

    private void ensurePositionAndObjectClasses(boolean positions, boolean structures) {
        if ((!positions || this.positions!=null) && (!structures || this.structures!=null)) return;
        initDB();
        if (positions && this.positions==null) this.positions = Utils.toList(ArrayUtil.generateIntegerArray(db.getExperiment().getPositionCount()));
        if (structures && this.structures==null) this.structures = ArrayUtil.generateIntegerArray(db.getExperiment().getStructureCount());
    }

    public boolean isValid() {
        initDB(); // read only by default
        Map<String, MLModelFileParameter> dlModelToDownload= new HashMap<>();
        Consumer<List<MLModelFileParameter>> analyzeDLModelFP = l -> l.stream()
                .filter(mfp -> {
                    try {
                        return mfp.needsToDownloadModel();
                    } catch (IOException e) {
                        errors.addExceptions(new Pair<>(mfp.getName(), e));
                        return false;
                    }
                })
                .forEach(m -> dlModelToDownload.put(m.getModelFilePath(), m));
        if (db.getExperiment()==null) {
            errors.addExceptions(new Pair(dbName, new Exception("DB: "+ dbName+ " not found")));
            printErrors();
            if (ownDB) {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
                db=null;
            }
            return false;
        }
        if (structures!=null) checkArray(structures, 0, db.getExperiment().getStructureCount(), "Invalid structure: ");
        if (positions!=null) checkArray(positions, db.getExperiment().getPositionCount(), "Invalid position: ");
        if (preProcess) { // compare pre processing to template
            ensurePositionAndObjectClasses(true, false);
            PreProcessingChain template = db.getExperiment().getPreProcessingTemplate();
            List<Integer> posWithDifferentPP = positions.stream().filter(p -> !template.getTransformations().sameContent(db.getExperiment().getPosition(p).getPreProcessingChain().getTransformations())).collect(Collectors.toList());
            if (!posWithDifferentPP.isEmpty()) publish("Warning: the pre-processing pipeline of the following position differs from template: "+Utils.toStringArrayShort(posWithDifferentPP));
        }
        if (selectionName!=null) {
            if (preProcess || exportPreProcessedImages || exportTrackImages || exportObjects) errors.addExceptions(new Pair(dbName, new Exception("Invalid action to run with selection")));
            else {
                Selection sel = db.getSelectionDAO().getOrCreate(selectionName, false);
                if (sel.isEmpty()) errors.addExceptions(new Pair<>(dbName, new Exception("Empty selection")));
                else {
                    int selObjectClass = sel.getObjectClassIdx();
                    if (segmentAndTrack || trackOnly) { // check that parent object class of all object class is selection object class
                        if (structures == null)
                            errors.addExceptions(new Pair<>(dbName, new Exception("One of the object class is not direct children of selection object class")));
                        else {
                            for (int objectClass : structures) {
                                if (!db.getExperiment().experimentStructure.isDirectChildOf(selObjectClass, objectClass))
                                    errors.addExceptions(new Pair<>(dbName, new Exception("One of the object class is not direct children of selection object class")));
                            }
                        }
                    }
                }
            }
        }
        // check files
        for (Pair<String, int[]> e : extractMeasurementDir) {
            String exDir = e.key==null? db.getDatasetDir().toFile().getAbsolutePath() : e.key;
            File f= new File(exDir);
            if (!f.exists()) errors.addExceptions(new Pair<>(dbName, new Exception("File: "+ exDir+ " not found")));
            else if (!f.isDirectory()) errors.addExceptions(new Pair<>(dbName, new Exception("File: "+ exDir+ " is not a directory")));
            else if (e.value!=null) checkArray(e.value, -1, db.getExperiment().getStructureCount(), "Extract structure for dir: "+e.value+": Invalid structure: ");
        }
        if (!measurements && !preProcess && !segmentAndTrack && ! trackOnly && extractMeasurementDir.isEmpty() && !exportData && extractDSFile==null && extractRawDSFile==null) errors.addExceptions(new Pair(dbName, new Exception("No action to run!")));
        // check parametrization
        if (preProcess) {
            ensurePositionAndObjectClasses(true, false);
            for (int p : positions) {
                if (!db.getExperiment().getPosition(p).isValid()) errors.addExceptions(new Pair<>(dbName, new Exception("Configuration error @ Position: "+ db.getExperiment().getPosition(p).getName())));
                // check dl model is on disk
                List<MLModelFileParameter> dlModelFP = ParameterUtils.getParameterByClass(db.getExperiment().getPosition(p), MLModelFileParameter.class, true);
                analyzeDLModelFP.accept(dlModelFP);
            }
        }
        if (segmentAndTrack || trackOnly) {
            ensurePositionAndObjectClasses(false, true);
            for (int s : structures) {
                if (!db.getExperiment().getStructure(s).isValid()) errors.addExceptions(new Pair<>(dbName, new Exception("Configuration error @ Object Class: "+ db.getExperiment().getStructure(s).getName())));
                List<MLModelFileParameter> dlModelFP = ParameterUtils.getParameterByClass(db.getExperiment().getStructure(s), MLModelFileParameter.class, true);
                analyzeDLModelFP.accept(dlModelFP);
            }
        }
        if (measurements) {
            if (!db.getExperiment().getMeasurements().isValid()) errors.addExceptions(new Pair<>(dbName, new Exception("Configuration error @ Measurements: ")));
        }
        for (Pair<String, Throwable> e : errors.getExceptions()) publish("Invalid Task Error @"+e.key+" "+(e.value==null?"null":e.value.toString()));

        // dataset extraction
        if (extractDSFile!=null || extractDSFeatures!=null || extractDSSelections!=null || extractDSDimensions!=null) {
            if (extractDSDimensions==null || extractDSDimensions.length!=2) {
                errors.addExceptions(new Pair(dbName, new Exception("Invalid extract dimensions:"+ Utils.toStringArray(extractDSDimensions))));
            }
            if (extractDSFeatures==null || extractDSFeatures.isEmpty()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No features to extract")));
            if (extractDSFeatures.stream().anyMatch(f->f.getName()==null || f.getName().isEmpty())) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid features names")));
            if (extractDSFeatures.stream().anyMatch(f->f.getObjectClass()<0)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid features object class")));
            if (extractDSFeatures.stream().anyMatch(f->f.getFeatureExtractor()==null)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid features type")));
            if (extractDSFeatures.stream().map(FeatureExtractor.Feature::getName).distinct().count()<extractDSFeatures.size()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Duplicate feature name")));
            if (extractDSSelections==null || extractDSSelections.isEmpty()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No selection to extract from")));
            if (extractDSSelections.stream().anyMatch(s->db.getSelectionDAO().getOrCreate(s, false).isEmpty())) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("One or several selection is empty or absent")));
        }
        // raw dataset extraction
        if (extractRawDSFile!=null || extractDSRawChannels!=null || extractDSRawPositionMapFrames!=null ) {
            if (extractDSRawPositionMapFrames.isEmpty() || extractDSRawPositionMapFrames.values().iterator().next().isEmpty()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No frames to extract")));
            int nChannels = db.getExperiment().getChannelImageCount(false);
            if (extractDSRawChannels == null || extractDSRawChannels.length==0) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No channel images to extract")));
            else {
                for (int c=0;c<extractDSRawChannels.length; ++c) if (extractDSRawChannels[c]>=nChannels) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid channel")));
            }
            if (extractDSRawBounds!=null && (extractDSRawBounds.xMin()<0 || extractDSRawBounds.yMin()<0 || extractDSRawBounds.zMin()<0) ) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid bounds for raw dataset extraction")));
        }
        if (!dlModelToDownload.isEmpty()) {
            StringBuilder question = new StringBuilder();
            if (ui.isGUI()) {
                question.append("DL Model need to be downloaded:");
                dlModelToDownload.forEach((path, dlModelFP) -> {
                    try {
                        dlModelFP.getLargeFileGist();
                        question.append('\n').append(path).append(" (").append(String.format("%.2f", dlModelFP.getLargeFileGist().getSizeMb())).append("Mb)");
                    } catch (Exception e) {
                        errors.addExceptions(new Pair<>("ModelFile:"+path, e));
                    }
                });
                question.append('\n').append("Proceed ? ");
            }
            boolean download = !ui.isGUI() || Utils.promptBoolean(question.toString(), ui instanceof Component ? (Component) ui : null);
            if (download) {
                dlModelToDownload.forEach( (path, dlModelFP) -> {
                    try{if (ui!=null) ui.setMessage("Downloading: "+path + " ("+ String.format("%.2f", dlModelFP.getLargeFileGist().getSizeMb()) + ')' );}catch(IOException e){}
                    try {
                        dlModelFP.getModelFile();
                    } catch (IOException e) {
                        errors.addExceptions(new Pair<>("Task validity check", e));
                    }
                } );
            } else {
                errors.addExceptions(new Pair<>("Task validity check", new IOException("Missing dl model files")));
            }
        }
        logger.info("task : {}, isValid: {}, config read only {} own db: {}", dbName, errors.isEmpty(), db.isConfigurationReadOnly(), ownDB);
        if (ownDB) {
            db.unlockPositions();
            db.unlockConfiguration();
            db.clearCache(true, true, true);
            db=null;
        } else db.clearCache(false, false,true);
        return errors.isEmpty();
    }
    private void checkArray(int[] array, int minValueIncl, int maxValueExcl, String message) {
        if (array.length==0) return;
        if (array[ArrayUtil.max(array)]>=maxValueExcl) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException(message + array[ArrayUtil.max(array)]+ " not found, max value: "+maxValueExcl)));
        if (array[ArrayUtil.min(array)]<minValueIncl) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException(message + array[ArrayUtil.min(array)]+ " not found")));
    }
    private void checkArray(List<Integer> array, int maxValue, String message) {
        if (array==null || array.isEmpty()) {
            errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException(message)));
            return;
        }
        if (Collections.max(array)>=maxValue) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException(message + Collections.max(array)+ " not found, max value: "+maxValue)));
        if (Collections.min(array)<0) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException(message + Collections.min(array)+ " not found")));
    }
    public void printErrors() {
        if (!errors.isEmpty()) logger.error("Errors for Task: {}", toString());
        for (Pair<String, ? extends Throwable> e : errors.getExceptions()) logger.error(e.key, e.value);
    }
    public void printErrorsTo(ProgressLogger ui) {
        if (!errors.isEmpty()) ui.setMessage("Errors for Task: " + this);
        for (Pair<String, ? extends Throwable> e : errors.getExceptions()) ui.setMessage(e.key + ": " + e.value);
    }
    public int countSubtasks() {
        initDB();
        ensurePositionAndObjectClasses(true, true);

        Selection selection = selectionName==null ? null : db.getSelectionDAO().getOrCreate(selectionName, false);
        Predicate<String> selFilter = selectionName==null ? p->true : p->selection.getAllPositions().contains(p);
        Function<Integer, String> posIdxNameMapper = pIdx -> db.getExperiment().getPosition(pIdx).getName();
        int positionsToProcess = (int)positions.stream().map(posIdxNameMapper).filter(selFilter).count();

        int count=0;
        // preProcess:
        if (preProcess) count += positionsToProcess;
        if (this.segmentAndTrack || this.trackOnly) count += positionsToProcess * structures.length + 1; // +1 for storing objects
        if (this.measurements) {
            int nCallOC = db.getExperiment().getMeasurementsByCallStructureIdx().size();
            count += positionsToProcess * (nCallOC+1); // +1 for upsert
        }
        if (extractByPosition) count+=extractMeasurementDir.size() * positionsToProcess;
        else count+=extractMeasurementDir.size();
        if (this.exportObjects || this.exportPreProcessedImages || this.exportTrackImages) count+=positionsToProcess;
        if (extractDSFile!=null && extractDSFeatures!=null && extractDSSelections!=null) {
            ToIntFunction<String> countPosition = sName -> {
                Selection sel = db.getSelectionDAO().getOrCreate(sName, false);
                return sel.getAllPositions().size();
            };
            for (String sel : extractDSSelections) {
                logger.debug("count sub task: sel: {} = {} pos x {} feat", sel, countPosition.applyAsInt(sel), extractDSFeatures.size());
                count += extractDSFeatures.size() * countPosition.applyAsInt(sel);
            }
        }
        if (extractRawDSFile!=null) {
            count += extractDSRawPositionMapFrames.size();
        }
        return count;
    }
    public void setTaskCounter(int[] taskCounter) {
        this.taskCounter=taskCounter;
    }

    public void setPreprocessingMemoryThreshold(double preProcessingMemoryThreshold) {
        this.preProcessingMemoryThreshold=preProcessingMemoryThreshold;
    }

    public void runTask() {
        //if (ui!=null) ui.setRunning(true);
        publish("Run task: "+this.toString());
        initDB();
        logger.debug("configuration read only: {}", db.isConfigurationReadOnly());
        Core.freeDisplayMemory();
        publishMemoryUsage("Before processing");
        this.ensurePositionAndObjectClasses(true, true);
        Function<Integer, String> posIdxNameMapper = pIdx -> db.getExperiment().getPosition(pIdx).getName();
        Selection selection = selectionName==null ? null : db.getSelectionDAO().getOrCreate(selectionName, false);
        Predicate<String> selFilter = selectionName==null ? p->true : p->selection.getAllPositions().contains(p);
        List<String> positionsToProcess = positions.stream().map(posIdxNameMapper).filter(selFilter).collect(Collectors.toList());
        db.lockPositions(positionsToProcess.toArray(new String[0]));

        // check that all position to be processed are effectively locked
        if (preProcess || segmentAndTrack || trackOnly || measurements) {
            List<String> readOnlyPos = positionsToProcess.stream().filter(p -> db.getDao(p).isReadOnly()).collect(Collectors.toList());
            logger.debug("locked positions: {} / {}", positionsToProcess.size() - readOnlyPos.size(), positionsToProcess.size());
            if (!readOnlyPos.isEmpty()) {
                ui.setMessage("Some positions could not be locked and will not be processed: " + readOnlyPos);
                for (String p : readOnlyPos)
                    errors.addExceptions(new Pair<>(p, new RuntimeException("Locked position. Already used by another process?")));
                positionsToProcess.removeAll(readOnlyPos);
            }
        }
        boolean needToDeleteObjects = preProcess || segmentAndTrack;
        boolean deleteAll =  needToDeleteObjects && selection==null && structures.length==db.getExperiment().getStructureCount() && positionsToProcess.size()==db.getExperiment().getPositionCount();
        boolean canDeleteAll = IntStream.range(0, db.getExperiment().getStructureCount()).mapToObj(db.getExperiment()::getStructure).allMatch(s -> s.getProcessingPipelineParameter().isOnePluginSet());
        if (deleteAll) {
            if (canDeleteAll) {
                publish("deleting objects...");
                db.deleteAllObjects();
            }
        }
        boolean deleteAllPosition = needToDeleteObjects && selection==null && structures.length==db.getExperiment().getStructureCount() && !deleteAll && canDeleteAll;
        logger.info("Run task: db: {} preProcess: {}, segmentAndTrack: {}, trackOnly: {}, runMeasurements: {}, need to delete objects: {}, delete all: {}, delete all by field: {}", dbName, preProcess, segmentAndTrack, trackOnly, measurements, needToDeleteObjects, deleteAll, deleteAllPosition);
        if (this.taskCounter==null) this.taskCounter = new int[]{0, this.countSubtasks()};
        publish("number of subtasks: "+countSubtasks());
        if (preProcess || segmentAndTrack || trackOnly || measurements) {
            try {
                for (String position : positionsToProcess) {
                    try {
                        process(position, deleteAllPosition, selection, preProcessingMemoryThreshold);
                    } catch (MultipleException e) {
                        errors.addExceptions(e.getExceptions());
                    } catch (Throwable e) {
                        errors.addExceptions(new Pair("Error while processing: db: " + db.getDBName() + " pos: " + position, e));
                    } finally {
                        db.getExperiment().getPosition(position).freeMemoryImages(true, true);
                        db.getExperiment().getDLengineProvider().closeAllEngines();
                        Core.clearDiskBackedImageManagers();
                        db.clearCache(position);
                        if (db.getSelectionDAO() != null) db.getSelectionDAO().clearCache();
                        Core.freeDisplayMemory();
                        System.gc();
                        publishMemoryUsage("After clearing cache");
                    }
                }
            } catch (Throwable t) {
                publish("Error While Processing Positions");
                publishError(t);
                publishErrors();
            } finally {
                logger.debug("closing engines...");
                db.getExperiment().getDLengineProvider().closeAllEngines();
                logger.debug("engines closed!");
                logger.debug("clearing disk backed image manager...");
                Core.clearDiskBackedImageManagers();
                logger.debug("disk backed image manager cleared!");
            }
        }
        if (!extractMeasurementDir.isEmpty()) logger.debug("extracting meas...");
        for (Pair<String, int[]> e  : this.extractMeasurementDir) extractMeasurements(e.key==null?db.getDatasetDir().toFile().getAbsolutePath():e.key, e.value, positionsToProcess);
        //if (exportData) exportData();

        // extract dataset
        if (extractDSFile!=null && extractDSSelections!=null && !extractDSSelections.isEmpty() && extractDSFeatures!=null && !extractDSFeatures.isEmpty()) {
            // using reflection for now to avoid dependency
            try {
                Class clazz = Class.forName("bacmman.py_dataset.ExtractDatasetUtil");
                java.lang.reflect.Method m = clazz.getMethod("runTask", Task.class);
                m.invoke(null, this);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
                errors.addExceptions(new Pair<>("Dataset extraction", new RuntimeException("Could not extract dataset", e)));
            } catch (Throwable e) {
                errors.addExceptions(new Pair<>("Dataset extraction", e));
            }
        }
        // extract raw dataset
        if (extractRawDSFile!=null && extractDSRawChannels!=null && extractDSRawPositionMapFrames!=null && !extractDSRawPositionMapFrames.isEmpty()) {
            // using reflection for now to avoid dependency
            logger.debug("extracting raw dataset...");
            try {
                Class clazz = Class.forName("bacmman.py_dataset.ExtractDatasetUtil");
                java.lang.reflect.Method m = clazz.getMethod("runTaskRaw", Task.class);
                m.invoke(null, this);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
                errors.addExceptions(new Pair<>("Raw Dataset extraction", new RuntimeException("Could not extract dataset, missing bacmman-dl module", e)));
            } catch (Throwable e) {
                errors.addExceptions(new Pair<>("Raw Dataset extraction", e));
            }
        }
        logger.debug("unlocking positions...");
        if (ownDB) {
            db.unlockPositions(positionsToProcess.toArray(new String[0]));
            db.unlockConfiguration();
            db.clearCache(true, true, true);
            db=null;
        } else {
            logger.debug("clearing cache...");
            for (String position:positionsToProcess) db.clearCache(position);
            logger.debug("cache cleared...");
        }
    }

    public void flush(boolean errors) {
        if (db!=null) {
            if (ownDB) {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
                db=null;
            } else db.clearCache(false, false, true);
        }
        if (errors) {
            this.errors.getExceptions().clear();
        }
    }

    private void process(String position, boolean deleteAllPosition, Selection selection, double preProcessingMemoryThreshold) {
        publish("Dataset" + dbName+ " Position: "+position);
        logger.debug("position: {} delete all position: {}", position, deleteAllPosition);
        if (deleteAllPosition) db.getDao(position).erase();
        if (preProcess) {
            publish("Pre-Processing...");
            logger.info("Pre-Processing: DB: {}, Position: {}", dbName, position);
            try {
                Processor.preProcessImages(db.getExperiment().getPosition(position), db.getDao(position), !deleteAllPosition, preProcessingMemoryThreshold, this);
                boolean createRoot = true; //segmentAndTrack || trackOnly || generateTrackImages;
                if (createRoot) Processor.getOrCreateRootTrack(db.getDao(position)); // will set opened pre-processed images to root -> no need to open them once again in further steps
            } catch (IOException e) {
                if (db.getExperiment().getPosition(position).inputImagesInstantiated()) db.getExperiment().getPosition(position).getInputImages().deleteFromDAO(); // erase pre-processed images that where temporarily saved
                throw new RuntimeException(e);
            } finally {
                db.getExperiment().getPosition(position).freeMemoryImages(true, true);
                System.gc();
                incrementProgress();
                publishMemoryUsage("After PreProcessing:");
            }

        }
        
        if ((segmentAndTrack || trackOnly)) {
            publish("Processing...");
            if (selection==null) {
                int[] structuresToDelete = IntStream.of(structures).filter(s -> db.getExperiment().getStructure(s).getProcessingPipelineParameter().isOnePluginSet()).toArray();
                deleteObjects(db.getDao(position), structuresToDelete);
            }
            List<SegmentedObject> root = null;
            try {
                root = getOrCreateRootTrack(db.getDao(position));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (int s : structures) { // TODO take code from processor
                publish("Processing object class: "+s);
                try {
                    executeProcessingScheme(root, s, trackOnly, selection!=null, selection, this);
                } catch (MultipleException e) {
                    errors.addExceptions(e.getExceptions());
                } catch (Throwable e) {
                    errors.addExceptions(new Pair<>("Error while processing: db: "+db.getDBName()+" pos: "+position+" structure: "+s, e));
                }
                incrementProgress();
                //db.getDao(position).applyOnAllOpenedObjects(o->{if (o.hasRegion()) o.getRegion().clearVoxels();}); // possible memory leak at this stage : list of voxels of big objects -> no necessary for further processing.
                // TODO : when no more processing with direct parent as root: get all images of direct root children & remove images from root
                System.gc();
                publishMemoryUsage("After Processing structure:"+s);
            }
            publishMemoryUsage("After Processing:");
        }
        
        if (measurements) {
            publish("Measurements...");
            logger.info("Measurements: DB: {}, Position: {}", dbName, position);
            Processor.performMeasurements(db.getDao(position), measurementMode, selection, this);
            incrementProgress();
            //publishMemoryUsage("After Measurements");
        }
    }
    public void publishMemoryUsage(String message) {
        publish(message+Utils.getMemoryUsage());
    }
    public void extractMeasurements(String dir, int[] structures, List<String > positions) {

        publish("extracting measurements from object class: "+Utils.toStringArray(structures));

        Map<Integer, String[]> keys = db.getExperiment().getAllMeasurementNamesByStructureIdx(MeasurementKeyObject.class, structures);
        logger.debug("keys: {}", Utils.toStringList(keys.entrySet(), e -> e.getKey()+"="+ Arrays.toString(e.getValue())));
        Selection sel = selectionName == null ? null : db.getSelectionDAO().getOrCreate(selectionName, false);
        if (extractByPosition) {
            for (String p : positions) {
                String file = Paths.get(dir, db.getDBName() + Utils.toStringArray(structures, "_", "", "_") + "_p_"+p + ".csv").toString();
                publish("measurements will be extracted to: " + file);
                MeasurementExtractor.extractMeasurementObjects(db, file, Collections.singletonList(p), sel, keys);
                incrementProgress();
            }
        } else {
            String file = Paths.get(dir, db.getDBName() + Utils.toStringArray(structures, "_", "", "_") + ".csv").toString();
            publish("measurements will be extracted to: " + file);
            MeasurementExtractor.extractMeasurementObjects(db, file, positions, sel, keys);
            incrementProgress();
        }

    }
    /*public void exportData() {
        try {
            String file = db.getDir().resolve(db.getDBName()+"_dump.zip").toString();
            ZipWriter w = new ZipWriter(file);
            if (exportObjects || exportPreProcessedImages || exportTrackImages) {
                ImportExportJSON.exportPositions(w, db, exportObjects, exportPreProcessedImages, exportTrackImages , getPositions(), this);
            }
            if (exportConfig) ImportExportJSON.exportConfig(w, db);
            if (exportSelections) ImportExportJSON.exportSelections(w, db);
            w.close();
        } catch (Exception e) {
            publish("Error while dumping");
            this.errors.addExceptions(new Pair(this.dbName, e));
        }
    }*/
    private List<String> getPositions() {
        this.ensurePositionAndObjectClasses(true, false);
        List<String> res = new ArrayList<>(positions.size());
        for (int i : positions) res.add(db.getExperiment().getPosition(i).getName());
        return res;
    }
    @Override public String toString() {
        String sep = "; " ;
        StringBuilder sb = new StringBuilder();
        Runnable addSep = () -> {if (sb.length()>0) sb.append(sep);};
        sb.append("db:").append(dbName);
        if (structures!=null) {
            addSep.run();
            sb.append("structures:").append(ArrayUtil.toString(structures));
        }
        if (positions!=null) {
            addSep.run();
            sb.append("positions:").append(Utils.toStringArrayShort(positions));
        }
        if (selectionName!=null) {
            addSep.run();
            sb.append("selection:").append(selectionName);
        }
        if (preProcess) sb.append("preProcess");
        if (segmentAndTrack) {
            addSep.run();
            sb.append("segmentAndTrack");
        } else if (trackOnly) {
            addSep.run();
            sb.append("trackOnly");
        }
        if (measurements) {
            addSep.run();
            sb.append("measurements[").append(measurementMode.toString()).append("]");
        }

        if (!extractMeasurementDir.isEmpty()) {
            addSep.run();
            sb.append("Extract: ");
            for (Pair<String, int[]> p : this.extractMeasurementDir) sb.append((p.key==null?dir:p.key)).append('=').append(p.value==null ? "all" : ArrayUtil.toString(p.value));
        }
        if (exportData) {
            if (exportPreProcessedImages) {
                addSep.run();
                sb.append("ExportPPImages");
            }
            if (exportTrackImages) {
                addSep.run();
                sb.append("ExportTrackImages");
            }
            if (exportObjects) {
                addSep.run();
                sb.append("ExportObjects");
            }
            if (exportConfig) {
                addSep.run();
                sb.append("ExportConfig");
            }
            if (exportSelections) {
                addSep.run();
                sb.append("ExportSelection");
            }
        }
        // extract Dataset
        if (extractDSFile!=null) {
            addSep.run();
            sb.append("ExtractDSFile:").append(extractDSFile);
            if (extractDSTracking) {
                addSep.run();
                sb.append("ExtractDSTracking:").append(extractDSTracking);
            }
            if (extractDSSpatialDownsamplingFactor>1) {
                addSep.run();
                sb.append("ExtractDSSpatialDownsamplingFactor:").append(this.extractDSSpatialDownsamplingFactor);
            }
            if (extractDSSubsamplingFactor>1) {
                addSep.run();
                sb.append("ExtractDSSubsamplingFactor:").append(this.extractDSSubsamplingFactor);
                addSep.run();
                sb.append("ExtractDSSubsamplingNumber:").append(this.extractDSSubsamplingNumber);
            }
            addSep.run();
            sb.append("ExtractDSCompression:").append(this.extractDSCompression);
        }
        if (extractDSSelections!=null) {
            addSep.run();
            sb.append("ExtractDSSelections:").append(Utils.toStringList(extractDSSelections));
        }
        if (extractDSFeatures!=null) {
            addSep.run();
            sb.append("ExtractDSFeatures:").append(Utils.toStringList(extractDSFeatures, feat->{
                PluginParameter<FeatureExtractor> pp = new PluginParameter<>("FE", FeatureExtractor.class, feat.getFeatureExtractor(), false);
                return feat.getName()+":oc="+feat.getObjectClass()+"("+pp.toJSONEntry().toJSONString()+")"+(feat.getSelectionFilter()!=null?"selectionFilter:"+feat.getSelectionFilter() : "");
            }));
        }
        if (extractDSDimensions!=null) {
            addSep.run();
            sb.append("ExtractDSDimensions:").append(Utils.toStringArray(extractDSDimensions));
        }
        if (extractDSEraseTouchingContoursOC!=null && extractDSEraseTouchingContoursOC.length>0) {
            addSep.run();
            sb.append("extractDSEraseTouchingContoursOC:").append(Utils.toStringArray(extractDSEraseTouchingContoursOC));
        }
        if (extractRawDSFile!=null) {
            addSep.run();
            sb.append("ExtractRawDSFile:").append(extractRawDSFile);
            addSep.run();
            sb.append("ExtractRawZAxis:").append(extractRawZAxis.toJSONEntry().toString());
        }
        if (extractDSRawChannels!=null) {
            addSep.run();
            sb.append("ExtractRawDatasetChannels:").append(ArrayUtil.toString(extractDSRawChannels));
        }
        if (extractDSRawBounds!=null) {
            addSep.run();
            sb.append("ExtractRawDatasetBounds:").append(extractDSRawBounds);
        }
        if (extractDSRawPositionMapFrames!=null) {
            addSep.run();
            sb.append("ExtractRawDatasetPositions:").append(extractDSRawPositionMapFrames.keySet());
        }
        addSep.run();
        sb.append("dir:").append(dir);
        return sb.toString();
    }

    public static boolean printStackTraceElement(String stackTraceElement) {
        //return true;
        return !stackTraceElement.startsWith("java.util.")&&!stackTraceElement.startsWith("java.lang.")&&!stackTraceElement.startsWith("java.security.")
                &&!stackTraceElement.startsWith("java.awt.")&&!stackTraceElement.startsWith("java.lang.")
                &&!stackTraceElement.startsWith("sun.reflect.")&&!stackTraceElement.startsWith("javax.swing.")
                &&!stackTraceElement.startsWith("bacmman.core.")&&!stackTraceElement.startsWith("bacmman.utils.");
    }
    //@Override
    public void done() {
        //logger.debug("EXECUTING DONE FOR : {}", this.toJSON().toJSONString());
        if (db !=null) {
            if (ownDB) {
                db.unlockPositions();
                db.unlockConfiguration();
                db.clearCache(true, true, true);
                db = null;
            } else db.clearCache(false, false, true);
        }
        this.publish("Task done.");
        publishErrors();
        this.printErrors();
        this.publish("------------------");
        //if (ui!=null) ui.setRunning(false); // in case several tasks run
    }

    public void publish(String message) {
        if (ui!=null) ui.setMessage(message);
        logger.debug(message);
    }

    public void publishErrors() {
        errors.unroll();
        this.publish("Errors: "+this.errors.getExceptions().size()+ " For JOB: "+ this);
        for (Pair<String, ? extends Throwable> e : errors.getExceptions()) publishError(e.key, e.value);
    }

    public void publishError(String localizer, Throwable error) {
        publish("Error @"+localizer+" "+(error==null?"null":error.toString()));
        publishError(error);
    }
    public void publishError(Throwable t) {
        Arrays.stream(t.getStackTrace())
                .map(StackTraceElement::toString)
                .filter(Task::printStackTraceElement)
                .forEachOrdered(this::publish);
        if (t.getCause()!=null && !t.getCause().equals(t)) {
            publish("caused By");
            publishError(t.getCause());
        }
    }

    // Progress Callback
    @Override
    public void incrementTaskNumber(int subtask) {
        if (taskCounter!=null) this.taskCounter[1]+=subtask;
    }

    @Override
    public synchronized void incrementProgress() {
        ++taskCounter[0];
        subtaskCounter = 0;
        subtaskNumber = 0;
        //logger.debug("Progress: {}/{}", taskCounter[0], taskCounter[1]);
        if (ui!=null) ui.setProgress(100*taskCounter[0]/taskCounter[1]);
    }

    @Override
    public void setSubtaskNumber(int number) {
        subtaskNumber = number;
        subtaskCounter = 0;
    }

    public void setTaskNumber(int taskNumber) {
        if (taskCounter!=null) this.taskCounter[1]=taskNumber;
        subtaskCounter = 0;
        subtaskNumber = 0;
    }

    @Override
    public synchronized void incrementSubTask() {
        ++subtaskCounter;
        //logger.debug("Progress: {}/{}, subtask: {}/{}", taskCounter[0], taskCounter[1], subtaskCount, subtaskNumber);
        if (ui!=null && subtaskNumber>0) ui.setProgress((int)(100*(taskCounter[0] + subtaskCounter / subtaskNumber)/taskCounter[1] + 0.5));
    }

    @Override
    public synchronized void setProgress(int i) {
        taskCounter[0] = i -1;
        incrementProgress();
    }

    @Override
    public int getTaskNumber() {
        return taskCounter[1];
    }

    @Override
    public void log(String message) {
        publish(message);
    }

    @Override
    public void setRunning(boolean running) {
        if (ui!=null) ui.setRunning(running);
    }

    public static void executeTasksInForeground(List<TaskI> tasks, ProgressLogger ui, double preProcessingMemoryThreshold) {
        int totalSubtasks = 0;
        for (TaskI t : tasks) {
            logger.debug("checking task: {}", t);
            if (!t.isValid()) {
                if (ui!=null) ui.setMessage("Invalid task: "+t.toString());
                t.printErrorsTo(ui);
                return;
            }
            t.setUI(ui);
            totalSubtasks+=t.countSubtasks();
        }
        if (ui!=null) ui.setMessage("Total subTasks: "+totalSubtasks);
        int[] taskCounter = new int[]{0, totalSubtasks};
        for (TaskI t : tasks) t.setTaskCounter(taskCounter);
        for (int i = 0; i<tasks.size(); ++i) {
            //if (ui!=null && i==0) ui.setRunning(true);
            tasks.get(i).initDB();
            final int ii = i;
            Consumer<FileProgressLogger> setLF = l->{if (l.getLogFile()==null) l.setLogFile(Paths.get(tasks.get(ii).getDir(),"Log.txt").toString());};
            Consumer<FileProgressLogger> unsetLF = l->l.setLogFile(null);
            if (ui instanceof MultiProgressLogger) ((MultiProgressLogger)ui).applyToLogUserInterfaces(setLF);
            else if (ui instanceof FileProgressLogger) setLF.accept((FileProgressLogger)ui);
            tasks.get(i).setPreprocessingMemoryThreshold(preProcessingMemoryThreshold);
            tasks.get(i).runTask(); // clears cache +  unlock if !keepdb
            tasks.get(i).done();
            if (ui instanceof MultiProgressLogger) ((MultiProgressLogger)ui).applyToLogUserInterfaces(unsetLF);
            else if (ui instanceof FileProgressLogger) unsetLF.accept((FileProgressLogger)ui);

            if (ui!=null && i==tasks.size()-1) {
                if (tasks.size()>1) {
                    for (TaskI t : tasks) t.publishErrors();
                }
            }
        }
    }
    public static void executeTasks(List<TaskI> tasks, ProgressLogger ui, double preProcessingMemoryThreshold, Runnable... endOfWork) {
        int totalSubtasks = 0;
        for (TaskI t : tasks) {
            logger.debug("checking task: {}", t);
            if (!t.isValid()) {
                if (ui!=null) ui.setMessage("Invalid task: "+t.toString());
                t.printErrorsTo(ui);
                return;
            }
            t.setUI(ui);
            totalSubtasks+=t.countSubtasks();
        }
        logger.debug("all tasks are valid");
        if (ui!=null) ui.setMessage("Total subTasks: "+totalSubtasks);
        int[] taskCounter = new int[]{0, totalSubtasks};
        for (TaskI t : tasks) t.setTaskCounter(taskCounter);
        DefaultWorker.execute(i -> {
            //if (ui!=null && i==0) ui.setRunning(true);
            tasks.get(i).initDB();
            Consumer<FileProgressLogger> setLF = l->{if (l.getLogFile()==null) l.setLogFile(Paths.get(tasks.get(i).getDir(),"Log.txt").toString());};
            Consumer<FileProgressLogger> unsetLF = l->l.setLogFile(null);
            if (ui instanceof MultiProgressLogger) ((MultiProgressLogger)ui).applyToLogUserInterfaces(setLF);
            else if (ui instanceof FileProgressLogger) setLF.accept((FileProgressLogger)ui);
            tasks.get(i).setPreprocessingMemoryThreshold(preProcessingMemoryThreshold);
            tasks.get(i).runTask(); // clears cache +  unlock if !keepdb
            tasks.get(i).done();
            if (ui instanceof MultiProgressLogger) ((MultiProgressLogger)ui).applyToLogUserInterfaces(unsetLF);
            else if (ui instanceof FileProgressLogger) unsetLF.accept((FileProgressLogger)ui);
            
            if (ui!=null && i==tasks.size()-1) {
                ui.setRunning(false);
                if (tasks.size()>1) {
                    for (TaskI t : tasks) t.publishErrors();
                }
            }
            return "";
        }, tasks.size()).setEndOfWork(
                ()->{for (Runnable r : endOfWork) r.run();});
    }
    public static void executeTaskInForeground(TaskI t, ProgressLogger ui, double preProcessingMemoryThreshold) {
        executeTasksInForeground(new ArrayList<TaskI>(1){{add(t);}}, ui, preProcessingMemoryThreshold);
    }
    public static void executeTask(TaskI t, ProgressLogger ui, double preProcessingMemoryThreshold, Runnable... endOfWork) {
        executeTasks(new ArrayList<TaskI>(1){{add(t);}}, ui, preProcessingMemoryThreshold, endOfWork);
    }
    public Stream<Task> splitByPosition() {
        ensurePositionAndObjectClasses(true, true);
        Function<Integer, Task> subTaskCreator = p -> {
            Task res = new Task(dbName, dir).setPositions(p).setStructures(structures);
            if (preProcess) res.preProcess = true;
            res.setStructures(structures);
            res.segmentAndTrack = segmentAndTrack;
            res.trackOnly = trackOnly;
            if (measurements) res.measurements = true;
            return res;
        };
        return positions.stream().map(subTaskCreator);
    }
    
    // check that no 2 xp with same name and different dirs
    private static void checkXPNameDir(List<Task> tasks) {
        boolean[] haveDup = new boolean[1];
        tasks.stream().map(t -> new Pair<>(t.dbName, t.dir)).distinct().collect(Collectors.groupingBy(p -> p.key)).entrySet().stream().filter(e->e.getValue().size()>1).forEach(e -> {
            haveDup[0] = true;
            logger.error("Task: {} has several directories: {}", e.getKey(), e.getValue().stream().map(p->p.value).collect(Collectors.toList()));
        });
        if (haveDup[0]) throw new IllegalArgumentException("Cannot process tasks: some duplicate experiment name with distinct path");
    }
    public static Map<XP_POS, List<Task>> getProcessingTasksByPosition(List<Task> tasks) {
        //checkXPNameDir(tasks);
        BinaryOperator<Task> taskMerger=(t1, t2) -> {
            if (!Arrays.equals(t1.structures, t2.structures)) throw new IllegalArgumentException("Tasks should have same structures to be merged");
            if (t2.measurements) t1.measurements= true;
            if (t2.preProcess) t1.preProcess = true;
            if (t2.segmentAndTrack) {
                t1.segmentAndTrack = true;
                t1.trackOnly = false;
            } else if (t2.trackOnly && !t1.segmentAndTrack) t1.trackOnly = true;
            
            return t1;
        };
        Map<XP_POS, List<Task>> res = tasks.stream().flatMap(Task::splitByPosition) // split by db / position
                .collect(Collectors.groupingBy(t->new XP_POS_S(t.dbName, t.dir, t.positions.get(0), new StructureArray(t.structures)))) // group including structures;
                .entrySet().stream().map(e -> e.getValue().stream().reduce(taskMerger).get()) // merge all tasks from same group
                .collect(Collectors.groupingBy(t->new XP_POS(t.dbName, t.dir, t.positions.get(0)))); // merge without including structures
        Function<Task, Stream<Task>> splitByStructure = t -> {
            return Arrays.stream(t.structures).mapToObj(s->  new Task(t.dbName, t.dir).setActions(false, t.segmentAndTrack, t.trackOnly, false).setPositions(t.positions.get(0)).setStructures(s));
        };
        Comparator<Task> subTComp = (t1, t2)-> {
            int sC = Integer.compare(t1.structures[0], t2.structures[0]);
            if (sC!=0) return sC;
            if (t1.segmentAndTrack && t2.segmentAndTrack) return 0;
            if (t1.segmentAndTrack && !t2.segmentAndTrack) return -1;
            else return 1;
        };
        res.entrySet().stream().filter(e->e.getValue().size()>1).forEach(e-> { // remove redundent tasks
            boolean meas = false, pp = false;
            for (Task t : e.getValue()) {
                if (t.measurements) {
                    meas = true;
                    t.measurements=false;
                }
                if (t.preProcess) {
                    pp = true;
                    t.preProcess=false;
                }
            }
            Task ta = e.getValue().get(0);
            e.getValue().removeIf(t->!t.segmentAndTrack && !t.trackOnly);
            if (e.getValue().isEmpty() && (meas || pp) ) e.getValue().add(ta);
            else { // tasks are only segment / track : reduce tasks in minimal number
                // split in one task per structure
                List<Task> subT = e.getValue().stream().flatMap(splitByStructure).distinct().sorted(subTComp).collect(Collectors.toList());
                logger.debug("subtT: {}", subT);
                // remove redondent tasks
                BiPredicate<Task, Task> removeNext = (t1, t2) -> t1.structures[0] == t2.structures[0] && t2.trackOnly; // sorted tasks
                for (int i = 0; i<subT.size()-1; ++i) { 
                    while(subT.size()>i+1 && removeNext.test(subT.get(i), subT.get(i+1))) subT.remove(i+1);
                }
                logger.debug("subtT after remove: {}", subT);
                // merge per segment
                BiPredicate<Task, Task> mergeNext = (t1, t2) -> t1.segmentAndTrack == t2.segmentAndTrack && t1.trackOnly == t2.trackOnly ;
                BiConsumer<Task, Task> merge = (t1, t2) -> {
                    List<Integer> allS = Utils.toList(t1.structures);
                    allS.addAll(Utils.toList(t2.structures));
                    Utils.removeDuplicates(allS, false);
                    Collections.sort(allS);
                    t1.setStructures(Utils.toArray(allS, false));
                };
                for (int i = 0; i<subT.size()-1; ++i) {
                    while(subT.size()>i+1 && mergeNext.test(subT.get(i), subT.get(i+1))) merge.accept(subT.get(i), subT.remove(i+1));
                }
                logger.debug("subtT after merge: {}", subT);
                e.setValue(subT);
            }
            if (pp) e.getValue().get(0).preProcess=true;
            if (meas) e.getValue().get(e.getValue().size()-1).measurements = true;
        });
        return res;
    }
    public static Map<XP, List<Task>> getGlobalTasksByExperiment(List<Task> tasks) {
        //checkXPNameDir(tasks);
        Function<Task, Task> getGlobalTask = t -> {
            if (!t.exportConfig && !t.exportData && !t.exportObjects && !t.exportPreProcessedImages && !t.exportSelections && !t.exportTrackImages && t.extractMeasurementDir.isEmpty()) return null;
            Task res = new Task(t.dbName, t.dir);
            res.extractMeasurementDir.addAll(t.extractMeasurementDir);
            res.exportConfig = t.exportConfig;
            res.exportData = t.exportData;
            res.exportObjects = t.exportObjects;
            res.exportPreProcessedImages = t.exportPreProcessedImages;
            res.exportSelections = t.exportSelections;
            res.exportTrackImages = t.exportTrackImages;
            res.setPositions(Utils.toArray(t.positions, false));
            return res;
        };
        return tasks.stream().map(getGlobalTask).filter(t->t!=null).collect(Collectors.groupingBy(t->new XP(t.dbName, t.dir)));
    }
    // utility classes for task split & merge
    public static class XP {
        public final String dbName, dir;

        public XP(String dbName, String dir) {
            this.dbName = dbName;
            this.dir = dir;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 89 * hash + Objects.hashCode(this.dbName);
            hash = 89 * hash + Objects.hashCode(this.dir);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final XP other = (XP) obj;
            if (!Objects.equals(this.dbName, other.dbName)) {
                return false;
            }
            if (!Objects.equals(this.dir, other.dir)) {
                return false;
            }
            return true;
        }
        
    }
    
    public static class XP_POS extends XP {
        public final int position;
        
        public XP_POS(String dbName, String dir, int position) {
            super(dbName, dir);
            this.position = position;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 53 * hash + Objects.hashCode(this.dbName);
            hash = 53 * hash + Objects.hashCode(this.dir);
            hash = 53 * hash + this.position;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final XP_POS other = (XP_POS) obj;
            if (this.position != other.position) {
                return false;
            }
            if (!Objects.equals(this.dbName, other.dbName)) {
                return false;
            }
            if (!Objects.equals(this.dir, other.dir)) {
                return false;
            }
            return true;
        }
        
        
    }
    private static class XP_POS_S extends XP_POS {
        StructureArray structures;
        
        public XP_POS_S(String dbName, String dir, int position, StructureArray structures) {
            super(dbName, dir, position);
            this.structures=structures;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 37 * hash + Objects.hashCode(this.dbName);
            hash = 37 * hash + Objects.hashCode(this.dir);
            hash = 37 * hash + Objects.hashCode(this.structures);
            hash = 37 * hash + this.position;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final XP_POS_S other = (XP_POS_S) obj;
            if (this.position != other.position) {
                return false;
            }
            if (!Objects.equals(this.dbName, other.dbName)) {
                return false;
            }
            if (!Objects.equals(this.dir, other.dir)) {
                return false;
            }
            if (!Objects.equals(this.structures, other.structures)) {
                return false;
            }
            return true;
        }
        
    }
    
    private static class StructureArray implements Comparable<StructureArray> {
        final int[] structures;

        public StructureArray(int[] structures) {
            this.structures = structures;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 97 * hash + Arrays.hashCode(this.structures);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final StructureArray other = (StructureArray) obj;
            return Arrays.equals(this.structures, other.structures);
        }

        @Override
        public int compareTo(StructureArray o) {
            if (structures==null) {
                if (o.structures==null) return 0;
                else return o.structures[0] == 0 ? 0 : -1;
            } else {
                if (o.structures==null) return structures[0] == 0 ? 0 : 1;
                else return Integer.compare(structures[0], o.structures[0]); // structures is a sorted array
            }
        }
        
    }
}
