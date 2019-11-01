package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.dl.Utils;
import bacmman.tf.TensorWrapper;
import bacmman.utils.ArrayUtil;
import org.scijava.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.*;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static bacmman.processing.ResizeUtils.allShapeEqual;
import static bacmman.processing.ResizeUtils.getShapes;

public class TFengine implements DLengine {
    Logger logger = LoggerFactory.getLogger(DLengine.class);
    FileChooser modelFile = new FileChooser("Tensorflow graph file", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 64, 0, null);
    SimpleListParameter<TextParameter> inputs = new SimpleListParameter<>("Input layer names", 0, new TextParameter("layer name", "input", true, false)).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1);
    SimpleListParameter<TextParameter> outputs = new SimpleListParameter<>("Output layer names", 0, new TextParameter("layer name", "output", true, false)).setNewInstanceNameFunction((s, i)->"output #"+i).setChildrenNumber(1);
    Graph graph;

    public TFengine setModelPath(String path) {
        this.modelFile.setSelectedFilePath(path);
        return this;
    }

    public TFengine setBatchSize(int batchSize) {
        this.batchSize.setValue(batchSize);
        return this;
    }

    @Override
    public TFengine setOutputNumber(int outputNumber) {
        if (outputNumber<1) throw new IllegalArgumentException("Invalid output number:"+outputNumber);
        boolean oneOutput = getNumOutputArrays()==1;
        outputs.setChildrenNumber(outputNumber);
        if (oneOutput) { // modify the output name by adding a the index
            String name = outputs.getChildAt(0).getValue();
            for (int i = 0; i<outputNumber; ++i) outputs.getChildAt(i).setValue(name+i);
        }
        return this;
    }
    @Override
    public int getNumOutputArrays() {
        return outputs.getActivatedChildCount();
    }

    @Override
    public void close() {
        if (graph!=null) graph.close();
    }

    @Override
    public void init() {
        try {
            TensorFlow.version();
        } catch(UnsatisfiedLinkError e) {
            logger.error("Error while loading tensorflow:", e);
        }
        logger.debug("tensorflow version: {}", TensorFlow.version());
        // TODO: load library... use tensorflow service ?
        //model = SavedModelBundle.load("/data/Images/MOP/data_segDis_resampled/seg_edm16dy24_model/", "serve");
        final byte[] graphDef;
        if (modelFile.getFirstSelectedFilePath()==null || !new File(modelFile.getFirstSelectedFilePath()).exists()) return;
        try {
            graphDef = FileUtils.readFile(new File(modelFile.getFirstSelectedFilePath()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Convert to a TensorFlow Graph object.
        graph = new Graph();
        graph.importGraphDef(graphDef);
        logger.debug("model loaded!");
        boolean[] missingLayer = new boolean[2];
        inputs.getActivatedChildren().stream().map(i->i.getValue()).forEach(i -> {
            if (graph.operation(i)==null) {
                logger.error("Input layer {} not found in graph", i);
                missingLayer[0] = true;
            }
        });
        outputs.getActivatedChildren().stream().forEach(o -> {
            String name = findOutputName(o.getValue());
            if (name==null) {
                logger.error("Output layer {} not found in graph", o.getValue());
                missingLayer[1] = true;
            } else o.setValue(name); // name may have changed
        });
        if (missingLayer[0] || missingLayer[1]) {
            logger.info("List of all operation from graph:");
            logOperations();
            String err;
            if (missingLayer[0] && missingLayer[1]) err = "Input and output";
            else if (missingLayer[0]) err="Input";
            else err="Output";
            throw new RuntimeException(err+" layer not found in graph");
        }
    }

    static String[] activations = new String[]{"Relu", "Tanh", "Sigmoid", "Softmax", "Atan", "LeakyRelu"}; // TODO add other activations
    protected String findOutputName(String name) {
        if (graph.operation(name)!=null) return name;
        // test with activation layers
        for (String activation : activations) {
            String newName = name + "/" + activation;
            Operation o = graph.operation(newName);
            if (o!=null) return newName;
        }
        // case of "linear" activation ie no activation
        String newName = name + "/" + "BiasAdd";
        Operation o = graph.operation(newName);
        if (o!=null) return newName;

        if (!name.endsWith("_1")) return findOutputName(name + "_1");
        return null;
    }
    public void logOperations() {
        graph.operations().forEachRemaining(o -> {
            logger.info("operation: {}, type: {}", o.name(), o.type());
        });
    }
    public synchronized Image[][][] process(Image[][]... inputNC) {
        int batchSize = this.batchSize.getValue().intValue();
        int nSamples = inputNC[0].length;
        /*int[][] inputShapes = getInputShapes();
        for (int i = 0; i<inputNC.length; ++i) {
            int[][] shapes = getShapes(inputNC[i], true);
            if (!allShapeEqual(shapes, inputShapes[i])) throw new IllegalArgumentException("Input # "+i+": At least one image have a shapes from input shape: "+Arrays.toString(inputShapes[i]));
            if (nSamples != inputNC[i].length) throw new IllegalArgumentException("nSamples from input #"+i+"("+inputNC[0].length+") differs from input #0 = "+nSamples);
        }
        // scale inputs
        List<HistogramScaler> scalers = this.inputScalers.getAll();
        for (int i = 0; i<inputNC.length; ++i) Utils.scale(inputNC[i], scalers.get(i));
        */
        final Session s = new Session(graph);
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        float[][] bufferContainer = new float[1][];
        long wrapTime = 0, predictTime = 0;
        String[] inputNames = this.inputs.getActivatedChildren().stream().map(t->t.getValue()).toArray(String[]::new);
        String[] outputNames = this.outputs.getActivatedChildren().stream().map(t->t.getValue()).toArray(String[]::new);

        for (int idx = 0; idx<nSamples; idx+=batchSize) {
            int idxMax = Math.min(idx+batchSize, nSamples);
            logger.debug("batch: [{};{}[", idx, idxMax);
            final int fidx = idx;
            long t0 = System.currentTimeMillis();
            Tensor<Float>[] input = Arrays.stream(inputNC).map(imNC ->  TensorWrapper.fromImagesNC(imNC, fidx, idxMax, bufferContainer)).toArray(Tensor[]::new);
            long t1 = System.currentTimeMillis();
            Session.Runner r = s.runner();
            for (int ii = 0; ii<inputNames.length; ++ii)  r.feed(inputNames[ii], input[ii]);

                for (int io = 0; io < outputNames.length; ++io) {
                    try {
                        r.fetch(outputNames[io]);
                    } catch (IllegalArgumentException e) {
                        logger.error("No output named: {} in the graph. Check operation list:", outputNames[io]);
                        logOperations();
                        throw e;
                    }
                }

            List<Tensor<?>> outputs = r.run();
            long t2 = System.currentTimeMillis();
            for (int io = 0; io<outputNames.length; ++io) {
                Image[][] resIm = TensorWrapper.getImagesNC((Tensor<Float>)outputs.get(io), bufferContainer);
                for (int i = idx;  i<idxMax; ++i) res[io][i] = resIm[i-idx];
            }
            long t3 = System.currentTimeMillis();
            wrapTime+=t1-t0 + t3 - t2;
            predictTime += t2-t1;

        }
        logger.debug("prediction: {}ms, image wrapping: {}ms", predictTime, wrapTime);
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, inputs, outputs};
    }
}
