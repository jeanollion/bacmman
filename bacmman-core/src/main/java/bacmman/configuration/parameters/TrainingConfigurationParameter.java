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
public class TrainingConfigurationParameter extends GroupParameter implements PythonConfiguration {
    TrainingParameter trainingParameters;
    GlobalDatasetParameters datasetParameters;
    SimpleListParameter<DatasetParameter> datasetList;
    Path refPath;
    public TrainingConfigurationParameter(String name, boolean multipleInputChannels, boolean tiling, Parameter[] trainingParameters, Parameter[] datasetParameters, Parameter[] dataAugmentationParameters) {
        super(name);
        this.trainingParameters = new TrainingParameter("Training", trainingParameters);
        this.datasetParameters = new GlobalDatasetParameters("Dataset", tiling, datasetParameters);
        this.datasetList = new SimpleListParameter<>("Dataset List", new DatasetParameter("Dataset", multipleInputChannels, tiling, dataAugmentationParameters))
                .addchildrenPropertyValidation(DatasetParameter::getChannelNumber, true).setChildrenNumber(1).setUnmutableIndex(0);
        this.children = Arrays.asList(this.trainingParameters, this.datasetParameters, datasetList);
        initChildList();
    }

    protected TrainingConfigurationParameter(String name, TrainingParameter tp, GlobalDatasetParameters gdp,  SimpleListParameter<DatasetParameter> dl) {
        super(name);
        this.trainingParameters = tp.duplicate();
        this.datasetParameters = gdp.duplicate();
        this.datasetList = dl.duplicate();
        this.children = Arrays.asList(this.trainingParameters, this.datasetParameters, datasetList);
        initChildList();
    }

    public TrainingConfigurationParameter setReferencePath(Path refPath) {
        this.refPath = refPath;
        datasetList.getChildren().forEach(c -> c.path.setRefPath(refPath));
        return this;
    }

    public Parameter[] getChildParameters() {
        return children.toArray(new Parameter[0]);
    }

    @Override
    public TrainingConfigurationParameter duplicate() {
        TrainingConfigurationParameter res = new TrainingConfigurationParameter(name, trainingParameters, datasetParameters, datasetList);
        ParameterUtils.setContent(res.children, children);
        transferStateArguments(this, res);
        return res;
    }

    @Override
    public Object getPythonConfiguration() {
        JSONObject res = new JSONObject();
        res.put(trainingParameters.getPythonConfigurationKey(), trainingParameters.getPythonConfiguration());
        res.put(datasetParameters.getPythonConfigurationKey(), datasetParameters.getPythonConfiguration());
        JSONArray dsList = new JSONArray();
        for (DatasetParameter ds : datasetList.getActivatedChildren()) dsList.add(ds.getPythonConfiguration());
        res.put("dataset_list", dsList);
        return res;
    }
    @Override public String getPythonConfigurationKey() {
        return "training_configuration";
    }

    public TrainingParameter getTrainingParameters() {
        return trainingParameters;
    }

    public GlobalDatasetParameters getDatasetParameters() {
        return datasetParameters;
    }

    public SimpleListParameter<DatasetParameter> getDatasetList() {
        return datasetList;
    }

    public int getChannelNumber() {
        if (datasetList.isEmpty()) return -1;
        return datasetList.getChildAt(0).getChannelNumber();
    }

    public static class GlobalDatasetParameters extends GroupParameter implements PythonConfiguration {
        BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 32, 1, null ).setHint("Size of mini-batch");
        BoundedNumberParameter concatBatchSize = new BoundedNumberParameter("Concat Batch Size", 0, 1, 1, null ).setHint("In case several datasets are set, allows to draw mini-batches from different datasets: each final mini-batch size will be <em>Concat Batch Size</em> x <em>Batch Size</em> ");

        ArrayNumberParameter tileShape = InputShapesParameter.getInputShapeParameter(false, true, new int[]{512, 512}, null)
                .setMaxChildCount(2)
                .setName("Tile Shape").setHint("Shape (Y, X) of extracted tiles at training");

        final boolean tiling;
        protected GlobalDatasetParameters(String name, boolean tiling, Parameter[] additionnalParameter) {
            super(name);
            this.tiling = tiling;
            this.children = new ArrayList<>();
            children.add(batchSize);
            children.add(concatBatchSize);
            if (tiling) children.add(tileShape);
            children.addAll(Arrays.asList(additionnalParameter));
            initChildList();
        }

        @Override
        public GlobalDatasetParameters duplicate() {
            GlobalDatasetParameters res = new GlobalDatasetParameters(name, tiling, children.stream().skip(3).toArray(Parameter[]::new) );
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
        @Override
        public Object getPythonConfiguration() {
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
    public static BoundedNumberParameter getNEpochParameter(int defaultValue) {
        return new BoundedNumberParameter("Epoch Number", 0, defaultValue, 1, null);
    }

    public static BoundedNumberParameter getLearningRateParameter(double defaultValue) {
        return new BoundedNumberParameter("Learning Rate", 8, defaultValue, 10e-8, null);
    }

    public static BoundedNumberParameter getPatienceParameter(int defaultValue) {
        return new BoundedNumberParameter("Patience", 0, defaultValue, 1, null);
    }

    public static BoundedNumberParameter getMultiprocessingWorkerParameter(int defaultValue) {
        return new BoundedNumberParameter("Multiprocessing Workers", 0, defaultValue, 1, null).setHint("Number of CPU threads at training. Can increase training speed when mini-batch generation is time-consuming");
    }

    public static class TrainingParameter extends GroupParameter implements PythonConfiguration {
        TextParameter modelName = new TextParameter("Model Name", "", false, false).setHint("Name given to log / weight and saved model");

        TextParameter weightDir = new TextParameter("Weight Dir", "", false, true).setHint("Relative path to directory where weights will be stored (created if not existing)");
        TextParameter logDir = new TextParameter("Log Dir", "Logs", false, true).setHint("Relative path to directory where training logs will be stored (created if not existing)");
        protected TrainingParameter(String name, Parameter[] additionnalParameter) {
            super(name);
            this.children = new ArrayList<>();
            children.addAll(Arrays.asList(additionnalParameter));
            children.add(modelName);
            children.add(weightDir);
            children.add(logDir);
            initChildList();
        }

        @Override
        public TrainingParameter duplicate() {
            TrainingParameter res = new TrainingParameter(name, children.stream().limit(children.size()-3).toArray(Parameter[]::new) );
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
        @Override
        public Object getPythonConfiguration() {
            JSONObject res = new JSONObject();
            for (Parameter p : this.children) {
                if (p instanceof PythonConfiguration) res.put(((PythonConfiguration)p).getPythonConfigurationKey(), ((PythonConfiguration)p).getPythonConfiguration());
                else if (p instanceof NumberParameter && p.getName().toLowerCase().contains("epochs") && p.getName().toLowerCase().contains("number")) res.put("n_epochs", p.toJSONEntry());
                else res.put(toSnakeCase(p.getName()), p.toJSONEntry());
            }
            return res;
        }
        public String getModelWeightFile() {
            String file = modelName.getValue();
            if (!file.contains(".")) return file + ".h5";
            else return file;
        }
        public String getSavedWeightRelativePath() {
            if (!weightDir.getValue().isEmpty()) return Paths.get(weightDir.getValue(), getModelWeightFile()).toString();
            else return getModelWeightFile();
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
    public class DatasetParameter extends GroupParameter implements PythonConfiguration {
        FileChooser path = new FileChooser("File Path", FileChooser.FileChooserOption.FILE_ONLY,false)
                .setRelativePath(true);
        TextParameter keyword = new TextParameter("Keyword", "", false, true).setHint("Keyword to filter paths within dataset. Only paths that include the keyword will be considered");
        TextParameter channel = new TextParameter("Channel Name", "raw", false, false).setHint("Name of images / movies to consider within the dataset");
        SimpleListParameter<TextParameter> channels = new SimpleListParameter<>("Channel Names", 0, channel).unique(TextParameter::getValue).setChildrenNumber(1).setUnmutableIndex(0);
        BoundedNumberParameter concatProp = new BoundedNumberParameter("Concatenate Proportion", 5, 1, 0, null ).setHint("In case list contains several datasets, this allows to modulate the probability that a dataset is picked in a mini batch.");

        EnumChoiceParameter<TILE_NUMBER_MODE> tileNumberMode = new EnumChoiceParameter<>("Tile Number Mode", TILE_NUMBER_MODE.values(), TILE_NUMBER_MODE.AUTOMATIC).setHint("Tile number determination: constant or depending on image size");
        BoundedNumberParameter nTiles = new BoundedNumberParameter("Tile Number", 0, 0, 1, null ).setHint("Number of tiles.");
        BoundedNumberParameter tileOverlapFraction = new BoundedNumberParameter("Tile Overlap Fraction", 5, 0.3, 0, 1);
        ConditionalParameter<TILE_NUMBER_MODE> tileNumberModeCond = new ConditionalParameter<>(tileNumberMode).setActionParameters(TILE_NUMBER_MODE.CONSTANT, nTiles).setActionParameters(TILE_NUMBER_MODE.AUTOMATIC, tileOverlapFraction);
        final boolean tiling, multipleChannel;
        final GroupParameter dataAug;
        protected DatasetParameter(String name, boolean multipleChannel, boolean tiling, Parameter[] dataAugParameters) {
            super(name);
            if (refPath!=null) path.setRefPath(refPath);
            this.tiling = tiling;
            this.multipleChannel=multipleChannel;
            this.children = new ArrayList<>();
            children.add(path);
            if (multipleChannel) children.add(channels);
            else children.add(channel);
            children.add(keyword);
            children.add(concatProp);
            if (tiling) children.add(tileNumberModeCond);
            dataAug = new GroupParameter("Data Augmentation", ParameterUtils.duplicateArray(dataAugParameters));
            children.add(dataAug);
            initChildList();
        }

        public int getChannelNumber() {
            if (multipleChannel) return channels.getChildCount();
            else return 1;
        }

        public void setRefPath(Path path) {
            this.path.setRefPath(path);
        }

        public List<Parameter> getDataAugmentationParameters() {
            return Collections.unmodifiableList(dataAug.children);
        }

        @Override
        public DatasetParameter duplicate() {
            DatasetParameter res = new DatasetParameter(name, multipleChannel, tiling, dataAug.children.toArray(new Parameter[0])  );
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
        @Override
        public Object getPythonConfiguration() {
            JSONObject res = new JSONObject();
            if (path.selectedFiles.length>0) res.put("path", path.selectedFiles[0]);
            res.put("channel_name", multipleChannel ? channels.toJSONEntry() : channel.toJSONEntry());
            res.put("keyword", keyword.toJSONEntry());
            res.put("concat_proportion", concatProp.toJSONEntry());
            switch (tileNumberMode.getValue()) {
                case AUTOMATIC:
                default:
                    res.put("tile_overlap_fraction", tileOverlapFraction.getDoubleValue());
                    break;
                case CONSTANT:
                    res.put("n_tiles", nTiles.getIntValue());
            }
            JSONObject dataAug = new JSONObject();
            res.put("data_augmentation", dataAug);
            for (Parameter p : this.dataAug.children) {
                if (p instanceof PythonConfiguration) dataAug.put(((PythonConfiguration)p).getPythonConfigurationKey(), ((PythonConfiguration)p).getPythonConfiguration());
                else dataAug.put(toSnakeCase(p.getName()), p.toJSONEntry());
            }
            return res;
        }
        @Override
        public String getPythonConfigurationKey() {return null;}
        @Override
        public String toString() {
            return getName()+ (path.getFirstSelectedFilePath()==null ? "" : ": " + new File(path.getFirstSelectedFilePath()).getName());
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
