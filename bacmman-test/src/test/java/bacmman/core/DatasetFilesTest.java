package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.parameters.FileChooser;
import bacmman.data_structure.image_container.MultipleImageContainerSingleFile;
import bacmman.plugins.plugins.transformations.BackgroundAndShadingCorrection;
import org.json.simple.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class DatasetFilesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void copyExternalFilesIntoDataset() throws Exception {
        bacmman.plugins.PluginFactory.findPlugins("bacmman.plugins.plugins"); // modules are reloaded by name
        File root = tmp.getRoot().getCanonicalFile();
        Path ds = new File(root, "Images/dataset1").toPath();
        Files.createDirectories(ds);
        // external files: flat-field image, input images (with a companion file and a sub-folder that is not part of the images)
        File ext = new File(root, "Calibration");
        ext.mkdirs();
        File flat = new File(ext, "flat.tif");
        Files.write(flat.toPath(), new byte[100]);
        File raw = new File(root, "Microscope/run1");
        new File(raw, "other").mkdirs();
        Files.write(new File(raw, "img.tif").toPath(), new byte[300]);
        Files.write(new File(raw, "img.nd").toPath(), new byte[10]);
        Files.write(new File(raw, "other/big.tif").toPath(), new byte[1000]);
        // file already inside the dataset folder: not copied
        File inside = new File(ds.toFile(), "dark.tif");
        Files.write(inside.toPath(), new byte[50]);

        Experiment xp = new Experiment("dataset1").setPath(ds);
        xp.getChannelImages().insert(xp.getChannelImages().createChildInstance("phase"));
        BackgroundAndShadingCorrection corr = new BackgroundAndShadingCorrection();
        setBoolean(corr, "correctFlatField", true); // active parameters only
        setBoolean(corr, "correctDarkField", true);
        setFile(corr, "flatField", flat.getAbsolutePath());
        setFile(corr, "darkField", inside.getAbsolutePath());
        xp.getPreProcessingTemplate().addTransformation(0, 0, null, corr);
        Position p = xp.getPositionParameter().createChildInstance("xy01");
        xp.getPositionParameter().insert(p);
        p.setImages(new MultipleImageContainerSingleFile("xy01", new File(raw, "img.tif").getAbsolutePath(), 0, 1, 1, 1, 1, 1, false));

        List<DatasetFiles.ExternalFile> files = DatasetFiles.findExternalFiles(xp);
        assertEquals("only the flat-field image is outside of the dataset folder", 1, files.size());
        assertEquals(flat.getAbsolutePath(), files.get(0).paths.get(0));
        assertTrue(files.get(0).location, files.get(0).location.endsWith("Flat-field image"));
        assertEquals(100, files.get(0).size);
        List<DatasetFiles.ImageFolder> folders = DatasetFiles.findExternalImageFolders(xp);
        assertEquals(1, folders.size());
        assertEquals(raw.getAbsolutePath(), folders.get(0).folder);
        assertEquals("files of the folder, not its sub-folders", 310, folders.get(0).size);

        DatasetFiles.copyIntoDataset(xp, files, folders, null);
        File copiedFlat = ds.resolve("Resources/flat.tif").toFile();
        assertTrue(copiedFlat.isFile());
        assertTrue(ds.resolve("InputImages/run1/img.tif").toFile().isFile());
        assertTrue("companion files are copied", ds.resolve("InputImages/run1/img.nd").toFile().isFile());
        assertFalse("sub-folders are not copied", ds.resolve("InputImages/run1/other").toFile().exists());
        assertTrue("originals are kept", flat.isFile() && new File(raw, "img.tif").isFile());
        // configuration modified in place
        assertTrue(DatasetFiles.findExternalFiles(xp).isEmpty());
        assertTrue(DatasetFiles.findExternalImageFolders(xp).isEmpty());
        assertEquals(ds.resolve("InputImages/run1/img.tif").toFile().getAbsolutePath(), p.getSourceImages().getInputPaths().get(0));
        JSONObject json = xp.toJSONEntry();
        String s = json.toJSONString().replace("\\/", "/");
        assertFalse("no path outside of the dataset folder: " + s, s.contains(root.getAbsolutePath().replace(File.separatorChar, '/') + "/Calibration") || s.contains("/Microscope/"));
        assertTrue(s, s.contains("InputImages/run1/img.tif"));
        // dataset folder moved: the configuration still finds its files
        File moved = new File(root, "Moved/dataset1");
        org.apache.commons.io.FileUtils.copyDirectory(ds.toFile(), moved);
        Experiment xp2 = new Experiment("dataset1").setPath(moved.toPath());
        xp2.initFromJSONEntry(json);
        assertEquals(new File(moved, "InputImages/run1/img.tif").getAbsolutePath(), xp2.getPosition(0).getSourceImages().getInputPaths().get(0));
        BackgroundAndShadingCorrection corr2 = (BackgroundAndShadingCorrection) xp2.getPreProcessingTemplate().getTransformations(false).get(0).instantiatePlugin();
        assertEquals(new File(moved, "Resources/flat.tif").getAbsolutePath(), getFile(corr2, "flatField"));
    }

    static void setFile(Object plugin, String field, String path) throws Exception {
        Field f = plugin.getClass().getDeclaredField(field);
        f.setAccessible(true);
        ((FileChooser) f.get(plugin)).setSelectedFilePath(path);
    }

    static void setBoolean(Object plugin, String field, boolean value) throws Exception {
        Field f = plugin.getClass().getDeclaredField(field);
        f.setAccessible(true);
        ((bacmman.configuration.parameters.BooleanParameter) f.get(plugin)).setSelected(value);
    }

    static String getFile(Object plugin, String field) throws Exception {
        Field f = plugin.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return ((FileChooser) f.get(plugin)).getFirstSelectedFilePath();
    }

    @Test
    public void modelsInDLModelsFolder() throws Exception {
        File root = tmp.getRoot().getCanonicalFile();
        Path ds = new File(root, "Images/dataset1").toPath();
        Files.createDirectories(ds);
        Experiment xp = new Experiment("dataset1").setPath(ds);
        // model stored in a local library: weights folder
        bacmman.github.gist.LocalRepository lib = new bacmman.github.gist.LocalRepository(new File(root, "Library"));
        File weights = new File(root, "tmpw/my_model");
        weights.mkdirs();
        Files.write(new File(weights, "saved_model.pb").toPath(), new byte[200]);
        String fileId = bacmman.github.gist.LargeFileGist.storeFile(lib, weights, true, "weights", "dl_model", false, null, null).key;
        // configured from the library: default folder DLModels of the dataset
        bacmman.configuration.parameters.MLModelFileParameter model = new bacmman.configuration.parameters.MLModelFileParameter("Model");
        bacmman.configuration.parameters.GroupParameter group = new bacmman.configuration.parameters.GroupParameter("DL Engine", model);
        group.setParent(xp);
        model.configureFromMetadata(lib.toURL(fileId), new bacmman.github.gist.DLModelMetadata());
        File modelDir = ds.resolve(DatasetFiles.MODEL_FOLDER).toFile();
        assertTrue("folder DLModels created", modelDir.isDirectory());
        assertEquals(modelDir.getAbsolutePath(), model.getModelFilePath());
        // linked but not downloaded
        org.apache.commons.io.FileUtils.deleteDirectory(modelDir);
        assertTrue(model.isModelMissing());
        List<DatasetFiles.ModelToDownload> missing = new java.util.ArrayList<>();
        DatasetFiles.collectModels(group, xp, missing);
        assertEquals(1, missing.size());
        assertEquals("DL Engine > Model", missing.get(0).location);
        DatasetFiles.copyIntoDataset(xp, java.util.Collections.emptyList(), java.util.Collections.emptyList(), missing, null);
        File downloaded = new File(model.getModelFilePath());
        assertTrue("downloaded into DLModels: " + downloaded, downloaded.toPath().startsWith(modelDir.toPath()));
        assertTrue(new File(downloaded, "saved_model.pb").isFile());
        assertFalse(model.isModelMissing());
        // model file outside of the dataset: copied into DLModels
        bacmman.configuration.parameters.MLModelFileParameter model2 = new bacmman.configuration.parameters.MLModelFileParameter("Model");
        bacmman.configuration.parameters.GroupParameter group2 = new bacmman.configuration.parameters.GroupParameter("DL Engine 2", model2);
        group2.setParent(xp);
        File external = new File(root, "Models/other_model");
        external.mkdirs();
        Files.write(new File(external, "saved_model.pb").toPath(), new byte[50]);
        model2.setSelectedFilePath(external.getAbsolutePath());
        List<DatasetFiles.ExternalFile> files = new java.util.ArrayList<>();
        DatasetFiles.collect(group2, xp, ds, files);
        assertEquals(1, files.size());
        DatasetFiles.copyIntoDataset(xp, files, java.util.Collections.emptyList(), null);
        assertEquals(new File(modelDir, "other_model").getAbsolutePath(), model2.getModelFilePath());
        assertTrue(new File(modelDir, "other_model/saved_model.pb").isFile());
    }
}
