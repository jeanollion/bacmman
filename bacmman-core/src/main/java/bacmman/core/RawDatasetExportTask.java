package bacmman.core;

import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.configuration.parameters.SimpleListParameter;
import bacmman.configuration.parameters.TransformationPluginParameter;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Transformation;
import bacmman.utils.ArrayUtil;
import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;
import org.json.simple.JSONObject;

import java.util.*;

/**
 * Export of channel images of selected frames of selected positions to a HDF5 file.
 */
public class RawDatasetExportTask extends AbstractDatasetExportTask<RawDatasetExportTask> {
    SimpleBoundingBox bounds;
    Map<String, List<Integer>> positionMapFrames;
    int[] channels;
    ExtractZAxisParameter.ExtractZAxisConfig zAxis = new ExtractZAxisParameter.IMAGE3D();
    SimpleListParameter<TransformationPluginParameter<Transformation>> transformations = createTransformationList();

    public RawDatasetExportTask() {
        super();
    }

    public RawDatasetExportTask(MasterDAO db) {
        super(db);
    }

    public RawDatasetExportTask(String dbName, String dir) {
        super(dbName, dir);
    }

    static SimpleListParameter<TransformationPluginParameter<Transformation>> createTransformationList() {
        return new SimpleListParameter<>("Pre-Processing pipeline", new TransformationPluginParameter<>("Transformation", Transformation.class, false));
    }

    /**
     * @param transformations transformations applied to the images before export. If null, no transformation is applied.
     */
    public RawDatasetExportTask setParameters(String outputFile, int[] channels, SimpleBoundingBox bounds, ExtractZAxisParameter.ExtractZAxisConfig zAxis, SimpleListParameter<TransformationPluginParameter<Transformation>> transformations, Map<String, List<Integer>> positionMapFrames, int compression) {
        this.outputFile = outputFile;
        this.positionMapFrames = positionMapFrames;
        this.bounds = bounds;
        this.channels = channels;
        this.zAxis = zAxis;
        this.compression = compression;
        this.transformations = transformations == null ? createTransformationList() : transformations;
        return this;
    }

    public BoundingBox getBounds() {
        return bounds;
    }

    public ExtractZAxisParameter.ExtractZAxisConfig getZAxis() {
        return zAxis;
    }

    public SimpleListParameter<TransformationPluginParameter<Transformation>> getTransformations() {
        return transformations;
    }

    public Map<String, List<Integer>> getPositionMapFrames() {
        return positionMapFrames;
    }

    public int[] getChannels() {
        return channels;
    }

    @Override
    protected String getExportName() {
        return "Raw dataset export";
    }

    @Override
    protected void export() throws java.io.IOException {
        ExtractDatasetUtil.runRawDatasetExport(this);
    }

    @Override
    protected Collection<String> getExportedPositions() {
        return positionMapFrames == null ? Collections.emptyList() : positionMapFrames.keySet();
    }

    @Override
    protected void checkParameters() {
        String dbName = getDBName();
        if (positionMapFrames == null || positionMapFrames.isEmpty() || positionMapFrames.values().iterator().next().isEmpty()) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No frames to export")));
        int nChannels = db.getExperiment().getChannelImageCount(false);
        if (channels == null || channels.length == 0) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("No channel images to export")));
        else {
            for (int c : channels) if (c >= nChannels) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid channel")));
        }
        if (bounds != null && (bounds.xMin() < 0 || bounds.yMin() < 0 || bounds.zMin() < 0)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid bounds for raw dataset export")));
    }

    // JSON
    @Override
    public JSONObject toJSONEntry() {
        JSONObject res = initJSONEntry();
        if (channels != null) res.put("channels", JSONUtils.toJSONArray(channels));
        if (bounds != null) res.put("bounds", bounds.toJSONEntry());
        if (positionMapFrames != null) {
            JSONObject pf = new JSONObject();
            for (String p : positionMapFrames.keySet()) pf.put(p, JSONUtils.toJSONArray(positionMapFrames.get(p)));
            res.put("positionMapFrame", pf);
        }
        res.put("zAxis", zAxis.toJSONEntry());
        if (!transformations.isEmpty()) res.put("transformations", transformations.toJSONEntry());
        return res;
    }

    /**
     * @param data JSON entry of this task, or of a former {@link ProcessingTask} that included a raw dataset export ("extractRawDataset" entry)
     */
    @Override
    public void initFromJSONEntry(JSONObject data) {
        if (data == null) return;
        JSONObject legacy = (JSONObject) data.get(LEGACY_KEY);
        initCommonFromJSONEntry(data, legacy);
        JSONObject params = legacy != null ? legacy : data;
        if (params.containsKey("channels")) channels = JSONUtils.fromIntArray((org.json.simple.JSONArray) params.get("channels"));
        if (params.containsKey("bounds")) {
            bounds = new SimpleBoundingBox();
            bounds.initFromJSONEntry(params.get("bounds"));
        } else bounds = null;
        Object z = get(params, "zAxis", "extractZAxis");
        zAxis = ExtractZAxisParameter.getConfigFromJSONEntry(z == null ? ExtractZAxisParameter.ExtractZAxis.IMAGE3D.toString() : z);
        positionMapFrames = new HashMap<>();
        if (params.containsKey("positionMapFrame")) {
            JSONObject pf = (JSONObject) params.get("positionMapFrame");
            for (Object k : pf.keySet()) positionMapFrames.put((String) k, JSONUtils.fromIntArrayToList((org.json.simple.JSONArray) pf.get(k)));
        }
        transformations = createTransformationList();
        Object tr = legacy != null ? data.get("extractRawDSTransformations") : data.get("transformations");
        if (tr != null) transformations.initFromJSONEntry(tr);
    }

    /**
     * key of the raw dataset export parameters in the JSON entry of a former {@link ProcessingTask}
     */
    static final String LEGACY_KEY = "extractRawDataset";

    @Override
    public String toString() {
        String sep = "; ";
        StringBuilder sb = new StringBuilder();
        sb.append("Raw dataset export: ");
        sb.append("file:").append(outputFile);
        if (channels != null) sb.append(sep).append("channels:").append(ArrayUtil.toString(channels));
        if (bounds != null) sb.append(sep).append("bounds:").append(bounds);
        if (positionMapFrames != null) sb.append(sep).append("positions:").append(positionMapFrames.keySet());
        sb.append(sep).append("zAxis:").append(zAxis.toJSONEntry().toString());
        sb.append(sep).append("compression:").append(compression);
        sb.append(sep).append("db:").append(getDBName());
        sb.append(sep).append("dir:").append(dir);
        return sb.toString();
    }
}
