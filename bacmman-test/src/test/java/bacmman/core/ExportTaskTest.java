package bacmman.core;

import org.json.simple.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.*;

public class ExportTaskTest {

    static Task<?> readSingle(JSONObject json) {
        List<Task> tasks = Task.createTasksFromJSONEntry(json);
        assertEquals(1, tasks.size());
        return tasks.get(0);
    }
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    ExportTask export() {
        String dir = folder.getRoot().getAbsolutePath();
        return new ExportTask("ds", dir).setPositions(0, 2).setSelection("sel")
                .addExportDir(dir, 1).addExportDir(dir, 2)
                .setExportActions(true, false, true).setExportByPosition(true).setCompression(7);
    }

    @Test
    public void exportDirIncludesRoot() { // root measurements are always exported
        ExportTask t = export();
        assertEquals(3, t.exportDir.size());
        assertEquals(1, t.exportDir.stream().filter(p -> p.value.length == 1 && p.value[0] == -1).count());
        assertEquals(2, t.getSingleObjectClassExportDir().size());
    }

    @Test
    public void exportJSON() {
        ExportTask t = export();
        JSONObject json = t.toJSONEntry();
        assertEquals(ExportTask.class.getName(), json.get("class"));
        Task<?> read = readSingle(json);
        assertTrue(read instanceof ExportTask);
        assertEquals(t, read);
        assertEquals(t, t.duplicate());
    }

    /**
     * @return JSON entry of a processing task of former versions, that also contained export actions
     */
    JSONObject legacyMixedEntry(boolean processing) {
        ExportTask export = export().setCompression(4); // compression was not saved
        JSONObject json = export.toJSONEntry();
        json.remove("class");
        json.remove("compression");
        if (processing) {
            JSONObject p = new ProcessingTask("ds", folder.getRoot().getAbsolutePath()).setActions(false, true, false, true).setPositions(0, 2).setStructures(1, 2).toJSONEntry();
            json.putAll(p);
        }
        return json;
    }

    @Test
    public void legacyProcessingAndExport() {
        JSONObject json = legacyMixedEntry(true);
        List<Task> tasks = Task.createTasksFromJSONEntry(json);
        assertEquals(2, tasks.size());
        assertTrue(tasks.get(0) instanceof ProcessingTask);
        ProcessingTask p = (ProcessingTask) tasks.get(0);
        assertTrue(p.isSegmentAndTrack());
        assertTrue(p.isMeasurements());
        assertFalse(p.toJSONEntry().containsKey("exportDir"));
        assertTrue(tasks.get(1) instanceof ExportTask);
        assertEquals(export().setCompression(4), tasks.get(1));
    }

    @Test
    public void legacyExportOnly() {
        List<Task> tasks = Task.createTasksFromJSONEntry(legacyMixedEntry(false));
        assertEquals(1, tasks.size());
        assertEquals(export().setCompression(4), tasks.get(0));
    }

    @Test
    public void legacyMeasurementDir() { // oldest format
        JSONObject json = new JSONObject();
        json.put("dir", folder.getRoot().getAbsolutePath());
        org.json.simple.JSONArray ex = new org.json.simple.JSONArray();
        JSONObject d = new JSONObject();
        d.put("dir", folder.getRoot().getAbsolutePath());
        d.put("s", bacmman.utils.JSONUtils.toJSONArray(new int[]{0}));
        ex.add(d);
        json.put("extractMeasurementDir", ex);
        List<Task> tasks = Task.createTasksFromJSONEntry(json);
        assertEquals(1, tasks.size());
        ExportTask t = (ExportTask) tasks.get(0);
        assertTrue(t.exportMeasurements);
        assertEquals(1, t.exportDir.size());
    }

    @Test
    public void processingOnly() {
        ProcessingTask t = new ProcessingTask("ds", folder.getRoot().getAbsolutePath()).setActions(true, false, false, false);
        List<Task> tasks = Task.createTasksFromJSONEntry(t.toJSONEntry());
        assertEquals(1, tasks.size());
        assertEquals(t, tasks.get(0));
    }
}
