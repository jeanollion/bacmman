package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.tf.TensorWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.*;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static bacmman.processing.ResizeUtils.getShapes;

public class TFengine implements DLengine {
    Logger logger = LoggerFactory.getLogger(DLengine.class);
    FileChooser modelFile = new FileChooser("Tensorflow graph file", FileChooser.FileChooserOption.DIRECTORIES_ONLY, false).setEmphasized(true);
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 64, 0, null);
    SimpleListParameter<TextParameter> inputs = new SimpleListParameter<>("Input layer names", 0, new TextParameter("layer name", "input", true, false)).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1);
    SimpleListParameter<TextParameter> outputs = new SimpleListParameter<>("Output layer names", 0, new TextParameter("layer name", "output", true, false)).setNewInstanceNameFunction((s, i)->"output #"+i).setChildrenNumber(1);
    Graph graph;
    Session session;
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
        if (!oneOutput) { // modify the output name by adding a the index
            String name = outputs.getChildAt(0).getValue();
            if (name.endsWith("0")) name = name.substring(0, name.length()-1);
            for (int i = 0; i<outputNumber; ++i) outputs.getChildAt(i).setValue(name+i);
        }
        return this;
    }
    @Override
    public TFengine setInputNumber(int inputNumber) {
        if (inputNumber<1) throw new IllegalArgumentException("Invalid input number:"+inputNumber);
        boolean oneInput = getNumInputArrays()==1;
        inputs.setChildrenNumber(inputNumber);
        if (!oneInput) { // modify the input name by adding a the index
            String name = inputs.getChildAt(0).getValue();
            if (name.endsWith("0")) name = name.substring(0, name.length()-1);
            for (int i = 0; i<inputNumber; ++i) inputs.getChildAt(i).setValue(name+i);
        }
        return this;
    }
    @Override
    public int getNumOutputArrays() {
        return outputs.getActivatedChildCount();
    }

    @Override
    public int getNumInputArrays() {
        return inputs.getActivatedChildCount();
    }

    @Override
    public void close() {
        if (graph!=null) {
            graph.close();
            graph = null;
        }
        if (session!=null) {
            session.close();
            session = null;
        }
    }

    @Override
    public void init() {
        if (graph!=null && session !=null) return; // already init
        try {
            TensorFlow.version();
        } catch(UnsatisfiedLinkError e) {
            logger.error("Error while loading tensorflow:", e);
        }
        logger.debug("tensorflow version: {}", TensorFlow.version());
        SavedModelBundle model = SavedModelBundle.load(modelFile.getFirstSelectedFilePath(), "serve");
        session = model.session();
        graph = model.graph();
        logger.debug("model loaded!");
        boolean[] missingLayer = new boolean[2];
        logOperations();
        inputs.getActivatedChildren().stream().forEach(i -> {
            String name = findInputOperationName(i.getValue());
            if (name==null) {
                logger.error("Input layer \"{}\" not found in graph", name);
                missingLayer[0] = true;
            } else i.setValue(name);
        });
        outputs.getActivatedChildren().stream().forEach(o -> {
            String name = findOutputOperationName(o.getValue());
            if (name==null) {
                logger.error("Output layer {} not found in graph", o.getValue());
                missingLayer[1] = true;
            } else o.setValue(name); // name may have been changed by findOutputName
        });
        if (missingLayer[0] || missingLayer[1]) {
            logger.info("List of all operation from graph:");
            logOperations();
            String err;
            if (missingLayer[0] && missingLayer[1]) err = "Input and output";
            else if (missingLayer[0]) err="Input";
            else err="Output";
            throw new RuntimeException(err+" layer(s) not found in graph");
        }
    }
    protected String findInputOperationName(String name) {
        if (graph.operation(name)!=null) return name;
        if (graph.operation("serving_default_input")!=null) return "serving_default_input"; // TODO fix this: how input names are set in TF2.keras.model.save method ???
        Iterator<Operation> ops = graph.operations();
        String newName = null;
        while (ops.hasNext()) {
            Operation next = ops.next();
            if (!next.type().equals("Placeholder")) continue;
            if (next.name().startsWith(name)) newName = next.name();
        }
        return newName;
    }
    protected String findOutputOperationName(String name) {
        if (graph.operation(name)!=null) return name;
        // get last layer that starts with the name
        Iterator<Operation> ops = graph.operations();
        String newName=null;
        while (ops.hasNext()) {
            Operation next = ops.next();
            if (next.name().startsWith(name) && !next.name().endsWith("ReadVariableOp")) newName = next.name(); // ReadVariableOp is added in tf2.keras
        }
        if (newName!=null) {
            logger.debug("output: {} was found with operation name: {}", name, newName);
            return newName;
        }
        if (!name.endsWith("_1")) return findOutputOperationName(name + "_1");
        return null;
    }
    public void logOperations() {
        graph.operations().forEachRemaining(o -> {
            logger.info("operation: {}, type: {}", o.name(), o.type());
        });
    }
    public synchronized Image[][][] process(Image[][]... inputNC) {
        if (inputNC.length!=getNumInputArrays()) throw new IllegalArgumentException("Invalid number of input provided. Expected:"+getNumInputArrays()+" provided:"+inputNC.length);
        int batchSize = this.batchSize.getValue().intValue();
        int nSamples = inputNC[0].length;
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        float[][] bufferContainer = new float[1][];
        long wrapTime = 0, predictTime = 0;
        String[] inputNames = this.inputs.getActivatedChildren().stream().map(t->t.getValue()).toArray(String[]::new);
        String[] outputNames = this.outputs.getActivatedChildren().stream().map(t->t.getValue()).toArray(String[]::new);

        for (int idx = 0; idx<nSamples; idx+=batchSize) {
            int idxMax = Math.min(idx+batchSize, nSamples);
            logger.debug("batch: [{};{})", idx, idxMax);
            final int fidx = idx;
            long t0 = System.currentTimeMillis();
            Tensor<Float>[] input = Arrays.stream(inputNC).map(imNC ->  TensorWrapper.fromImagesNC(imNC, fidx, idxMax, bufferContainer)).toArray(Tensor[]::new);
            long t1 = System.currentTimeMillis();
            Session.Runner r = session.runner();
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
