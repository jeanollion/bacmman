package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.plugins.Hint;
import bacmman.processing.ImageOperations;
import bacmman.tf2.TensorWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.ConcreteFunction;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Signature;
import org.tensorflow.Tensor;
import org.tensorflow.ndarray.buffer.FloatDataBuffer;
import org.tensorflow.types.TFloat32;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TF2engine implements DLengine, Hint {
    public final static Logger logger = LoggerFactory.getLogger(TF2engine.class);
    FileChooser modelFile = new FileChooser("Tensorflow model", FileChooser.FileChooserOption.DIRECTORIES_ONLY, false).setEmphasized(true).setHint("Select the folder containing the saved model");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 16, 0, null).setEmphasized(true).setHint("Size of the mini batches. Reduce to limit out-of-memory errors, and optimize according to the device");
    ArrayNumberParameter flip = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0, 0}, 1).setName("Average Flipped predictions").setHint("If 1 is set to an axis, flipped image will be predicted and averaged with original image. If 1 is set to X and Y axis, 3 flips are performed (X, Y and XY) which results in a 4-fold prediction number");


    String[] inputNames, outputNames;
    SavedModelBundle model;

    @Override
    public synchronized void init() {
        if (model==null) {
            model = SavedModelBundle.load(modelFile.getFirstSelectedFilePath(), "serve");
            Signature s = model.function("serving_default").signature();
            inputNames = s.inputNames().stream().toArray(String[]::new);
            outputNames = s.outputNames().stream().toArray(String[]::new);
            logger.debug("model loaded: inputs: {}, outputs: {}", inputNames, outputNames);
            assert inputNames!=null && inputNames.length>1 && outputNames !=null && outputNames.length>1;
        }
    }
    public TF2engine setModelPath(String path) {
        this.modelFile.setSelectedFilePath(path);
        return this;
    }

    public TF2engine setBatchSize(int batchSize) {
        this.batchSize.setValue(batchSize);
        return this;
    }

    @Override
    public int getNumOutputArrays() {
        return  outputNames.length;
    }

    @Override
    public int getNumInputArrays() {
        return inputNames.length;
    }

    @Override
    public TF2engine setOutputNumber(int outputNumber) {
        return this;
    }

    @Override
    public TF2engine setInputNumber(int outputNumber) {
        return this;
    }

    @Override
    public void close() {
        if (model!=null) {
            model.close();
            model = null;
        }
        inputNames = null;
        outputNames = null;
    }

    public synchronized Image[][][] process(Image[][]... inputNC) {
        if (inputNC.length!=getNumInputArrays()) throw new IllegalArgumentException("Invalid number of input provided. Expected:"+getNumInputArrays()+" provided:"+inputNC.length);
        int batchSize = this.batchSize.getValue().intValue();
        int nSamples = inputNC[0].length;
        for (int i = 1; i<inputNC.length; ++i) {
            if (inputNC[i].length!=nSamples) throw new IllegalArgumentException("Input #"+i+" has #"+inputNC[i].length+" samples whereas input 0 has #"+nSamples+" samples");
        }
        init();
        boolean[] flipXYZ = new boolean[flip.getChildCount()];
        for (int i = 0;i<flipXYZ.length; ++i) flipXYZ[i] = flip.getChildAt(i).getValue().intValue()==1;
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        FloatDataBuffer[] bufferContainer = new FloatDataBuffer[1];
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
    private void predict(Image[][][] inputNC, int idx, int idxMax, FloatDataBuffer[] bufferContainer, Image[][][] outputONC, boolean... flipXYZ) {
        TFloat32[] input = Arrays.stream(inputNC).map(imNC ->  TensorWrapper.fromImagesNC(imNC, idx, idxMax, bufferContainer, flipXYZ)).toArray(TFloat32[]::new);
        TFloat32[] output = predict(input);
        if (flipXYZ==null || flipXYZ.length==0) {
            for (int io = 0; io < outputNames.length; ++io) {
                Image[][] resIm = TensorWrapper.getImagesNC(output[io]);
                output[io].close();
                for (int i = idx; i < idxMax; ++i) outputONC[io][i] = resIm[i - idx];
            }
        } else { // supposes outputON already contains images
            for (int io = 0; io < outputNames.length; ++io) {
                int fio = io;
                Image[][] resImNC = IntStream.range(idx, idxMax).mapToObj(i -> outputONC[fio][i]).toArray(Image[][]::new);
                TensorWrapper.addToImagesNC(resImNC, output[io], flipXYZ);
                output[io].close();
            }
        }
    }

    private TFloat32[] predict(TFloat32[] input) {
        assert input.length == inputNames.length;
        Map<String, Tensor> inputMap = new HashMap<>(inputNames.length);
        for (int i = 0; i<input.length; ++i) inputMap.put(inputNames[i], input[i]);
        Map<String, Tensor> output = model.call(inputMap);
        for (TFloat32 t : input) t.close();
        output.forEach((s, t) -> logger.debug("output: {} class: {}",s, t.getClass()));
        return Arrays.stream(outputNames).map(s->output.get(s)).toArray(TFloat32[]::new);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, flip};
    }

    @Override
    public String getHintText() {
        return "Deep Learning engine based on tensorflow 2.x. <br />For GPU computing, the <em>tensorflow-core-platform</em> jar should be replaced by <em>tensorflow-core-platform-gpu</em>";
    }
}
