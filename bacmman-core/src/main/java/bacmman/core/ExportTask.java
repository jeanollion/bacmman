package bacmman.core;

import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.measurement.MeasurementExtractor;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.utils.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Export of measurements (csv files), masks or contours (HDF5 files) of a dataset.
 * Each export is performed for each pair (directory, object classes) of {@link #addExportDir(String, int...)}.
 * Masks and contours are only exported for single object classes.
 */
public class ExportTask extends AbstractTask<ExportTask> {
    private static final Logger logger = LoggerFactory.getLogger(ExportTask.class);
    boolean exportMeasurements, exportMasks, exportContours, exportByPosition;
    List<Integer> positions;
    String selectionName;
    List<Pair<String, int[]>> exportDir = new ArrayList<>();

    public ExportTask() {
        super();
    }

    public ExportTask(MasterDAO db) {
        super(db);
    }

    public ExportTask(String dbName, String dir) {
        super(dbName, dir);
    }

    public ExportTask setExportActions(boolean measurements, boolean masks, boolean contours) {
        this.exportMeasurements = measurements;
        this.exportMasks = masks;
        this.exportContours = contours;
        return this;
    }

    public boolean hasExportAction() {
        return exportMeasurements || exportMasks || exportContours;
    }

    public ExportTask setExportByPosition(boolean exportByPosition) {
        this.exportByPosition = exportByPosition;
        return this;
    }

    /**
     * @param dir output directory. If null, the dataset directory is used
     * @param exportObjectClasses object classes exported together. If empty, each object class is exported separately
     */
    public ExportTask addExportDir(String dir, int... exportObjectClasses) {
        if (exportObjectClasses == null || exportObjectClasses.length == 0) {
            initDB();
            for (int s = 0; s < db.getExperiment().getStructureCount(); ++s) this.exportDir.add(new Pair<>(dir, new int[]{s}));
        } else this.exportDir.add(new Pair<>(dir, exportObjectClasses));
        if (exportDir.stream().noneMatch(p -> Objects.equals(p.key, dir) && Arrays.stream(p.value).anyMatch(s -> s == -1))) { // root measurements
            this.exportDir.add(new Pair<>(dir, new int[]{-1}));
        }
        return this;
    }

    public ExportTask setPositions(int... positions) {
        if (positions != null && positions.length > 0) this.positions = Utils.toList(positions);
        return this;
    }

    public ExportTask setPositions(String... positions) {
        if (positions != null && positions.length > 0) {
            boolean initDB = db == null;
            if (initDB) initDB();
            this.positions = new ArrayList<>(positions.length);
            for (String p : positions) this.positions.add(db.getExperiment().getPositionIdx(p));
            if (initDB) { // only set to null if no db was set before, to be able to run on GUI db without lock issues
                db.unlockConfiguration();
                db.unlockPositions();
                db.clearCache(true, true, true);
                db = null;
            }
        }
        return this;
    }

    public ExportTask setSelection(String selectionName) {
        this.selectionName = selectionName;
        return this;
    }

    /**
     * @return names of positions to export: selected positions (all if none selected) that contain elements of the selection if a selection is set
     */
    protected List<String> getPositionsToExport() {
        initDB();
        Selection selection = selectionName == null ? null : db.getSelectionDAO().getOrCreate(selectionName, false);
        Predicate<String> selFilter = selection == null ? p -> true : p -> selection.getAllPositions().contains(p);
        List<Integer> posIdx = positions == null ? Utils.toList(ArrayUtil.generateIntegerArray(db.getExperiment().getPositionCount())) : positions;
        return posIdx.stream().map(p -> db.getExperiment().getPosition(p).getName()).filter(selFilter).collect(Collectors.toList());
    }

    /**
     * @return pairs (directory, object class) for masks and contours: export of several object classes together are reserved to measurements
     */
    protected List<Pair<String, int[]>> getSingleObjectClassExportDir() {
        return exportDir.stream().filter(e -> e.value != null && e.value.length == 1 && e.value[0] >= 0).collect(Collectors.toList());
    }

    protected String getDir(Pair<String, int[]> exportDir) {
        return exportDir.key == null ? db.getDatasetDir().toFile().getAbsolutePath() : exportDir.key;
    }

    @Override
    public boolean isValid() {
        try {
            initDB();
        } catch (RuntimeException e) {
            errors.addExceptions(new Pair<>("DB could not be initialized", e));
            return false;
        }
        String dbName = getDBName();
        if (db == null || db.getExperiment() == null) {
            errors.addExceptions(new Pair<>(dbName, new Exception("DB: " + dbName + " not found")));
            printErrors();
            return false;
        }
        int nPositions = db.getExperiment().getPositionCount();
        if (positions != null && positions.stream().anyMatch(p -> p < 0 || p >= nPositions)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid position: " + positions)));
        if (selectionName != null && db.getSelectionDAO().getOrCreate(selectionName, false).isEmpty()) errors.addExceptions(new Pair<>(dbName, new Exception("Empty selection")));
        int nOC = db.getExperiment().getStructureCount();
        for (Pair<String, int[]> e : exportDir) {
            File f = new File(getDir(e));
            if (!f.exists()) errors.addExceptions(new Pair<>(dbName, new Exception("File: " + f + " not found")));
            else if (!f.isDirectory()) errors.addExceptions(new Pair<>(dbName, new Exception("File: " + f + " is not a directory")));
            if (e.value != null && Arrays.stream(e.value).anyMatch(oc -> oc < -1 || oc >= nOC)) errors.addExceptions(new Pair<>(dbName, new IllegalArgumentException("Invalid object class to export: " + Utils.toStringArray(e.value))));
        }
        if (!hasExportAction() || exportDir.isEmpty()) errors.addExceptions(new Pair<>(dbName, new Exception("No export to run!")));
        for (Pair<String, Throwable> e : errors.getExceptions()) publish("Invalid Task Error @" + e.key + " " + (e.value == null ? "null" : e.value.toString()));
        return errors.isEmpty();
    }

    @Override
    public int countSubtasks() {
        int nPositions = exportByPosition ? getPositionsToExport().size() : 1;
        int count = 0;
        if (exportMeasurements) count += exportDir.size() * nPositions;
        int nExport = (exportMasks ? 1 : 0) + (exportContours ? 1 : 0);
        count += getSingleObjectClassExportDir().size() * nPositions * nExport;
        return count;
    }

    @Override
    public void runTask() {
        publish("Run task: " + this);
        initDB();
        List<String> positionsToExport = getPositionsToExport();
        if (progress == null) {
            progress = new ProgressTracker(ui);
            progress.setTaskNumber(countSubtasks());
        }
        db.lockPositions(positionsToExport.toArray(new String[0]));
        try {
            if (exportMeasurements) {
                for (Pair<String, int[]> e : exportDir) exportMeasurements(getDir(e), e.value, positionsToExport);
            }
            if (exportMasks) {
                for (Pair<String, int[]> e : getSingleObjectClassExportDir()) exportSegmentation(getDir(e), e.value[0], positionsToExport, false);
            }
            if (exportContours) {
                for (Pair<String, int[]> e : getSingleObjectClassExportDir()) exportSegmentation(getDir(e), e.value[0], positionsToExport, true);
            }
        } catch (MultipleException e) {
            errors.addExceptions(e.getExceptions());
        } catch (Throwable e) {
            errors.addExceptions(new Pair<>("Export", e));
        } finally {
            if (ownDB) {
                db.unlockPositions(positionsToExport.toArray(new String[0]));
                db.unlockConfiguration();
                db.clearCache(true, true, true);
                db = null;
            } else {
                for (String position : positionsToExport) db.clearCache(position);
            }
        }
    }

    protected void exportMeasurements(String dir, int[] objectClasses, List<String> positions) {
        publish("exporting measurements from object class: " + Utils.toStringArray(objectClasses));
        setProgressMessage("Export Measurements");
        Map<Integer, String[]> keys = db.getExperiment().getAllMeasurementNamesByStructureIdx(MeasurementKeyObject.class, objectClasses);
        logger.debug("keys: {}", Utils.toStringList(keys.entrySet(), e -> e.getKey() + "=" + Arrays.toString(e.getValue())));
        Selection sel = selectionName == null ? null : db.getSelectionDAO().getOrCreate(selectionName, false);
        if (exportByPosition) {
            for (String p : positions) {
                String file = Paths.get(dir, db.getDBName() + Utils.toStringArray(objectClasses, "_", "", "_") + "_p_" + p + ".csv").toString();
                publish("measurements will be exported to: " + file);
                MeasurementExtractor.extractMeasurementObjects(db, file, Collections.singletonList(p), sel, keys);
                incrementProgress();
            }
        } else {
            String file = Paths.get(dir, db.getDBName() + Utils.toStringArray(objectClasses, "_", "", "_") + ".csv").toString();
            publish("measurements will be exported to: " + file);
            MeasurementExtractor.extractMeasurementObjects(db, file, positions, sel, keys);
            incrementProgress();
        }
    }

    /**
     * Export masks or contours of an object class
     */
    protected void exportSegmentation(String dir, int objectClass, List<String> positions, boolean contours) {
        String type = contours ? "contours" : "masks";
        String label = contours ? "Export Contours" : "Export Masks";
        publish("exporting " + type + " from object class: " + objectClass);
        setProgressMessage(label);
        Selection selection = selectionName == null ? null : db.getSelectionDAO().getOrCreate(selectionName, false);
        String ocName = db.getExperiment().getStructure(objectClass).getName();
        if (exportByPosition) {
            for (String p : positions) {
                Selection sel = selection != null ? selection : new Selection(ocName, -1, db).addElements(db.getDao(p).getRoots());
                String file = Paths.get(dir, db.getDBName() + "_" + type + "_" + objectClass + "_p_" + p + ".h5").toString();
                exportSegmentation(file, sel, objectClass, contours, p);
            }
        } else {
            Selection sel = selection;
            if (sel == null) {
                sel = new Selection(ocName, -1, db);
                for (String p : positions) sel.addElements(db.getDao(p).getRoots());
            }
            String file = Paths.get(dir, db.getDBName() + "_" + type + "_" + objectClass + ".h5").toString();
            exportSegmentation(file, sel, objectClass, contours, label);
        }
    }

    private void exportSegmentation(String file, Selection sel, int objectClass, boolean contours, String errorLocalizer) {
        publish((contours ? "contours" : "masks") + " will be exported to: " + file);
        try {
            if (contours) SegmentationExporter.exportContours(db, file, sel, objectClass, compression);
            else SegmentationExporter.exportMasks(db, file, sel, objectClass, compression);
        } catch (MultipleException e) {
            this.errors.addExceptions(e.getExceptions());
        } catch (Throwable e) {
            this.errors.addExceptions(new Pair<>(errorLocalizer, e));
        }
        incrementProgress();
    }

    // JSON
    /**
     * @return true if the JSON entry of a former processing task contains export actions
     */
    static boolean containsExport(JSONObject data) {
        return Stream.of("exportMeasurements", "exportMasks", "exportContours").anyMatch(k -> Boolean.TRUE.equals(data.get(k))) || data.containsKey("extractMeasurementDir");
    }

    @Override
    public JSONObject toJSONEntry() {
        JSONObject res = initJSONEntry();
        if (exportMeasurements) res.put("exportMeasurements", true);
        if (exportMasks) res.put("exportMasks", true);
        if (exportContours) res.put("exportContours", true);
        if (exportByPosition) res.put("exportByPosition", true);
        if (positions != null) res.put("positions", JSONUtils.toJSONArray(positions));
        if (selectionName != null) res.put("selection", selectionName);
        JSONArray ex = new JSONArray();
        for (Pair<String, int[]> p : exportDir) {
            JSONObject o = new JSONObject();
            o.put("dir", p.key);
            o.put("s", JSONUtils.toJSONArray(p.value));
            ex.add(o);
        }
        if (!ex.isEmpty()) res.put("exportDir", ex);
        res.put("compression", compression);
        return res;
    }

    /**
     * @param data JSON entry of this task, or of a former processing task that contained export actions
     */
    @Override
    public void initFromJSONEntry(JSONObject data) {
        if (data == null) return;
        initDirFromJSONEntry(data);
        exportMeasurements = (Boolean) data.getOrDefault("exportMeasurements", data.containsKey("extractMeasurementDir"));
        exportMasks = (Boolean) data.getOrDefault("exportMasks", false);
        exportContours = (Boolean) data.getOrDefault("exportContours", false);
        exportByPosition = (Boolean) data.getOrDefault("exportByPosition", false);
        selectionName = (String) data.get("selection");
        positions = data.containsKey("positions") ? JSONUtils.fromIntArrayToList((JSONArray) data.get("positions")) : null;
        exportDir = new ArrayList<>();
        if (data.containsKey("exportDir") || data.containsKey("extractMeasurementDir")) {
            JSONArray ex = (JSONArray) data.getOrDefault("exportDir", data.get("extractMeasurementDir"));
            for (Object o : ex) {
                JSONObject jo = (JSONObject) o;
                exportDir.add(new Pair<>((String) jo.get("dir"), JSONUtils.fromIntArray((JSONArray) jo.get("s"))));
            }
        }
        if (data.containsKey("compression")) compression = ((Number) data.get("compression")).intValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExportTask other = (ExportTask) o;
        return exportMeasurements == other.exportMeasurements && exportMasks == other.exportMasks && exportContours == other.exportContours
                && exportByPosition == other.exportByPosition && compression == other.compression
                && Objects.equals(dir, other.dir) && Objects.equals(positions, other.positions) && Objects.equals(selectionName, other.selectionName)
                && exportDir.size() == other.exportDir.size()
                && java.util.stream.IntStream.range(0, exportDir.size()).allMatch(i -> Objects.equals(exportDir.get(i).key, other.exportDir.get(i).key) && Arrays.equals(exportDir.get(i).value, other.exportDir.get(i).value));
    }

    @Override
    public int hashCode() {
        return Objects.hash(dir, exportMeasurements, exportMasks, exportContours, exportByPosition, positions, selectionName, exportDir.size());
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (positions != null) parts.add("positions:" + Utils.toStringArrayShort(positions));
        if (selectionName != null) parts.add("selection:" + selectionName);
        if (exportMeasurements) {
            StringBuilder sb = new StringBuilder("Measurements:");
            for (Pair<String, int[]> p : exportDir) sb.append(' ').append(p.key == null ? dir : p.key).append('=').append(p.value == null ? "all" : ArrayUtil.toString(p.value));
            parts.add(sb.toString());
        }
        if (exportMasks || exportContours) {
            StringBuilder sb = new StringBuilder(exportMasks && exportContours ? "Masks & Contours:" : (exportMasks ? "Masks:" : "Contours:"));
            for (Pair<String, int[]> p : getSingleObjectClassExportDir()) sb.append(' ').append(p.key == null ? dir : p.key).append('=').append(p.value[0]);
            parts.add(sb.toString());
        }
        if (exportByPosition) parts.add("by position");
        parts.add("db:" + getDBName());
        parts.add("dir:" + dir);
        return "Export: " + String.join("; ", parts);
    }
}
