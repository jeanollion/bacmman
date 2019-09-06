package bacmman.dl4j_keras;

import bacmman.core.Core;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.processing.ImageOperations;
import bacmman.py_dataset.PyDatasetReader;
import bacmman.utils.Utils;
import ij.ImageJ;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.function.Function;

public class TestImport {
    public final static Logger logger = LoggerFactory.getLogger(TestImport.class);
    public static void main(String[] args) {

        ComputationGraph model=null;
        String modelFile = "/data/Images/MOP/data_segDis_resampled/seg_and_track_model.h5";
        String inputImages = "/data/Images/MOP/data_segDis_resampled/bacteriaSegTrackToPredict.h5";
        PyDatasetReader reader = new PyDatasetReader(new File(inputImages));
        PyDatasetReader.DatasetAccess dsA = reader.getDatasetAccess("ds_test", "MutH_151220");
        Image[] images = dsA.getImages("raw", "xy02", new int[]{0, 1}, false, false);
        Histogram histo = dsA.getHistogram("raw");
        double[] quantiles = histo.getQuantiles(0.05, 0.5, 0.95);
        double IQR = quantiles[2] - quantiles[0];
        double center = quantiles[1];
        Function<Image, Image> normalize = im -> ImageOperations.affineOperation2(im, null, 1d/IQR, -center);
        images = Utils.transform(images, Image[]::new, normalize);
        new ImageJ();

        Core.showImage(images[0].setName("prev"));
        Core.showImage(images[1].setName("cur"));
        try {
            model = KerasModelImport.importKerasModelAndWeights(modelFile, false);
        } catch (IOException|UnsupportedKerasConfigurationException|InvalidKerasConfigurationException e) {
            e.printStackTrace();
        }
        long t0 = System.currentTimeMillis();
        INDArray input = getInput(images);
        //input = Nd4j.concat(0, input, input);
        //input = Nd4j.concat(0, input, input, input, input, input, input, input, input);
        //input = Nd4j.concat(0, input, input);
        //input = Nd4j.concat(0, input, input);
        //input = Nd4j.concat(0, input, input);
        //64 -> 3s
        //128 -> 5.8s
        //256 -> 10s (max) (4ms/ image pour Keras sur google colab = 10x plus rapide)
        //
        logger.debug("input shape: {}", input.shape());
        long t1 = System.currentTimeMillis();
        INDArray output = model.output(input)[0];
        long t2 = System.currentTimeMillis();

        Image edm = INDArrayImageWrapper.getImage(output, 0, 0).setName("edm");
        Image dy = INDArrayImageWrapper.getImage(output, 0, 1).setName("dy");
        long t3 = System.currentTimeMillis();
        Core.showImage(edm);
        Core.showImage(dy);
        logger.debug("get indarrays : {} predict {}, get images {}", t1-t0, t2-t1, t3-t2);
    }

    public static INDArray getInput(Image[] curPrev) {
        INDArray prev = INDArrayImageWrapper.fromImage(curPrev[0]);
        INDArray cur = INDArrayImageWrapper.fromImage(curPrev[1]);
        return Nd4j.concat(1, prev, cur);
    }
}
