package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.tf.TensorWrapper;
import bacmman.utils.ArrayUtil;
import org.scijava.util.FileUtils;
import org.tensorflow.*;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static bacmman.processing.ResizeUtils.allShapeEqual;
import static bacmman.processing.ResizeUtils.getShapes;

public class TFengine implements DLengine {
    FileChooser modelFile = new FileChooser("Tensorflow model file", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
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
    SimpleListParameter<TextParameter> inputs = new SimpleListParameter<TextParameter>("Input layer names", 0, new TextParameter("layer name", "input", true, false)).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1).setEmphasized(true);
    SimpleListParameter<TextParameter> outputs = new SimpleListParameter<TextParameter>("Output layer names", 0, new TextParameter("layer name", "output", true, false)).setNewInstanceNameFunction((s, i)->"output #"+i).setChildrenNumber(1).setEmphasized(true);
    //SavedModelBundle model;
    Graph graph;
    public TFengine() {
        inputShape.resetName(null);
        inputShape.addListener(l -> l.resetName(null));
        inputShapes.addValidationFunction(l -> l.getActivatedChildCount() == inputs.getActivatedChildCount());
        inputs.addValidationFunction(l -> l.getActivatedChildCount() == inputShapes.getActivatedChildCount());
    }
    public TFengine setModelPath(String path) {
        this.modelFile.setSelectedFilePath(path);
        return this;
    }
    public TFengine(int batchSize, int[][] inputShapes) {
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

    @Override
    public int getNumOutputArrays() {
        return outputs.getActivatedChildCount();
    }

    @Override
    public void init() {
        logger.debug("tensorflow version: {}", TensorFlow.version());
        // TODO: load library... use tensorflow service ?
        //model = SavedModelBundle.load("/data/Images/MOP/data_segDis_resampled/seg_edm16dy24_model/", "serve");
        final byte[] graphDef;
        try {
            graphDef = FileUtils.readFile(new File(modelFile.getFirstSelectedFilePath()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Convert to a TensorFlow Graph object.
        graph = new Graph();
        graph.importGraphDef(graphDef);
        logger.debug("model loaded!");
        graph.operations().forEachRemaining(o -> {
            logger.debug("operation: {}", o.name());
        });
    }

    public synchronized Image[][][] process(Image[][]... inputNC) {
        int batchSize = this.batchSize.getValue().intValue();
        int[][] inputShapes = getInputShapes();
        int nSamples = inputNC[0].length;

        for (int i = 0; i<inputNC.length; ++i) {
            int[][] shapes = getShapes(inputNC[i], true);
            if (!allShapeEqual(shapes, inputShapes[i])) throw new IllegalArgumentException("Input # "+i+": At least one image have a shapes from input shape: "+Arrays.toString(inputShapes[i]));
            if (nSamples != inputNC[i].length) throw new IllegalArgumentException("nSamples from input #"+i+"("+inputNC[0].length+") differs from input #0 = "+nSamples);
        }
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
            for (int io = 0; io<outputNames.length; ++io) r.fetch(outputNames[io]);
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
        return new Parameter[]{modelFile, batchSize, inputShapes, inputs, outputs};
    }
}
