package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.*;
import bacmman.core.DLEngineProvider;
import bacmman.image.Image;
import bacmman.plugins.DLEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;

public class DefaultEngine implements DLEngine {
    final static Logger logger = LoggerFactory.getLogger(DefaultEngine.class);

    MLModelFileParameter modelFile = new MLModelFileParameter("Model").setValidDirectory(MLModelFileParameter.containsTensorflowModel).setEmphasized(true).setHint("Select the folder containing the saved model");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 0, 0, null).setHint("Size of the mini batches. Reduce to limit out-of-memory errors, and optimize according to the device. O : process all samples");
    EnumChoiceParameter<Z_AXIS> zAxis = new EnumChoiceParameter<>("Z-Axis", Z_AXIS.values(), Z_AXIS.Z)
            .setHint("Choose how to handle Z axis: <ul><li>Z_AXIS: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>BATCH: tensor are treated as 2D images </li></ul>");
    BoundedNumberParameter channelIdx = new BoundedNumberParameter("Channel Index", 0, 0, 0, null).setHint("Channel Used when Z axis is transposed to channel axis");
    ConditionalParameter<Z_AXIS> zAxisCond = new ConditionalParameter<>(zAxis)
            .setActionParameters(Z_AXIS.CHANNEL, channelIdx)
            .setLegacyParameter((p, a) -> a.setActionValue( ((BooleanParameter)p[0]).getSelected()? Z_AXIS.CHANNEL : Z_AXIS.Z), new BooleanParameter("Z as Channel", false));

    Parameter[] parameters = {modelFile, batchSize, zAxisCond};

    // properties
    int inputNumber = -1;
    int outputNumber = -1;
    DLEngine engine;

    @Override
    public Image[][][] process(Image[][]... inputNC) {
        return engine.process(inputNC);
    }

    @Override
    public void init() {
        if (this.engine == null) {
            Experiment xp = ParameterUtils.getExperiment(this.modelFile);
            this.engine = DLEngineProvider.getDefaultEngine(xp, Arrays.asList(this.parameters));
            if (engine == null)
                throw new RuntimeException("No Default DL Engine defined. Define it in menu Option > Default DL Engine");
            if (inputNumber >= 0) engine.setInputNumber(inputNumber);
            if (outputNumber >= 0) engine.setOutputNumber(outputNumber);
        }
        this.engine.init();
    }

    @Override
    public int getNumOutputArrays() {
        return engine.getNumOutputArrays();
    }

    @Override
    public int getNumInputArrays() {
        return engine.getNumOutputArrays();
    }

    @Override
    public String[] getOutputNames() {return engine.getOutputNames();}

    @Override
    public String[] getInputNames() {return engine.getInputNames();}

    @Override
    public DLEngine setOutputNumber(int outputNumber) {
        this.outputNumber = outputNumber;
        if (engine != null) engine.setOutputNumber(outputNumber);
        return this;
    }

    @Override
    public DLEngine setInputNumber(int outputNumber) {
        this.inputNumber = outputNumber;
        if (engine != null) engine.setInputNumber(outputNumber);
        return this;
    }

    @Override
    public void close() {
        if (engine != null) engine.close();
        engine = null;
    }

    @Override
    public int[] getGPUs() {
        return engine.getGPUs();
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
