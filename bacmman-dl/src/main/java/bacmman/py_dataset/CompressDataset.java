package bacmman.py_dataset;

import bacmman.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

public class CompressDataset {
    public final static Logger logger = LoggerFactory.getLogger(CompressDataset.class);
    public static void main(String[] args) {
        String input = "/data/Images/MOP/data_segDis_resampled/predictions.h5";
        String output = "/data/Images/MOP/data_segDis_resampled/predictionsComp.h5";
        File outFile = new File(output);
        PyDatasetReader reader = new PyDatasetReader(new File(input));
        reader.getDBs().forEach(p -> {
            PyDatasetReader.DatasetAccess ds = reader.getDatasetAccess(p.key, p.value);
            ds.positions.forEach(pos -> {
                List<String> datasets = ds.getDatasetNames(pos);
                datasets.removeIf(s->s.equals("originalDims") || s.equals("labels"));
                boolean[] savedLabels = new boolean[1];
                datasets.stream().filter(s->!s.equals("labels") && !s.equals("originalDimensions")).forEach(dsName -> {
                    logger.debug("compressing dataset: {}/{}", ds.dsName(pos), dsName);
                    long t1 = System.currentTimeMillis();
                    List<Image> images = ds.extractImagesForPositions(dsName, new String[]{pos}, false, false).collect(Collectors.toList());
                    long t2 = System.currentTimeMillis();
                    HDF5IO.savePyDataset(images, outFile, true, ds.dsName(pos)+ "/" +dsName, 4, !savedLabels[0], !savedLabels[0] ? ds.originalDims.get(pos) : null, null );
                    long t3 = System.currentTimeMillis();
                    //logger.debug("#{} images read in: {}ms written in: {}ms", images.size(), t2-t1, t3-t2);
                    if (!savedLabels[0]) savedLabels[0] = true;
                    // todo : also save metadata
                });
            });
        });
    }
}
