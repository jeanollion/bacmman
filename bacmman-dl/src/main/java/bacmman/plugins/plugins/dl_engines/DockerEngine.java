package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.core.DockerGateway;
import bacmman.data_structure.dao.UUID;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.image.LazyImage5D;
import bacmman.image.LazyImage5DStack;
import bacmman.plugins.DLengine;
import bacmman.plugins.DockerComplient;
import bacmman.plugins.Hint;
import bacmman.processing.ResizeUtils;
import bacmman.py_dataset.HDF5IO;
import bacmman.utils.*;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.core.DockerGateway.formatDockerTag;

public class DockerEngine implements DLengine, DLMetadataConfigurable, Hint {
    private static final Logger logger = LoggerFactory.getLogger(DockerEngine.class);
    MLModelFileParameter modelFile = new MLModelFileParameter("Model").setValidDirectory(MLModelFileParameter.containsTensorflowModel).setEmphasized(true).setHint("Select the folder containing the saved model");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 0, 0, null).setHint("Size of the mini batches. Reduce to limit out-of-memory errors, and optimize according to the device. O : process all samples");
    DockerImageParameter dockerImage = new DockerImageParameter("Docker Image");
    protected TextParameter dockerVisibleGPUList = new TextParameter("Visible GPU List", "-1", true, true).setEmphasized(true).setHint("Comma-separated list of GPU ids that determines the <em>visible</em> to <em>virtual</em> mapping of GPU devices. <br>GPU order identical as given by nvidia-smi command.<br/>Leave blank to run on CPU, set -1 to use default GPU (from Option menu)");
    protected FloatParameter dockerShmSizeGb = new FloatParameter("Shared Memory Size", 0).setLowerBound(1).setUpperBound(0.5 * ((1024 * 1024 / (1000d * 1000d)) * (Utils.getTotalMemory() / (1000d * 1000))) / 1000d).setHint("Shared Memory Size (GB). Set 0 to use default value (set in Option menu)");
    FloatParameter initTimeout = new FloatParameter("Init TimeOut", 60).setHint("Maximum time (in s) to initialize the engine.");
    FloatParameter processTimeout = new FloatParameter("Processing TimeOut", 480).setHint("Maximum time (in s) for the engine to process each batch");
    EnumChoiceParameter<Z_AXIS> zAxis = new EnumChoiceParameter<>("Z-Axis", Z_AXIS.values(), Z_AXIS.Z)
            .setHint("Choose how to handle Z axis: <ul><li>Z_AXIS: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>BATCH: tensor are treated as 2D images </li></ul>");
    BoundedNumberParameter channelIdx = new BoundedNumberParameter("Channel Index", 0, 0, 0, null).setHint("Channel Used when Z axis is transposed to channel axis");
    ConditionalParameter<Z_AXIS> zAxisCond = new ConditionalParameter<>(zAxis)
            .setActionParameters(Z_AXIS.CHANNEL, channelIdx)
            .setLegacyParameter((p, a) -> a.setActionValue( ((BooleanParameter)p[0]).getSelected()? Z_AXIS.CHANNEL : Z_AXIS.Z), new BooleanParameter("Z as Channel", false));
    GroupParameter dockerParameters = new GroupParameter("Docker Parameters", dockerVisibleGPUList, initTimeout, processTimeout);
    Parameter[] parameters = {modelFile, dockerImage, dockerParameters, batchSize, zAxisCond};
    static final int loopFreqMs = 100;

    // statefull attributes
    String[] inputNames, outputNames;
    DockerGateway dockerGateway;
    String containerID;
    Path dataDir;

    public DockerEngine() {
        dockerGateway = Core.getCore().getDockerGateway();
        if (dockerGateway == null) throw new RuntimeException("Docker Gateway could not be initialized. Is bacmman-docker installed ? ");
        DockerComplient dc = ParameterUtils.getFirstParameterFromParents(DockerComplient.class, dockerImage, false);
        if (dc != null) dockerImage.setImageRequirement(dc.getDockerImageName(), dc.getVersionPrefix(), dc.minimalVersion(), dc.maximalVersion());
        else dockerImage.setImageRequirement("predict_dnn", null, null, null);
    }

    @Override
    public void init() {
        if (containerID != null) return;
        containerID = getContainer();
        if (containerID == null) throw new RuntimeException("Container could not be initialized");
        int i = 0;
        int nIterMax = (int)Math.ceil(1000 * initTimeout.getDoubleValue() / (double)loopFreqMs);
        Path model_specs = dataDir.resolve("model_specs.json");
        Path model_specs_lock = dataDir.resolve("model_specs.lock");
        Path model_error = dataDir.resolve("load_model.error");
        while(i++ < nIterMax) {
            if (Files.exists(model_error)) {
                List<String> error = FileIO.readFromFile(model_error.toString(), s->s, null);
                if (!error.isEmpty()) {
                    deleteSilently(model_error);
                    close();
                    throw new RuntimeException(String.join("\n", error));
                }
            }
            if (Files.exists(model_specs) && !Files.exists(model_specs_lock)) {
                List<String> lines = FileIO.readFromFile(model_specs.toString(), s->s, null);
                if (!lines.isEmpty()) {
                    try {
                        logger.debug("model specs: {}", lines.get(0));
                        JSONObject o = JSONUtils.parse(lines.get(0));
                        this.inputNames = ((Stream<String>) ((JSONArray)o.get("inputs")).stream()).toArray(String[]::new);
                        this.outputNames = ((Stream<String>) ((JSONArray)o.get("outputs")).stream()).toArray(String[]::new);
                        Files.delete(model_specs);
                    } catch (ParseException | IOException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }
            }
            try {
                Thread.sleep(loopFreqMs);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        logger.debug("inputs: {} outputs: {}", inputNames, outputNames);
        if (inputNames == null || outputNames == null) {
            close();
            if (i == nIterMax) throw new RuntimeException("Timeout Error while initializing");
            else throw new RuntimeException("Engine not initialized (model not loaded properly)");
        }
    }

    @Override
    public Image[][][] process(Image[][]... inputNC) {
        if (containerID == null) throw new RuntimeException("Engine not initialized (no container)");
        if (inputNames == null || outputNames == null) throw new RuntimeException("Engine not initialized (model not loaded properly)");
        if (inputNC.length != inputNames.length) {
            throw new RuntimeException("Provided input number is: " + inputNC.length + " while model expects : " + inputNames.length + " inputs");
        }
        int batchSize = this.batchSize.getValue().intValue();
        int nSamples = inputNC[0].length;
        for (int i = 1; i < inputNC.length; ++i) {
            if (inputNC[i].length != nSamples)
                throw new IllegalArgumentException("Input #" + i + " has #" + inputNC[i].length + " samples whereas input 0 has #" + nSamples + " samples");
        }
        int sizeZ = 1;
        switch (zAxis.getSelectedEnum()) {
            case Z:
            default: {
                break;
            }
            case CHANNEL: {
                for (int i = 0; i < inputNC.length; ++i) {
                    inputNC[i] = ResizeUtils.setZtoChannel(inputNC[i], channelIdx.getValue().intValue());
                }
                break;
            }
            case BATCH: {
                sizeZ = DLengine.getSizeZ(inputNC);
                //logger.debug("Z to batch: size Z = {}", sizeZ);
                if (sizeZ > 1) {
                    for (int idx = 0; idx < inputNC.length; ++idx) {
                        //logger.debug("before Z to batch : input: {} N batch: {}, N chan: {}, shape: X={}, Y={}, Z={}", idx, inputNC[idx].length, inputNC[idx][0].length, inputNC[idx][0][0].sizeX(), inputNC[idx][0][0].sizeY(), inputNC[idx][0][0].sizeZ());
                        inputNC[idx] = ResizeUtils.setZtoBatch(inputNC[idx]);
                        //logger.debug("after Z to batch input: {} N batch: {}, N chan: {}, shape: X={}, Y={}, Z={}", idx, inputNC[idx].length, inputNC[idx][0].length, inputNC[idx][0][0].sizeX(), inputNC[idx][0][0].sizeY(), inputNC[idx][0][0].sizeZ());
                        nSamples = inputNC[0].length;
                    }
                }
                break;
            }
        }
        int increment = batchSize==0? nSamples : (int) Math.max(1, Math.ceil(nSamples / Math.ceil((double) nSamples / batchSize)));
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        for (int idx = 0; idx < nSamples; idx += increment) {
            int idxMax = Math.min(idx + increment, nSamples);
            Image[][][] inputINC = new Image[inputNC.length][idxMax - idx][];
            int curIdx = 0;
            for (int i = idx; i<idxMax; ++i) {
                for (int in = 0; in<inputINC.length; ++in) {
                    inputINC[in][curIdx] = inputNC[in][i];
                }
                ++curIdx;
            }
            Image[][][] pred = predictBatch(inputINC);
            curIdx = 0;
            for (int i = idx; i<idxMax; ++i) {
                for (int out = 0; out<res.length; ++out) {
                    res[out][i] = pred[out][curIdx];
                }
                ++curIdx;
            }
        }
        return res;

    }

    protected Image[][][] predictBatch(Image[][][] inputINC) {
        String ds_name = "bdp_inputs"+UUID.get().toHexString()+".h5";
        String ds_name_out = ds_name.replace("inputs", "outputs");
        Path ds_path = dataDir.resolve(ds_name);
        // write input images to dataset
        Path ds_path_lock = dataDir.resolve(ds_name.replace("h5", "lock"));
        FileIO.writeToFile(ds_path_lock.toString(), Stream.of("").collect(Collectors.toList()), s->s);
        IHDF5Writer writer = HDF5IO.getWriter(ds_path.toFile(), false);
        for (int i = 0; i<inputNames.length; ++i) {
            LazyImage5DStack im = new LazyImage5DStack(inputNames[i], inputINC[i]);
            HDF5IO.saveImage(im, writer, "inputs/"+inputNames[i], true, DockerGateway.hasShm() ? 0 : 4);
        }
        writer.close();
        deleteSilently(ds_path_lock);
        int t=0;
        int nIterMax = (int)Math.ceil(1000 * processTimeout.getDoubleValue() / (double)loopFreqMs);
        Path ds_path_out = dataDir.resolve(ds_name_out);
        Path ds_path_error = dataDir.resolve(ds_name.replace("h5", "error"));
        while(t++ < nIterMax) {
            if (Files.exists(ds_path_out)) {
                try {
                    Image[][][] resONC = new Image[outputNames.length][][];
                    IHDF5Reader reader = HDF5IO.getReader(ds_path_out.toFile());
                    for (int i = 0; i<outputNames.length; ++i) {
                        LazyImage5D im = HDF5IO.readDatasetLazy(reader, "outputs/"+outputNames[i], true);
                        resONC[i] = toImageArray(im);
                    }
                    reader.close();
                    return resONC;
                } catch (Exception e) {
                    deleteSilently(ds_path);
                    throw new RuntimeException(e);
                } finally {
                    deleteSilently(ds_path_out);
                }
            } else if (Files.exists(ds_path_error)) {
                List<String> error = FileIO.readFromFile(ds_path_error.toString(), s->s, null);
                if (!error.isEmpty()) {
                    deleteSilently(ds_path_error);
                    deleteSilently(ds_path);
                    deleteSilently(ds_path_out);
                    if (error.get(0).contains("No algorithm worked!")) {
                        Core.userLog("No algorithm worked error: check that selected GPU is compatible, or set no GPU to run on CPU");
                    }
                    close();
                    throw new RuntimeException(String.join("\n", error));
                }

            }
            try {
                Thread.sleep(loopFreqMs);
            } catch (InterruptedException e) {
                deleteSilently(ds_path);
                deleteSilently(ds_path_out);
                throw new RuntimeException(e);
            }
        }
        deleteSilently(ds_path);
        deleteSilently(ds_path_out);
        throw new RuntimeException("Time out error for prediction");
    }

    protected static void deleteSilently(Path p) {
        try {
            Files.delete(p);
        } catch (IOException e) {

        }
    }

    protected Image[][] toImageArray(LazyImage5D im) { // N, C
        Image[][] res = new Image[im.getSizeF()][im.getSizeC()];
        for (int c = 0; c<im.getSizeC(); ++c) {
            for (int f = 0; f<im.getSizeF(); ++f) {
                res[f][c] = im.getImage(f, c);
            }
        }
        return res;
    }
    String[] ignoreStdErr = {"WARNING", "Created device", "cpu_feature_guard", "successful NUMA node read", "Unable to register cuDNN factory", "Unable to register cuFFT factory", "Unable to register cuBLAS factory"};



    protected String parseStdError(String output, String[] error) {
        if (Arrays.stream(ignoreStdErr).anyMatch(output::contains)) return null;
        logger.debug("not ignored std error: {}", output);
        return null;
    }

    protected void parsePredictStdOut(String output) {
        logger.debug("predict output: {}", output);
    }

    protected void parseLoadModelOutput(String output) {
        String[] split = output.split("#");
        for (String s : split) {
            if (s.contains("Inputs")) {
                s = s.replace("Inputs: ", "");
                s = s.replaceAll("[\\]\\[\\'\\s]", "");
                if (s.contains(",")) inputNames = s.split(",");
                else inputNames = new String[]{s};
            } else if (s.contains("Outputs")) {
                s = s.replace("Outputs: ", "");
                s = s.replaceAll("[\\]\\[\\'\\s]", "");
                if (s.contains(",")) outputNames = s.split(",");
                else outputNames = new String[]{s};
            }
        }
    }

    @Override
    public int getNumOutputArrays() {
        return outputNames.length;
    }

    @Override
    public int getNumInputArrays() {
        return inputNames.length;
    }

    @Override
    public DLengine setOutputNumber(int outputNumber) { // TODO can this method be removed ?
        return this;
    }

    @Override
    public DLengine setInputNumber(int outputNumber) {
        return this;
    }

    @Override
    public void close() {
        if (containerID != null && dockerGateway != null) {
            try {
                dockerGateway.stopContainer(containerID);
            } catch (Exception e) {}
        }
        this.containerID = null;
        if (dataDir != null) deleteSilently(dataDir);
        this.dataDir = null;
        this.inputNames = null;
        this.outputNames = null;
    }

    @Override
    public String getHintText() {
        return "Deep Learning engine based on Docker. Requires Docker, see bacmman wiki install section.";
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    protected String getContainer() {
        String image = ensureImage();
        logger.debug("docker image: {}", image);
        try {
            List<UnaryPair<String>> mounts = new ArrayList<>();
            mounts.add(new UnaryPair<>(modelFile.getModelFile().getAbsolutePath(), "/model"));
            dataDir = getDataDirectory();
            mounts.add(new UnaryPair<>(dataDir.toString(), "/data"));
            return dockerGateway.createContainer(image, dockerShmSizeGb.getDoubleValue(), DockerGateway.parseGPUList(dockerVisibleGPUList.getValue()), null, null, mounts.toArray(new UnaryPair[0]));
        } catch (RuntimeException e) {
            if (e.getMessage().toLowerCase().contains("permission denied")) {
                Core.userLog("Error trying to start container: permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock");
                logger.error("Error trying to start container: Permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock", e);
            } else {
                Core.userLog("Error trying to start container");
                Core.userLog(e.getMessage());
                logger.error("Error trying to start container", e);
            }
            throw e;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected String ensureImage() {
        DockerComplient dc = ParameterUtils.getFirstParameterFromParents(DockerComplient.class, dockerImage, false);
        dockerImage.refreshImageList();
        DockerImageParameter.DockerImage currentImage = dockerImage.getValue();
        if (!currentImage.isInstalled()) { // look for dockerfile and build it
            String dockerFilePath = null;
            Path dockerDir = null;
            try {
                String dockerfileName = currentImage.getFileName();
                String tag = formatDockerTag(dockerfileName);
                dockerDir = getLocalDirectory();
                dockerFilePath = dockerDir.resolve("DockerFile").toString();
                logger.debug("will build docker image: {} from dockerfile: {} @ {}", tag, dockerfileName, dockerFilePath);
                if (dc != null) Utils.extractResourceFile(dc.getClass(), "/dockerfiles/" + dockerfileName, dockerFilePath);
                else Utils.extractResourceFile(getClass(), "/dockerfiles/" + dockerfileName, dockerFilePath);
                Core.userLog("Building docker image: " + tag);
                Consumer<String> progressParser = m -> {
                    int[] prog = DockerGateway.parseBuildProgress(m);
                    if (prog !=null) Core.userLog("Build Progress: step " + prog[0] +" / "+ prog[1]);
                };
                return dockerGateway.buildImage(tag, new File(dockerFilePath), progressParser, Core::userLog, null);
            } catch (IOException e) {
                Core.userLog("Could not build docker image");
                logger.error("Error while extracting resources", e);
                throw new RuntimeException(e);
            } catch (RuntimeException e) {
                if (e.getMessage().toLowerCase().contains("permission denied")) {
                    Core.userLog("Could not build docker image: permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock");
                    logger.error("Error connecting with Docker: Permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock", e);
                }
                throw e;
            } finally {
                if (dockerFilePath != null && new File(dockerFilePath).exists()) new File(dockerFilePath).delete();
            }
        } else return currentImage.getTag();
    }

    protected Path getDataDirectory() throws IOException {
        Path dir;
        if (DockerGateway.hasShm()) {
            dir = Paths.get("/dev/shm");
        } else dir = getLocalDirectory();
        dir = dir.resolve(UUID.get().toHexString());
        Files.createDirectories(dir);
        return dir;
    }

    protected Path getLocalDirectory() {
        Experiment xp = ParameterUtils.getExperiment(modelFile); // any parameter should be in Experiment tree
        if (xp == null) throw new RuntimeException("No XP in parameter tree");
        Path dataDir = Paths.get(xp.getOutputImageDirectory(), "dockerData");
        if (!Files.exists(dataDir)) {
            try {
                Files.createDirectories(dataDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return dataDir;
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        String exportLib = metadata.getExportLibrary();
        if (exportLib!= null && !exportLib.isEmpty()) {
            try {
                Pair<String, int[]> tag = DockerGateway.parseVersion(exportLib);
                dockerImage.setImageRequirement(dockerImage.getImageName(), tag.key, tag.value, tag.value);
                DockerImageParameter.DockerImage im = dockerImage.getAllImages().filter(DockerImageParameter.DockerImage::isInstalled).findFirst().orElse(dockerImage.getAllImages().findFirst().orElse(null));
                dockerImage.setValue(im);
            } catch (Exception e) {}
        }
    }

}
