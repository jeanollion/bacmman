package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.ProcessingChain;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.Processor;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.measurement.MeasurementExtractor;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.FeatureExtractor;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.data_structure.Processor.*;

public class Optimization {
    static final Logger logger = LoggerFactory.getLogger(Optimization.class);
    final Experiment xp;
    final Path dir;
    final Map<String, Run> runs = new HashMap<>();
    public Optimization(Experiment xp) throws IOException {
        this.xp = xp;
        if (xp.getPath() == null) throw new IOException("Data must have a path");
        this.dir = xp.getPath().resolve("Optimization");
        if (!Files.exists(dir)) Files.createDirectories(dir);
        for (Path p : Files.list(dir).filter(Files::isDirectory).collect(Collectors.toList())) {
            String name = p.getFileName().toString();
            Run r = new Run(name);
            if (r.objectClasses().findAny().isPresent()) runs.put(name, new Run(name));
        }
    }

    public void run(MasterDAO db, Selection selection, ProgressCallback pcb, String... runs) {
        if (runs==null || runs.length==0) runs = steamRuns().map(Run::name).toArray(String[]::new);
        else for (String run : runs) if (!containsRun(run)) throw new RuntimeException("Unknown Run: "+run);
        boolean lock;
        db.setConfigurationReadOnly(true);
        if (selection == null) lock = db.lockPositions();
        else lock = db.lockPositions(selection.getAllPositions().toArray(new String[0]));
        if (!lock) throw new RuntimeException("Could not acquire lock on some positions");
        for (String run : runs) {
            try {
                getRun(run).run(db, selection, pcb);
            } catch (MultipleException e) {
                throw e;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public int getCommonParentIdx(String... runs) {
        List<String> runL = runs == null || runs.length==0 ? null : Arrays.asList(runs);
        return steamRuns()
                .filter(runL==null ? r->true : r -> runL.contains(r.name()))
                .mapToInt(Optimization.Run::getCommonParentIdx).min().orElse(-1);
    }

    public Stream<Run> steamRuns() {
        return runs.values().stream().sorted(Comparator.comparing(Run::name));
    }

    public List<String> getRunWithOC(int objectClass) {
        return runs.entrySet().stream().filter(r -> r.getValue().contains(objectClass))
                .map(Map.Entry::getKey).collect(Collectors.toList());
    }

    public boolean containsRun(String name) {
        return runs.containsKey(name);
    }

    public Run getRun(String name) throws IOException {
        if (runs.containsKey(name)) return runs.get(name);
        Path p = dir.resolve(name);
        if (!Files.exists(p)) Files.createDirectories(p);
        Run res = new Run(name);
        runs.put(name, res);
        return res;
    }

    public void deleteRun(String name) {
        Run r = runs.remove(name);
        if (r != null) Utils.deleteDirectory(r.dir.toFile());
    }

    public class Run {
        final String name;
        final Path dir;
        final Map<Integer, Path> ocConfig;
        public Run(String name) throws IOException {
            this.name = name;
            this.dir = Optimization.this.dir.resolve(name);
            this.ocConfig = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> dir.resolve("config_oc_"+oc+".json"));
            if (!Files.exists(dir)) Files.createDirectories(dir);
            for (Path p : Files.list(dir)
                    .filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith("config_oc_") && p.getFileName().toString().endsWith(".json"))
                    .collect(Collectors.toList())) {
                int oc = Integer.parseInt(Utils.removeExtension(p.getFileName().toString().replace("config_oc_", "")));
                ocConfig.put(oc, p);
            }
        }

        public String name() {
            return name;
        }

        public IntStream objectClasses() {
            return ocConfig.keySet().stream().mapToInt(i->i);
        }

        public boolean contains(int objectClass) {
            return ocConfig.containsKey(objectClass);
        }

        public void put(int objectClass, ProcessingChain config) throws IOException {
            Path p = ocConfig.get(objectClass);
            FileIO.TextFile file = new FileIO.TextFile(p.toString(), true, true);
            file.write(config.toJSONEntry().toJSONString(), false);
            file.close();
        }

        public void delete(int objectClass) throws IOException {
            Path p = ocConfig.get(objectClass);
            if (Files.exists(p)) Files.delete(p);
            ocConfig.remove(objectClass);
        }


        public ProcessingChain load(int objectClass) throws IOException {
            String config = FileIO.readFirstLineFromFile(ocConfig.get(objectClass).toString(), s -> s);
            if (config == null || config.isEmpty()) throw new IOException("Empty config for object class"+objectClass+" in run: "+name);
            try {
                JSONAware configJ = JSONUtils.parseJSON(config);
                ProcessingChain res = new ProcessingChain(name+"_oc_"+objectClass);
                res.initFromJSONEntry(configJ);
                return res;
            } catch (ParseException e) {
                throw new IOException(e);
            }
        }

        public Experiment getConfig() throws IOException {
            Experiment res = xp.duplicate();
            for (int oc : objectClasses().toArray()) {
                res.getStructure(oc).getProcessingPipelineParameter().setContentFrom(load(oc));
            }
            return res;
        }

        public void run(MasterDAO db, Selection selection, ProgressCallback pcb) throws MultipleException {
            MultipleException errors = new MultipleException();
            Experiment xp = null;
            try {
                xp = getConfig();
            } catch (IOException e) {
                errors.addExceptions(new Pair<>("Error getting config for run: "+name, e));
                throw errors;
            }
            db.setExperiment(xp, false);
            List<String> positions = selection == null ? Arrays.asList(xp.getPositionsAsString()) : selection.getAllPositions().stream().sorted().collect(Collectors.toList());
            for (String position : positions) {
                db.clearCache(position);
                run(db.getDao(position), selection, errors, pcb);
                db.clearCache(position);
            }
            if (!errors.isEmpty()) throw errors;
            // export all measurements
            for (int oc = 0 ; oc < xp.getStructureCount(); ++oc) {
                int[] ocA = new int[]{oc};
                Map<Integer, String[]> keys = db.getExperiment().getAllMeasurementNamesByStructureIdx(MeasurementKeyObject.class, ocA);
                String file = dir.resolve(db.getDBName() + Utils.toStringArray(ocA, "_", "", "_") + ".csv").toString();
                MeasurementExtractor.extractMeasurementObjects(db, file, positions, selection, keys);
            }
            pcb.incrementProgress();
            if (!errors.isEmpty()) throw errors;
        }

        public void run(ObjectDAO dao, Selection selection, MultipleException errors, ProgressCallback pcb) {
            int[] ocA = objectClasses().toArray();
            if (selection==null) {
                deleteObjects(dao, ocA);
            }
            List<SegmentedObject> root = null;
            try {
                root = getOrCreateRootTrack(dao);
            } catch (IOException e) {
                errors.addExceptions(new Pair<>("Error getting root track", e));
                throw errors;
            }
            for (int oc : ocA) {
                try {
                    executeProcessingScheme(root, oc, false, selection != null, selection, pcb);
                    pcb.incrementProgress();
                } catch (MultipleException e) {
                    errors.addExceptions(e.getExceptions());
                } catch (Throwable e) {
                    errors.addExceptions(new Pair<>("Error while processing: pos: " + dao.getPositionName() + " oc: " + oc, e));
                } finally {
                    dao.getExperiment().getPosition(dao.getPositionName()).flushImages(true, true);
                    dao.getExperiment().getDLengineProvider().closeAllEngines();
                    Core.clearDiskBackedImageManagers();
                }
            }
            Processor.performMeasurements(dao, MEASUREMENT_MODE.ERASE_ALL, selection, pcb);
            pcb.incrementProgress();
        }

        public boolean performed() {
            try {
                return Files.list(dir).filter(Files::isRegularFile).anyMatch(p -> p.getFileName().toString().endsWith(".csv"));
            } catch (IOException e) {
                return false;
            }
        }

        public int getCommonParentIdx() {
            return objectClasses().map(xp.experimentStructure::getParentObjectClassIdx).min().orElse(-1);
        }
    }

    public static class OptimizationTask implements TaskI<OptimizationTask> {
        MasterDAO db;
        String dir, dbName;
        Optimization optimization;
        MultipleException errors = new MultipleException();
        ProgressLogger ui;
        int[] taskCounter;
        String selectionName;
        String[] runA;

        public OptimizationTask() {

        }

        public OptimizationTask setDBName(String dbName) {
            this.dbName = dbName;
            return this;
        }

        public OptimizationTask setDBDir(String dbDir) {
            this.dir = dbDir;
            return this;
        }

        public OptimizationTask setRuns(String... runs) {
            this.runA = runs;
            return this;
        }

        public OptimizationTask setSelectionName(String selectionName) {
            this.selectionName = selectionName;
            return this;
        }

        @Override
        public boolean isValid() {
            try {
                initDB();
            } catch (RuntimeException e) {
                errors.addExceptions(new Pair<>("DB could not be initialized", e));
                return false;
            }
            if (selectionName != null) {
                Selection sel = db.getSelectionDAO().getSelections().stream().filter(s -> s.getName().equals(selectionName)).findFirst().orElse(null);
                if (sel == null ) {
                    errors.addExceptions(new Pair<>("Selection: "+selectionName+ " not found", new RuntimeException()));
                } else if (sel.getObjectClassIdx() > optimization.getCommonParentIdx(runA)) {
                    errors.addExceptions(new Pair<>("Invalid selection: "+selectionName+ ". ObjectIdx must be lower or equal than "+optimization.getCommonParentIdx(runA)+ " object idx: "+sel.getObjectClassIdx(), new RuntimeException()));
                }
            }
            if (runA != null) {
                List<String> runsNotFound = Arrays.stream(runA).filter(n -> !optimization.containsRun(n)).collect(Collectors.toList());
                if (!runsNotFound.isEmpty()) errors.addExceptions(new Pair<>("Some runs where not found: "+Utils.toStringList(runsNotFound), new RuntimeException()));
            }
            String[] rA = runA == null ? optimization.steamRuns().map(Run::name).toArray(String[]::new) : runA;
            for (String runN : rA) {
                try {
                    Run r = optimization.getRun(runN);
                    for (int oc : r.objectClasses().toArray()) r.load(oc);
                } catch (IOException e) {
                    errors.addExceptions(new Pair<>("Invalid run: "+runN, e));
                }
            }
            return errors.isEmpty();
        }

        @Override
        public int countSubtasks() {
            initDB();
            String[] rA = runA == null ? optimization.steamRuns().map(Run::name).toArray(String[]::new) : runA;
            int nPos = selectionName == null ? db.getExperiment().getPositionCount() : db.getSelectionDAO().getOrCreate(selectionName, false).getAllPositions().size();
            int count = 1; // extract measurements
            for (String runN : rA) {
                try {
                    Run r = optimization.getRun(runN);
                    count += r.objectClasses().count()  * nPos; // each object class
                    count += nPos; // measurements
                } catch (IOException e) {
                    throw new RuntimeException();
                }
            }
            return count;
        }

        @Override
        public void printErrorsTo(ProgressLogger ui) {
            if (!errors.isEmpty()) ui.setMessage("Errors for Task: " + this);
            for (Pair<String, ? extends Throwable> e : errors.getExceptions()) ui.setMessage(e.key + ": " + e.value);
        }

        @Override
        public void setUI(ProgressLogger ui) {
            this.ui = ui;
        }

        @Override
        public void setTaskCounter(int[] taskCounter) {
            this.taskCounter=taskCounter;
        }

        @Override
        public String getDir() {
            return dir;
        }

        @Override
        public void initDB() {
            if (db==null) {
                if (dir==null) throw new RuntimeException("XP not found");
                db = MasterDAOFactory.getDAO(dbName, dir);
                if (db == null) throw new RuntimeException("Could not initialize db: dir: "+dir+ " name: "+dbName);
                try {
                    optimization = new Optimization(db.getExperiment());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void runTask() {
            initDB();
            ProgressCallback pcb = ProgressCallback.get(ui);
            pcb.setTaskNumber(taskCounter[1]);
            optimization.run(db, selectionName!=null?db.getSelectionDAO().getOrCreate(selectionName, false):null, pcb, runA);
            db.clearCache(true, true, true);
        }

        public void publish(String message) {
            if (ui!=null) ui.setMessage(message);
            logger.debug(message);
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

        @Override
        public void publishErrors() {
            errors.unroll();
            this.publish("Errors: "+this.errors.getExceptions().size()+ " For JOB: "+ this);
            for (Pair<String, ? extends Throwable> e : errors.getExceptions()) publishError(e.key, e.value);
        }

        @Override
        public OptimizationTask duplicate() {
            OptimizationTask res = new OptimizationTask();
            res.initFromJSONEntry(toJSONEntry());
            return res;
        }

        @Override
        public JSONObject toJSONEntry() {
            JSONObject object = new JSONObject();
            object.put("class", OptimizationTask.class.getName());
            object.put("dbName", dbName);
            object.put("dir", dir);
            if (selectionName != null) object.put("selectionName", selectionName);
            if (runA != null) object.put("runArray", JSONUtils.toJSONArray(runA));
            return object;
        }

        @Override
        public void initFromJSONEntry(JSONObject jsonEntry) {
            JSONObject o = (JSONObject) jsonEntry;
            dbName = (String)o.get("dbName");
            dir = (String)o.get("dir");
            if (o.containsKey("selectionName")) selectionName = (String)o.get("selectionName");
            if (o.containsKey("runArray")) runA = JSONUtils.fromStringArray((JSONArray)o.get("runArray"));
        }

        @Override public String toString() {
            String sep = "; " ;
            StringBuilder sb = new StringBuilder();
            Runnable addSep = () -> {if (sb.length()>0) sb.append(sep);};
            sb.append("db:").append(dbName);
            if (runA!=null) {
                addSep.run();
                sb.append("runs:").append(ArrayUtil.toString(runA));
            }
            if (selectionName!=null) {
                addSep.run();
                sb.append("selection:").append(selectionName);
            }
            addSep.run();
            sb.append("dir:").append(dir);
            return sb.toString();
        }
    }
}
