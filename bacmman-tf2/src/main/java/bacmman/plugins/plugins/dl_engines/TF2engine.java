package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.plugins.Hint;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.tf2.TensorWrapper;
import bacmman.utils.HashMapGetCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.*;
import org.tensorflow.ndarray.buffer.DataBuffer;
import org.tensorflow.proto.framework.ConfigProto;
import org.tensorflow.proto.framework.GPUOptions;
//import org.tensorflow.proto.ConfigProto;
//import org.tensorflow.proto.GPUOptions;
import org.tensorflow.types.TFloat32;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

public class TF2engine implements DLengine, Hint, DLMetadataConfigurable {
    public final static Logger logger = LoggerFactory.getLogger(TF2engine.class);
    public final static Logger loaderLog = LoggerFactory.getLogger(org.bytedeco.javacpp.Loader.class);
    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        if (!metadata.getInputs().get(0).is3D()) zAxis.setValue(Z_AXIS.BATCH); // TODO what if several inputs ?
        TextParameter zAxis = metadata.getOtherParameter("Z-Axis", TextParameter.class);
        if (zAxis!=null) {
            logger.debug("Z-axis configured from metadata: {}", zAxis.getValue());
            this.zAxis.setSelectedItem(zAxis.getValue());
        } else logger.debug("no Z-Axis in metadata");
    }

    MLModelFileParameter modelFile = new MLModelFileParameter("Model").setValidDirectory(MLModelFileParameter.containsTensorflowModel).setEmphasized(true).setHint("Select the folder containing the saved model (.pb file)");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 16, 0, null).setEmphasized(true).setHint("Size of the mini batches. Reduce to limit out-of-memory errors, and optimize according to the device");
    ArrayNumberParameter flip = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0, 0}, 1).setName("Average Flipped predictions").setHint("If 1 is set to an axis, flipped image will be predicted and averaged with original image. If 1 is set to X and Y axis, 3 flips are performed (X, Y and XY) which results in a 4-fold prediction number");
    EnumChoiceParameter<Z_AXIS> zAxis = new EnumChoiceParameter<>("Z-Axis", Z_AXIS.values(), Z_AXIS.Z)
            .setHint("Choose how to handle Z axis: <ul><li>Z_AXIS: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>BATCH: tensor are treated as 2D images </li></ul>");
    BoundedNumberParameter channelIdx = new BoundedNumberParameter("Channel Index", 0, 0, 0, null).setHint("Channel Used when Z axis is transposed to channel axis");
    ConditionalParameter<Z_AXIS> zAxisCond = new ConditionalParameter<>(zAxis)
            .setActionParameters(Z_AXIS.CHANNEL, channelIdx)
            .setLegacyParameter((p, a) -> a.setActionValue( ((BooleanParameter)p[0]).getSelected()? Z_AXIS.CHANNEL : Z_AXIS.Z), new BooleanParameter("Z as Channel", false));

    BooleanParameter halfPrecision = new BooleanParameter("Half Precision", false).setEmphasized(true).setHint("Prediction are performed in float 16 precision to lower memory usage");
    String[] inputNames, outputNames;
    SavedModelBundle model;
    public boolean halfPrecision() {return halfPrecision.getSelected();}
    @Override
    public synchronized void init() {
        if (model==null) {
            logger.debug("GPU options: visible device list {}, per process memory fraction: {}, allow growth: {}" ,Core.getCore().tfVisibleDeviceList, Core.getCore().tfPerProcessGpuMemoryFraction, Core.getCore().tfSetAllowGrowth);
            // TO SET THE GPU : https://github.com/tensorflow/java/issues/443
            ConfigProto configProto = null;
            if (Core.getCore().tfVisibleDeviceList!=null && Core.getCore().tfVisibleDeviceList.length()>0) {
                GPUOptions gpu = GPUOptions.newBuilder() //
                        .setVisibleDeviceList(Core.getCore().tfVisibleDeviceList)
                        .setPerProcessGpuMemoryFraction(Core.getCore().tfPerProcessGpuMemoryFraction) //
                        .setAllowGrowth(Core.getCore().tfSetAllowGrowth) //
                        .build(); //
                configProto = ConfigProto.newBuilder() //
                        .setAllowSoftPlacement(true) //
                        .setLogDevicePlacement(true) //
                        .mergeGpuOptions(gpu) //
                        .build(); //
            } else {
                configProto = ConfigProto.newBuilder().setAllowSoftPlacement(true).putDeviceCount("GPU", 0).build(); // force CPU
            }
            SavedModelBundle.Loader loader;
            try {
                loader = SavedModelBundle
                        .loader(modelFile.getModelFile().getAbsolutePath())
                        .withTags("serve");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (configProto!=null) loader = loader.withConfigProto(configProto);
            model = loader.load();

            //model = SavedModelBundle.load(modelFile.getModelFile().getAbsolutePath(), "serve");
            Signature s = model.function("serving_default").signature();
            //System.setOut(stdout);
            logger.debug("model signature : {}", s.toString());
            inputNames = s.inputNames().stream().sorted().toArray(String[]::new);
            outputNames = s.outputNames().stream().sorted().toArray(String[]::new);
            logger.debug("model loaded: inputs: {} (imported order: {}), outputs: {} (imported order: {})", inputNames, s.inputNames(), outputNames, s.outputNames());
            if (inputNames==null || inputNames.length==0 || outputNames ==null || outputNames.length==0 ) throw new RuntimeException("invalid input/output");
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
    public void close() { // TODO this do not deallocate GPU memory
        if (model!=null) {
            model.close();
            model = null;
            System.gc();
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
        int sizeZ = 1;
        switch (zAxis.getSelectedEnum()) {
            case Z:
            default: {
                break;
            }
            case CHANNEL: {
                for (int i = 0; i <inputNC.length; ++i) {
                    inputNC[i] = ResizeUtils.setZtoChannel(inputNC[i], channelIdx.getValue().intValue());
                }
                break;
            }
            case BATCH: {
                sizeZ = DLengine.getSizeZ(inputNC);
                logger.debug("Z to batch: size Z = {}", sizeZ);
                if (sizeZ>1) {
                    for (int idx = 0; idx < inputNC.length; ++idx) {
                        logger.debug("before Z to batch : input: {} N batch: {}, N chan: {}, shape: X={}, Y={}, Z={}", idx, inputNC[idx].length, inputNC[idx][0].length, inputNC[idx][0][0].sizeX(), inputNC[idx][0][0].sizeY(), inputNC[idx][0][0].sizeZ());
                        inputNC[idx] = ResizeUtils.setZtoBatch(inputNC[idx]);
                        logger.debug("after Z to batch input: {} N batch: {}, N chan: {}, shape: X={}, Y={}, Z={}", idx, inputNC[idx].length, inputNC[idx][0].length, inputNC[idx][0][0].sizeX(), inputNC[idx][0][0].sizeY(), inputNC[idx][0][0].sizeZ());
                        nSamples = inputNC[0].length;
                    }
                }
                break;
            }
        }

        init();
        boolean[] flipXYZ = new boolean[flip.getChildCount()];
        for (int i = 0;i<flipXYZ.length; ++i) flipXYZ[i] = flip.getChildAt(i).getValue().intValue()==1;
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        DataBufferContainer bufferContainer = new DataBufferContainer();
        long wrapTime = 0, predictTime = 0;
        int increment = (int)Math.ceil( nSamples / Math.ceil( (double)nSamples / batchSize) );
        logger.debug("batch size: {} nSamples: {} increment: {}", batchSize, increment, nSamples);
        for (int idx = 0; idx<nSamples; idx+=increment) {
            int idxMax = Math.min(idx+increment, nSamples);
            logger.debug("batch: [{};{}) / [0;{})", idx, idxMax, nSamples);
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
        switch (zAxis.getSelectedEnum()) {
            case Z:
            default: {
                break;
            }
            case CHANNEL: {
                for (int i = 0; i <res.length; ++i) {
                    int nC = res[i][0].length;
                    if (nC>1) {
                        res[i] = ResizeUtils.setChanneltoZ(res[i]);
                    }
                }
                break;
            }
            case BATCH: {
                if (sizeZ>1) {
                    for (int o = 0; o<res.length; ++o) {
                        logger.debug("before batch to Z : output: {} N batch: {}, N chan: {}, shape: X={}, Y={}, Z={}", o, res[o].length, res[o][0].length, res[o][0][0].sizeX(), res[o][0][0].sizeY(), res[o][0][0].sizeZ());
                        res[o] = ResizeUtils.setBatchToZ(res[o], sizeZ);
                        logger.debug("after batch to Z : output: {} N batch: {}, N chan: {}, shape: X={}, Y={}, Z={}", o, res[o].length, res[o][0].length, res[o][0][0].sizeX(), res[o][0][0].sizeY(), res[o][0][0].sizeZ());
                    }
                }
                break;
            }
        }

        logger.debug("prediction: {}ms, image wrapping: {}ms", predictTime, wrapTime);
        return res;
    }
    private void predict(Image[][][] inputNC, int idx, int idxMaxExcl, DataBufferContainer bufferContainer, Image[][][] outputONC, boolean... flipXYZ) {
        Tensor[] input = IntStream.range(0, inputNC.length).mapToObj(i ->  TensorWrapper.fromImagesNC(inputNC[i], idx, idxMaxExcl, bufferContainer.getDataBufferContainer(i), flipXYZ)).toArray(Tensor[]::new);
        TFloat32[] output = predict(input);
        if (flipXYZ==null || flipXYZ.length==0) {
            for (int io = 0; io < outputNames.length; ++io) {
                Image[][] resIm = TensorWrapper.getImagesNC(output[io], halfPrecision.getSelected() );
                output[io].close();
                for (int i = idx; i < idxMaxExcl; ++i) outputONC[io][i] = resIm[i - idx];
            }
        } else { // supposes outputON already contains images
            for (int io = 0; io < outputNames.length; ++io) {
                int fio = io;
                Image[][] resImNC = IntStream.range(idx, idxMaxExcl).mapToObj(i -> outputONC[fio][i]).toArray(Image[][]::new);
                TensorWrapper.addToImagesNC(resImNC, output[io], flipXYZ);
                output[io].close();
            }
        }
    }

    private TFloat32[] predict(Tensor[] input) {
        assert input.length == inputNames.length;
        Map<String, Tensor> inputMap = new HashMap<>(inputNames.length);
        for (int i = 0; i<input.length; ++i) inputMap.put(inputNames[i], input[i]);
        Map<String, Tensor> output = model.call(inputMap);
        for (Tensor t : input) t.close();
        return Arrays.stream(outputNames).map(output::get).toArray(TFloat32[]::new);
        //Result output = model.call(inputMap);
        //for (Tensor t : input) t.close();
        //return Arrays.stream(outputNames).map(n -> output.get(n).get()).toArray(TFloat32[]::new);
    }

    static class DataBufferContainer {
        Map<Integer, DataBuffer[]> buffersByInput = new HashMapGetCreate.HashMapGetCreateRedirected<>(i -> new DataBuffer[1]);
        public DataBuffer[] getDataBufferContainer(int inputIdx) {
            return buffersByInput.get(inputIdx);
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, flip, zAxisCond, halfPrecision};
    }

    @Override
    public String getHintText() {
        return "Deep Learning engine based on tensorflow 2.x. <br />For GPU computing, install CUDA 11.2 (see bacmman wiki installation page) select BACMMAN-DL-GPU update site. <br/>If CUDA is not installed or no GPU is found, CPU will be used. Note that this is currently not compatible with ARM CPUs (such as Mac M1). See DockerEngine instead.";
    }
}
