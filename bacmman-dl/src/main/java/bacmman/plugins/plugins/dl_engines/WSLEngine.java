package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.dl.wsl.ArrayCodec;
import bacmman.dl.wsl.ArrayCodec.ArraySpec;
import bacmman.dl.wsl.ArrayCodec.DType;
import bacmman.dl.wsl.WSLInstallation;
import bacmman.dl.wsl.WSLPredictionServer;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.plugins.DLEngine;
import bacmman.plugins.Hint;
import bacmman.plugins.PlatformSpecific;
import bacmman.plugins.PluginWithActions;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deep learning engine for Windows: predictions run with TensorFlow (python) on the GPU, inside a dedicated WSL
 * distribution. Useful when Docker cannot be used.
 * When the distribution is missing, or its TensorFlow version differs from the "TensorFlow Version" parameter, the
 * installer (bundled with BACMMAN, resource /wsl/install-bacmman-wslengine.ps1) is run automatically, as missing DL
 * models are downloaded.
 * The model is loaded through its "serving_default" signature, as with TF2Engine (same input / output names and order).
 * Images are streamed to the server through a pipe, and the next batch is sent while the previous one is processed.
 */
@PlatformSpecific(PlatformSpecific.OS.WINDOWS)
public class WSLEngine implements DLEngine, Hint, DLMetadataConfigurable, PluginWithActions {
    private static final Logger logger = LoggerFactory.getLogger(WSLEngine.class);
    public static final String DEFAULT_DISTRIBUTION = "BACMMAN-WSLEngine";
    public static final String DEFAULT_TENSORFLOW_VERSION = "2.18.0"; // same as TF2Engine (tensorflow-java 1.1.0)
    // distributions whose TensorFlow version was checked during this session: distribution -> version
    static final Map<String, String> VERIFIED = new ConcurrentHashMap<>();
    // installations that failed during this session: distribution/version -> error (not retried automatically)
    static final Map<String, String> FAILED = new ConcurrentHashMap<>();
    static final Object INSTALL_LOCK = new Object();
    static final long MAX_REQUEST_BYTES = 1L << 30; // batches are split so that a request stays below 1 GiB

    MLModelFileParameter modelFile = new MLModelFileParameter("Model").setValidDirectory(MLModelFileParameter.containsTensorflowModel).setEmphasized(true).setHint("Select the folder containing the saved model. It must be on a local drive (C:, D:...)");
    TextParameter distribution = new TextParameter("WSL Distribution", DEFAULT_DISTRIBUTION, true, false).setHint("Name of the WSL distribution where Tensorflow runs. It is installed automatically at first use (right-click on the engine to check, install or update it)");
    TextParameter tensorflowVersion = new TextParameter("TensorFlow Version", DEFAULT_TENSORFLOW_VERSION, true, false).setHint("TensorFlow version used in the WSL distribution. If another version is installed, this one is installed automatically at next use");
    TextParameter visibleGPUList = new TextParameter("Visible GPU List", "-1", true, true).setEmphasized(true).setHint("Comma-separated list of GPU ids (same order as the nvidia-smi command).<br/>Leave blank to run on CPU, set -1 to use the GPU list of the Tensorflow options (Option menu)");
    FloatParameter initTimeout = new FloatParameter("Init TimeOut", 180).setHint("Maximum time (in s) to start the engine and load the model. The first start can be slow (WSL start, GPU initialization)");
    FloatParameter processTimeout = new FloatParameter("Processing TimeOut", 480).setHint("Maximum time (in s) for the engine to process each batch");
    GroupParameter wslParameters = new GroupParameter("WSL Parameters", distribution, tensorflowVersion, visibleGPUList, initTimeout, processTimeout);
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 0, 0, null).setEmphasized(true).setHint("Size of the mini batches. Reduce to limit out-of-memory errors, and optimize according to the device. Set 0 to process all samples at once (batches are limited to 1 GB of data)");
    ArrayNumberParameter flip = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0, 0}, 1).setName("Average Flipped predictions").setHint("If 1 is set to an axis, flipped image will be predicted and averaged with original image. If 1 is set to X and Y axis, 3 flips are performed (X, Y and XY) which results in a 4-fold prediction number");
    EnumChoiceParameter<Z_AXIS> zAxis = new EnumChoiceParameter<>("Z-Axis", Z_AXIS.values(), Z_AXIS.BATCH)
            .setHint("Choose how to handle Z axis: <ul><li>Z_AXIS: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>BATCH: tensor are treated as 2D images </li></ul>");
    BoundedNumberParameter channelIdx = new BoundedNumberParameter("Channel Index", 0, 0, 0, null).setHint("Channel Used when Z axis is transposed to channel axis");
    ConditionalParameter<Z_AXIS> zAxisCond = new ConditionalParameter<>(zAxis).setActionParameters(Z_AXIS.CHANNEL, channelIdx);
    BooleanParameter halfPrecision = new BooleanParameter("Half Precision", false).setEmphasized(true).setHint("Predictions are transferred and stored in float 16 precision: halves transfer time and memory usage");
    Parameter[] parameters = {modelFile, wslParameters, batchSize, flip, zAxisCond, halfPrecision};

    // state
    WSLPredictionServer server;
    String[] inputNames, outputNames;
    int requestId;

    @Override
    public int[] getGPUs() {
        return DLEngine.parseGPUList(getGPUListString());
    }

    String getGPUListString() {
        String list = visibleGPUList.getValue().trim();
        if (list.equals("-1")) return Core.getCore().tfVisibleDeviceList == null ? "" : Core.getCore().tfVisibleDeviceList;
        return list;
    }

    @Override
    public synchronized void init() {
        if (server != null && server.isAlive() && inputNames != null) return;
        close();
        if (!devMode()) ensureInstallation(false);
        Core.userLog("Starting WSL prediction engine (" + distribution.getValue() + ")...");
        try {
            String modelPath = modelFile.getModelFile().getAbsolutePath();
            server = new WSLPredictionServer(distribution.getValue(), getGPUListString(), Core.getCore().tfSetAllowGrowth);
            JSONObject load = new JSONObject();
            load.put("cmd", "load");
            load.put("model", server.translatePath(modelPath));
            server.send(load, null, null);
            WSLPredictionServer.Message m = server.receive(initTimeout.getDoubleValue(), "loading the model");
            inputNames = toStringArray(m.header.get("inputs"));
            outputNames = toStringArray(m.header.get("outputs"));
            logger.debug("WSL engine: model loaded. inputs: {} outputs: {} tensorflow: {} gpus: {} environment: {}", inputNames, outputNames, m.header.get("tf_version"), m.header.get("gpus"), m.header.get("env_version"));
            if (inputNames.length == 0 || outputNames.length == 0) throw new RuntimeException("invalid model signature: no input or output");
        } catch (IOException | RuntimeException e) {
            close();
            logger.error("Error initializing WSL engine", e);
            Core.userLog("WSL engine: " + e.getMessage());
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    static boolean devMode() { // python started by another command (see WSLPredictionServer): no installation check
        String command = System.getProperty(WSLPredictionServer.COMMAND_PROPERTY);
        return command != null && !command.trim().isEmpty();
    }

    /**
     * Checks that the distribution is installed with the required TensorFlow version, and otherwise installs it, as DL
     * models are downloaded when they are missing: the installer opens in a new window, where the user can follow it.
     * An installation that failed is not retried automatically during the session (use the action "Check / Install
     * WSL Environment" to retry).
     * @param keepWindowOpen the installer window stays open at the end (installation started by the user)
     * @throws RuntimeException if the environment is not installed at the end
     */
    void ensureInstallation(boolean keepWindowOpen) {
        String distro = distribution.getValue().trim(), version = tensorflowVersion.getValue().trim();
        String key = distro + "/" + version;
        if (version.equals(VERIFIED.get(distro))) return;
        synchronized (INSTALL_LOCK) { // engines can be initialized in parallel: only one installation at a time
            if (version.equals(VERIFIED.get(distro))) return;
            String failure = FAILED.get(key);
            if (failure != null) throw new RuntimeException(failure + " (right-click on WSLEngine > Check / Install WSL Environment to retry)");
            Core.userLog("WSL engine: checking the WSL distribution " + distro + "...");
            WSLInstallation.Status status = WSLInstallation.getStatus(distro);
            logger.debug("WSL distribution {}: {}", distro, status);
            if (status.state == WSLInstallation.State.OK && version.equals(status.tensorflowVersion)) {
                VERIFIED.put(distro, version);
                return;
            }
            switch (status.state) {
                case NO_WSL:
                    Core.userLog("WSL engine: WSL is not installed: installing WSL (administrator rights are requested, then Windows must be restarted). Follow the installation in the installer window.");
                    break;
                case NOT_INSTALLED:
                    Core.userLog("WSL engine: installing TensorFlow " + version + " in the WSL distribution " + distro + " (done once, about 6 GB downloaded, several minutes). Follow the installation in the installer window.");
                    break;
                case OK:
                    Core.userLog("WSL engine: TensorFlow " + status.tensorflowVersion + " is installed in " + distro + ": installing version " + version + ". Follow the installation in the installer window.");
                    break;
                case ERROR:
                default:
                    throw new RuntimeException("WSL engine: could not check the WSL distribution " + distro + ": " + status.message);
            }
            try {
                runInstaller(distro, version, false, !keepWindowOpen);
            } catch (RuntimeException e) {
                FAILED.put(key, e.getMessage());
                throw e;
            }
        }
    }

    void runInstaller(String distro, String version, boolean update, boolean automatic) {
        int code;
        try {
            code = WSLInstallation.runInstaller(distro, version, update, automatic);
        } catch (IOException e) {
            throw new RuntimeException("WSL engine: could not run the installer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("WSL engine: interrupted while installing", e);
        }
        VERIFIED.remove(distro);
        if (code == WSLInstallation.EXIT_REBOOT) throw new RuntimeException("WSL has been installed: restart Windows, then run the task again (the TensorFlow environment will then be installed)");
        WSLInstallation.Status status = WSLInstallation.getStatus(distro);
        if (status.state == WSLInstallation.State.OK && version.equals(status.tensorflowVersion)) {
            VERIFIED.put(distro, version);
            Core.userLog("WSL engine: TensorFlow " + version + " is installed in " + distro);
        } else {
            throw new RuntimeException("WSL engine: the installation of TensorFlow " + version + " in " + distro + " did not complete (installer exit code " + code + ", status: " + status + ")");
        }
    }

    @Override
    public List<PluginAction> getActions() {
        Runnable check = () -> {
            try {
                VERIFIED.remove(distribution.getValue().trim());
                FAILED.remove(distribution.getValue().trim() + "/" + tensorflowVersion.getValue().trim());
                ensureInstallation(true);
                Core.userLog("WSL engine: TensorFlow " + tensorflowVersion.getValue().trim() + " is installed in the WSL distribution " + distribution.getValue().trim());
            } catch (RuntimeException e) {
                logger.error("WSL engine installation check", e);
                Core.userLog(e.getMessage());
            }
        };
        Runnable update = () -> {
            String distro = distribution.getValue().trim(), version = tensorflowVersion.getValue().trim();
            Boolean ok = Core.userPrompt("Update the WSL distribution " + distro + " (Ubuntu and python packages, TensorFlow " + version + ")? The installer opens in a new window. Running WSL engines are stopped.");
            if (ok == null || !ok) return;
            try {
                close();
                WSLPredictionServer.closeAll(distro); // the distribution must not be in use (engines restart at next use)
                runInstaller(distro, version, true, false);
            } catch (RuntimeException e) {
                logger.error("WSL engine update", e);
                Core.userLog(e.getMessage());
            }
        };
        return Arrays.asList(
                new PluginAction("Check / Install WSL Environment", "Checks that the WSL distribution is installed with the required TensorFlow version, and installs it otherwise", check),
                new PluginAction("Update WSL Environment", "Updates Ubuntu and python packages of the WSL distribution, and installs the required TensorFlow version", update));
    }

    static String[] toStringArray(Object jsonArray) {
        if (jsonArray == null) return new String[0];
        JSONArray a = (JSONArray) jsonArray;
        String[] res = new String[a.size()];
        for (int i = 0; i < res.length; ++i) res[i] = String.valueOf(a.get(i));
        return res;
    }

    @Override
    public Image[][][] process(Image[][]... inputNC) {
        return process(null, 0, inputNC);
    }

    @Override
    public synchronized Image[][][] process(ProgressCallback.SubProgress progress, double steps, Image[][]... inputNC) {
        init();
        if (inputNC.length != inputNames.length) throw new IllegalArgumentException("Invalid number of inputs. Expected: " + inputNames.length + " provided: " + inputNC.length);
        int nSamples = inputNC[0].length;
        for (int i = 1; i < inputNC.length; ++i) {
            if (inputNC[i].length != nSamples) throw new IllegalArgumentException("Input #" + i + " has #" + inputNC[i].length + " samples whereas input 0 has #" + nSamples + " samples");
        }
        int sizeZ = DLEngine.getSizeZ(inputNC);
        switch (zAxis.getSelectedEnum()) {
            case Z:
            default:
                break;
            case CHANNEL:
                for (int i = 0; i < inputNC.length; ++i) inputNC[i] = ResizeUtils.setZtoChannel(inputNC[i], channelIdx.getValue().intValue());
                break;
            case BATCH:
                if (sizeZ > 1) {
                    for (int i = 0; i < inputNC.length; ++i) inputNC[i] = ResizeUtils.setZtoBatch(inputNC[i]);
                    nSamples = inputNC[0].length;
                }
                break;
        }
        Image[][][] res = predict(inputNC, nSamples, progress, steps);
        switch (zAxis.getSelectedEnum()) {
            case Z:
            default:
                break;
            case CHANNEL:
                for (int i = 0; i < res.length; ++i) {
                    if (res[i][0].length > 1) res[i] = ResizeUtils.setChanneltoZ(res[i]);
                }
                break;
            case BATCH:
                if (sizeZ > 1) {
                    for (int o = 0; o < res.length; ++o) res[o] = ResizeUtils.setBatchToZ(res[o], sizeZ);
                }
                break;
        }
        return res;
    }

    static class Job {
        final int id, from, to;
        final boolean[] flipXYZ;
        final boolean lastOfBatch;
        Job(int id, int from, int to, boolean[] flipXYZ, boolean lastOfBatch) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.flipXYZ = flipXYZ;
            this.lastOfBatch = lastOfBatch;
        }
    }

    protected Image[][][] predict(Image[][][] inputNC, int nSamples, ProgressCallback.SubProgress progress, double steps) {
        DType[] inputTypes = new DType[inputNC.length];
        long bytesPerSample = 0;
        for (int i = 0; i < inputNC.length; ++i) {
            inputTypes[i] = ArrayCodec.inputType(inputNC[i]);
            bytesPerSample += ArrayCodec.byteCountPerSample(inputNC[i], inputTypes[i]);
        }
        int batchSize = this.batchSize.getValue().intValue();
        int increment = batchSize == 0 ? nSamples : (int) Math.max(1, Math.ceil(nSamples / Math.ceil((double) nSamples / batchSize)));
        int maxBySize = (int) Math.max(1, MAX_REQUEST_BYTES / Math.max(1, bytesPerSample));
        if (increment > maxBySize) increment = (int) Math.max(1, Math.ceil(nSamples / Math.ceil((double) nSamples / maxBySize)));
        List<boolean[]> flips = getFlipVariants();
        List<Job> jobs = new ArrayList<>();
        for (int idx = 0; idx < nSamples; idx += increment) {
            int idxMax = Math.min(idx + increment, nSamples);
            for (int f = 0; f < flips.size(); ++f) jobs.add(new Job(++requestId, idx, idxMax, flips.get(f), f == flips.size() - 1));
        }
        String outType = halfPrecision.getSelected() ? DType.FLOAT16.name : DType.FLOAT32.name;
        WSLPredictionServer server = this.server;
        // requests are sent by another thread: the next batch is transferred while the current one is processed
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        Thread sender = new Thread(() -> {
            try {
                for (Job job : jobs) {
                    if (Thread.currentThread().isInterrupted()) return;
                    List<ArraySpec> specs = new ArrayList<>(inputNC.length);
                    List<byte[]> data = new ArrayList<>(inputNC.length);
                    for (int i = 0; i < inputNC.length; ++i) {
                        specs.add(new ArraySpec(inputNames[i], inputTypes[i], ArrayCodec.shape(inputNC[i], job.from, job.to)));
                        data.add(ArrayCodec.encode(inputNC[i], job.from, job.to, inputTypes[i], job.flipXYZ));
                    }
                    JSONObject header = new JSONObject();
                    header.put("cmd", "predict");
                    header.put("id", job.id);
                    header.put("out_dtype", outType);
                    server.send(header, specs, data);
                }
            } catch (Throwable t) {
                sendError.set(t);
            }
        }, "WSLEngine-sender");
        sender.setDaemon(true);
        sender.start();
        Image[][][] res = new Image[outputNames.length][nSamples][];
        long t0 = System.currentTimeMillis();
        try {
            for (Job job : jobs) {
                WSLPredictionServer.Message m;
                try {
                    m = server.receive(processTimeout.getDoubleValue(), "predicting samples [" + job.from + "; " + job.to + ")");
                } catch (RuntimeException e) {
                    Throwable se = sendError.get();
                    if (se != null) throw new RuntimeException("Error sending images to the WSL prediction server: " + se.getMessage(), se);
                    throw e;
                }
                Object id = m.header.get("id");
                if (id == null || ((Number) id).intValue() != job.id) throw new RuntimeException("Unexpected response from the WSL prediction server: id " + id + " instead of " + job.id);
                if (m.data.size() != outputNames.length) throw new RuntimeException("WSL prediction server returned " + m.data.size() + " outputs instead of " + outputNames.length);
                for (int o = 0; o < outputNames.length; ++o) {
                    ArraySpec spec = m.specs.get(o);
                    if (job.flipXYZ == null) {
                        Image[][] imNC = ArrayCodec.decode(m.data.get(o), spec);
                        if (imNC.length != job.to - job.from) throw new RuntimeException("output " + outputNames[o] + ": " + imNC.length + " samples instead of " + (job.to - job.from));
                        for (int i = job.from; i < job.to; ++i) res[o][i] = imNC[i - job.from];
                    } else {
                        Image[][] target = new Image[job.to - job.from][];
                        for (int i = job.from; i < job.to; ++i) target[i - job.from] = res[o][i];
                        ArrayCodec.addTo(target, m.data.get(o), spec, job.flipXYZ);
                    }
                    m.data.set(o, null); // release memory
                }
                if (job.lastOfBatch) {
                    if (flips.size() > 1) { // average of summed flipped predictions
                        double norm = flips.size();
                        for (int o = 0; o < res.length; ++o) {
                            for (int i = job.from; i < job.to; ++i) {
                                for (Image im : res[o][i]) ImageOperations.affineOpMulAdd(im, im, 1 / norm, 0);
                            }
                        }
                    }
                    if (progress != null) progress.increment(steps * (job.to - job.from) / nSamples);
                }
            }
        } catch (RuntimeException e) {
            sender.interrupt();
            close(); // stops the server and unblocks the sender; it is restarted at next call
            throw e;
        }
        try {
            sender.join(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.debug("WSL engine: {} samples predicted in {}ms ({} requests)", nSamples, System.currentTimeMillis() - t0, jobs.size());
        return res;
    }

    /**
     * @return null (no flip) followed by the flips to average, as in TF2Engine
     */
    List<boolean[]> getFlipVariants() {
        boolean[] f = new boolean[flip.getChildCount()];
        for (int i = 0; i < f.length; ++i) f[i] = flip.getChildAt(i).getValue().intValue() == 1;
        List<boolean[]> res = new ArrayList<>();
        res.add(null);
        if (f.length > 0 && f[0]) res.add(new boolean[]{true, false, false});
        if (f.length > 1 && f[1]) res.add(new boolean[]{false, true, false});
        if (f.length > 1 && f[0] && f[1]) res.add(new boolean[]{true, true, false});
        if (f.length > 2 && f[2]) res.add(new boolean[]{false, false, true});
        return Collections.unmodifiableList(res);
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
    public String[] getOutputNames() {
        return outputNames;
    }

    @Override
    public String[] getInputNames() {
        return inputNames;
    }

    @Override
    public DLEngine setOutputNumber(int outputNumber) {
        return this;
    }

    @Override
    public DLEngine setInputNumber(int inputNumber) {
        return this;
    }

    @Override
    public synchronized void close() { // stopping the server also frees the GPU memory
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                logger.debug("error closing WSL server", e);
            }
        }
        server = null;
        inputNames = null;
        outputNames = null;
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        if (!metadata.getInputs().get(0).is3D()) zAxis.setValue(Z_AXIS.BATCH);
        TextParameter zAxis = metadata.getOtherParameter("Z-Axis", TextParameter.class);
        if (zAxis != null) this.zAxis.setSelectedItem(zAxis.getValue());
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "Deep Learning engine for Windows: predictions run on the GPU with Tensorflow, in a dedicated WSL (Windows Subsystem for Linux) distribution. Does not require Docker. <br/>" +
                "At first use, the WSL distribution is installed automatically (right-click on the engine to check, install or update it). Requires an NVIDIA GPU and a recent NVIDIA driver installed on Windows. See BACMMAN wiki, Installation page.";
    }
}
