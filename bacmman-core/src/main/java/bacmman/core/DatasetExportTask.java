package bacmman.core;

import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.TrainingConfigurationParameter;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.SelectionDAO;
import bacmman.plugins.FeatureExtractor;
import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Export of features (images, labels, links...) of the objects of one or several selections to a HDF5 file.
 */
public class DatasetExportTask extends AbstractDatasetExportTask<DatasetExportTask> {
    List<FeatureExtractor.Feature> features;
    List<String> selectionNames;
    List<Selection> selections;
    int[] dimensions;
    TrainingConfigurationParameter.RESIZE_MODE resizeMode = TrainingConfigurationParameter.RESIZE_MODE.NONE;
    int cropRefOC = -1;
    int[] eraseTouchingContoursOC = new int[0];
    boolean timelapse;
    int subsamplingFactor = 1;
    int subsamplingNumber = 0;
    int spatialDownsamplingFactor = 1;

    public DatasetExportTask() {
        super();
    }

    public DatasetExportTask(MasterDAO db) {
        super(db);
    }

    public DatasetExportTask(String dbName, String dir) {
        super(dbName, dir);
    }

    /**
     * Same as {@link #setParameters} with selections that are not stored in the dataset
     */
    public DatasetExportTask setParametersWithSelections(String outputFile, List<Selection> selections, List<FeatureExtractor.Feature> features, int[] dimensions, TrainingConfigurationParameter.RESIZE_MODE resizeMode, int cropRefOC, int[] eraseTouchingContoursOC, boolean timelapse, int spatialDownsamplingFactor, int subsamplingFactor, int subsamplingNumber, int compression) {
        setParameters(outputFile, selections.stream().map(Selection::getName).collect(Collectors.toList()), features, dimensions, resizeMode, cropRefOC, eraseTouchingContoursOC, timelapse, spatialDownsamplingFactor, subsamplingFactor, subsamplingNumber, compression);
        this.selections = selections;
        return this;
    }

    public DatasetExportTask setParameters(String outputFile, List<String> selections, List<FeatureExtractor.Feature> features, int[] dimensions, TrainingConfigurationParameter.RESIZE_MODE resizeMode, int cropRefOC, int[] eraseTouchingContoursOC, boolean timelapse, int spatialDownsamplingFactor, int subsamplingFactor, int subsamplingNumber, int compression) {
        this.outputFile = outputFile;
        this.selectionNames = selections;
        this.selections = null;
        this.features = features;
        this.dimensions = dimensions;
        this.resizeMode = resizeMode;
        this.cropRefOC = cropRefOC;
        this.eraseTouchingContoursOC = eraseTouchingContoursOC == null ? new int[0] : eraseTouchingContoursOC;
        this.timelapse = timelapse;
        this.spatialDownsamplingFactor = spatialDownsamplingFactor;
        this.subsamplingFactor = subsamplingFactor;
        this.subsamplingNumber = subsamplingNumber;
        this.compression = compression;
        return this;
    }

    public List<FeatureExtractor.Feature> getFeatures() {
        if (db != null && features != null) { // set Experiment as parent in case needed
            features.stream()
                    .flatMap(f -> f.getFeatureExtractor().getParameters() == null ? Stream.empty() : Arrays.stream(f.getFeatureExtractor().getParameters()))
                    .forEach(p -> p.setParent(db.getExperiment()));
        }
        return features;
    }

    public List<String> getSelectionNames() {
        return selectionNames;
    }

    public List<Selection> getSelections() {
        if (this.selections == null) {
            if (this.selectionNames == null) return Collections.emptyList();
            SelectionDAO selDAO = getDB().getSelectionDAO();
            return this.selectionNames.stream().map(selName -> selDAO.getOrCreate(selName, false)).collect(Collectors.toList());
        } else return selections;
    }

    public int[] getDimensions() {
        return dimensions;
    }

    public TrainingConfigurationParameter.RESIZE_MODE getResizeMode() {
        return resizeMode;
    }

    public int getCropRefOC() {
        return cropRefOC;
    }

    public int[] getEraseTouchingContoursOC() {
        return eraseTouchingContoursOC;
    }

    @Override
    public boolean isTimelapse() {
        return timelapse;
    }

    public int getSubsamplingFactor() {
        return subsamplingFactor;
    }

    public int getSubsamplingNumber() {
        return subsamplingNumber <= 0 ? subsamplingFactor : subsamplingNumber;
    }

    public int getSpatialDownsamplingFactor() {
        return spatialDownsamplingFactor;
    }

    @Override
    public void clearSelections() {
        if (selections != null) {
            for (Selection s : selections) s.freeMemoryForPositions();
        }
    }

    @Override
    protected String getExportName() {
        return "Dataset export";
    }

    @Override
    protected void export() {
        ExtractDatasetUtil.runDatasetExport(this);
    }

    @Override
    protected Collection<String> getExportedPositions() {
        return getSelections().stream().flatMap(s -> s.getAllPositions().stream()).collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    protected void checkParameters() {
        String dbName = getDBName();
        if (dimensions == null || dimensions.length < 2 || dimensions.length > 3) {
            errors.addExceptions(new Pair<>(dbName, new Exception("Invalid export dimensions:" + Utils.toStringArray(dimensions))));
        } else if (resizeMode != null && !resizeMode.equals(TrainingConfigurationParameter.RESIZE_MODE.NONE) && !resizeMode.equals(TrainingConfigurationParameter.RESIZE_MODE.CROP) && IntStream.of(dimensions).anyMatch(d -> d == 0)) {
            errors.addExceptions(new Pair<>(dbName, new Exception("Invalid export dimensions (resize mode not null):" + Utils.toStringArray(dimensions))));
        }
        if (selectionNames == null || selectionNames.isEmpty()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No selection to export from")));
        else {
            if (TrainingConfigurationParameter.RESIZE_MODE.EXTEND.equals(resizeMode) && getSelections().stream().anyMatch(s -> s.getObjectClassIdx() == -1)) {
                errors.addExceptions(new Pair<>(dbName, new Exception("Parent objects cannot be root in extend mode")));
            }
            if (getSelections().stream().anyMatch(Selection::isEmpty)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("One or several selection is empty or absent")));
        }
        if (features == null || features.isEmpty()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No features to export")));
        else {
            if (features.stream().anyMatch(f -> f.getName() == null || f.getName().isEmpty())) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid features names")));
            if (features.stream().anyMatch(f -> f.getObjectClass() < 0)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid features object class")));
            if (features.stream().anyMatch(f -> f.getFeatureExtractor() == null)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid features type")));
            if (features.stream().map(FeatureExtractor.Feature::getName).distinct().count() < features.size()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Duplicate feature name")));
        }
    }

    // JSON
    @Override
    public JSONObject toJSONEntry() {
        JSONObject res = initJSONEntry();
        if (selectionNames != null) {
            JSONArray sels = new JSONArray();
            sels.addAll(selectionNames);
            res.put("selections", sels);
        }
        if (dimensions != null) res.put("dimensions", JSONUtils.toJSONArray(dimensions));
        if (resizeMode != null && !resizeMode.equals(TrainingConfigurationParameter.RESIZE_MODE.NONE)) res.put("resizeMode", resizeMode.toString());
        if (TrainingConfigurationParameter.RESIZE_MODE.CROP.equals(resizeMode)) res.put("cropRefOC", cropRefOC);
        if (features != null) {
            JSONArray feats = new JSONArray();
            for (FeatureExtractor.Feature feature : features) {
                JSONObject feat = new JSONObject();
                feat.put("name", feature.getName());
                feat.put("oc", feature.getObjectClass());
                PluginParameter<FeatureExtractor> pp = new PluginParameter<>("FE", FeatureExtractor.class, feature.getFeatureExtractor(), false);
                feat.put("feature", pp.toJSONEntry());
                String selFilter = feature.getSelectionFilterName();
                if (selFilter != null) feat.put("selectionFilter", selFilter);
                feats.add(feat);
            }
            res.put("features", feats);
        }
        if (eraseTouchingContoursOC != null && eraseTouchingContoursOC.length > 0) res.put("eraseTouchingContoursOC", JSONUtils.toJSONArray(eraseTouchingContoursOC));
        if (subsamplingFactor > 1) res.put("subsamplingFactor", subsamplingFactor);
        if (subsamplingNumber > 1) res.put("subsamplingNumber", subsamplingNumber);
        if (spatialDownsamplingFactor > 1) res.put("spatialDownsamplingFactor", spatialDownsamplingFactor);
        res.put("timelapse", timelapse);
        return res;
    }

    /**
     * @param data JSON entry of this task, or of a former {@link ProcessingTask} that included a dataset export ("extractDataset" entry)
     */
    @Override
    public void initFromJSONEntry(JSONObject data) {
        if (data == null) return;
        JSONObject legacy = (JSONObject) data.get(LEGACY_KEY);
        initCommonFromJSONEntry(data, legacy);
        JSONObject params = legacy != null ? legacy : data;
        if (params.containsKey("selections")) {
            JSONArray sels = (JSONArray) params.get("selections");
            selectionNames = new ArrayList<>(sels.size());
            for (Object s : sels) selectionNames.add((String) s);
        }
        selections = null;
        if (params.containsKey("features")) {
            JSONArray feats = (JSONArray) params.get("features");
            features = new ArrayList<>(feats.size());
            for (Object f : feats) {
                JSONObject feat = (JSONObject) f;
                PluginParameter<FeatureExtractor> pp = new PluginParameter<>("FE", FeatureExtractor.class, false);
                pp.initFromJSONEntry(feat.get("feature"));
                features.add(new FeatureExtractor.Feature(
                        (String) feat.get("name"),
                        pp.instantiatePlugin(),
                        ((Number) feat.get("oc")).intValue(),
                        feat.containsKey("selectionFilter") ? (String) feat.get("selectionFilter") : null));
            }
        }
        if (params.containsKey("dimensions")) dimensions = JSONUtils.fromIntArray((JSONArray) params.get("dimensions"));
        Object rm = get(params, "resizeMode", "extractDSResizeMode");
        resizeMode = rm == null ? TrainingConfigurationParameter.RESIZE_MODE.NONE : TrainingConfigurationParameter.RESIZE_MODE.valueOf((String) rm);
        Object crop = get(params, "cropRefOC", "extractDSCropRefOC");
        cropRefOC = crop == null ? -1 : ((Number) crop).intValue();
        eraseTouchingContoursOC = params.containsKey("eraseTouchingContoursOC") ? JSONUtils.fromIntArray((JSONArray) params.get("eraseTouchingContoursOC")) : new int[0];
        Object sf = get(params, "subsamplingFactor", "extractDSSubsamplingFactor");
        subsamplingFactor = sf == null ? 1 : ((Number) sf).intValue();
        Object sn = get(params, "subsamplingNumber", "extractDSSubsamplingNumber");
        subsamplingNumber = sn == null ? 0 : ((Number) sn).intValue();
        Object sdf = get(params, "spatialDownsamplingFactor", "extractDSSpatialDownsamplingFactor");
        spatialDownsamplingFactor = sdf == null ? 1 : ((Number) sdf).intValue();
        Object tl = get(params, "timelapse", "extractDSTracking");
        timelapse = tl != null && (Boolean) tl;
    }

    /**
     * key of the dataset export parameters in the JSON entry of a former {@link ProcessingTask}
     */
    static final String LEGACY_KEY = "extractDataset";

    @Override
    public String toString() {
        String sep = "; ";
        StringBuilder sb = new StringBuilder();
        sb.append("Dataset export: ");
        sb.append("file:").append(outputFile);
        if (selectionNames != null) sb.append(sep).append("selections:").append(Utils.toStringList(selectionNames));
        if (features != null) {
            sb.append(sep).append("features:").append(Utils.toStringList(features, feat -> {
                PluginParameter<FeatureExtractor> pp = new PluginParameter<>("FE", FeatureExtractor.class, feat.getFeatureExtractor(), false);
                return feat.getName() + ":oc=" + feat.getObjectClass() + "(" + pp.toJSONEntry().toJSONString() + ")" + (feat.getSelectionFilterName() != null ? "selectionFilter:" + feat.getSelectionFilterName() : "");
            }));
        }
        if (dimensions != null && resizeMode != null && !TrainingConfigurationParameter.RESIZE_MODE.NONE.equals(resizeMode)) {
            sb.append(sep).append("dimensions:").append(Utils.toStringArray(dimensions)).append("(").append(resizeMode).append(")");
        }
        if (eraseTouchingContoursOC != null && eraseTouchingContoursOC.length > 0) sb.append(sep).append("eraseTouchingContoursOC:").append(Utils.toStringArray(eraseTouchingContoursOC));
        if (timelapse) sb.append(sep).append("timelapse");
        if (spatialDownsamplingFactor > 1) sb.append(sep).append("spatialDownsamplingFactor:").append(spatialDownsamplingFactor);
        if (subsamplingFactor > 1) sb.append(sep).append("subsamplingFactor:").append(subsamplingFactor).append(sep).append("subsamplingNumber:").append(subsamplingNumber);
        sb.append(sep).append("compression:").append(compression);
        sb.append(sep).append("db:").append(getDBName());
        sb.append(sep).append("dir:").append(dir);
        return sb.toString();
    }
}
