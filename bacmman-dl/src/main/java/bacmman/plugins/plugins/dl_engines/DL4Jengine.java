package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.ArrayNumberParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.Parameter;
import bacmman.dl4j_keras.INDArrayImageWrapper;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.py_dataset.Utils;
import bacmman.utils.ArrayUtil;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.py_dataset.Utils.*;

public class DL4Jengine implements DLengine {
    FileChooser modelFile = new FileChooser("Keras model file", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 64, 0, null);
    ArrayNumberParameter inputShape = new ArrayNumberParameter("Input shape", 2, new BoundedNumberParameter("", 0, 0, 0, null))
            .setMaxChildCount(4)
            .setNewInstanceNameFunction((l,i) -> {
                if (l.getChildCount()==3 && i<3) return "CYX".substring(i, i+1);
                else {
                    if (i>3) i = 3;
                    return "CZYX".substring(i, i+1);
                }
            }).setValue(2, 256, 32).setAllowMoveChildren(false)
            .addValidationFunction(l -> Arrays.stream(l.getArrayInt()).allMatch(i->i>0));

    public DL4Jengine() {
        inputShape.resetName(null);
        inputShape.addListener(l -> l.resetName(null));
    }
    public DL4Jengine(int[] inputShape, int batchSize) {
        this();
        this.inputShape.setValue(ArrayUtil.toDouble(inputShape));
        this.batchSize.setValue(batchSize);
    }

    public int getInputRank() {
        return inputShape.getChildCount();
    }
    public int[] getInputShape() {
        return inputShape.getArrayInt();
    }
    private ComputationGraph model;
    private void init() {
        String modelFile = this.modelFile.getFirstSelectedFilePath();
        if (!new File(modelFile).exists()) throw new RuntimeException("Model file not found: "+modelFile);
        try {
            model = KerasModelImport.importKerasModelAndWeights(modelFile, false);
        } catch (IOException | UnsupportedKerasConfigurationException | InvalidKerasConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized Image[][] process(Image[][] inputNC) {
        if (model==null) init();
        int batchSize = this.batchSize.getValue().intValue();
        int[][] shapes = getShapes(inputNC, true);
        if (!allShapeEqual(shapes, getInputShape())) throw new IllegalArgumentException("At least one image have a shapes from input shape: "+getInputShape());

        Image[][] res = new Image[inputNC.length][];
        for (int idx = 0; idx<inputNC.length; idx+=batchSize) {
            int idxMax = Math.min(idx+batchSize, inputNC.length);
            logger.debug("batch: [{};{}[", idx, idxMax);
            INDArray input = INDArrayImageWrapper.fromImagesNC(inputNC, idx, idxMax);
            INDArray output = model.output(input)[0];
            for (int i = idx; i<idxMax; ++i) res[i] = INDArrayImageWrapper.getImagesC(output, i-idx);
        }
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, inputShape};
    }

    // x , y

}
