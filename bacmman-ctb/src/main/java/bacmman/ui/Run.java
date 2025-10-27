package bacmman.ui;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.core.Task;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.Processor;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.ui.logger.ConsoleProgressLogger;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Run {
    /**
     * Run a configuration (2nd argument) on images and export CTB data in parent folder of image folder (1st argument)
     * @param args 0 = image folder (string) / 1 = json config (string) / 2 = remove temp file (boolean, optional, default True) / 3 = edge margin (int, optional, default 0)
     */
    public static void main(String[] args) {
        // check args and init path
        if (args.length<2) throw new IllegalArgumentException("at least 2 arguments required: image path and json configuration");
        File imageFolder = null;
        try {
            imageFolder = Paths.get(args[0]).normalize().toFile().getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (!imageFolder.exists() || !imageFolder.isDirectory()) throw new IllegalArgumentException("Folder: " +imageFolder+ " not found");

        Path dsPath = Paths.get("", "tmp");
        String jsonConfig = args[1];
        if (jsonConfig == null || (jsonConfig.charAt(0)!='{' && jsonConfig.charAt(jsonConfig.length()-1)!='}') ) throw new IllegalArgumentException("Arg 2 must be a JSON configuration");
        // init bacmman
        Core.getCore();
        ConsoleProgressLogger ui = new ConsoleProgressLogger();
        Core.setUserLogger(ui);

        // init experiment
        MasterDAO db = null;
        try {
            db = MasterDAOFactory.getDAO(dsPath);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        db.setConfigurationReadOnly(false);
        Experiment xp = new Experiment("tmp");
        try {
            xp.initFromJSONEntry(JSONUtils.parse(jsonConfig));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        db.setExperiment(xp, true);
        xp.setOutputDirectory("Output");

        // import images, run and export
        Processor.importFiles(db.getExperiment(), true, false, ProgressCallback.get(ui), imageFolder.getAbsolutePath());
        db.storeExperiment();
        Task t = new Task(db)
                .setActions(false, true, true, false);
        t.setUI(ui);
        if (!t.isValid()) throw new RuntimeException("Invalid Task");
        t.setPreprocessingMemoryThreshold(0.5);
        t.runTask();
        t.done();
        t.flush(false);
        int margin = args.length>3 ? Integer.parseInt(args[3]) : 0;
        File parent = imageFolder.getParentFile();
        ExportCellTrackingBenchmark.exportPositions(db, parent.getAbsolutePath(), 0, null, margin, CTB_IO_MODE.RESULTS, false, 1);

        // close / remove temp files
        if (args.length==2 || Boolean.parseBoolean(args[2])) {
            try {
                db.eraseAll();
            } catch (IOException e) {

            }
            Utils.deleteDirectory(dsPath.toFile());
        } else {
            db.unlockPositions();
            db.unlockConfiguration();
            db.clearCache(true, true, true);
        }
        java.lang.System.exit(0);
    }
}
