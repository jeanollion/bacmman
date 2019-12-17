package bacmman.plugins.plugins.dl_engines;

import bacmman.plugins.DLengine;
import org.deeplearning4j.nn.conf.layers.samediff.SameDiffLambdaLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasLayer;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.File;
import java.io.IOException;

public class DL4JKerasEngine extends DL4JEngine{
    protected ComputationGraph model;
    int outputNumber, inputNumber;
    @Override
    public void init() {
        lambdalayers.getActivatedChildren().stream().map(pp -> pp.instantiatePlugin()).forEach(l -> {
            KerasLayer.registerLambdaLayer(l.getLayerName(), (SameDiffLambdaLayer)l);
            logger.debug("registering lambda layer: {} with {}", l.getLayerName(), l.getClass());
        });
        String modelFile = this.modelFile.getFirstSelectedFilePath();
        if (!new File(modelFile).exists()) throw new RuntimeException("Model file not found: "+modelFile);
        try {
            model = KerasModelImport.importKerasModelAndWeights(modelFile, false);
            if (inputNumber>0 && getNumInputArrays()!=inputNumber) throw new IllegalArgumentException("Invalid input number");
            if (outputNumber>0 && getNumOutputArrays()!=outputNumber) throw new IllegalArgumentException("Invalid output number");
        } catch (IOException | UnsupportedKerasConfigurationException | InvalidKerasConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected INDArray[] run(INDArray[] inputs) {
        return model.output(inputs);
    }
    @Override
    public int getNumOutputArrays() {
        return model.getNumOutputArrays();
    }

    @Override
    public int getNumInputArrays() {
        return model.getNumInputArrays();
    }

    @Override
    public DLengine setOutputNumber(int outputNumber) {
        this.outputNumber=outputNumber;
        return this;
    }

    @Override
    public DLengine setInputNumber(int outputNumber) {
        this.inputNumber=inputNumber;
        return this;
    }

    @Override
    public void close() {
        if (model!=null) model.clear();
    }
}
