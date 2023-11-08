package bacmman.configuration.parameters;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static bacmman.configuration.parameters.PythonConfiguration.toSnakeCase;
public class TrainingConfigurationParameter extends GroupParameterAbstract<TrainingConfigurationParameter> implements PythonConfiguration {
    TrainingParameter trainingParameters;
    GlobalDatasetParameters globalDatasetParameters;
    SimpleListParameter<DatasetParameter> datasetList;
    BooleanParameter testConstantView;
    ArrayNumberParameter testInputShape;
    GroupParameter testDataAug;
    Parameter[] otherParameters;
    Path refPath;
    public TrainingConfigurationParameter(String name, boolean multipleInputChannels, Parameter[] trainingParameters, Parameter[] globalDatasetParameters, Parameter[] dataAugmentationParameters, Parameter[] otherDatasetParameters, Parameter[] otherParameters, Parameter[] testDataAugmentationParameters) {
        super(name);
        this.trainingParameters = new TrainingParameter("Training", trainingParameters);
        this.trainingParameters.loadModelName.addValidationFunction(lmn -> {
            TrainingParameter tp = (TrainingParameter) lmn.getParent();
            String file = tp.getLoadModelWeightRelativePath();
            if (file==null) return true;
            else if (refPath==null) return true;
            return Paths.get(refPath.toString(), file).toFile().exists();
        });
        this.globalDatasetParameters = new GlobalDatasetParameters("Dataset", globalDatasetParameters);
        this.datasetList = new SimpleListParameter<>("Dataset List", new DatasetParameter("Dataset", multipleInputChannels, dataAugmentationParameters, otherDatasetParameters))
            .addchildrenPropertyValidation(DatasetParameter::getChannelNumber, true)
            .setChildrenNumber(1).addValidationFunction(l -> !l.getActivatedChildren().isEmpty());
        if (otherParameters == null) otherParameters = new Parameter[0];
        this.otherParameters = otherParameters;
        List<Parameter> testAugParams = new ArrayList<>();
        testAugParams.add(new BoundedNumberParameter("Iteration Number", 0, 10, 1, null).setHint("Number of random versions of the same mini batch"));
        testAugParams.add(new BoundedNumberParameter("Batch Index", 0, -1, -1, null)
            .setHint("Index of batch on which augmentation parameters will be tested. -1 = random idx")
            .addValidationFunction(b -> {
                Object tp = ((ContainerParameter)b.getParent().getParent()).getChildren().stream().filter(p -> p instanceof TrainingParameter).findAny().orElse(null);
                if (tp==null) return true;
                return b.getIntValue() < ((TrainingParameter)tp).getStepNumber();
        }));
        testAugParams.add(new BooleanParameter("Input Only", true).setHint("Only return augmented input images"));
        testAugParams.add(new BoundedNumberParameter("Batch Size", 0, 1, 1, null).setHint("size of test mini-batch for testing"));
        testAugParams.add(new BoundedNumberParameter("Concat Batch Size", 0, 1, 1, null).setHint("size of concatenated test mini-batch for testing"));
        testConstantView = new BooleanParameter("Constant View", false).setHint("Disable some augmentation parameters so that images have same view field, in order to facilitate comparison between iterations");
        testAugParams.add(testConstantView);
        testInputShape = InputShapesParameter.getInputShapeParameter(false, true, new int[]{256, 256}, null)
            .setMaxChildCount(3)
            .setName("Input Shape").setHint("Shape (Y, X) of the input image of the neural network");
        testAugParams.add(testInputShape);
        if (testDataAugmentationParameters!=null) testAugParams.addAll(Arrays.asList(testDataAugmentationParameters));
        this.testDataAug = new GroupParameter("Test Data Augmentation", testAugParams).setHint("These parameters will override the training parameter when testing data augmentation");
        this.children = new ArrayList<>();
        this.children.add(this.trainingParameters);
        this.children.add(this.globalDatasetParameters);
        this.children.add(datasetList);
        this.children.addAll(Arrays.asList(otherParameters));
        this.children.add(testDataAug);
        initChildList();
    }

    public TrainingConfigurationParameter setBatchSize(int batchSize) {
        this.globalDatasetParameters.batchSize.setValue(batchSize);
        return this;
    }
    public TrainingConfigurationParameter setConcatBatchSize(int batchSize) {
        this.globalDatasetParameters.concatBatchSize.setValue(batchSize);
        return this;
    }

    public TrainingConfigurationParameter setConcatBatchSize(int... inputShape) {
        this.globalDatasetParameters.inputShape.setValue(inputShape);
        return this;
    }

    public TrainingConfigurationParameter setEpochNumber(int value) {
        this.trainingParameters.epochNumber.setValue(value);
        return this;
    }
    public TrainingConfigurationParameter setStepNumber(int value) {
        this.trainingParameters.stepNumber.setValue(value);
        return this;
    }
    public TrainingConfigurationParameter setLearningRate(int value) {
        this.trainingParameters.learningRate.setValue(value);
        return this;
    }
    public TrainingConfigurationParameter setWorkers(int value) {
        this.trainingParameters.workers.setValue(value);
        return this;
    }

    protected TrainingConfigurationParameter(String name, TrainingParameter tp, GlobalDatasetParameters gdp,  SimpleListParameter<DatasetParameter> dl, Parameter[] otherParameters, GroupParameter testDataAug) {
        super(name);
        this.trainingParameters = tp.duplicate();
        this.globalDatasetParameters = gdp.duplicate();
        this.datasetList = dl.duplicate();
        this.otherParameters =  otherParameters == null ? new Parameter[0] : ParameterUtils.duplicateArray(otherParameters);
        this.testDataAug = testDataAug.duplicate();
        this.children = Arrays.asList(this.trainingParameters, this.globalDatasetParameters, datasetList);
        this.children.addAll(Arrays.asList(this.otherParameters));
        this.children.add(testDataAug);
        initChildList();
    }

    public TrainingConfigurationParameter setReferencePath(Path refPath) {
        this.refPath = refPath;
        datasetList.getChildren().forEach(c -> c.path.setRefPath(refPath));
        trainingParameters.refPath = refPath;
        return this;
    }

    public TrainingConfigurationParameter setDockerImageRequirements(String imageName, int[] minimalVersion, int[] maximalVersion) {
        trainingParameters.dockerImage.setImageRequirement(imageName, minimalVersion, maximalVersion);
        return this;
    }

    public TrainingConfigurationParameter refreshDockerImages() {
        trainingParameters.dockerImage.refreshImageList();
        return this;
    }

    public DockerImageParameter.DockerImage getSelectedDockerImage() {
        return trainingParameters.dockerImage.getValue();
    }

    public Parameter[] getChildParameters() {
        return children.toArray(new Parameter[0]);
    }

    @Override
    public TrainingConfigurationParameter duplicate() {
        TrainingConfigurationParameter res = new TrainingConfigurationParameter(name, trainingParameters, globalDatasetParameters, datasetList, otherParameters, testDataAug);
        ParameterUtils.setContent(res.children, children);
        transferStateArguments(this, res);
        return res;
    }

    @Override
    public JSONObject getPythonConfiguration() {
        JSONObject res = new JSONObject();
        res.put(trainingParameters.getPythonConfigurationKey(), trainingParameters.getPythonConfiguration());
        res.put(globalDatasetParameters.getPythonConfigurationKey(), globalDatasetParameters.getPythonConfiguration());
        JSONArray dsList = new JSONArray();
        for (DatasetParameter ds : datasetList.getActivatedChildren()) dsList.add(ds.getPythonConfiguration());
        res.put("dataset_list", dsList);
        for (Parameter p : otherParameters) {
            if (p instanceof PythonConfiguration) res.put(((PythonConfiguration)p).getPythonConfigurationKey(), ((PythonConfiguration)p).getPythonConfiguration());
            else res.put(toSnakeCase(p.getName()), p.toJSONEntry());
        }
        res.put("test_data_augmentation_parameters", testDataAug.getPythonConfiguration());
        return res;
    }
    @Override public String getPythonConfigurationKey() {
        return "training_configuration";
    }

    public TrainingParameter getTrainingParameters() {
        return trainingParameters;
    }

    public GlobalDatasetParameters getGlobalDatasetParameters() {
        return globalDatasetParameters;
    }

    public SimpleListParameter<DatasetParameter> getDatasetList() {
        return datasetList;
    }

    public Parameter[] getOtherParameters() {
        return otherParameters;
    }

    public int getChannelNumber() {
        if (datasetList.isEmpty()) return -1;
        return datasetList.getChildAt(0).getChannelNumber();
    }

    public static class GlobalDatasetParameters extends GroupParameterAbstract<GlobalDatasetParameters> implements PythonConfiguration {
        BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 32, 1, null ).setHint("Size of mini-batch");
        BoundedNumberParameter concatBatchSize = new BoundedNumberParameter("Concat Batch Size", 0, 1, 1, null ).setHint("In case several datasets are set, allows to draw mini-batches from different datasets: each final mini-batch size will be <em>Concat Batch Size</em> x <em>Batch Size</em> ");
        ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(false, true, new int[]{512, 512}, null)
                .setMaxChildCount(3)
                .setName("Input Shape").setHint("Shape (Y, X) of the input image of the neural network");
        protected GlobalDatasetParameters(String name, Parameter[] additionnalParameter) {
            super(name);
            this.children = new ArrayList<>();
            children.add(inputShape);
            children.add(batchSize);
            children.add(concatBatchSize);
            children.addAll(Arrays.asList(additionnalParameter));
            initChildList();
        }

        public int[] getInputShape() {
            return inputShape.getArrayInt();
        }

        @Override
        public GlobalDatasetParameters duplicate() {
            GlobalDatasetParameters res = new GlobalDatasetParameters(name, children.stream().skip(3).toArray(Parameter[]::new) );
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
        @Override
        public JSONObject getPythonConfiguration() {
            JSONObject res = new JSONObject();
            for (Parameter p : this.children) {
                if (p instanceof PythonConfiguration) res.put(((PythonConfiguration)p).getPythonConfigurationKey(), ((PythonConfiguration)p).getPythonConfiguration());
                else res.put(toSnakeCase(p.getName()), p.toJSONEntry());
            }
            return res;
        }
        @Override
        public String getPythonConfigurationKey() {return "dataset_parameters";}
    }


    public static BoundedNumberParameter getPatienceParameter(int defaultValue) {
        return new BoundedNumberParameter("Patience", 0, defaultValue, 1, null);
    }
    public static BoundedNumberParameter getMinLearningRateParameter(double defaultValue) {
        return new BoundedNumberParameter("Min Learning Rate", 8, defaultValue, 10e-8, null);
    }

    public static BooleanParameter getUseSharedMemParameter(boolean defaultValue) {
        return new BooleanParameter("Use Shared Memory", defaultValue).setHint("If true, and multiprocessing is enabled, mini-batch will be passed through shared memory to speed up training. Increase the size of shared memory so that the mini-batch queue can fit (queue max size = workers*1.5)");
    }

    public static class TrainingParameter extends GroupParameterAbstract<TrainingParameter> implements PythonConfiguration {
        DockerImageParameter dockerImage = new DockerImageParameter("Docker Image");
        BoundedNumberParameter epochNumber = new BoundedNumberParameter("Epoch Number", 0, 32, 0, null);
        BoundedNumberParameter stepNumber = new BoundedNumberParameter("Step Number", 0, 100, 1, null);;
        BoundedNumberParameter learningRate = new BoundedNumberParameter("Learning Rate", 8, 2e-4, 10e-8, null);
        TextParameter modelName = new TextParameter("Model Name", "", false, false).setHint("Name given to log / weight and saved model");
        BoundedNumberParameter workers = new BoundedNumberParameter("Multiprocessing Workers", 0, 8, 1, null).setHint("Number of CPU threads at training. Can increase training speed when mini-batch generation is time-consuming");
        TextParameter weightDir = new TextParameter("Weight Dir", "", false, true).setHint("Relative path to directory where weights will be stored (created if not existing)");
        TextParameter logDir = new TextParameter("Log Dir", "Logs", false, true).setHint("Relative path to directory where training logs will be stored (created if not existing)");
        Path refPath;
        MLModelFileParameter loadModelName = new MLModelFileParameter("Load Model")
                .setFileChooserOption(FileChooser.FileChooserOption.FILE_OR_DIRECTORY)
                .setSelectedFilePath(null)
                .setGetRefPathFunction(p -> refPath==null ? null : refPath.resolve(weightDir.getValue()))
                .allowNoSelection(true)
                .setHint("Saved model weights that will be loaded before training (optional)")
                .setFileChooserHint("Saved model weight, relative to <em>Weight Dir</em>");
        protected TrainingParameter(String name, Parameter[] additionnalParameter) {
            super(name);
            this.children = new ArrayList<>();
            children.add(dockerImage);
            children.add(epochNumber);
            children.add(stepNumber);
            children.add(learningRate);
            children.add(workers);
            children.addAll(Arrays.asList(additionnalParameter));
            children.add(modelName);
            children.add(loadModelName);
            children.add(weightDir);
            children.add(logDir);
            initChildList();
        }

        public int getStepNumber() {
            return stepNumber.getIntValue();
        }

        @Override
        public TrainingParameter duplicate() {
            TrainingParameter res = new TrainingParameter(name, children.stream().limit(children.size()-3).toArray(Parameter[]::new) );
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
        @Override
        public JSONObject getPythonConfiguration() {
            JSONObject res = new JSONObject();
            for (Parameter p : this.children) {
                if (p == loadModelName) {
                    String lm = getLoadModelWeightFileName();
                    if (lm !=null ) res.put("load_model_filename", lm);
                } else if (p instanceof PythonConfiguration) res.put(((PythonConfiguration)p).getPythonConfigurationKey(), ((PythonConfiguration)p).getPythonConfiguration());
                else if (p instanceof NumberParameter && p.getName().equals("Epoch Number")) res.put("n_epochs", p.toJSONEntry());
                else res.put(toSnakeCase(p.getName()), p.toJSONEntry());
            }
            return res;
        }
        public String getModelWeightFileName() {
            String file = modelName.getValue();
            if (!file.contains(".")) return file + ".h5";
            else return file;
        }
        public String getLoadModelWeightFileName() {
            File f = loadModelName.getModelFile();
            if (f == null) return null;
            else return f.getName();
        }
        public String getSavedWeightRelativePath() {
            if (!weightDir.getValue().isEmpty()) return Paths.get(weightDir.getValue(), getModelWeightFileName()).toString();
            else return getModelWeightFileName();
        }
        public String getLoadModelWeightRelativePath() {
            String file = getLoadModelWeightFileName();
            if (file == null) return null;
            if (!weightDir.getValue().isEmpty()) return Paths.get(weightDir.getValue(), file).toString();
            else return file;
        }

        public String getModelWeightRelativePath() {
            return modelName.getValue();
        }

        public String getLogRelativePath() {
            return logDir.getValue();
        }

        @Override
        public String getPythonConfigurationKey() {return "training_parameters";}
    }

    enum TILE_NUMBER_MODE {CONSTANT, AUTOMATIC}
    public class DatasetParameter extends GroupParameterAbstract<DatasetParameter> implements PythonConfiguration, Deactivatable {
        FileChooser path = new FileChooser("File Path", FileChooser.FileChooserOption.FILE_ONLY,false)
                .setRelativePath(true);
        TextParameter keyword = new TextParameter("Keyword", "", false, true).setHint("Keyword to filter paths within dataset. Only paths that include the keyword will be considered");
        TextParameter channel = new TextParameter("Channel Name", "raw", false, false).setHint("Name of images / movies to consider within the dataset");
        SimpleListParameter<TextParameter> channels = new SimpleListParameter<>("Channel Names", 0, channel).unique(TextParameter::getValue).setChildrenNumber(1).setUnmutableIndex(0);
        DLScalingParameter scaler = new DLScalingParameter("Intensity Scaling");
        SimpleListParameter<DLScalingParameter> scalers = new SimpleListParameter<>("Intensity Scaling", scaler).setHint("Input channel scaling parameter (one per channel or one for all channels)")
                .addValidationFunction(TrainingConfigurationParameter.channelNumberValidation(true))
                .setNewInstanceNameFunction((l, i) -> "Channel "+i).setChildrenNumber(1);
        BoundedNumberParameter concatProp = new BoundedNumberParameter("Concatenate Proportion", 5, 1, 0, null ).setHint("In case list contains several datasets, this allows to modulate the probability that a dataset is picked in a mini batch. <br /> e.g.: 0.5 means a batch has twice less chances to be picked from this dataset compared to 1.");

        final boolean multipleChannel;
        final GroupParameter dataAug;
        final Parameter[] otherParameters;
        protected DatasetParameter(String name, boolean multipleChannel, Parameter[] dataAugParameters, Parameter[] otherParameters){
            super(name);
            if (refPath!=null) path.setRefPath(refPath);
            this.multipleChannel=multipleChannel;
            this.otherParameters= otherParameters!=null ? otherParameters : new Parameter[0];
            this.children = new ArrayList<>();
            children.add(path);
            if (multipleChannel) children.add(channels);
            else children.add(channel);
            children.add(keyword);
            children.add(concatProp);
            if (multipleChannel) children.add(scalers);
            else children.add(scaler);
            children.addAll(Arrays.asList(this.otherParameters));
            if (dataAugParameters == null) dataAugParameters = new Parameter[0];
            dataAug = new GroupParameter("Data Augmentation", ParameterUtils.duplicateArray(dataAugParameters));
            children.add(dataAug);
            initChildList();
        }

        public int getChannelNumber() {
            if (multipleChannel) return channels.getChildCount();
            else return 1;
        }

        public DLScalingParameter getScalingParameter(int channel) {
            if (multipleChannel) return scalers.getChildAt(channel);
            else return scaler;
        }

        public void setRefPath(Path path) {
            this.path.setRefPath(path);
        }

        public List<Parameter> getDataAugmentationParameters() {
            return Collections.unmodifiableList(dataAug.children);
        }

        @Override
        public DatasetParameter duplicate() {
            DatasetParameter res = new DatasetParameter(name, multipleChannel, ParameterUtils.duplicateArray(dataAug.children.toArray(new Parameter[0])), ParameterUtils.duplicateArray(otherParameters));
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
        @Override
        public JSONObject getPythonConfiguration() {
            JSONObject res = new JSONObject();
            if (path.selectedFiles.length>0) res.put("path", path.selectedFiles[0]);
            res.put("channel_name", multipleChannel ? channels.toJSONEntry() : channel.toJSONEntry());
            res.put("keyword", keyword.toJSONEntry());
            res.put("concat_proportion", concatProp.toJSONEntry());
            PythonConfiguration.putParameters(this.otherParameters, res);
            JSONObject dataAug = new JSONObject();
            res.put("data_augmentation", dataAug);
            if (multipleChannel) {
                dataAug.put(scaler.getPythonConfigurationKey(), scalers.getPythonConfiguration());
            } else {
                dataAug.put(scaler.getPythonConfigurationKey(), scaler.getPythonConfiguration());
            }
            PythonConfiguration.putParameters(this.dataAug.children, dataAug);
            return res;
        }
        @Override
        public String getPythonConfigurationKey() {return null;}
        @Override
        public String toString() {
            return getName()
                + (path.getFirstSelectedFilePath()==null ? "" : ": " + new File(path.getFirstSelectedFilePath()).getName()) +
                (keyword.getValue().isEmpty()? "" : " @"+keyword.getValue());
        }

        @Override
        public boolean isActivated() {
            return activated;
        }
        boolean activated=true;
        @Override
        public void setActivated(boolean activated) {
            this.activated = activated;
        }
        @Override
        public JSONArray toJSONEntry() {
            JSONArray res = super.toJSONEntry();
            if (!activated) Deactivatable.appendActivated(res, false);
            return res;
        }
        @Override
        public void initFromJSONEntry(Object entry) {
            this.activated = Deactivatable.getActivated(entry);
            entry = Deactivatable.copyAndRemoveActivatedPropertyIfNecessary(entry);
            super.initFromJSONEntry(entry);
        }
    }

    public static class RandomTilingParameter extends GroupParameterAbstract<RandomTilingParameter> implements PythonConfiguration {
        EnumChoiceParameter<TILE_NUMBER_MODE> tileNumberMode = new EnumChoiceParameter<>("Tile Number Mode", TILE_NUMBER_MODE.values(), TILE_NUMBER_MODE.AUTOMATIC).setHint("Tile number determination: constant or depending on image size");
        BoundedNumberParameter nTiles = new BoundedNumberParameter("Tile Number", 0, 0, 1, null ).setHint("Number of tiles.");
        BoundedNumberParameter tileOverlapFraction = new BoundedNumberParameter("Tile Overlap Fraction", 5, 0.3, 0, 1);
        ConditionalParameter<TILE_NUMBER_MODE> tileNumberModeCond = new ConditionalParameter<>(tileNumberMode).setActionParameters(TILE_NUMBER_MODE.CONSTANT, nTiles).setActionParameters(TILE_NUMBER_MODE.AUTOMATIC, tileOverlapFraction);
        IntervalParameter zoomRange = new IntervalParameter("Zoom Range", 5, 1/2, 2, 1/1.1, 1.1).setHint("Interval for random zoom range; a value < 1 zoom out. Zoom is randomized for each axis and aspect ratio can be limited by the aspect ratio parameter");
        IntervalParameter aspectRatioRange = new IntervalParameter("Aspect Ratio Range", 5, 1/2, 2, 1/1.1, 1.1).setHint("Interval that limits aspect ratio when zooming in/out");
        ArrayNumberParameter jitter = InputShapesParameter.getInputShapeParameter(false, true, new int[]{10, 10}, null)
            .setMaxChildCount(3)
            .setName("Jitter Shape").setHint("Random jitter between different time points for timelapse dataset, in pixels. Allows for instance to improve robustness to lack of microscope stage stability");
        BooleanParameter randomStride = new BooleanParameter("Random Stride", true).setHint("Random spacing between tiles");
        BooleanParameter performAug = new BooleanParameter("Perform Augmentation", true).setHint("Tiles are randomly flipped and 90° rotated");
        BooleanParameter augRotate = new BooleanParameter("Augmentation Rotate", true).setHint("If false, tiles are only flipped during random rotate");
        BoundedNumberParameter interpolationOrder = new BoundedNumberParameter("Interpolation Order", 0, 1, 0, 5).setHint("The order of the spline interpolation for zoom in / zoom out");

        public RandomTilingParameter(String name) {
            super(name);
            this.children = new ArrayList<>();
            setConstant(false);
        }

        public RandomTilingParameter setConstant(boolean constant) {
            children.clear();
            if (constant) {
                children.add(tileNumberModeCond);
                children.add(performAug);
                children.add(augRotate);
                zoomRange.setValues(1, 1);
                aspectRatioRange.setValues(1, 1);
                jitter.setValue(0, 0);
                randomStride.setValue(false);
                performAug.setValue(false);
                tileNumberMode.setSelectedEnum(TILE_NUMBER_MODE.CONSTANT);
                nTiles.setValue(1);
            } else {
                children.add(tileNumberModeCond);
                children.add(zoomRange);
                children.add(aspectRatioRange);
                children.add(jitter);
                children.add(performAug);
                children.add(augRotate);
                children.add(randomStride);
                children.add(interpolationOrder);
            }
            initChildList();
            return this;
        }

        @Override
        public RandomTilingParameter duplicate() {
            RandomTilingParameter res = new RandomTilingParameter(name);
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }

        @Override
        public String getPythonConfigurationKey() {return "tiling_parameters";}
        @Override
        public Object getPythonConfiguration() {
            JSONObject json = new JSONObject();
            switch (tileNumberMode.getValue()) {
                case AUTOMATIC:
                default:
                    json.put("tile_overlap_fraction", tileOverlapFraction.getDoubleValue());
                    break;
                case CONSTANT:
                    json.put("n_tiles", nTiles.getIntValue());
            }
            json.put("random_channel_jitter_shape", jitter.toJSONEntry());
            for (Parameter p : new Parameter[]{zoomRange, aspectRatioRange, performAug, augRotate, randomStride, interpolationOrder}) {
                json.put(toSnakeCase(p.getName()), p.toJSONEntry());
            }
            return json;
        }
    }
    public enum RESIZE_OPTION {RANDOM_TILING, CONSTANT_SIZE, CONSTANT_TILING} // PAD, RESAMPLE
    public static class InputSizerParameter extends ConditionalParameterAbstract<RESIZE_OPTION, InputSizerParameter> implements PythonConfiguration {
        RandomTilingParameter randomTiling = new RandomTilingParameter("Tiling");
        RandomTilingParameter constantTiling = new RandomTilingParameter("Tiling").setConstant(true);
        public InputSizerParameter(String name) {
            this(name, RESIZE_OPTION.CONSTANT_SIZE);
        }
        public InputSizerParameter(String name, RESIZE_OPTION defaultOption) {
            this(name, defaultOption, RESIZE_OPTION.values());
        }
        public InputSizerParameter(String name, RESIZE_OPTION defaultOption, RESIZE_OPTION... supportedOptions) {
            super(new EnumChoiceParameter<>(name, supportedOptions, defaultOption));
            setActionParameters(RESIZE_OPTION.RANDOM_TILING, randomTiling.getChildren());
            setActionParameters(RESIZE_OPTION.CONSTANT_TILING, constantTiling.getChildren());
        }

        @Override
        public String getPythonConfigurationKey() {
            switch (getActionValue()) {
                case RANDOM_TILING:
                    return randomTiling.getPythonConfigurationKey();
                case CONSTANT_TILING:
                    return constantTiling.getPythonConfigurationKey();
                case CONSTANT_SIZE:
                default:
                    return "constant_size";
            }
        }
        @Override
        public Object getPythonConfiguration() {
            switch (getActionValue()) {
                case RANDOM_TILING:
                    return randomTiling.getPythonConfiguration();
                case CONSTANT_TILING:
                    return constantTiling.getPythonConfiguration();
                case CONSTANT_SIZE:
                default:
                    return "true";
            }
        }
        @Override
        public void setContentFrom(Parameter other) {
            super.setContentFrom(other);
            if (other instanceof InputSizerParameter) {
                EnumChoiceParameter<RESIZE_OPTION> otherChoice = (EnumChoiceParameter<RESIZE_OPTION>)((InputSizerParameter)other).action;
                EnumChoiceParameter<RESIZE_OPTION> thisChoice = (EnumChoiceParameter<RESIZE_OPTION>)action;
                thisChoice.setEnumChoiceList(otherChoice.enumChoiceList);
            }
        }
    }

    public static <T extends ListParameter> Predicate<T> channelNumberValidation(boolean allowOneForAll) {
        return p -> {
            if (allowOneForAll && p.getChildCount() == 1) return true;
            if (p.getParent() instanceof GroupParameter && p.getParent().getParent() instanceof DatasetParameter) {
                return p.getChildCount() == ((DatasetParameter)p.getParent().getParent()).getChannelNumber();
            } else return true;
        };
    }
}
