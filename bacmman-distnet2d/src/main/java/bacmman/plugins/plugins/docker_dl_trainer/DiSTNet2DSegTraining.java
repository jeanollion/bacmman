package bacmman.plugins.plugins.docker_dl_trainer;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.DLModelMetadata;
import bacmman.plugins.DockerDLTrainer;
import bacmman.plugins.Hint;
import bacmman.py_dataset.ExtractDatasetUtil;
import bacmman.ui.PropertyUtils;
import bacmman.utils.Triplet;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DiSTNet2DSegTraining implements DockerDLTrainer, Hint {
    Parameter[] trainingParameters = new Parameter[]{TrainingConfigurationParameter.getPatienceParameter(40), TrainingConfigurationParameter.getMinLearningRateParameter(1e-6)};
    Parameter[] datasetParameters = new Parameter[0];
    BoundedNumberParameter frameSubSampling = new BoundedNumberParameter("Frame Subsampling", 0, 15, 1, null).setHint("Random time Subsampling of dataset to increase input diversity. Only used in timelapse mode<br>Extent E of the frame window <em>seen</em> by the neural network is drawn randomly in interval [0, FRAME_SUBSAMLPING). final seen frame window is W = 2 x E + 1. If W is greater than the input window of the neural network, gaps between frame are introduced, except between central frame and first adjacent frame");
    Parameter[] dataAugmentationParameters = new Parameter[]{frameSubSampling, new ElasticDeformParameter("Elastic Deform"), new IlluminationParameter("Illumination Transform")};
    ArchitectureParameter arch = new ArchitectureParameter("Architecture");
    BooleanParameter excludeEmpty = new BooleanParameter("Exclude Empty Frames", true).setHint("When a timelapse dataset has sparesly annotated frames, only consider frames that contains annotations");
    enum CENTER_MODE {MEDOID, GEOMETRICAL}
    EnumChoiceParameter<CENTER_MODE> centerMode = new EnumChoiceParameter<>("Center Mode", CENTER_MODE.values(), CENTER_MODE.MEDOID);
    EnumChoiceParameter<DiSTNet2DTraining.CENTER_DISTANCE_MODE> centerDistanceMode = new EnumChoiceParameter<>("Center Distance Mode", DiSTNet2DTraining.CENTER_DISTANCE_MODE.values(), DiSTNet2DTraining.CENTER_DISTANCE_MODE.GEODESIC).setHint("Defines the predicted DCM (distance to center) map: geodesic is the geodesic distance inside the objects, recommended for regular to big size objects especially with non convex shapes. <br/>EUCLIDEAN is the Euclidean distance to center, thus do not take shape into account but can be predicted outside the objects, thus recommended for small objects such as spots");
    GroupParameter datasetFeatures = new GroupParameter("Dataset Features", centerMode, centerDistanceMode);
    Parameter[] otherDatasetParameters = new Parameter[]{excludeEmpty, new TrainingConfigurationParameter.InputSizerParameter("Input Images", TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.CONSTANT_SIZE), datasetFeatures};
    Parameter[] otherParameters = new Parameter[]{arch};
    Parameter[] testParameters = new Parameter[]{new BoundedNumberParameter("Frame Subsampling", 0, 1, 1, null)};
    TrainingConfigurationParameter configuration = new TrainingConfigurationParameter("Configuration", true, true, trainingParameters, datasetParameters, dataAugmentationParameters, otherDatasetParameters, otherParameters, testParameters)
            .setBatchSize(4).setConcatBatchSize(2).setEpochNumber(500).setStepNumber(200)
            .setDockerImageRequirements(getDockerImageName(), null, null, null);

    // dataset extraction
    enum SELECTION_MODE {SPARSE_FRAMES}
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class", -1, false, false)
        .setHint("Select object class of reference segmented objects");
    ChannelImageParameter channel = new ChannelImageParameter("Channel Image").setHint("Input raw image channel");
    static class OtherObjectClassParameter extends GroupParameterAbstract<OtherObjectClassParameter> {
        ObjectClassParameter otherOC = new ObjectClassParameter("Object Class", -1, false, false);
        BooleanParameter otherOCLabel = new BooleanParameter("Label", false).setHint("Extract label image if true otherwise raw image");
        TextParameter otherOCKey = new TextParameter("Name", "", false, false);

        public OtherObjectClassParameter(String name) {
            super(name);
            this.children = new ArrayList<>();
            this.children.add(otherOC);
            this.children.add(otherOCLabel);
            this.children.add(otherOCKey);
            initChildList();
        }

        @Override
        public OtherObjectClassParameter duplicate() {
            OtherObjectClassParameter res = new OtherObjectClassParameter(name);
            ParameterUtils.setContent(res.children, children);
            transferStateArguments(this, res);
            return res;
        }
    }
    SimpleListParameter<OtherObjectClassParameter> otherOCList = new SimpleListParameter<>("Other Channels", new OtherObjectClassParameter("Channel"))
            .addValidationFunctionToChildren(g -> {
                String k = g.otherOCKey.getValue();
                SimpleListParameter<OtherObjectClassParameter> parent = (SimpleListParameter<OtherObjectClassParameter>)g.getParent();
                return parent.getChildren().stream().filter(gg -> g != gg).map(gg -> gg.otherOCKey.getValue()).noneMatch(kk -> kk.equals(k));
            }).setHint("Other object class label or raw input image to be extracted");

    ObjectClassParameter parentObjectClass = new ObjectClassParameter("Parent Object Class", -1, true, false)
        .setNoSelectionString("Viewfield")
        .addListener(poc -> {
            logger.debug("modified poc: parent: {}", poc.getParent());
            logger.debug("modified poc: siblings: {}", ((ContainerParameter)poc.getParent()).getChildren());
            ConditionalParameter<SELECTION_MODE> selModeCond = ParameterUtils.getParameterFromSiblings(ConditionalParameter.class, poc, p->p.getName().equals("Selection"));
            logger.debug("selMode Cond found ? {}", selModeCond!=null);
            ((SelectionParameter)selModeCond.getParameters(SELECTION_MODE.SPARSE_FRAMES).get(0)).setSelectionObjectClass(poc.getSelectedClassIdx());
        })
        .setHint("Select object class that will define the frame (usually parent object class)");

    EnumChoiceParameter<SELECTION_MODE> selMode = new EnumChoiceParameter<>("Selection", SELECTION_MODE.values(), SELECTION_MODE.SPARSE_FRAMES).setHint("Which subset of the current dataset should be included into the extracted dataset. SPARSE_FRAMES: choose a previously defined selection of frames that are entirely annotated. Those frames do not need to be contiguous.");
    SelectionParameter extractSel = new SelectionParameter("Selection", false, false).setSelectionObjectClass(parentObjectClass.getSelectedClassIdx());
    EnumChoiceParameter<Task.ExtractZAxis> extractZ = new EnumChoiceParameter<>("Extract Z", Task.ExtractZAxis.values(), Task.ExtractZAxis.IMAGE3D);
    BoundedNumberParameter extractZPlane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    IntegerParameter frameInteval = new IntegerParameter("Frame Interval", 0).setHint("Frames to include before and after frames located in the selected selection. If any segmented object is present in the newly included frames but not in the selection, it will be ignored");
    IntegerParameter spatialDownsampling = new IntegerParameter("Spatial downsampling factor", 1).setLowerBound(1).setHint("Divides the size of the image by this factor");

    ConditionalParameter<Task.ExtractZAxis> extractZCond = new ConditionalParameter<>(extractZ)
            .setActionParameters(Task.ExtractZAxis.SINGLE_PLANE, extractZPlane)
            .setHint("Choose how to handle Z-axis: <ul><li>Image3D: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>SINGLE_PLANE: a single plane is extracted, defined in <em>Plane Index</em> parameter</li><li>MIDDLE_PLANE: the middle plane is extracted</li><li>BATCH: tensor are treated as 2D images </li></ul>");;
    ConditionalParameter<SELECTION_MODE> selModeCond = new ConditionalParameter<>(selMode)
            .setActionParameters(SELECTION_MODE.SPARSE_FRAMES, extractSel, frameInteval);
    GroupParameter datasetExtractionParameters = new GroupParameter("Dataset Extraction Parameters", objectClass, parentObjectClass, channel, extractZCond, otherOCList, selModeCond, spatialDownsampling);


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
        int parentOC = parentObjectClass.getSelectedClassIdx();
        int frameInterval = this.frameInteval.getIntValue();
        String selection, selectionFilter;
        switch (selMode.getSelectedEnum()) {
            case SPARSE_FRAMES:
            default: {
                Selection sel = mDAO.getSelectionDAO().getOrCreate(extractSel.getSelectedItem(), false);
                Selection selFilter = mDAO.getSelectionDAO().getOrCreate("DiSTNet2D_dataset_objects", true); // create a new selection that contains objects in this selected = selection filter
                for (String position : sel.getAllPositions()) {
                    selFilter.addElements(sel.getElements(position).stream().flatMap(p->p.getChildren(selOC)).collect(Collectors.toList()));
                }
                mDAO.getSelectionDAO().store(selFilter);
                selectionFilter = selFilter.getName();
                if (frameInterval>0) { // create new selection with extended frames
                    Selection newSel = mDAO.getSelectionDAO().getOrCreate("DiSTNet2D_dataset_frames", true);
                    sel.getAllElementsAsStream().forEach(e -> {
                        for (int f = e.getFrame() - frameInterval; f<=e.getFrame() + frameInterval; ++f) {
                            SegmentedObject n = e.getAtFrame(f, false);
                            if (n!=null) newSel.addElement(n);
                        }
                    });
                    mDAO.getSelectionDAO().store(newSel);
                    selection = newSel.getName();
                } else selection = sel.getName();
                break;
            }
        }
        if (selectionContainer != null) selectionContainer.add(selection);
        List<Triplet<Integer, Boolean, String>> otherOC = otherOCList.getActivatedChildren().stream().map(g -> new Triplet<>( g.otherOC.getSelectedClassIdx(), g.otherOCLabel.getSelected(), g.otherOCKey.getValue() )).collect(Collectors.toList());
        return ExtractDatasetUtil.getDiSTNetSegDatasetTask(mDAO, selOC, channel.getSelectedClassIdx(), extractZ.getSelectedEnum(), extractZPlane.getIntValue(), selection, selectionFilter, otherOC, outputFile, spatialDownsampling.getIntValue(), compression);
    }

    public String getDockerImageName() {
        return "distnet2d_seg";
    }

    @Override
    public DLModelMetadata getDLModelMetadata(String workingDirectory) {
        ArchitectureParameter archP = (ArchitectureParameter)getConfiguration().getOtherParameters()[0];
        boolean timelapse = archP.timelapse.getSelected();
        int frameWindow = archP.frameWindow.getIntValue();
        DLModelMetadata.DLModelInputParameter input = new DLModelMetadata.DLModelInputParameter("Input")
            .setChannelNumber(timelapse ? 2 * frameWindow + 1 : archP.channelNumber.getIntValue())
            .setScaling(configuration.getDatasetList().getChildAt(0).getScalingParameter(0).getScaler());
        input.setShape();
        DLModelMetadata.DLModelOutputParameter[] outputs = new DLModelMetadata.DLModelOutputParameter[2];
        outputs[0] = new DLModelMetadata.DLModelOutputParameter("Output0_EDM");
        outputs[1] = new DLModelMetadata.DLModelOutputParameter("Output1_GDCM");
        return new DLModelMetadata()
                .setInputs(input)
                .setOutputs(outputs)
                .setContraction(archP.getContraction());
    }

    @Override
    public Parameter[] getParameters() {
        return getConfiguration().getChildParameters();
    }
    enum ARCH_TYPE {ENC_DEC}
    public static class ArchitectureParameter extends ConditionalParameterAbstract<ARCH_TYPE, ArchitectureParameter> implements PythonConfiguration {
        BooleanParameter timelapse = new BooleanParameter("Timelapse", false).setHint("Whether raw input channel is a timelapse sequence. In that case input image will be concatenated with previous and next frames, as defined in the parameter <em>Frame Window</em> ");
        IntegerParameter frameWindow = new IntegerParameter("Frame Window", 3).setHint("Number of frames before and after current frame");
        IntegerParameter channelNumber = new IntegerParameter("Channel Number", 1).setHint("Number of input channels");
        BooleanParameter sharedEncoder = new BooleanParameter("Shared Encoder", true).setHint("For timelapse dataset, whether each input frames are encoded independently by a shared encoder or processed as a multichannel batch by the encoder");
        ConditionalParameter<Boolean> timelapseCond = new ConditionalParameter<>(timelapse)
                .setActionParameters(true, frameWindow, sharedEncoder)
                .setActionParameters(false, channelNumber);
        BoundedNumberParameter filters = new BoundedNumberParameter("Feature Filters", 0, 128, 64, 1024).setHint("Number of filters at the feature level");
        BoundedNumberParameter downsamplingNumber = new BoundedNumberParameter("Downsampling Number", 0, 2, 2, 3).addListener(p-> {
            SimpleListParameter list = ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, p, null);
            list.setChildrenNumber(p.getIntValue()-1);
        });
        SimpleListParameter<BooleanParameter> skip = new SimpleListParameter<>("Skip Connections", new BooleanParameter("Skip Connection", true))
                .setChildrenNumber(downsamplingNumber.getIntValue()-1)
                .setNewInstanceNameFunction((l, i) -> "Level: "+(i+1)).setAllowDeactivable(false).setAllowMoveChildren(false).setAllowModifications(false)
                .setHint("Define which level includes a skip connection. First level cannot have a skip connection");
        protected ArchitectureParameter(String name) {
            super(new EnumChoiceParameter<>(name, ARCH_TYPE.values(), ARCH_TYPE.ENC_DEC));
            setActionParameters(ARCH_TYPE.ENC_DEC, downsamplingNumber, filters, skip, sharedEncoder, timelapseCond);
        }
        public int getContraction() {
            switch (getActionValue()) {
                case ENC_DEC:
                default:
                    return (int)Math.pow(2, downsamplingNumber.getIntValue());
            }
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
            res.put("skip_connections", sc);
            res.put("shared_encoder", sharedEncoder.toJSONEntry());
            switch (getActionValue()) {
                case ENC_DEC:
                default: {
                    res.put("n_downsampling", downsamplingNumber.getIntValue());
                    res.put("filters", filters.getIntValue());
                    break;
                }
            }
            res.put("timelapse", timelapse.toJSONEntry());
            res.put("channel_number", timelapse.getSelected() ? 2 * frameWindow.getIntValue() + 1 : channelNumber.getIntValue());
            return res;
        }
    }
}
