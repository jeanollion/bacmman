package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
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
            .addValidationFunction(l -> Arrays.stream(l.getArrayInt()).allMatch(i->i>0)).setEmphasized(true);
    SimpleListParameter<ArrayNumberParameter> inputShapes = new SimpleListParameter<>("Input Shapes", 0, inputShape).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1).setEmphasized(true);

    public DL4Jengine() {
        inputShape.resetName(null);
        inputShape.addListener(l -> l.resetName(null));
    }
    public DL4Jengine(int batchSize, int[][] inputShapes) {
        this();
        this.batchSize.setValue(batchSize);
        if (inputShapes==null || inputShapes.length==0) return;
        this.inputShapes.setChildrenNumber(inputShapes.length);
        for (int i = 0; i<inputShapes.length; ++i) {
            this.inputShapes.getChildAt(i).setValue(ArrayUtil.toDouble(inputShapes[i]));
        }
    }

    @Override
    public int[][] getInputShapes() {
        return inputShapes.getActivatedChildren().stream().map(a -> a.getArrayInt()).toArray(int[][]::new);
    }
    private ComputationGraph model;

    @Override
    public void init() {
        String modelFile = this.modelFile.getFirstSelectedFilePath();
        if (!new File(modelFile).exists()) throw new RuntimeException("Model file not found: "+modelFile);
        try {
            model = KerasModelImport.importKerasModelAndWeights(modelFile, false);
        } catch (IOException | UnsupportedKerasConfigurationException | InvalidKerasConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized Image[][][] process(Image[][]... inputNC) {
        if (model==null) init();
        int batchSize = this.batchSize.getValue().intValue();
        int[][] inputShapes = getInputShapes();
        int nSamples = inputNC[0].length;
        for (int i = 0; i<inputNC.length; ++i) {
            int[][] shapes = getShapes(inputNC[i], true);
            if (!allShapeEqual(shapes, inputShapes[i])) throw new IllegalArgumentException("Input # "+i+": At least one image have a shapes from input shape: "+inputShapes[i]);
            if (nSamples != inputNC[i].length) throw new IllegalArgumentException("nSamples from input #"+i+"("+inputNC[0].length+") differs from input #0 = "+nSamples);
        }
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        for (int idx = 0; idx<nSamples; idx+=batchSize) {
            int idxMax = Math.min(idx+batchSize, nSamples);
            logger.debug("batch: [{};{}[", idx, idxMax);
            final int fidx = idx;
            INDArray[] input = Arrays.stream(inputNC).map(in -> INDArrayImageWrapper.fromImagesNC(in, fidx, idxMax)).toArray(INDArray[]::new);
            INDArray[] output = model.output(input);
            for (int outI = 0; outI<res.length; ++outI) {
                for (int i = idx; i<idxMax; ++i) res[outI][i] = INDArrayImageWrapper.getImagesC(output[outI], i-idx);
            }
        }
        return res;
    }
    @Override
    public int getNumOutputArrays() {
        return model.getNumOutputArrays();
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, inputShapes};
    }

    // x , y

}
