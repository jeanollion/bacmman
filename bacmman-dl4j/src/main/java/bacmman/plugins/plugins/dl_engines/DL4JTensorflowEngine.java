package bacmman.plugins.plugins.dl_engines;

import org.nd4j.autodiff.execution.NativeGraphExecutioner;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.imports.graphmapper.tf.TFGraphMapper;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.File;
import java.util.List;

public class DL4JTensorflowEngine extends DL4JEngine {
    SameDiff graph;
    NativeGraphExecutioner executioner;
    //SimpleListParameter<TextParameter> inputNames = new SimpleListParameter<TextParameter>("Input layer names", 0, new TextParameter("Layer name", "input", true, false)).setChildrenNumber(1);
    List<String> inputNames;
    @Override
    public void init() {
        File modelDir = new File(modelFile.getFirstSelectedFilePath());
        if (modelDir.isFile()) modelDir = modelDir.getParentFile();
        graph = TFGraphMapper.getInstance().importGraph(modelDir);
        if (graph == null){
            throw new RuntimeException("Error loading model : " + modelFile.getFirstSelectedFilePath());
        } else if (graph.inputs().size() != this.inputShapes.getActivatedChildren().size()) {
            throw new IllegalArgumentException("Model has "+graph.inputs().size()+" inputs, whereas "+this.inputShapes.getActivatedChildren().size()+" input shapes are provided");
        }

        executioner = new NativeGraphExecutioner();
        inputNames = graph.inputs();
        logger.debug("graph inputs: {}, outputs: {}", inputNames, graph.outputs());
    }

    @Override
    public int getNumOutputArrays() {
        return graph.outputs().size();
    }

    @Override
    protected INDArray[] run(INDArray[] inputs) {
        for (int i = 0; i<inputs.length; ++i) {
            graph.associateArrayWithVariable(inputs[i], graph.variableMap().get(this.inputNames.get(i)));
        }
        return executioner.executeGraph(graph);
    }

    /*@Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, inputNames, inputShapes, lambdalayers};
    }*/
}
