package bacmman.plugins.plugins.docker_dl_trainer;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SelectionOperations;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.DLModelMetadata;
import bacmman.plugins.DockerDLTrainer;
import bacmman.plugins.Hint;
import bacmman.py_dataset.ExtractDatasetUtil;
import bacmman.ui.PropertyUtils;
import bacmman.utils.ArrayUtil;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DiSTNet2DSegTraining implements DockerDLTrainer, Hint {
    Parameter[] trainingParameters = new Parameter[]{TrainingConfigurationParameter.getPatienceParameter(40), TrainingConfigurationParameter.getMinLearningRateParameter(1e-6)};

    Parameter[] datasetParameters = new Parameter[0];
    BoundedNumberParameter frameSubSampling = new BoundedNumberParameter("Frame Subsampling", 0, 15, 1, null).setHint("Random time Subsampling of dataset to increase input diversity. Only used in timelapse mode<br>Extent E of the frame window <em>seen</em> by the neural network is drawn randomly in interval [0, FRAME_SUBSAMLPING). final seen frame window is W = 2 x E + 1. If W is greater than the input window of the neural network, gaps between frame are introduced, except between central frame and first adjacent frame");
    SimpleListParameter<IlluminationParameter> illumAugList = new SimpleListParameter<>("Illumination Transform", new IlluminationParameter("Illumination Transform", true))
        .setNewInstanceNameFunction( (l, idx) -> {
            SimpleListParameter<TextParameter> channelNames = ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, l.getParent(), cn -> cn.getName().equals("Channel Names"));
            if ( channelNames.getChildCount() > idx ) return channelNames.getChildAt(idx).getValue();
            else return "Channel #"+idx;
        } ).addValidationFunction( l -> {
            SimpleListParameter<TextParameter> channelNames = ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, l.getParent(), cn -> cn.getName().equals("Channel Names"));
            return l.getChildCount() == channelNames.getChildCount();
        } );
    Parameter[] dataAugmentationParameters = new Parameter[]{frameSubSampling, new ElasticDeformParameter("Elastic Deform"), illumAugList };
    ArchitectureParameter arch = new ArchitectureParameter("Architecture");
    BooleanParameter excludeEmpty = new BooleanParameter("Exclude Empty Frames", true).setHint("When a timelapse dataset has sparesly annotated frames, only consider frames that contains annotations");
    Parameter[] otherDatasetParameters = new Parameter[]{excludeEmpty, new TrainingConfigurationParameter.InputSizerParameter("Input Images", TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.CONSTANT_SIZE)};
    DiSTNet2DTraining.SegmentationParameters segmentationParam = new DiSTNet2DTraining.SegmentationParameters("Segmentation", DiSTNet2DTraining.SegmentationParameters.CENTER_MODE.MEDOID, DiSTNet2DTraining.SegmentationParameters.CENTER_MODE.GEOMETRICAL);
    Parameter[] otherParameters = new Parameter[]{segmentationParam, arch};
    Parameter[] testParameters = new Parameter[]{new BoundedNumberParameter("Frame Subsampling", 0, 1, 1, null)};
    TrainingConfigurationParameter configuration = new TrainingConfigurationParameter("Configuration", true, true, trainingParameters, datasetParameters, dataAugmentationParameters, otherDatasetParameters, otherParameters, testParameters)
            .setBatchSize(4).setConcatBatchSize(2).setEpochNumber(500).setStepNumber(200)
            .setDockerImageRequirements(getDockerImageName(), null, null, null);

    // dataset extraction
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class", -1, false, false)
        .addListener(poc -> {
            ParameterUtils.getParameterFromSiblings(CategoryParameter.class, poc, null).setSelectionObjectClass(poc.getSelectedClassIdx());
            ChannelImageParameter chan = ParameterUtils.getParameterFromSiblings(ChannelImageParameter.class, poc, null);
            if (chan.getSelectedIndex() < 0 && ParameterUtils.getExperiment(chan) != null) chan.setChannelFromObjectClass(poc.getSelectedClassIdx());
        }).setHint("Select object class of reference segmented objects");
    ChannelImageParameter channel = new ChannelImageParameter("Channel Image").setHint("Input raw image channel");
    ExtractZAxisParameter extractZAxisParameter = new ExtractZAxisParameter(new ExtractZAxisParameter.ExtractZAxis[]{ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE, ExtractZAxisParameter.ExtractZAxis.SINGLE_PLANE}, ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE);

    SimpleListParameter<DiSTNet2DTraining.OtherObjectClassParameter> otherOCList = new SimpleListParameter<>("Other Channels", new DiSTNet2DTraining.OtherObjectClassParameter())
            .addValidationFunctionToChildren(g -> {
                String k = g.key.getValue();
                if (k.equals("raw") || k.equals("regionLabels") || k.equals("/raw") || k.equals("/regionLabels")) return false;
                SimpleListParameter<DiSTNet2DTraining.OtherObjectClassParameter> parent = (SimpleListParameter<DiSTNet2DTraining.OtherObjectClassParameter>)g.getParent();
                return parent.getChildren().stream().filter(gg -> g != gg).map(gg -> gg.key.getValue()).noneMatch(kk -> kk.equals(k));
            }).setHint("Other object class label or raw input image to be extracted");

    CategoryParameter extractCategory = new CategoryParameter(false);

    ObjectClassParameter parentObjectClass = new ObjectClassParameter("Parent Object Class", -1, true, false)
        .setNoSelectionString("Viewfield")
        .setHint("Select object class that will define the frame (usually parent object class)");

    EnumChoiceParameter<SELECTION_MODE> selMode = new EnumChoiceParameter<>("Selection", SELECTION_MODE.values(), SELECTION_MODE.NEW).setHint("Which subset of the current dataset should be included into the extracted dataset. <br/>EXISTING: choose previously defined selection. NEW: will generate a selection<br/>In either case, all objets of the resulting selection must have identical spatial dimensions. <br>To include subsets that do not have same spatial dimension make one dataset per spatial dimension, and list them in the training configuration (DatasetList parameter)");
    PositionParameter extractPos = new PositionParameter("Position", true, true).setHint("Position to include in extracted dataset. If no position is selected, all position will be included.");
    SelectionParameter extractSel = new SelectionParameter("Selection", false, true);
    ArrayNumberParameter extractDims = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0,0}, null).setHint("Images will be rescaled to these dimensions. Set 0 for no rescaling");
    BooleanParameter extendToDims = new BooleanParameter("Extend", false)
            .addValidationFunction(b -> {
                if (b.getSelected()) {
                    if (IntStream.of(extractDims.getArrayInt()).anyMatch(i -> i==0)) return false; // dimension cannot be null
                    if (selMode.getSelectedEnum().equals(SELECTION_MODE.NEW)) return parentObjectClass.getSelectedIndex()>=0; // also check that selection are not from root
                    else return extractSel.getSelectedSelections().noneMatch(s -> s.getObjectClassIdx()<0);
                }
                else return true;
            }).setHint("If true, extracted images are extended to target dimensions otherwise it is resampled if necessary. <br>In extend mode, dimensions cannot be null, and selection cannot be of viewfield objects (root)");
    IntegerParameter spatialDownsampling = new IntegerParameter("Spatial downsampling factor", 1).setLowerBound(1).setHint("Divides the size of the image by this factor");

    ConditionalParameter<SELECTION_MODE> selModeCond = new ConditionalParameter<>(selMode)
            .setActionParameters(SELECTION_MODE.EXISTING, extractSel)
            .setActionParameters(SELECTION_MODE.NEW, parentObjectClass, extractPos);
    SelectionParameter selectionFilter = new SelectionParameter("Subset", true, false).setHint("Optional: choose a selection to subset objects (objects not contained in the selection will be ignored)");


    GroupParameter datasetExtractionParameters = new GroupParameter("Dataset Extraction Parameters", objectClass, channel, extractZAxisParameter, otherOCList, extractCategory, extractDims, extendToDims, selModeCond, selectionFilter, spatialDownsampling);


    @Override
    public String getHintText() {
        return "Training for segmentation part of Distnet2D<br/> If you use this method please cite: Ollion, J., Maliet, M., Giuglaris, C., Vacher, E., & Deforet, M. (2023). DistNet2D: Leveraging long-range temporal information for efficient segmentation and tracking. PRXLife";
    }
    @Override
    public TrainingConfigurationParameter getConfiguration() {
        return configuration;
    }

    @Override
    public Parameter[] getDatasetExtractionParameters() {
        return datasetExtractionParameters.getChildren().toArray(new Parameter[0]);
    }

    @Override
    public Task getDatasetExtractionTask(MasterDAO mDAO, String outputFile, List<String> selectionContainer) {
        int compression = PropertyUtils.get("extract_DS_compression", 0);
        int selOC = objectClass.getSelectedClassIdx();
        List<String> selections;
        switch (selMode.getSelectedEnum()) {
            case NEW:
            default: {
                int parentOC = parentObjectClass.getSelectedClassIdx();
                String[] selectedPositions = extractPos.getSelectedPosition(true);
                Selection s = SelectionOperations.createSelection("DiSTNet2DSeg_dataset", Arrays.asList(selectedPositions), parentOC, mDAO);
                // remove empty tracks
                s.getAllElementsAsStream()
                        .collect(Collectors.groupingBy(SegmentedObject::getTrackHead)).values()
                        .stream().filter(l -> l.stream().noneMatch(o -> o.getChildren(selOC).findAny().isPresent()))
                        .forEach(s::removeElements);
                mDAO.getSelectionDAO().store(s);
                selections = Collections.singletonList(s.getName());
                break;
            }
            case EXISTING: {
                selections = Arrays.asList(extractSel.getSelectedItems());
                break;
            }
        }
        if (selectionContainer != null) selectionContainer.addAll(selections);
        List<ExtractDatasetUtil.ExtractOCParameters> labelsAndChannels = otherOCList.getActivatedChildren().stream().map(g -> new ExtractDatasetUtil.ExtractOCParameters( g.getSelectedChannelOrObjectClass(), g.isLabel(), g.key.getValue(), g.getExtractZAxis())).collect(Collectors.toList());
        labelsAndChannels.add(0, new ExtractDatasetUtil.ExtractOCParameters(channel.getSelectedIndex(), false, "raw", extractZAxisParameter.getConfig()));
        return ExtractDatasetUtil.getDiSTNetSegDatasetTask(mDAO, selOC, labelsAndChannels, extractCategory.getCategorySelections(), extractCategory.addDefaultCategory(), ArrayUtil.reverse(extractDims.getArrayInt(), true), extendToDims.getSelected(), selections, selectionFilter.getSelectedItem(), outputFile, spatialDownsampling.getIntValue(), compression);
    }

    public String getDockerImageName() {
        return "distnet2d_seg";
    }

    @Override
    public DLModelMetadata getDLModelMetadata(String workingDirectory) {
        ArchitectureParameter archP = (ArchitectureParameter)getConfiguration().getOtherParameters()[1];
        TrainingConfigurationParameter.DatasetParameter p;
        if (getConfiguration().getDatasetList().getChildCount() > 0) {
            if (getConfiguration().getDatasetList().getActivatedChildCount() > 0) p = getConfiguration().getDatasetList().getActivatedChildren().get(0);
            else p = getConfiguration().getDatasetList().getChildAt(0);
        } else p = null;
        DLModelMetadata.DLModelInputParameter[] inputs;
        if (p == null) { // assuming one single input
            inputs = new DLModelMetadata.DLModelInputParameter[1];
            inputs[0] = new DLModelMetadata.DLModelInputParameter("Input").setChannelNumber(1);
        } else {
            int nchan = p.getChannelNumber();
            int nlab = p.getLabelNumber();
            inputs = new DLModelMetadata.DLModelInputParameter[nchan + nlab * 2];
            for (int i = 0; i<nchan; ++i) {
                inputs[i] = new DLModelMetadata.DLModelInputParameter("Input"+i)
                    .setChannelNumber(1)
                    .setScaling(p.getScalingParameter(i).getScaler());
            }
            for (int i = 0; i<nlab * 2; ++i) {
                inputs[i+nchan] = new DLModelMetadata.DLModelInputParameter("Input"+(i+nchan)+ (i%2==0 ? "_EDM" : "_CDM" ) )
                    .setChannelNumber(1);
            }
        }
        for (int i = 0;i<inputs.length; ++i) inputs[i].setShape();
        DLModelMetadata.DLModelOutputParameter[] outputs = new DLModelMetadata.DLModelOutputParameter[archP.category() ? 3 : 2];
        outputs[0] = new DLModelMetadata.DLModelOutputParameter("Output0_EDM");
        outputs[1] = new DLModelMetadata.DLModelOutputParameter("Output1_CDM");
        if (archP.category()) outputs[2] = new DLModelMetadata.DLModelOutputParameter("Output2_Category");
        return new DLModelMetadata()
                .setInputs(inputs)
                .setOutputs(outputs)
                .setContraction(archP.getContraction());
    }

    @Override
    public Parameter[] getParameters() {
        return getConfiguration().getChildParameters();
    }
    enum ARCH_TYPE {ENC_DEC}
    public static class ArchitectureParameter extends ConditionalParameterAbstract<ARCH_TYPE, ArchitectureParameter> implements PythonConfiguration {
        BoundedNumberParameter filters = new BoundedNumberParameter("Feature Filters", 0, 128, 64, 1024).setHint("Number of filters at the feature level");
        BoundedNumberParameter downsamplingNumber = new BoundedNumberParameter("Downsampling Number", 0, 2, 2, 3).addListener(p-> {
            SimpleListParameter list = ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, p, null);
            list.setChildrenNumber(p.getIntValue()-1);
        });
        SimpleListParameter<BooleanParameter> skip = new SimpleListParameter<>("Skip Connections", new BooleanParameter("Skip Connection", true))
                .setChildrenNumber(downsamplingNumber.getIntValue()-1)
                .setNewInstanceNameFunction((l, i) -> "Level: "+(i+1)).setAllowDeactivable(false).setAllowMoveChildren(false).setAllowModifications(false)
                .setHint("Define which level includes a skip connection. First level cannot have a skip connection");
        IntegerParameter categoryNumber = new IntegerParameter("Category Number", 0).setLowerBound(0).setHint("If greater than 1, a category will be predicted. <br/>Each dataset must contain an array named <em>category</em> that maps label to a category");

        protected ArchitectureParameter(String name) {
            super(new EnumChoiceParameter<>(name, ARCH_TYPE.values(), ARCH_TYPE.ENC_DEC));
            setActionParameters(ARCH_TYPE.ENC_DEC, downsamplingNumber, filters, skip, categoryNumber);
        }

        public int getContraction() {
            switch (getActionValue()) {
                case ENC_DEC:
                default:
                    return (int)Math.pow(2, downsamplingNumber.getIntValue());
            }
        }

        public boolean category() {
            return categoryNumber.getIntValue() > 1;
        }

        @Override
        public ArchitectureParameter duplicate() {
            ArchitectureParameter res = new ArchitectureParameter(name);
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }

        @Override
        public String getPythonConfigurationKey() {
            return "model_architecture";
        }
        @Override
        public JSONObject getPythonConfiguration() {
            JSONObject res = new JSONObject();
            res.put("architecture_type", getActionValue().toString());
            JSONArray sc = new JSONArray();
            for (int i = 0; i<skip.getChildCount(); ++i) {
                if (skip.getChildAt(i).getSelected()) sc.add(i+1);
            }
            if (categoryNumber.getIntValue() > 1) res.put("category_number", categoryNumber.getIntValue());
            res.put("skip_connections", sc);
            switch (getActionValue()) {
                case ENC_DEC:
                default: {
                    res.put("n_downsampling", downsamplingNumber.getIntValue());
                    res.put("filters", filters.getIntValue());
                    break;
                }
            }
            return res;
        }
    }
}
