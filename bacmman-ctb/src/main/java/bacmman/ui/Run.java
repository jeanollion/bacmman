package bacmman.ui;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.ImageFieldFactory;
import bacmman.core.ProgressCallback;
import bacmman.core.Task;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.ui.logger.ConsoleProgressLogger;
import bacmman.utils.JSONUtils;

import java.io.File;
import java.nio.file.Paths;

public class Run {
    public static void main(String[] args) {
        File imageFolder = Paths.get(args[0]).normalize().toFile().getAbsoluteFile();
        if (!imageFolder.exists() || imageFolder.isDirectory()) throw new RuntimeException("Folder: " +imageFolder+ " not found");
        File parent = imageFolder.getParentFile();
        File dsFolder = Paths.get(parent.getAbsolutePath(), "temp").toFile();
        MasterDAO db = MasterDAOFactory.createDAO(parent.getName(), dsFolder.getAbsolutePath(), MasterDAOFactory.DAOType.DBMap);
        Experiment xp = new Experiment(parent.getName());
        String jsonConfig = args[1];
        xp.initFromJSONEntry(JSONUtils.parse(jsonConfig));
        db.setExperiment(xp);
        ConsoleProgressLogger ui = new ConsoleProgressLogger();
        ImageFieldFactory.importImages(new String[]{imageFolder.getAbsolutePath()}, xp, ProgressCallback.get(ui));
        Task t = new Task()
                .setActions(false, true, true, false)
                .setStructures(0);
        t.runTask(0.5);
        t.done();
        ExportCellTrackingBenchmark.export(db, parent.getAbsolutePath(), 0);
    }
}
