package bacmman.dl4j_keras;

import bacmman.image.Image;
import bacmman.py_dataset.Utils;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.*;
import java.util.stream.IntStream;

import static bacmman.py_dataset.Utils.*;

public class DL4Jengine {
    final private ComputationGraph model;
    final private int batchSize;
    final private int[] inputShape, inputShapeSpa; // nChannels, (Z) Y, X
    public DL4Jengine(String modelFile, int batchSize, int[] inputShape) {
        this.inputShape=inputShape;
        this.inputShapeSpa = IntStream.range(1, inputShape.length).map(i->inputShape[i]).toArray();
        try {
            model = KerasModelImport.importKerasModelAndWeights(modelFile, false);
        } catch (IOException | UnsupportedKerasConfigurationException | InvalidKerasConfigurationException e) {
            throw new RuntimeException(e);
        }
        this.batchSize=batchSize;

    }
    public int[] getInputShape() {
        return inputShape;
    }
    public synchronized Image[][] process(Image[][] inputNC) {
        int[][] shapes = getShapes(inputNC, true);
        if (!allShapeEqual(shapes, inputShape)) throw new IllegalArgumentException("At least two images have shapes that differ");

        Image[][] res = new Image[inputNC.length][];
        for (int idx = 0; idx<inputNC.length; idx+=batchSize) {
            int idxMax = Math.min(idx+batchSize, inputNC.length);
            INDArray input = INDArrayImageWrapper.fromImagesNC(inputNC, idx, idxMax);
            INDArray output = model.output(input)[0];
            for (int i = 0; i<idxMax; ++i) res[i] = INDArrayImageWrapper.getImagesC(output, i);
        }
        return res;
    }

    // x , y

}
