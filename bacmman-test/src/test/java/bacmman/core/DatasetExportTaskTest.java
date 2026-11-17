package bacmman.core;

import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.configuration.parameters.TrainingConfigurationParameter;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.PluginFactory;
import bacmman.plugins.plugins.feature_extractor.Labels;
import org.json.simple.JSONObject;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.*;

import static org.junit.Assert.*;

public class DatasetExportTaskTest {

    static Task<?> readSingle(JSONObject json) {
        List<Task> tasks = Task.createTasksFromJSONEntry(json);
        assertEquals(1, tasks.size());
        return tasks.get(0);
    }
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void findPlugins() { // features are stored by plugin name
        PluginFactory.findPlugins("bacmman.plugins.plugins.feature_extractor");
    }

    DatasetExportTask datasetExport() {
        String dir = folder.getRoot().getAbsolutePath();
        List<FeatureExtractor.Feature> features = Collections.singletonList(new FeatureExtractor.Feature("labels", new Labels(), 1, "filterSel"));
        return new DatasetExportTask("ds", dir).setParameters("/tmp/out.h5", Arrays.asList("sel1", "sel2"), features, new int[]{64, 32}, TrainingConfigurationParameter.RESIZE_MODE.CROP, 1, new int[]{2}, true, 2, 3, 2, 6);
    }

    static void assertSameDatasetExport(DatasetExportTask expected, DatasetExportTask actual) {
        assertEquals(expected.getDir(), actual.getDir());
        assertEquals(expected.getOutputFile(), actual.getOutputFile());
        assertEquals(expected.getSelectionNames(), actual.getSelectionNames());
        assertArrayEquals(expected.getDimensions(), actual.getDimensions());
        assertEquals(expected.getResizeMode(), actual.getResizeMode());
        assertEquals(expected.getCropRefOC(), actual.getCropRefOC());
        assertArrayEquals(expected.getEraseTouchingContoursOC(), actual.getEraseTouchingContoursOC());
        assertEquals(expected.isTimelapse(), actual.isTimelapse());
        assertEquals(expected.getSpatialDownsamplingFactor(), actual.getSpatialDownsamplingFactor());
        assertEquals(expected.getSubsamplingFactor(), actual.getSubsamplingFactor());
        assertEquals(expected.getSubsamplingNumber(), actual.getSubsamplingNumber());
        assertEquals(expected.getCompression(), actual.getCompression());
        assertEquals(1, actual.getFeatures().size());
        FeatureExtractor.Feature f = actual.getFeatures().get(0);
        assertEquals("labels", f.getName());
        assertEquals(1, f.getObjectClass());
        assertEquals("filterSel", f.getSelectionFilterName());
        assertTrue(f.getFeatureExtractor() instanceof Labels);
    }

    @Test
    public void datasetExportJSON() {
        DatasetExportTask t = datasetExport();
        JSONObject json = t.toJSONEntry();
        assertEquals(DatasetExportTask.class.getName(), json.get("class"));
        Task<?> read = readSingle(json);
        assertTrue(read instanceof DatasetExportTask);
        assertSameDatasetExport(t, (DatasetExportTask) read);
        assertSameDatasetExport(t, t.duplicate());
    }

    @Test
    public void legacyDatasetExport() { // task list saved when dataset export was performed by Task
        DatasetExportTask t = datasetExport();
        JSONObject json = t.toJSONEntry();
        JSONObject legacy = new JSONObject();
        for (String k : new String[]{"outputFile", "compression", "selections", "dimensions", "features", "eraseTouchingContoursOC"}) legacy.put(k, json.get(k));
        legacy.put("extractDSResizeMode", json.get("resizeMode"));
        legacy.put("extractDSCropRefOC", json.get("cropRefOC"));
        legacy.put("extractDSSubsamplingFactor", json.get("subsamplingFactor"));
        legacy.put("extractDSSubsamplingNumber", json.get("subsamplingNumber"));
        legacy.put("extractDSSpatialDownsamplingFactor", json.get("spatialDownsamplingFactor"));
        legacy.put("extractDSTracking", json.get("timelapse"));
        JSONObject task = new JSONObject();
        task.put("dir", t.getDir());
        task.put("extractDataset", legacy);
        Task<?> read = readSingle(task);
        assertTrue(read instanceof DatasetExportTask);
        assertSameDatasetExport(t, (DatasetExportTask) read);
    }

    RawDatasetExportTask rawExport() {
        String dir = folder.getRoot().getAbsolutePath();
        Map<String, List<Integer>> positionMapFrames = new HashMap<>();
        positionMapFrames.put("pos1", Arrays.asList(0, 2, 4));
        return new RawDatasetExportTask("ds", dir).setParameters("/tmp/raw.h5", new int[]{0, 2}, new SimpleBoundingBox(1, 10, 2, 20, 0, 0), new ExtractZAxisParameter.IMAGE3D(), null, positionMapFrames, 5);
    }

    static void assertSameRawExport(RawDatasetExportTask expected, RawDatasetExportTask actual) {
        assertEquals(expected.getDir(), actual.getDir());
        assertEquals(expected.getOutputFile(), actual.getOutputFile());
        assertArrayEquals(expected.getChannels(), actual.getChannels());
        assertTrue(expected.getBounds().sameBounds(actual.getBounds()));
        assertEquals(expected.getPositionMapFrames(), actual.getPositionMapFrames());
        assertEquals(expected.getZAxis().toJSONEntry(), actual.getZAxis().toJSONEntry());
        assertEquals(expected.getCompression(), actual.getCompression());
        assertNotNull(actual.getTransformations());
    }

    @Test
    public void rawDatasetExportJSON() {
        RawDatasetExportTask t = rawExport();
        JSONObject json = t.toJSONEntry();
        Task<?> read = readSingle(json);
        assertTrue(read instanceof RawDatasetExportTask);
        assertSameRawExport(t, (RawDatasetExportTask) read);
    }

    @Test
    public void legacyRawDatasetExport() {
        RawDatasetExportTask t = rawExport();
        JSONObject json = t.toJSONEntry();
        JSONObject legacy = new JSONObject();
        for (String k : new String[]{"outputFile", "compression", "channels", "bounds", "positionMapFrame"}) legacy.put(k, json.get(k));
        legacy.put("extractZAxis", json.get("zAxis"));
        JSONObject task = new JSONObject();
        task.put("dir", t.getDir());
        task.put("extractRawDataset", legacy);
        Task<?> read = readSingle(task);
        assertTrue(read instanceof RawDatasetExportTask);
        assertSameRawExport(t, (RawDatasetExportTask) read);
    }

    @Test
    public void processingTask() { // tasks without class are processing tasks
        ProcessingTask t = new ProcessingTask("ds", folder.getRoot().getAbsolutePath()).setActions(true, true, false, true);
        JSONObject json = t.toJSONEntry();
        assertFalse(json.containsKey("class"));
        Task<?> read = readSingle(json);
        assertTrue(read instanceof ProcessingTask);
        assertEquals(t, read);
    }
}
