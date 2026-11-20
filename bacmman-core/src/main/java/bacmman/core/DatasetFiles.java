package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.ui.logger.ProgressDisplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Files used by a dataset configuration that are located outside of the dataset folder: files of parameters (e.g. model files) and input images.
 * They can be copied into the dataset folder, and the configuration modified in place to use the copies, so that the dataset folder is autonomous (it can be moved or copied to another computer)
 */
public class DatasetFiles {
    public static final Logger logger = LoggerFactory.getLogger(DatasetFiles.class);
    public static final String RESOURCE_FOLDER = "Resources", IMAGE_FOLDER = "InputImages", MODEL_FOLDER = bacmman.configuration.parameters.MLModelFileParameter.MODEL_FOLDER;

    /**
     * DL model linked (model id) whose model file is not on this computer: it can be downloaded into the folder {@link #MODEL_FOLDER} of the dataset
     */
    public static class ModelToDownload {
        public final bacmman.configuration.parameters.MLModelFileParameter parameter;
        public final String location;
        ModelToDownload(bacmman.configuration.parameters.MLModelFileParameter parameter, String location) {
            this.parameter = parameter;
            this.location = location;
        }
    }

    /**
     * @return DL model parameters whose model is linked but not downloaded
     */
    public static List<ModelToDownload> findModelsToDownload(Experiment xp) {
        List<ModelToDownload> res = new ArrayList<>();
        collectModels(xp, xp, res);
        return res;
    }

    static void collectModels(ContainerParameter<?, ?> container, Experiment xp, List<ModelToDownload> res) {
        for (Object o : container.getChildren()) {
            if (o instanceof bacmman.configuration.parameters.MLModelFileParameter && ((bacmman.configuration.parameters.MLModelFileParameter) o).isModelMissing()) res.add(new ModelToDownload((bacmman.configuration.parameters.MLModelFileParameter) o, location((Parameter) o, xp)));
            else if (o instanceof ContainerParameter) collectModels((ContainerParameter<?, ?>) o, xp, res);
        }
    }

    /**
     * Files of a parameter located outside of the dataset folder
     */
    public static class ExternalFile {
        public final FileChooser parameter;
        public final String location; // e.g. "Position xy01 > Pre-processing > Flat-field image"
        public final List<String> paths; // absolute paths outside of the dataset folder
        public final long size; // bytes
        ExternalFile(FileChooser parameter, String location, List<String> paths) {
            this.parameter = parameter;
            this.location = location;
            this.paths = paths;
            this.size = paths.stream().mapToLong(p -> size(Paths.get(p), true)).sum();
        }
    }

    /**
     * Folder containing input images, located outside of the dataset folder
     */
    public static class ImageFolder {
        public final String folder;
        public final List<Position> positions = new ArrayList<>();
        final Set<String> directoryImages = new HashSet<>(); // images that are folders (e.g. zarr): copied with their content
        public long size; // bytes: files of the folder and images that are folders
        ImageFolder(String folder) {
            this.folder = folder;
        }
    }

    public static boolean isInside(String path, Path datasetDir) {
        if (path == null || datasetDir == null) return true;
        try {
            Path p = Paths.get(path).toAbsolutePath().normalize();
            return p.startsWith(datasetDir.toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return true; // not a path: not copied
        }
    }

    /**
     * @return parameters of the configuration whose selected files exist outside of the dataset folder (output folders of the dataset are not included)
     */
    public static List<ExternalFile> findExternalFiles(Experiment xp) {
        Path dir = xp.getPath();
        List<ExternalFile> res = new ArrayList<>();
        if (dir == null) return res;
        collect(xp, xp, dir, res);
        return res;
    }

    static void collect(ContainerParameter<?, ?> container, Experiment xp, Path dir, List<ExternalFile> res) {
        for (Object o : container.getChildren()) {
            if (o instanceof FileChooser) {
                FileChooser fc = (FileChooser) o;
                if (container == xp) continue; // output folders of the dataset
                List<String> outside = Stream.of(fc.getSelectedFilePath()).filter(Objects::nonNull).filter(p -> !p.isEmpty())
                        .filter(p -> !isInside(p, dir)).filter(p -> Files.exists(Paths.get(p))).collect(Collectors.toList());
                if (!outside.isEmpty()) res.add(new ExternalFile(fc, location(fc, xp), outside));
            } else if (o instanceof ContainerParameter) collect((ContainerParameter<?, ?>) o, xp, dir, res);
        }
    }

    static String location(Parameter p, Experiment xp) {
        LinkedList<String> names = new LinkedList<>();
        Parameter c = p;
        while (c != null && c != xp) {
            String n = c.toString();
            if (c instanceof Position) n = "Position " + ((Position) c).getName();
            else if (c instanceof FileChooser) n = c.getName();
            else if (c instanceof bacmman.configuration.parameters.ConditionalParameter || c instanceof bacmman.configuration.parameters.ListParameter) n = null; // options and lists: not needed to locate the parameter
            if (n != null && !n.isEmpty()) names.addFirst(n);
            c = (Parameter) c.getParent();
        }
        return String.join(" > ", names);
    }

    /**
     * @return folders containing input images that are located outside of the dataset folder
     */
    public static List<ImageFolder> findExternalImageFolders(Experiment xp) {
        Path dir = xp.getPath();
        Map<String, ImageFolder> res = new LinkedHashMap<>();
        if (dir == null) return new ArrayList<>();
        for (Position p : xp.getPositionParameter().getChildren()) {
            MultipleImageContainer images = p.getSourceImages();
            if (images == null) continue;
            List<String> paths = images.getInputPaths();
            for (String folder : images.getInputFolders()) {
                if (isInside(folder, dir) || !Files.isDirectory(Paths.get(folder))) continue;
                ImageFolder f = res.computeIfAbsent(folder, ImageFolder::new);
                if (!f.positions.contains(p)) f.positions.add(p);
                for (String path : paths) if (Files.isDirectory(Paths.get(path)) && folder.equals(new File(path).getParent())) f.directoryImages.add(path);
            }
        }
        for (ImageFolder f : res.values()) {
            f.size = size(Paths.get(f.folder), false) + f.directoryImages.stream().mapToLong(d -> size(Paths.get(d), true)).sum();
        }
        return new ArrayList<>(res.values());
    }

    /**
     * Copies the files into the dataset folder, and modifies the configuration in place to use the copies. The configuration must be saved afterwards.
     * Files: copied into {@link #RESOURCE_FOLDER}. Image folders: their files (and images that are folders) are copied into {@link #IMAGE_FOLDER}. A file or folder used several times is copied once
     * @return number of copied files and folders
     */
    public static int copyIntoDataset(Experiment xp, Collection<ExternalFile> files, Collection<ImageFolder> imageFolders, ProgressDisplay pd) throws IOException {
        return copyIntoDataset(xp, files, imageFolders, Collections.emptyList(), pd);
    }

    /**
     * Same as {@link #copyIntoDataset(Experiment, Collection, Collection, ProgressDisplay)}, and downloads the models into {@link #MODEL_FOLDER}. Files of DL model parameters are copied into {@link #MODEL_FOLDER}
     */
    public static int copyIntoDataset(Experiment xp, Collection<ExternalFile> files, Collection<ImageFolder> imageFolders, Collection<ModelToDownload> models, ProgressDisplay pd) throws IOException {
        Path dir = xp.getPath();
        if (dir == null) throw new IOException("The dataset has no folder");
        long total = files.stream().mapToLong(f -> f.size).sum() + imageFolders.stream().mapToLong(f -> f.size).sum();
        long[] done = new long[1];
        Map<String, String> copied = new HashMap<>(); // source -> destination
        int count = 0;
        // files of parameters
        Path resources = dir.resolve(RESOURCE_FOLDER);
        for (ExternalFile f : files) {
            String[] selected = f.parameter.getSelectedFilePath();
            for (int i = 0; i < selected.length; ++i) {
                String src = selected[i];
                if (!f.paths.contains(src)) continue;
                String dest = copied.get(src);
                if (dest == null) {
                    message(pd, "Copying " + src);
                    boolean model = f.parameter.getParent() instanceof bacmman.configuration.parameters.MLModelFileParameter;
                    Path d = unique(model ? dir.resolve(MODEL_FOLDER) : resources, Paths.get(src).getFileName().toString());
                    copy(Paths.get(src), d, true, done, total, pd);
                    dest = d.toAbsolutePath().toString();
                    copied.put(src, dest);
                    ++count;
                }
                selected[i] = dest;
            }
            f.parameter.setSelectedFilePath(selected); // stored relative to the dataset folder if the parameter uses relative paths
        }
        // input images
        Path imagesDir = dir.resolve(IMAGE_FOLDER);
        Map<String, String> folderMapping = new HashMap<>();
        for (ImageFolder f : imageFolders) {
            message(pd, "Copying input images of " + f.folder);
            Path d = unique(imagesDir, Paths.get(f.folder).getFileName().toString());
            Files.createDirectories(d);
            try (Stream<Path> s = Files.list(Paths.get(f.folder))) {
                for (Path p : s.collect(Collectors.toList())) {
                    if (Files.isRegularFile(p)) copy(p, d.resolve(p.getFileName().toString()), false, done, total, pd);
                    else if (f.directoryImages.contains(p.toAbsolutePath().toString())) copy(p, d.resolve(p.getFileName().toString()), true, done, total, pd);
                }
            }
            folderMapping.put(f.folder, d.toAbsolutePath().toString());
            ++count;
        }
        if (!folderMapping.isEmpty()) {
            Set<Position> positions = imageFolders.stream().flatMap(f -> f.positions.stream()).collect(Collectors.toCollection(LinkedHashSet::new));
            for (Position p : positions) p.getSourceImages().relocateInputFolders(folderMapping::get);
        }
        // models linked but not downloaded
        for (ModelToDownload m : models) {
            message(pd, "Downloading model " + m.parameter.getID());
            m.parameter.downloadToDataset(pd);
            ++count;
        }
        if (pd != null) pd.setProgress(100);
        return count;
    }

    static void message(ProgressDisplay pd, String m) {
        logger.debug(m);
        if (pd != null) pd.setMessage(m);
    }

    /**
     * @return path in {@param folder} named {@param name}, or name_2, name_3... if it already exists
     */
    static Path unique(Path folder, String name) {
        Path p = folder.resolve(name);
        if (!Files.exists(p)) return p;
        String base = name, ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) {
            base = name.substring(0, i);
            ext = name.substring(i);
        }
        int n = 2;
        while (Files.exists(p)) p = folder.resolve(base + "_" + (n++) + ext);
        return p;
    }

    static void copy(Path src, Path dest, boolean recursive, long[] done, long total, ProgressDisplay pd) throws IOException {
        if (Files.isDirectory(src)) {
            if (!recursive) return;
            Files.createDirectories(dest);
            try (Stream<Path> s = Files.list(src)) {
                for (Path p : s.collect(Collectors.toList())) copy(p, dest.resolve(p.getFileName().toString()), true, done, total, pd);
            }
        } else {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES);
            done[0] += Files.size(src);
            if (pd != null && total > 0) pd.setProgress((int) (100 * done[0] / total));
        }
    }

    /**
     * @param recursive whether the content of sub-folders is counted
     * @return size in bytes of a file, or of the files of a folder
     */
    public static long size(Path p, boolean recursive) {
        try {
            if (Files.isRegularFile(p)) return Files.size(p);
            if (!Files.isDirectory(p)) return 0;
            try (Stream<Path> s = recursive ? Files.walk(p) : Files.list(p)) {
                return s.filter(Files::isRegularFile).mapToLong(f -> {
                    try {
                        return Files.size(f);
                    } catch (IOException e) {
                        return 0;
                    }
                }).sum();
            }
        } catch (IOException e) {
            return 0;
        }
    }
}
