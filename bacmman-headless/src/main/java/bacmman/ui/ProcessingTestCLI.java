package bacmman.ui;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.core.Core;
import bacmman.core.ProcessingTest;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.Image;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageWriter;
import bacmman.plugins.*;
import bacmman.plugins.TestableProcessingPlugin.TestDataStore;
import bacmman.plugins.plugins.processing_pipeline.ProcessingPipelineWithSegmenter;
import bacmman.plugins.plugins.processing_pipeline.SegmentationAndTrackingProcessingPipeline;
import bacmman.ui.logger.ConsoleProgressLogger;
import bacmman.utils.geom.Point;
import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command line tool that runs the test of a processing plugin on a dataset, as the configuration test tab of the GUI does (same code: {@link ProcessingTest}), without user interface.
 * The dataset is opened in read-only mode and the test is performed on duplicated objects: the dataset is never modified.
 * After the test, the resulting objects can be listed, test commands (the right-click commands of test images, e.g. "Display Spot Projection") can be run on selected objects, and intermediate images can be saved.
 * Run with -h for usage.
 */
public class ProcessingTestCLI {
    static final String USAGE = String.join("\n",
            "Runs the test of a processing plugin on a dataset (equivalent to the configuration test tab of the GUI). The dataset is opened read-only and is not modified.",
            "Usage: ProcessingTestCLI --dataset <dir> --object-class <idx|name> [options]",
            "  --dataset <dir>             dataset directory (containing the configuration file)",
            "  --position <idx|name>       position (default: 0)",
            "  --object-class <idx|name>   object class whose processing pipeline is tested",
            "  --step <step>               SEGMENTATION_TRACKING (default), PRE_FILTER, TRACK_PRE_FILTER, POST_FILTER, TRACK_POST_FILTER",
            "  --plugin-idx <n>            index of the plugin within its sequence (pre/post-filters), default: 0",
            "  --segmenter                 SEGMENTATION_TRACKING step: test the segmenter instead of the tracker",
            "  --track-only                tracking / track post-filters: use the objects already segmented in the dataset",
            "  --parent-track <indices>    parent track: indices of the parent object (e.g. \"0\" for the first microchannel, as in the test tab), optionally starting with the frame of the track head (e.g. \"0-0\"). Default: \"0\"",
            "  --frames <min>-<max>        frame range (inclusive) of the parent track, default: whole track",
            "  --no-expert                 disable expert mode (expert mode adds intermediate images and test commands)",
            "  --list-objects [<idx|name>] after the test: list the resulting objects of an object class (default: tested object class) with their links",
            "  --list-commands             after the test: list the test commands",
            "  --command <name>            after the test: run a test command (e.g. \"Display Spot Projection\") on the objects given with --select; can be repeated",
            "  --select <i1>[,<i2>...]     objects for the preceding --command: indices as displayed in object names (e.g. 100-0-0 for P:0/S:6/I:100-0-0)",
            "  --select-class <idx|name>   object class of selected objects (default: tested object class)",
            "  --save-images <dir>         save intermediate images (TIF) in this directory",
            "  --log-level <level>         logging level of the libraries (default: WARN)",
            "  -h, --help                  this message");

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        try {
            System.exit(run(args));
        } catch (Throwable t) {
            System.err.println("Error: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    static class Command {
        final String name; final List<String> selection = new ArrayList<>(); String selectionClass;
        Command(String name) { this.name = name; }
    }

    public static int run(String[] args) throws Exception {
        Map<String, String> opts = new HashMap<>();
        List<Command> commands = new ArrayList<>();
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < args.length; ++i) {
            String a = args[i];
            switch (a) {
                case "-h": case "--help": System.out.println(USAGE); return 0;
                case "--segmenter": case "--track-only": case "--no-expert": case "--list-commands": flags.add(a); break;
                case "--list-objects":
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) opts.put(a, args[++i]); else opts.put(a, "");
                    break;
                case "--command": commands.add(new Command(next(args, ++i, a))); break;
                case "--select":
                    if (commands.isEmpty()) throw new IllegalArgumentException("--select must follow a --command");
                    commands.get(commands.size() - 1).selection.addAll(Arrays.asList(next(args, ++i, a).split(",")));
                    break;
                case "--select-class":
                    if (commands.isEmpty()) throw new IllegalArgumentException("--select-class must follow a --command");
                    commands.get(commands.size() - 1).selectionClass = next(args, ++i, a);
                    break;
                default:
                    if (!a.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + a + "\n" + USAGE);
                    opts.put(a, next(args, ++i, a));
            }
        }
        if (!opts.containsKey("--dataset") || !opts.containsKey("--object-class")) {
            System.out.println(USAGE);
            return 2;
        }
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)).setLevel(Level.toLevel(opts.getOrDefault("--log-level", "WARN")));
        Core.getCore();
        ConsoleProgressLogger console = new ConsoleProgressLogger();
        Core.setUserLogger(console);

        // open dataset (read-only)
        Path datasetDir = Paths.get(opts.get("--dataset"));
        MasterDAO db = MasterDAOFactory.getDAO(datasetDir);
        if (db == null || db.getExperiment() == null) throw new IllegalArgumentException("No dataset found in: " + datasetDir);
        db.setConfigurationReadOnly(true);
        try {
            Experiment xp = db.getExperiment();
            String position = position(xp, opts.getOrDefault("--position", "0"));
            int oc = objectClass(xp, opts.get("--object-class"));
            ProcessingTest.STEP step = ProcessingTest.STEP.valueOf(opts.getOrDefault("--step", "SEGMENTATION_TRACKING"));
            int pluginIdx = Integer.parseInt(opts.getOrDefault("--plugin-idx", "0"));
            ImageProcessingPlugin plugin = plugin(xp, oc, step, pluginIdx, flags.contains("--segmenter"));
            System.out.println("Dataset: " + db.getDBName() + " position: " + position + " object class: " + oc + " (" + xp.getStructure(oc).getName() + ") step: " + step + " plugin: " + plugin.getClass().getSimpleName() + (flags.contains("--track-only") ? " (track only)" : ""));

            // parent track
            ObjectDAO dao = db.getDao(position);
            int parentOC = xp.experimentStructure.getParentObjectClassIdx(oc);
            List<SegmentedObject> parents = parentTrack(dao, xp, parentOC, opts.getOrDefault("--parent-track", "0"), opts.get("--frames"));
            System.out.println("Parent track: " + parents.get(0) + " frames: [" + parents.get(0).getFrame() + "; " + parents.get(parents.size() - 1).getFrame() + "] (" + parents.size() + " objects)");

            // test
            String imageDir = opts.get("--save-images");
            if (imageDir != null) new File(imageDir).mkdirs();
            ProcessingTest.Environment env = new ProcessingTest.Environment(
                    imageDir == null ? null : im -> save(im, imageDir, "displayed"), null,
                    console::setMessage, t -> { System.err.println("Error during test: " + t); t.printStackTrace(); });
            long t0 = System.currentTimeMillis();
            List<Map<SegmentedObject, TestDataStore>> storeList = ProcessingTest.testImageProcessingPlugin(plugin, pluginIdx, step, xp, oc, parents, flags.contains("--track-only"), !flags.contains("--no-expert"), null, env);
            System.out.println("Test performed in " + (System.currentTimeMillis() - t0) + " ms");
            if (storeList == null || storeList.isEmpty()) return 1;
            List<TestDataStore> stores = storeList.stream().flatMap(m -> m.values().stream()).collect(Collectors.toList());
            List<SegmentedObject> testParents = storeList.get(storeList.size() - 1).keySet().stream().sorted().collect(Collectors.toList());

            if (imageDir != null) {
                for (TestDataStore s : stores) for (Map.Entry<String, Image> e : s.images.entrySet()) save(e.getValue(), imageDir, Selection.indicesString(s.getParent()) + "_" + e.getKey());
                System.out.println("Intermediate images saved in: " + imageDir);
            }
            if (opts.containsKey("--list-objects")) {
                String c = opts.get("--list-objects");
                listObjects(testParents, c.isEmpty() ? oc : objectClass(xp, c));
            }
            Set<String> available = stores.stream().flatMap(s -> s.getMiscCommands().stream()).collect(Collectors.toCollection(TreeSet::new));
            if (flags.contains("--list-commands")) System.out.println("Test commands: " + available);
            for (Command c : commands) {
                if (!available.contains(c.name)) {
                    System.out.println("Test command not found: \"" + c.name + "\". Available: " + available);
                    continue;
                }
                int selOC = c.selectionClass == null ? oc : objectClass(xp, c.selectionClass);
                List<SegmentedObject> sel = select(testParents, selOC, c.selection);
                System.out.println("Running test command \"" + c.name + "\" on: " + sel);
                for (TestDataStore s : storeList.get(storeList.size() - 1).values()) if (s.getMiscCommands().contains(c.name)) s.displayMiscData(c.name, sel); // commands decide whether they run for each store
            }
            return 0;
        } finally {
            db.unlockConfiguration();
            db.clearCache(false, false, true);
        }
    }

    static String next(String[] args, int i, String option) {
        if (i >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[i];
    }

    static String position(Experiment xp, String p) {
        String[] positions = xp.getPositionsAsString();
        try {
            int idx = Integer.parseInt(p);
            if (idx < 0 || idx >= positions.length) throw new IllegalArgumentException("Position index out of range: " + idx + " (" + positions.length + " positions)");
            return positions[idx];
        } catch (NumberFormatException e) {
            if (!Arrays.asList(positions).contains(p)) throw new IllegalArgumentException("Position not found: " + p + ". Positions: " + Arrays.toString(positions));
            return p;
        }
    }

    static int objectClass(Experiment xp, String c) {
        String[] names = xp.experimentStructure.getObjectClassesAsString();
        try {
            int idx = Integer.parseInt(c);
            if (idx < 0 || idx >= names.length) throw new IllegalArgumentException("Object class index out of range: " + idx);
            return idx;
        } catch (NumberFormatException e) {
            int idx = Arrays.asList(names).indexOf(c);
            if (idx < 0) throw new IllegalArgumentException("Object class not found: " + c + ". Object classes: " + Arrays.toString(names));
            return idx;
        }
    }

    static ImageProcessingPlugin plugin(Experiment xp, int oc, ProcessingTest.STEP step, int idx, boolean segmenter) {
        ProcessingPipeline<?> psc = xp.getStructure(oc).getProcessingScheme();
        if (psc == null) throw new IllegalArgumentException("No processing pipeline for object class " + oc);
        switch (step) {
            case SEGMENTATION_TRACKING:
                if (!segmenter && psc instanceof SegmentationAndTrackingProcessingPipeline) return ((SegmentationAndTrackingProcessingPipeline<?, ?>) psc).getTracker();
                if (!segmenter && psc instanceof ProcessingPipelineWithTracking) return ((ProcessingPipelineWithTracking<?, ?>) psc).getTracker();
                if (psc instanceof ProcessingPipelineWithSegmenter) return ((ProcessingPipelineWithSegmenter) psc).getSegmenter();
                throw new IllegalArgumentException("Processing pipeline " + psc.getClass().getSimpleName() + " has no tracker / segmenter");
            case PRE_FILTER: return instantiate(psc.getPreFilters().getChildAt(idx));
            case TRACK_PRE_FILTER: return instantiate(psc.getTrackPreFilters(false).getChildAt(idx));
            case POST_FILTER: return instantiate(psc.getPostFilters().getChildAt(idx));
            case TRACK_POST_FILTER:
                if (!(psc instanceof ProcessingPipelineWithTracking)) throw new IllegalArgumentException("Processing pipeline has no track post-filters");
                return instantiate(((ProcessingPipelineWithTracking<?, ?>) psc).getTrackPostFilters().getChildAt(idx));
            default: throw new IllegalArgumentException("Unsupported step: " + step);
        }
    }

    static ImageProcessingPlugin instantiate(PluginParameter<?> pp) {
        Plugin p = pp.instantiatePlugin();
        if (!(p instanceof ImageProcessingPlugin)) throw new IllegalArgumentException("Plugin cannot be tested: " + pp);
        return (ImageProcessingPlugin) p;
    }

    /**
     * Parent track as in the test tab: track head identified by its indices (optionally preceded by its frame), restricted to a frame range
     */
    static List<SegmentedObject> parentTrack(ObjectDAO dao, Experiment xp, int parentOC, String indices, String frames) {
        List<SegmentedObject> roots = dao.getRoots();
        if (roots == null || roots.isEmpty()) throw new IllegalArgumentException("No root objects found: has the position been processed?");
        SegmentedObject th;
        if (parentOC < 0) th = roots.get(0);
        else {
            int[] path = xp.experimentStructure.getPathToRoot(parentOC);
            int[] idx = Selection.parseIndices(indices);
            th = null;
            if (idx.length == path.length + 1) th = Selection.getObject(idx, path, roots);
            else if (idx.length == path.length) { // no frame: first frame where the object exists
                for (SegmentedObject r : roots) {
                    int[] full = new int[idx.length + 1];
                    full[0] = r.getFrame();
                    System.arraycopy(idx, 0, full, 1, idx.length);
                    th = Selection.getObject(full, path, roots);
                    if (th != null) break;
                }
            } else throw new IllegalArgumentException("Parent track indices should contain " + path.length + " indices (or " + (path.length + 1) + " with the frame)");
            if (th == null) throw new IllegalArgumentException("Parent track not found: " + indices);
            th = th.getTrackHead();
        }
        List<SegmentedObject> track = dao.getTrack(th);
        if (frames != null) {
            String[] f = frames.split("-");
            int min = Integer.parseInt(f[0]), max = Integer.parseInt(f[1]);
            track.removeIf(o -> o.getFrame() < min || o.getFrame() > max);
        }
        if (track.isEmpty()) throw new IllegalArgumentException("No parent object in frame range: " + frames);
        return track;
    }

    static List<SegmentedObject> select(List<SegmentedObject> testParents, int oc, List<String> indices) {
        Map<String, SegmentedObject> map = new HashMap<>();
        for (SegmentedObject p : testParents) p.getChildren(oc).forEach(o -> map.put(Selection.indicesString(o), o));
        List<SegmentedObject> res = new ArrayList<>();
        for (String i : indices) {
            SegmentedObject o = map.get(i);
            if (o == null) System.out.println("Object not found in test results: " + i);
            else res.add(o);
        }
        return res;
    }

    static void listObjects(List<SegmentedObject> testParents, int oc) {
        System.out.println("Objects of class " + oc + ":");
        for (SegmentedObject p : testParents) {
            p.getChildren(oc).sorted().forEach(o -> {
                Point c = o.getRegion().getCenterOrGeomCenter();
                System.out.println(String.format("  %s center=(%.1f, %.1f) quality=%.3f previous=%s next=%s trackHead=%s", o, c.get(0), c.get(1), o.getRegion().getQuality(), o.getPrevious(), o.getNext(), o.getTrackHead()));
            });
        }
    }

    static void save(Image image, String dir, String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._=,()-]+", "_");
        ImageWriter.writeToFile(image, Paths.get(dir, safe).toString(), ImageFormat.TIF);
    }
}
