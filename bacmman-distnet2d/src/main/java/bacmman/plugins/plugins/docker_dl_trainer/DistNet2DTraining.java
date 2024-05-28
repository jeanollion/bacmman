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
import bacmman.utils.ArrayUtil;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DistNet2DTraining implements DockerDLTrainer, DockerDLTrainer.ComputeMetrics, Hint {
    Parameter[] trainingParameters = new Parameter[]{TrainingConfigurationParameter.getPatienceParameter(80), TrainingConfigurationParameter.getMinLearningRateParameter(1e-6), TrainingConfigurationParameter.getStartEpochParameter(), new HardSampleMiningParameter("Hard Sample Mining", new FloatParameter("Center Scale", 4))};
    Parameter[] datasetParameters = new Parameter[0];
    BoundedNumberParameter frameSubSampling = new BoundedNumberParameter("Frame Subsampling", 0, 15, 1, null).setHint("Time Subsampling of dataset to increase displacement range<br>Extent E of the frame window <em>seen</em> by the neural network is drawn randomly in interval [0, FRAME_SUBSAMLPING). if neural network inputs previous and next frames, final seen frame window is W = 2 x E + 1 otherwise W = E + 1. If W is greater than the input window of the neural network, gaps between frame are introduced, except between central frame and first adjacent frame");
    BoundedNumberParameter eraseEdgeCellSize = new BoundedNumberParameter("Erase Edge Cell Size", 0, 50, 0, null).setHint("Size (in pixels) of cells touching edges that should be erased");
    BoundedNumberParameter staticProba = new BoundedNumberParameter("Static Probability", 5, 0.01, 0, 1).setHint("Probability that all frames seen by the neural network are identical (simulates no displacement)");

    Parameter[] dataAugmentationParameters = new Parameter[]{frameSubSampling, eraseEdgeCellSize, staticProba, new AffineTransformParameter("Affine Transform"), new ElasticDeformParameter("Elastic Deform"), new Swim1DParameter("Swim 1D"), new IlluminationParameter("Illumination Transform", true)};
    ArchitectureParameter arch = new ArchitectureParameter("Architecture");
    enum CENTER_MODE {MEDOID, GEOMETRICAL, SKELETON, EDM_MAX}
    EnumChoiceParameter<CENTER_MODE> center = new EnumChoiceParameter<>("Center Mode", CENTER_MODE.values(), CENTER_MODE.MEDOID);
    GroupParameter datasetFeatures = new GroupParameter("Dataset Features", center).setHint("MEDOID: ");
    Parameter[] otherDatasetParameters = new Parameter[]{new TrainingConfigurationParameter.InputSizerParameter("Input Images", TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.CONSTANT_SIZE), datasetFeatures};
    Parameter[] otherParameters = new Parameter[]{arch};
    Parameter[] testParameters = new Parameter[]{new BoundedNumberParameter("Frame Subsampling", 0, 1, 1, null)};
    TrainingConfigurationParameter configuration = new TrainingConfigurationParameter("Configuration", false, trainingParameters, datasetParameters, dataAugmentationParameters, otherDatasetParameters, otherParameters, testParameters)
            .setBatchSize(4).setConcatBatchSize(2).setEpochNumber(1000).setStepNumber(200)
            .setDockerImageRequirements(getDockerImageName(), null, null);

    // dataset extraction
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class", -1, false, false)
        .addListener(poc -> ParameterUtils.getParameterFromSiblings(SelectionParameter.class, poc, p->p.getName().equals("Subset")).setSelectionObjectClass(poc.getSelectedClassIdx()))
        .setHint("Select object class of reference segmented objects");
    ObjectClassParameter parentObjectClass = new ObjectClassParameter("Parent Object Class", -1, true, false)
        .setNoSelectionString("Viewfield")
        .setHint("Select object class that will define the frame (usually parent object class)");
    EnumChoiceParameter<SELECTION_MODE> selMode = new EnumChoiceParameter<>("Selection", SELECTION_MODE.values(), SELECTION_MODE.NEW).setHint("Which subset of the current dataset should be included into the extracted dataset. EXISTING: choose previously defined selection. NEW: will generate a selection");
    PositionParameter extractPos = new PositionParameter("Position", true, true).setHint("Position to include in extracted dataset. If no position is selected, all position will be included.");
    SelectionParameter extractSel = new SelectionParameter("Selection", false, true);
    ArrayNumberParameter extractDims = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0,0}, null).setHint("Images will be rescaled to these dimensions. Set 0 for no rescaling");
    IntegerParameter spatialDownsampling = new IntegerParameter("Spatial downsampling factor", 1).setLowerBound(1).setHint("Divides the size of the image by this factor");

    IntegerParameter subsamplingFactor = new IntegerParameter("Frame subsampling factor", 1).setLowerBound(1).setHint("Extract N time subsampled versions of the dataset. if this parameter is 2, this will extract N € [1, 2] versions of the dataset with one fame out of two");
    IntegerParameter subsamplingNumber = new IntegerParameter("Frame subsampling number", 1).setLowerBound(1)
            .addValidationFunction(n -> {
                IntegerParameter sf = ParameterUtils.getParameterFromSiblings(IntegerParameter.class, n, p -> p.getName().equals("Frame subsampling factor"));
                return n.getIntValue() <= sf.getIntValue();
            })
            .setHint("Number of subsampled version extracted.");

    ConditionalParameter<SELECTION_MODE> selModeCond = new ConditionalParameter<>(selMode)
            .setActionParameters(SELECTION_MODE.EXISTING, extractSel)
            .setActionParameters(SELECTION_MODE.NEW, parentObjectClass, extractPos);
    SelectionParameter selectionFilter = new SelectionParameter("Subset", true, false).setHint("Optional: choose a selection to subset objects (objects not contained in the selection will be ignored)");

    // store in a group so that parameters have same parent -> needed because of listener
    GroupParameter extractionParameters = new GroupParameter("ExtractionParameters", objectClass, extractDims, selModeCond, selectionFilter, spatialDownsampling, subsamplingFactor, subsamplingNumber);


    @Override
    public String getHintText() {
        return "Training for Distnet2D<br/> If you use this method please cite: Ollion, J., Maliet, M., Giuglaris, C., Vacher, E., & Deforet, M. (2023). DistNet2D: Leveraging long-range temporal information for efficient segmentation and tracking. PRXLife";
    }

    @Override
    public TrainingConfigurationParameter getConfiguration() {
        return configuration;
    }

    @Override
    public Parameter[] getDatasetExtractionParameters() {
        return extractionParameters.getChildren().toArray(new Parameter[0]);
    }

    @Override
    public Task getDatasetExtractionTask(MasterDAO mDAO, String outputFile, List<String> selectionContainer) {
        int selOC = objectClass.getSelectedClassIdx();
        List<String> selections;
        switch (selMode.getSelectedEnum()) {
            case NEW:
            default: {
                int parentOC = parentObjectClass.getSelectedClassIdx();
                String[] selectedPositions = extractPos.getSelectedPosition(true);
                Selection s = SelectionOperations.createSelection("DiSTNet2D_dataset", Arrays.asList(selectedPositions), parentOC, mDAO);
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
        return ExtractDatasetUtil.getDiSTNetDatasetTask(mDAO, selOC, ArrayUtil.reverse(extractDims.getArrayInt(), true), selections, selectionFilter.getSelectedItem(), outputFile, spatialDownsampling.getIntValue(), subsamplingFactor.getIntValue(), subsamplingNumber.getIntValue(), 0);

    }

    public String getDockerImageName() {
        return "distnet2d";
    }

    @Override
    public DLModelMetadata getDLModelMetadata() {
        ArchitectureParameter archP = (ArchitectureParameter)getConfiguration().getOtherParameters()[0];
        boolean next = archP.next.getSelected();
        int frameWindow = archP.frameWindow.getIntValue();
        boolean fixedShape = archP.attention.getIntValue()>0 || archP.selfAttention.getIntValue()>0;
        DLModelMetadata.DLModelInputParameter input = new DLModelMetadata.DLModelInputParameter("Input")
            .setChannelNumber(( next ? 2 : 1) * frameWindow + 1)
            .setScaling(configuration.getDatasetList().getChildAt(0).getScalingParameter(0).getScaler());
        if (fixedShape) input.setShape(getConfiguration().getGlobalDatasetParameters().getInputShape());
        else input.setShape();
        DLModelMetadata.DLModelOutputParameter[] outputs = new DLModelMetadata.DLModelOutputParameter[5];
        outputs[0] = new DLModelMetadata.DLModelOutputParameter("Output0_EDM");
        outputs[1] = new DLModelMetadata.DLModelOutputParameter("Output1_GDCM");
        outputs[2] = new DLModelMetadata.DLModelOutputParameter("Output2_dY");
        outputs[3] = new DLModelMetadata.DLModelOutputParameter("Output3_dX");
        outputs[4] = new DLModelMetadata.DLModelOutputParameter("Output4_LM");
        return new DLModelMetadata()
                .setInputs(input)
                .setOutputs(outputs)
                .setContraction(archP.getContraction())
                .addMiscParameters(new BooleanParameter("Next", archP.next.getValue()));
    }

    @Override
    public Parameter[] getParameters() {
        return getConfiguration().getChildParameters();
    }
    enum ARCH_TYPE {BLEND}
    public static class ArchitectureParameter extends ConditionalParameterAbstract<ARCH_TYPE, ArchitectureParameter> implements PythonConfiguration {
        // blend mode
        BoundedNumberParameter filters = new BoundedNumberParameter("Feature Filters", 0, 128, 64, 1024).setHint("Number of filters at the feature level");
        BoundedNumberParameter blendingFilterFactor = new BoundedNumberParameter("Blending Filters Factor", 3, 0.5, 0.1, 1).setHint("Number of filters of blending convolution is this factor x number of feature filters");
        BoundedNumberParameter downsamplingNumber = new BoundedNumberParameter("Downsampling Number", 0, 2, 2, 3);
        IntegerParameter attention = new IntegerParameter("Attention", 0).setLowerBound(0)
                .setLegacyParameter((p,i)->i.setValue(((BooleanParameter)p[0]).getSelected() ? 1 : 0), new BooleanParameter("Attention", false))
                .setHint("Number of heads of the attention layer in the PairBlender module. If 0 no attention layer is included. <br/>If an attention or self-attention layer is included, the input shape is fixed.");
        IntegerParameter selfAttention = new IntegerParameter("Self-Attention", 0).setLowerBound(0)
                .setLegacyParameter((p,i)->i.setValue(((BooleanParameter)p[0]).getSelected() ? 1 : 0), new BooleanParameter("Self-Attention", false))
                .setHint("Include a self-attention layer at the feature layer of the encoder. If 0 no self-attention is included. <br/>If an attention or self-attention layer is included, the input shape is fixed.");
        BooleanParameter next = new BooleanParameter("Next", true).setHint("Input frame window is symmetrical in future and past");
        BoundedNumberParameter frameWindow= new BoundedNumberParameter("Frame Window", 0, 3, 1, null).setHint("Number of input frames. If Next is enabled, total number of input frame is 2 x FRAME_WINDOW + 1, otherwise FRAME_WINDOW + 1");
        protected ArchitectureParameter(String name) {
            super(new EnumChoiceParameter<>(name, ARCH_TYPE.values(), ARCH_TYPE.BLEND));
            setActionParameters(ARCH_TYPE.BLEND, next, frameWindow, downsamplingNumber, filters, blendingFilterFactor, attention, selfAttention);
        }
        public int getContraction() {
            switch (getActionValue()) {
                case BLEND:
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
            res.put("frame_window", frameWindow.toJSONEntry());
            res.put("next", next.toJSONEntry());
            switch (getActionValue()) {
                case BLEND:
                default: {
                    res.put("n_downsampling", downsamplingNumber.getIntValue());
                    res.put("filters", filters.getIntValue());
                    res.put("blending_filter_factor", blendingFilterFactor.getDoubleValue());
                    res.put("attention", attention.getValue());
                    res.put("self_attention", selfAttention.getValue());
                    break;
                }
            }
            return res;
        }
    }
}
