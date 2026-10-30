package bacmman.plugins.plugins.dataset_writer;

import bacmman.plugins.DatasetWriter;
import bacmman.image.Image;
import bacmman.py_dataset.HDF5IO;
import ch.systemsx.cisd.hdf5.IHDF5Writer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes exported datasets to HDF5 files, in the format read by python (dataset_iterator)
 */
public class HDF5DatasetWriter implements DatasetWriter {
    private IHDF5Writer writer;

    @Override
    public void open(Path file, boolean append) {
        if (writer != null) throw new IllegalStateException("Writer is already opened");
        writer = HDF5IO.getWriter(file.toFile(), append);
    }

    @Override
    public void writeImages(String dsName, List<Image> images, int compression, String labelSuffix, int[][] originalDimensions, Map<String, Object> metadata) {
        if (writer == null) throw new IllegalStateException("Writer is not opened");
        HDF5IO.savePyDataset(images, writer, dsName, compression, labelSuffix, originalDimensions, metadata);
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
