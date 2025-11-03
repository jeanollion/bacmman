package bacmman.configuration.parameters;

import bacmman.core.DockerGateway;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.configuration.parameters.PythonConfiguration.toSnakeCase;
public class TrainingConfigurationParameter extends GroupParameterAbstract<TrainingConfigurationParameter> implements PythonConfiguration {
    TrainingParameter trainingParameters;
    GlobalDatasetParameters globalDatasetParameters;
    SimpleListParameter<? extends DatasetParameter> datasetList;
    BooleanParameter testConstantView;
    ArrayNumberParameter testInputShape;
    GroupParameter testDataAug;
    Parameter[] otherParameters;
    Supplier<Path> refPathFun;

    public TrainingConfigurationParameter(String name, boolean multipleInputChannels, boolean inputLabel, Parameter[] trainingParameters, Parameter[] globalDatasetParameters, Parameter[] dataAugmentationParameters, Parameter[] otherDatasetParameters, Parameter[] otherParameters, Parameter[] testDataAugmentationParameters) {
        this(name, multipleInputChannels, inputLabel, true, trainingParameters, globalDatasetParameters, dataAugmentationParameters, otherDatasetParameters, otherParameters, testDataAugmentationParameters);
    }
    
    public TrainingConfigurationParameter(String name, boolean multipleInputChannels, boolean inputLabel, boolean scaling, Parameter[] trainingParameters, Parameter[] globalDatasetParameters, Parameter[] dataAugmentationParameters, Parameter[] otherDatasetParameters, Parameter[] otherParameters, Parameter[] testDataAugmentationParameters) {
        super(name);
        this.trainingParameters = new TrainingParameter("Training", trainingParameters);
        this.globalDatasetParameters = new GlobalDatasetParameters("Dataset", globalDatasetParameters);
        this.datasetList = new SimpleListParameter<>("Dataset List", new DatasetParameter("Dataset", multipleInputChannels, inputLabel, scaling, dataAugmentationParameters, otherDatasetParameters))
            .addchildrenPropertyValidation(DatasetParameter::getChannelNumber, true)
            .addchildrenPropertyValidation(DatasetParameter::getLabelNumber, true)
            .setChildrenNumber(1)
            .addValidationFunction(l -> !l.getActivatedChildren().isEmpty());
        if (otherParameters == null) otherParameters = new Parameter[0];
        this.otherParameters = otherParameters;
        List<Parameter> testAugParams = new ArrayList<>();
        testAugParams.add(new BoundedNumberParameter("Iteration Number", 0, 10, 1, null).setHint("Number of random versions of the same mini batch"));
        testAugParams.add(new BoundedNumberParameter("Batch Index", 0, -1, -1, null)
            .setHint("Index of batch on which augmentation parameters will be tested. -1 = random idx"));
            /*.addValidationFunction(b -> {
                Object tp = ((ContainerParameter)b.getParent().getParent()).getChildren().stream().filter(p -> p instanceof TrainingParameter).findAny().orElse(null);
                if (tp==null) return true;
                return b.getIntValue() < ((TrainingParameter)tp).getStepNumber();
            }))*/
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

    public TrainingConfigurationParameter setInputShape(int... inputShape) {
        this.globalDatasetParameters.inputShape.setValue(inputShape);
        return this;
    }

    public TrainingConfigurationParameter setTestInputShape(int... inputShape) {
        this.testInputShape.setValue(inputShape);
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
    public TrainingConfigurationParameter setLearningRate(double value) {
        this.trainingParameters.learningRate.setValue(value);
        return this;
    }
    public TrainingConfigurationParameter setWorkers(int value) {
        this.trainingParameters.workers.setValue(value);
        return this;
    }

    protected TrainingConfigurationParameter(String name, TrainingParameter tp, GlobalDatasetParameters gdp,  SimpleListParameter<? extends DatasetParameter> dl, Parameter[] otherParameters, GroupParameter testDataAug) {
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

    public TrainingConfigurationParameter setReferencePathFunction(Supplier<Path> refPathFun) {
        this.refPathFun = refPathFun;
        datasetList.getChildren().forEach(c -> c.setRefPathFun(refPathFun));
        trainingParameters.setRefPathFun(refPathFun);
        return this;
    }

    public TrainingConfigurationParameter setDockerImageRequirements(String imageName, String versionPrefix, int[] minimalVersion, int[] maximalVersion) {
        trainingParameters.dockerImage.setImageRequirement(imageName, versionPrefix, minimalVersion, maximalVersion);
        trainingParameters.dockerImageExport.setImageRequirement(imageName, versionPrefix, minimalVersion, maximalVersion);
        return this;
    }

    public DockerGateway.DockerImage getSelectedDockerImage(boolean export) {
        if (export && trainingParameters.dockerImageExport.getSelectedIndex()>=0) {
            trainingParameters.dockerImageExport.refreshImageList();
            return trainingParameters.dockerImageExport.getValue();
        }
        trainingParameters.dockerImage.refreshImageList();
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

    public SimpleListParameter<? extends DatasetParameter> getDatasetList() {
        return datasetList;
    }

    public Parameter[] getOtherParameters() {
        return otherParameters;
    }

    public int getChannelNumber() {
        if (datasetList.isEmpty()) return -1;
        return datasetList.getChildAt(0).getChannelNumber();
    }

    public int getLabelNumber() {
        if (datasetList.isEmpty()) return -1;
        return datasetList.getChildAt(0).getLabelNumber();
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
    public static BoundedNumberParameter getStartEpochParameter() {
        return new BoundedNumberParameter("Start Epoch", 0, 0, 0, null).setHint("Indicate how many epoch have already been performed when retraining a network");
    }

    public static BoundedNumberParameter getPatienceParameter(int defaultValue) {
        return new BoundedNumberParameter("Patience", 0, defaultValue, 1, null);
    }

    public static BoundedNumberParameter getMinLearningRateParameter(double defaultValue) {
        return new BoundedNumberParameter("Min Learning Rate", 8, defaultValue, Math.min(10e-8, defaultValue), null);
    }

    public static IntervalParameter getEpsilonRangeParameter(double defaultMaxValue, double defaultMinValue) {
        return new IntervalParameter("Epsilon Range", 8, Math.min(10e-8, defaultMinValue), null, defaultMinValue, defaultMaxValue).setHint("Epsilon parameter used by optimiser (such as Adam). Default is usually a low value such as 1e-7 but values close to 1 can improve training efficiency in some cases. If a range is given, epsilon will start at the maximal value and end at the lower value.");
    }

    public static BoundedNumberParameter getLossMaxWeightParameter(double defaultMaxValue) {
        return new BoundedNumberParameter("Max Loss Weight", 5, 10, 0, null)
                .setLegacyParameter((i, p) -> p.setValue(((IntervalParameter)i[0]).getValuesAsDouble()[1]), new IntervalParameter("Loss Weight Range", 8, 1/defaultMaxValue, null, 1/defaultMaxValue, defaultMaxValue))
                .setHint("Category imbalance is corrected by loss weights that correspond to inverse class frequency. This parameter limits weigh values.");
    }

    public static BooleanParameter getUseSharedMemParameter(boolean defaultValue) {
        return new BooleanParameter("Use Shared Memory", defaultValue).setHint("If true, and multiprocessing is enabled, mini-batch will be passed through shared memory to speed up training. Increase the size of shared memory so that the mini-batch queue can fit (queue max size = workers*1.5)");
    }

    public static BoundedNumberParameter getValidationStepParameter(int defaultValue) {
        return new BoundedNumberParameter("Validation Step Number", 0, defaultValue, 1, null).setHint("Total number of steps (batches of samples) to draw before stopping when performing validation at the end of every epoch. <br/>Validation is only performed if datasets of type TEST are provided");
    }
    public static BoundedNumberParameter getValidationFreqParameter(int defaultValue) {
        return new BoundedNumberParameter("Validation Frequency", 0, defaultValue, 1, null).setHint("Specifies how many training epochs to run before a new validation run is performed, e.g. validation_freq=2 runs validation every 2 epochs.<br/>Validation is only performed if datasets of type TEST are provided");
    }

    public static BooleanParameter getMixedPrecisionParameter(boolean defaultValue) {
        return new BooleanParameter("Mixed Precision", defaultValue).setHint("If true, training is performed in mixed precision mode which reduces memory footprint (up to a factor 2) as well as computation time, at the cost of a small decrease in accuracy. <br/>Most operations are performed in float16 but gradients are still computed in float32. <br/>Not supported on all GPUs not CPUs. ");
    }

    public enum RESIZE_MODE {NONE, RESAMPLE, PAD, EXTEND}
    public static EnumChoiceParameter<RESIZE_MODE> getResizeModeParameter(RESIZE_MODE defaultValue, IntSupplier parentObjectClass, Supplier<int[]> resizeDim, RESIZE_MODE... options) {
        if (options.length == 0) options = RESIZE_MODE.values();
        return new EnumChoiceParameter<>("Resize Mode", options, defaultValue).addValidationFunction(rm -> {
            switch (rm.getSelectedEnum()) {
                case EXTEND:
                    if (resizeDim != null) {
                        if (IntStream.of(resizeDim.get()).anyMatch(i -> i==0)) return false; // dimension cannot be null
                    }
                    if (parentObjectClass!=null) return parentObjectClass.getAsInt() >=0;
                    return true;
                case RESAMPLE:
                case PAD:
                    if (resizeDim != null) {
                        if (IntStream.of(resizeDim.get()).anyMatch(i -> i==0)) return false; // dimension cannot be null
                    }
                    return true;
                default:
                    return true;
            }
        }).setHint("Method to resize method: <br /><ul>" +
                "<li>EXTEND: extracted images are extended to target dimensions even outside the parent bounds, dimensions cannot be null, and parent object class (selection object class) cannot be viewfield objects (root).</li>" +
                "<li>RESAMPLE: Resizes all images to a fixed size that must be compatible with the network input requirements.</li>" +
                "<li>PAD: Expands image on sides with border value. Differs from EXTEND because padded values are values at border.</li>" +
                "</ul>");
    }

    public static class TrainingParameter extends GroupParameterAbstract<TrainingParameter> implements PythonConfiguration {
        DockerImageParameter dockerImage = new DockerImageParameter("Docker Image");
        DockerImageParameter dockerImageExport = new DockerImageParameter("Docker Image (export)").setAllowNoSelection(true).setHint("Docker image used to export model only. If left to void, <em>Docker Image</em> will be used");
        BoundedNumberParameter epochNumber = new BoundedNumberParameter("Epoch Number", 0, 32, 0, null);
        BoundedNumberParameter stepNumber = new BoundedNumberParameter("Step Number", 0, 100, 1, null);;
        BoundedNumberParameter learningRate = new BoundedNumberParameter("Learning Rate", 8, 2e-4, 10e-8, null);
        TextParameter modelName = new TextParameter("Model Name", "", false, false).setHint("Name given to log / weight and saved model");
        BoundedNumberParameter workers = new BoundedNumberParameter("Multiprocessing Workers", 0, 8, 1, null).setHint("Number of CPU threads at training. Can increase training speed when mini-batch generation is time-consuming");
        Supplier<Path> refPathFun;
        MLModelFileParameter loadModelFile = new MLModelFileParameter("Load Model")
                .setFileChooserOption(FileChooser.FileChooserOption.FILE_OR_DIRECTORY)
                .setSelectedFilePath(null)
                .setGetRefPathFunction(p -> refPathFun != null ? refPathFun.get() : null)
                .allowNoSelection(true)
                .setHint("Saved model weights that will be loaded before training (optional)")
                .setFileChooserHint("Saved model weight");
        protected TrainingParameter(String name, Parameter[] additionnalParameter) {
            super(name);
            this.children = new ArrayList<>();
            children.add(dockerImage);
            children.add(dockerImageExport);
            children.add(epochNumber);
            children.add(stepNumber);
            children.add(learningRate);
            children.add(workers);
            children.addAll(Arrays.asList(additionnalParameter));
            children.add(modelName);
            children.add(loadModelFile);
            initChildList();
        }

        public DockerGateway.DockerImage getDockerImage(boolean export) {
            DockerGateway.DockerImage im = null;
            if (export) im = this.dockerImageExport.getValue();
            if (!export || im == null) im = this.dockerImage.getValue();
            return im;
        }

        public MLModelFileParameter getLoadModelFile() {
            return loadModelFile;
        }

        public TrainingParameter setRefPathFun(Supplier<Path> refPathFun) {
            this.refPathFun = refPathFun;
            return this;
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
                if (p == loadModelFile) {
                    String lm = loadModelFile.getSelectedPath();
                    if (lm !=null ) res.put("load_model_file", lm);
                } else if (p instanceof PythonConfiguration) res.put(((PythonConfiguration)p).getPythonConfigurationKey(), ((PythonConfiguration)p).getPythonConfiguration());
                else if (p instanceof NumberParameter && p.getName().equals("Epoch Number")) res.put("n_epochs", p.toJSONEntry());
                else res.put(toSnakeCase(p.getName()), p.toJSONEntry());
            }
            return res;
        }

        public <T extends Parameter> T getParameter(Class<T> clazz, String name) {
            List<T> res = children.stream().filter(p -> clazz.isAssignableFrom(p.getClass())).filter(name==null ? p -> true : p -> p.getName().equals(name)).map(p -> (T)p).collect(Collectors.toList());
            if (res.size() == 1) return res.get(0);
            else return null;
        }

        public String getModelWeightFileName() {
            boolean legacy = true;
            String file = modelName.getValue();
            if (!file.contains(".")) return file + (legacy ? ".h5" : ".weights.h5");
            else return file;
        }

        public String getModelName() {
            return modelName.getValue();
        }


        @Override
        public String getPythonConfigurationKey() {return "training_parameters";}
    }

    public enum TILE_NUMBER_MODE {CONSTANT, AUTOMATIC}
    enum DATASET_TYPE {TRAIN, TEST, EVAL}
    public class DatasetParameter extends GroupParameterAbstract<DatasetParameter> implements PythonConfiguration, Deactivable {
        FileChooser path = new FileChooser("File Path", FileChooser.FileChooserOption.FILE_OR_DIRECTORY,false)
                .setRelativePath(true);
        TextParameter keyword = new TextParameter("Keyword", "", false, true).setHint("Keyword to filter paths within dataset. Only paths that include the keyword will be considered");
        TextParameter channel = new TextParameter("Channel Name", "", false, false)
                .setHint("Name of images / movies to consider within the dataset");
        EnumChoiceParameter<DATASET_TYPE> type = new EnumChoiceParameter<>("Dataset Type", DATASET_TYPE.values(), DATASET_TYPE.TRAIN).setHint("Puropose of dataset: training, test (loss computation during training), evaluation (metric computation)");
        SimpleListParameter<TextParameter> channels = new SimpleListParameter<>("Channel Names", channel)
                .setMinChildCount(1).unique(TextParameter::getValue).setChildrenNumber(1).setUnmutableIndex(0).setHint("Name of images / movies to consider within the dataset")
                .setNewInstanceConfigurationFunction((l,i,t)-> {if (i>0) t.setValue("");})
                .setLegacyInitializationValue(Collections.singletonList(channel)); // in case switch from mono to multichannnel -> keep the parametrization
        TextParameter label = new TextParameter("Label Name", "", false, false).setHint("Name of images / movies corresponding to labeled objects to consider within the dataset");
        SimpleListParameter<TextParameter> labels = new SimpleListParameter<>("Label Names", label)
                .unique(TextParameter::getValue).setHint("Name of images / movies corresponding to labels to consider within the dataset, usually used as input labels (depending on the application)");
        DLScalingParameter scaler = new DLScalingParameter("Intensity Scaling");
        SimpleListParameter<DLScalingParameter> scalers = new SimpleListParameter<>("Intensity Scaling", scaler)
            .setHint("Input channel scaling parameter (one per channel or one for all channels)")
            .addValidationFunction(TrainingConfigurationParameter.channelNumberValidation(false))
            .setNewInstanceNameFunction((l, idx) -> {
                SimpleListParameter<TextParameter> channelNames = l.getParent()==null?null:ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, l, cn -> cn.getName().equals("Channel Names"));
                if ( channelNames != null && channelNames.getChildCount() > idx ) return channelNames.getChildAt(idx).getValue();
                else return "Channel "+idx;
            });
        BoundedNumberParameter concatProp = new BoundedNumberParameter("Concatenate Proportion", 5, 1, 0, null ).setHint("In case list contains several datasets, this allows to modulate the probability that a dataset is picked in a mini batch. <br /> e.g.: 0.5 means a batch has twice less chances to be picked from this dataset compared to 1.");
        ChoiceParameter loadInSharedMemory = new ChoiceParameter("Load in shared memory", new String[]{"true", "auto", "false"}, "auto", false).setHint("If true, the whole dataset will be loaded in shared memory, to improve access and memory management when using multiprocessing. <br/>Disable this option for large datasets that do not fit in shared memory. <br/>Amount of shared memory is set in the docker options. <br/> In auto mode, dataset is loaded only if Gb of shared memory for files smaller than 16Gb. When several datasets are concatenated, this test is performed independently for each dataset, so shared memory can be filled");

        final boolean multipleChannel, scaling, inputLabel;
        final GroupParameter dataAug;
        final Parameter[] otherParameters;
        protected DatasetParameter(String name, boolean multipleChannel, boolean inputLabel, boolean scaling, Parameter[] dataAugParameters, Parameter[] otherParameters){
            super(name);
            path.setGetRefPathFunction(p -> refPathFun == null ? null : refPathFun.get());
            this.multipleChannel=multipleChannel;
            this.otherParameters= otherParameters!=null ? otherParameters : new Parameter[0];
            this.children = new ArrayList<>();
            children.add(path);
            if (multipleChannel) children.add(channels);
            else children.add(channel);
            this.inputLabel = inputLabel;
            if (inputLabel) children.add(labels);
            children.add(keyword);
            children.add(type);
            children.add(concatProp);
            children.add(loadInSharedMemory);
            this.scaling = scaling;
            if (scaling) {
                if (multipleChannel) children.add(scalers);
                else children.add(scaler);
            }
            children.addAll(Arrays.asList(this.otherParameters));
            if (dataAugParameters != null) {
                dataAug = new GroupParameter("Data Augmentation", ParameterUtils.duplicateArray(dataAugParameters));
                children.add(dataAug);
            } else dataAug = null;
            initChildList();
            scalers.setChildrenNumber(1);
        }

        public int getChannelNumber() {
            if (multipleChannel) return channels.getChildCount();
            else return 1;
        }

        public int getLabelNumber() {
            if (inputLabel) return labels.getChildCount();
            else return 0;
        }

        public DLScalingParameter getScalingParameter(int channel) {
            if (multipleChannel) return scalers.getChildAt(channel);
            else return scaler;
        }

        public void setRefPathFun(Supplier<Path> refPathFun) {
            this.path.setRefPathFunction(refPathFun);
        }

        public DatasetParameter setFilePath(String path) {
            this.path.setSelectedFilePath(path);
            return this;
        }

        public String getFilePath() {
            return this.path.getFirstSelectedFilePath();
        }

        public List<Parameter> getDataAugmentationParameters() {
            return dataAug != null ? Collections.unmodifiableList(dataAug.children) : Collections.emptyList();
        }

        @Override
        public DatasetParameter duplicate() {
            DatasetParameter res = new DatasetParameter(name, multipleChannel, inputLabel, scaling, dataAug!=null ? ParameterUtils.duplicateArray(dataAug.children.toArray(new Parameter[0])) : null, ParameterUtils.duplicateArray(otherParameters));
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
        @Override
        public JSONObject getPythonConfiguration() {
            JSONObject res = new JSONObject();
            if (path.selectedFiles.length>0) res.put("path", path.selectedFiles[0]); // relative path if possible
            if (multipleChannel) {
                if (channels.getChildCount()>1) res.put("channel_name", channels.toJSONEntry());
                else res.put("channel_name", channels.getChildAt(0).toJSONEntry()); // retro compatibility
            } else res.put("channel_name", channel.toJSONEntry());
            if (inputLabel) res.put("label_name", labels.toJSONEntry());
            res.put("keyword", keyword.toJSONEntry());
            res.put("type", type.toJSONEntry());
            res.put("concat_proportion", concatProp.toJSONEntry());
            res.put("shared_memory", loadInSharedMemory.toJSONEntry());
            PythonConfiguration.putParameters(this.otherParameters, res);
            JSONObject dataAug = new JSONObject();
            res.put("data_augmentation", dataAug);
            if (scaling) {
                String sp_key = scaler.getPythonConfigurationKey(); // same for 1 or several channels
                if (multipleChannel) {
                    if (scalers.getChildCount()>1) dataAug.put(sp_key, scalers.getPythonConfiguration());
                    else dataAug.put(sp_key, scalers.getChildAt(0).getPythonConfiguration()); // retro compatibility
                } else {
                    dataAug.put(sp_key, scaler.getPythonConfiguration());
                }
            }
            if (this.dataAug!=null) PythonConfiguration.putParameters(this.dataAug.children, dataAug);
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
            if (!activated) Deactivable.appendActivated(res, false);
            return res;
        }
        @Override
        public void initFromJSONEntry(Object entry) {
            this.activated = Deactivable.getActivated(entry);
            entry = Deactivable.copyAndRemoveActivatedPropertyIfNecessary(entry);
            super.initFromJSONEntry(entry);
        }
    }

    public static class RandomTilingParameter extends GroupParameterAbstract<RandomTilingParameter> implements PythonConfiguration {
        EnumChoiceParameter<TILE_NUMBER_MODE> tileNumberMode = new EnumChoiceParameter<>("Tile Number Mode", TILE_NUMBER_MODE.values(), TILE_NUMBER_MODE.AUTOMATIC).setHint("Tile number determination: constant or depending on image size");
        BoundedNumberParameter nTiles = new BoundedNumberParameter("Tile Number", 0, 0, 1, null ).setHint("Number of tiles.");
        BoundedNumberParameter tileOverlapFraction = new BoundedNumberParameter("Tile Overlap Fraction", 5, 0.3, 0, 1);
        BooleanParameter anchorPoint = new BooleanParameter("Anchor Point", false).setHint("If true, an anchor point that will be included in all tiles will be defined as the average coordinated of the selected mask along all axis.");
        IntegerParameter anchorPointMaskIdx = new IntegerParameter("Mask Idx", -1).setLowerBound(0).setHint("Index of mask that defines the anchor point.");
        ConditionalParameter<Boolean> anchorPointCond = new ConditionalParameter<>(anchorPoint).setActionParameters(true, anchorPointMaskIdx);
        ConditionalParameter<TILE_NUMBER_MODE> tileNumberModeCond = new ConditionalParameter<>(tileNumberMode).setActionParameters(TILE_NUMBER_MODE.CONSTANT, nTiles, anchorPointCond).setActionParameters(TILE_NUMBER_MODE.AUTOMATIC, tileOverlapFraction);
        IntervalParameter zoomRange = new IntervalParameter("Zoom Range", 5, 1/4, 4, 1/1.1, 1.1).setHint("Interval for random zoom range; a value &lt; 1 zoom out. <br/>Zoom is randomized for each axis and aspect ratio can be limited by the aspect ratio parameter");
        IntervalParameter aspectRatioRange = new IntervalParameter("Aspect Ratio Range", 5, 1/2, 2, 1/1.1, 1.1).setHint("Interval that limits aspect ratio when zooming in/out");
        BoundedNumberParameter zoomProba = new BoundedNumberParameter("Zoom Probability", 5, 0.25, 0, 1).setHint("Probability to perform random zoom. 0 : tiles are never zoom, 1: tiles are always zoomed");
        ArrayNumberParameter jitter = InputShapesParameter.getInputShapeParameter(false, true, new int[]{10, 10}, null)
            .setMaxChildCount(3)
            .setName("Jitter Shape").setHint("Random jitter between different time points for timelapse dataset, in pixels. Allows for instance to improve robustness to lack of microscope stage stability");
        BooleanParameter randomStride = new BooleanParameter("Random Stride", true).setHint("Random spacing between tiles");
        BooleanParameter performAug = new BooleanParameter("Perform Augmentation", true).setHint("Tiles are randomly flipped and 90° rotated");
        BooleanParameter augRotate = new BooleanParameter("Augmentation Rotate", true).setHint("If false, tiles are only flipped during random rotate");
        ConditionalParameter<Boolean> performAugCond = new ConditionalParameter<>(performAug).setActionParameters(true, augRotate);
        BoundedNumberParameter interpolationOrder = new BoundedNumberParameter("Interpolation Order", 0, 1, 0, 5).setHint("The order of the spline interpolation for zoom in / zoom out");
        boolean constant, allowJitter;
        public RandomTilingParameter(String name) {
            super(name);
            this.children = new ArrayList<>();
            setConstant(false, true);
        }

        public RandomTilingParameter setConstant(boolean constant, boolean allowJitter) {
            children.clear();
            this.constant = constant;
            this.allowJitter = allowJitter;
            if (constant) {
                children.add(tileNumberModeCond);
                children.add(performAugCond);
                if (allowJitter) children.add(jitter);
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
                children.add(zoomProba);
                if (allowJitter) children.add(jitter);
                children.add(performAugCond);
                children.add(randomStride);
                children.add(interpolationOrder);
            }
            initChildList();
            return this;
        }

        public RandomTilingParameter setTileNumberMode(TILE_NUMBER_MODE mode, int defaultTileNumber, double defaultTileOverlapFraction) {
            tileNumberMode.setSelectedEnum(mode);
            nTiles.setValue(defaultTileNumber);
            this.tileOverlapFraction.setValue(defaultTileOverlapFraction);
            return this;
        }

        @Override
        public RandomTilingParameter duplicate() {
            RandomTilingParameter res = new RandomTilingParameter(name).setConstant(constant, allowJitter);
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            res.anchorPointMaskIdx.setHint(anchorPointMaskIdx.getHintText());
            return res;
        }
        @Override
        public void setContentFrom(Parameter other) {
            super.setContentFrom(other);
            anchorPointMaskIdx.setHint((((RandomTilingParameter)other).anchorPointMaskIdx).getHintText());
        }

        public RandomTilingParameter appendAnchorMaskIdxHint(String hint) {
            anchorPointMaskIdx.setHint(anchorPointMaskIdx.getHintText() + "<br/>" + hint);
            return this;
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
                    if (anchorPoint.getSelected()) json.put("anchor_point_mask_idx", anchorPointMaskIdx.getIntValue());
            }
            if (allowJitter) json.put("random_channel_jitter_shape", jitter.toJSONEntry());
            json.put(toSnakeCase(performAug.getName()), performAug.toJSONEntry());
            json.put(toSnakeCase(augRotate.getName()), augRotate.toJSONEntry());
            if (!constant) {
                for (Parameter p : new Parameter[]{zoomRange, aspectRatioRange, zoomProba, randomStride, interpolationOrder}) {
                    json.put(toSnakeCase(p.getName()), p.toJSONEntry());
                }
            }
            return json;
        }
    }
    public enum RESIZE_OPTION {RANDOM_TILING, CONSTANT_SIZE, CONSTANT_TILING} // PAD, RESAMPLE
    public static class InputSizerParameter extends ConditionalParameterAbstract<RESIZE_OPTION, InputSizerParameter> implements PythonConfiguration {
        RandomTilingParameter randomTiling = new RandomTilingParameter("Tiling");
        RandomTilingParameter constantTiling = new RandomTilingParameter("Tiling").setConstant(true, false);
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

        public InputSizerParameter appendAnchorMaskIdxHint(String hint) {
            randomTiling.appendAnchorMaskIdxHint(hint);
            constantTiling.appendAnchorMaskIdxHint(hint);
            return this;
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
            if (p.getParent() instanceof DatasetParameter) {
                return p.getChildCount() == ((DatasetParameter)p.getParent()).getChannelNumber();
            } else return true;
        };
    }
}
