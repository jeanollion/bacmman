package bacmman.plugins;

import bacmman.configuration.parameters.Parameter;
import bacmman.image.Image;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes exported datasets to a file.
 * Implementations are plugins provided by other modules (HDF5 in bacmman-dl). An instance writes to a single file: it is opened with {@link #open(Path, boolean)}, and must be closed.
 * Use {@link #create(Path, boolean)} to get an opened writer.
 */
public interface DatasetWriter extends Plugin, AutoCloseable {
    /**
     * Opens the file. Called once, before any write.
     * @param append if true and file exists, entries are added to the file, otherwise the file is overwritten
     */
    void open(Path file, boolean append);

    /**
     * Writes images in a single array, with images along the first axis.
     * @param dsName path of the array within the file
     * @param images images of same type and dimensions
     * @param compression compression level
     * @param labelSuffix if not null, image names are saved as labels in an entry whose name ends with this suffix
     * @param originalDimensions if not null, saved alongside the array
     * @param metadata if not null, saved as attributes of the array
     * @throws IllegalStateException if writer is not opened
     */
    void writeImages(String dsName, List<Image> images, int compression, String labelSuffix, int[][] originalDimensions, Map<String, Object> metadata);

    @Override
    void close();

    @Override
    default Parameter[] getParameters() {
        return new Parameter[0];
    }

    /**
     * @return an opened writer, instance of the first available DatasetWriter plugin
     * @throws IllegalStateException if no implementation is available
     */
    static DatasetWriter create(Path file, boolean append) {
        List<String> writers = PluginFactory.getPluginNames(DatasetWriter.class);
        if (writers.isEmpty()) throw new IllegalStateException("No dataset writer available: bacmman-dl module is required to export datasets");
        DatasetWriter writer = PluginFactory.getPlugin(DatasetWriter.class, writers.get(0));
        writer.open(file, append);
        return writer;
    }
}
