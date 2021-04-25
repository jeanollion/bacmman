package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.plugins.Hint;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.tf.TensorWrapper;
import bacmman.utils.ReflexionUtils;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.*;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

import static bacmman.processing.ResizeUtils.getShapes;

public class TFengine implements DLengine, Hint {
    Logger logger = LoggerFactory.getLogger(DLengine.class);
    FileChooser modelFile = new FileChooser("Tensorflow SavedModelBundle folder", FileChooser.FileChooserOption.DIRECTORIES_ONLY, false).setEmphasized(true).setHint("Select the folder containing the saved model (this folder should contain the .pb file). Model must be compiled with a tensorflow version inferior or equal to the java-tensorflow version");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 16, 0, null).setEmphasized(true).setHint("Size of the mini batches. Reduce to limit out-of-memory errors, and optimize according to the device");
    SimpleListParameter<TextParameter> inputs = new SimpleListParameter<>("Input layer names", 0, new TextParameter("layer name", "input", true, false)).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1).addValidationFunctionToChildren(t->((SimpleListParameter<TextParameter>)t.getParent()).getActivatedChildren().stream().filter(tt->!tt.equals(t)).map(TextParameter::getValue).noneMatch(v-> v.equals(t.getValue()))).setEmphasized(true).setHint("Name of the input layer(s): must correspond to the corresponding placeholder name in the saved model bundle");
    SimpleListParameter<TextParameter> outputs = new SimpleListParameter<>("Output layer names", 0, new TextParameter("layer name", "output", true, false)).setNewInstanceNameFunction((s, i)->"output #"+i).setChildrenNumber(1).addValidationFunctionToChildren(t->((SimpleListParameter<TextParameter>)t.getParent()).getActivatedChildren().stream().filter(tt->!tt.equals(t)).map(TextParameter::getValue).noneMatch(v-> v.equals(t.getValue()))).setEmphasized(true).setHint("Name of the output layer(s): must correspond to the corresponding output name in the saved model bundle");;
    ArrayNumberParameter flip = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0, 0}, 1).setName("Average Flipped predictions").setHint("If 1 is set to an axis, flipped image will be predicted and averaged with original image. If 1 is set to X and Y axis, 3 flips are performed (X, Y and XY) which results in a 4-fold prediction number");
    BooleanParameter ZasChannel = new BooleanParameter("Z as Channel", false).setHint("If true, Z axis will be considered as channel axis. If tensor has several channels only the first one will be used.");

    String[] inputNames, outputNames;
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
        outputs.setChildrenNumber(outputNumber);
        boolean oneOutput = getNumOutputArrays()==1;
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
        inputs.setChildrenNumber(inputNumber);
        boolean oneInput = getNumInputArrays()==1;
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

    public int getBatchSize() {
        return this.batchSize.getValue().intValue();
    }

    @Override
    public void close() {
        if (session!=null) {
            session.close();
            session = null;
        }
        if (graph!=null) {
            graph.close();
            graph = null;
        }
    }

    @Override
    public synchronized void init() {
        //ReflexionUtils.setvalueOnFinalField(DataType.FLOAT, "value", 20);
        if (graph!=null && session !=null) return; // already init
        if (graph==null && session== null) {
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
            initInputAndOutputNames();
        } else if (graph!=null && session == null) {
            //session = new Session(graph, null); // TODO: not working! Thrown errors such as: Error while reading resource variable encoder0_2_conv/kernel from Container: localhost. This could mean that the variable was uninitialized. Not found: Container localhost does not exist. (Could not find resource: localhost/encoder0_2_conv/kernel)
            graph.close();
            graph=null;
            init();
        }
    }
    private void initInputAndOutputNames() {
        boolean[] missingLayer = new boolean[2];
        //logOperations();
        inputNames = inputs.getActivatedChildren().stream().map(TextParameter::getValue).toArray(String[]::new);
        outputNames = outputs.getActivatedChildren().stream().map(TextParameter::getValue).toArray(String[]::new);
        for (int i = 0;i<inputNames.length; ++i) {
            String name = findInputOperationName(inputNames[i]);
            if (name == null) {
                logger.error("Input layer \"{}\" not found in graph", inputNames[i]);
                missingLayer[0] = true;
            } else inputNames[i] = name;// name may have been changed by findInputName
        }
        for (int i = 0;i<outputNames.length; ++i) {
            String name = findOutputOperationName(outputNames[i]);
            if (name == null) {
                logger.error("Output layer \"{}\" not found in graph", outputNames[i]);
                missingLayer[1] = true;
            } else outputNames[i] = name;// name may have been changed by findOutputName
        }
        if (missingLayer[0] || missingLayer[1]) {
            logger.info("List of all operation from graph:");
            logOperations();
            String err;
            if (missingLayer[0] && missingLayer[1]) err = "Input and output";
            else if (missingLayer[0]) err="Input";
            else err="Output";
            throw new RuntimeException(err+" layer(s) not found in graph");
        } //else logOperations();
    }
    protected String findInputOperationName(String name) {
        //if (graph.operation(name)!=null) return name; // issue when model is exported with tf.keras (1.15) -> two inputs are  present _1  -> return the last placeholder
        Iterator<Operation> ops = graph.operations();
        String newName=null;
        while (ops.hasNext()) { // return last placeholder whose name starts with "name"
            Operation next = ops.next();
            if (!next.type().equals("Placeholder")) continue;
            if (next.name().startsWith(name)) {
                logger.debug("placeholder tensor name found for input: {} -> {}", name, next.name());
                newName = next.name();
                //return next.name();
            }
        }
        if (newName!=null) return newName;
        if (graph.operation("serving_default_input")!=null) return "serving_default_input"; // TODO fix this: how input names are set in TF2.keras.model.save method ???
        return null;
    }
    protected String findOutputOperationName(String name) {
        if (graph.operation(name)!=null) return name;
        // get last layer that starts with the name
        Iterator<Operation> ops = graph.operations();
        String newName=null;
        while (ops.hasNext()) {
            Operation next = ops.next();
            if (next.name().startsWith(name) ) newName = next.name(); // ReadVariableOp is added in tf2.keras //&& !next.name().endsWith("ReadVariableOp")
            else if (next.name().startsWith("tf_op_layer_"+name)) {
                logger.debug("tensor name found for output: {} -> {}", name, next.name());
                newName = next.name();
            }
        }
        if (newName!=null) {
            logger.debug("output: {} was found with operation name: {}", name, newName);
            return newName;
        }
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
        for (int i = 1; i<inputNC.length; ++i) {
            if (inputNC[i].length!=nSamples) throw new IllegalArgumentException("Input #"+i+" has #"+inputNC[i].length+" samples whereas input 0 has #"+nSamples+" samples");
        }
        if (ZasChannel.getSelected()) {
            for (int i = 0; i <inputNC.length; ++i) {
                inputNC[i] = ResizeUtils.setZasChannel(inputNC[i], 0);
            }
        }
        init();
        boolean[] flipXYZ = new boolean[flip.getChildCount()];
        for (int i = 0;i<flipXYZ.length; ++i) flipXYZ[i] = flip.getChildAt(i).getValue().intValue()==1;
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        float[][] bufferContainer = new float[1][];
        long wrapTime = 0, predictTime = 0;

        for (int idx = 0; idx<nSamples; idx+=batchSize) {
            int idxMax = Math.min(idx+batchSize, nSamples);
            logger.debug("batch: [{};{})", idx, idxMax);
            long t0 = System.currentTimeMillis();
            predict(inputNC, idx, idxMax, bufferContainer, res);
            if (flipXYZ!=null && flipXYZ.length>0) { // flipped predictions will be summed
                double norm = 1;
                if (flipXYZ[0]) {
                    predict(inputNC, idx, idxMax, bufferContainer, res, true);
                    ++norm;
                }
                if (flipXYZ.length>1 && flipXYZ[1]) {
                    predict(inputNC, idx, idxMax, bufferContainer, res, false, true);
                    ++norm;
                }
                if (flipXYZ.length>1 && flipXYZ[1] && flipXYZ[0]) {
                    predict(inputNC, idx, idxMax, bufferContainer, res, true, true);
                    ++norm;
                }
                if (flipXYZ.length>2 && flipXYZ[2]) {
                    predict(inputNC, idx, idxMax, bufferContainer, res, false, false, true);
                    ++norm;
                }
                if (norm>1) { // average of summed flipped predictions
                    for (int oi = 0; oi<res.length; ++oi) {
                        for (int i = idx; i < idxMax; ++i) {
                            for (int c = 0; c<res[oi][i].length; ++c) ImageOperations.affineOperation(res[oi][i][c], res[oi][i][c], 1/norm, 0);
                        }
                    }
                }
            }
            long t1 = System.currentTimeMillis();
            predictTime += t1-t0;
        }
        logger.debug("prediction: {}ms, image wrapping: {}ms", predictTime, wrapTime);
        return res;
    }
    private void predict(Image[][][] inputNC, int idx, int idxMax, float[][] bufferContainer, Image[][][] outputONC, boolean... flipXYZ) {
        Tensor<Float>[] input = Arrays.stream(inputNC).map(imNC ->  TensorWrapper.fromImagesNC(imNC, idx, idxMax, bufferContainer, flipXYZ)).toArray(Tensor[]::new);
        Tensor<Float>[] output = predict(input);
        if (flipXYZ==null || flipXYZ.length==0) {
            for (int io = 0; io < outputNames.length; ++io) {
                Image[][] resIm = TensorWrapper.getImagesNC(output[io], bufferContainer);
                output[io].close();
                for (int i = idx; i < idxMax; ++i) outputONC[io][i] = resIm[i - idx];
            }
        } else { // supposes outputON already contains images
            for (int io = 0; io < outputNames.length; ++io) {
                int fio = io;
                Image[][] resImNC = IntStream.range(idx, idxMax).mapToObj(i -> outputONC[fio][i]).toArray(Image[][]::new);
                TensorWrapper.addToImagesNC(resImNC, output[io], bufferContainer, flipXYZ);
                output[io].close();
            }
        }
    }

    private Tensor<Float>[] predict(Tensor<Float>[] input) {
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

        List<Tensor<?>> outputs=null;
        try {
            outputs = r.run();
        } catch (UnsupportedOperationException e) {
            logger.error("An error occurred during NN execution. Check input shapes: #{} inputs given with shapes: {}", input.length, Arrays.stream(input).map(Tensor::shape).toArray());
            if (outputs!=null) for (Tensor o : outputs) o.close();
            throw e;
        } finally {
            for (Tensor i : input) i.close();
        }
        Tensor<Float>[] output = new Tensor[outputNames.length];
        for (int io = 0; io<outputNames.length; ++io) {
            output[io] = (Tensor<Float>)outputs.get(io);
        }
        return output;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, inputs, outputs, flip, ZasChannel};
    }

    @Override
    public String getHintText() {
        return "Deep Learning engine based on tensorflow 1.x. <br />For GPU computing, the <em>libtensorflow_jni</em> jar should be replaced by <em>libtensorflow_jni_gpu</em>";
    }
}
