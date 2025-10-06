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

public class DiSTNet2DTraining implements DockerDLTrainer, DockerDLTrainer.ComputeMetrics, DockerDLTrainer.TestPredict, Hint {
    Parameter[] trainingParameters = new Parameter[]{TrainingConfigurationParameter.getPatienceParameter(80), TrainingConfigurationParameter.getMinLearningRateParameter(1e-6), TrainingConfigurationParameter.getStartEpochParameter(), new HardSampleMiningParameter("Hard Sample Mining", new FloatParameter("Center Scale", 4))};
    Parameter[] datasetParameters = new Parameter[0];
    BoundedNumberParameter frameSubSampling = new BoundedNumberParameter("Frame Subsampling", 0, 15, 1, null).setHint("Time Subsampling of dataset to increase displacement range<br>Extent E of the frame window <em>seen</em> by the neural network is drawn randomly in interval [0, FRAME_SUBSAMLPING). if neural network inputs previous and next frames, final seen frame window is W = 2 x E + 1 otherwise W = E + 1. If W is greater than the input window of the neural network, gaps between frame are introduced, except between central frame and first adjacent frame");
    BoundedNumberParameter eraseEdgeCellSize = new BoundedNumberParameter("Erase Edge Cell Size", 0, 50, 0, null).setHint("Size (in pixels) of cells touching edges that should be erased");
    BoundedNumberParameter staticProba = new BoundedNumberParameter("Static Probability", 5, 0.01, 0, 1).setHint("Probability that all frames seen by the neural network are identical (simulates no displacement)");
    SimpleListParameter<IlluminationParameter> illumAugList = new SimpleListParameter<>("Illumination Transform", new IlluminationParameter("Illumination Transform", true))
        .setNewInstanceNameFunction( (l, idx) -> {
            SimpleListParameter<TextParameter> channelNames = ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, l.getParent(), cn -> cn.getName().equals("Channel Names"));
            if ( channelNames.getChildCount() > idx ) return channelNames.getChildAt(idx).getValue();
            else return "Channel #"+idx;
        } ).addValidationFunction( l -> {
            SimpleListParameter<TextParameter> channelNames = ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, l.getParent(), cn -> cn.getName().equals("Channel Names"));
            return l.getChildCount() == channelNames.getChildCount();
        } );
    Parameter[] dataAugmentationParameters = new Parameter[]{frameSubSampling, eraseEdgeCellSize, staticProba, new AffineTransformParameter("Affine Transform"), new ElasticDeformParameter("Elastic Deform"), new Swim1DParameter("Swim 1D"), illumAugList };
    ArchitectureParameter arch = new ArchitectureParameter("Architecture");
    Parameter[] otherDatasetParameters = new Parameter[]{new TrainingConfigurationParameter.InputSizerParameter("Input Images", TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.CONSTANT_SIZE).appendAnchorMaskIdxHint("0 = target object class idx. i &gt; 0 = additional label of index i-1")};

    public static class SegmentationParameters extends GroupParameterAbstract<SegmentationParameters> {
        public enum CENTER_MODE {
            MEDOID("point with minimal distance to other points in the object, ensured to be inside the object. Slowest mode but recommended for non convex shapes"),
            GEOMETRICAL("Faster but assumes convex shapes."), SKELETON("Medoid of the skeleton (faster than MEDOID)"),
            EDM_MAX("Maximal value of the EDM");
            final String hint;
            CENTER_MODE(String hint) {this.hint = hint;}
        }
        public enum CENTER_DISTANCE_MODE {GEODESIC, EUCLIDEAN}
        EnumChoiceParameter<CENTER_MODE> centerMode = new EnumChoiceParameter<>("Center Mode", CENTER_MODE.values(), CENTER_MODE.MEDOID);
        EnumChoiceParameter<CENTER_DISTANCE_MODE> centerDistanceMode = new EnumChoiceParameter<>("Center Distance Mode", CENTER_DISTANCE_MODE.values(), CENTER_DISTANCE_MODE.GEODESIC).setHint("Defines the predicted CDM (distance to center) map: <br>GEODESIC is the geodesic distance inside the objects, recommended for regular to big size objects especially with non convex shapes. <br/>EUCLIDEAN is the Euclidean distance to center, thus do not take shape into account but can be predicted outside the objects, thus recommended for small objects such as spots and for which shape matters less");
        FloatParameter cdmLossRadius = new FloatParameter("CDM Loss Radius", 5).setLowerBound(0).setHint("if greater than zero: center loss is computed in an area around the center defined by this radius. This is useful for small objects such as spots. <br/>If zero, loss is computed within the objects (default)");
        ConditionalParameter<CENTER_DISTANCE_MODE> centerDistanceModeCond = new ConditionalParameter<>(centerDistanceMode).setActionParameters(CENTER_DISTANCE_MODE.EUCLIDEAN, cdmLossRadius);
        BooleanParameter scaleEDM = new BooleanParameter("Scale EDM", false).setHint("If true, for each object EDM is normalized so that maximal value is 1. Recommended for small objects if size do not matter");
        BoundedNumberParameter EDMmaxFreqWeigh = new BoundedNumberParameter("EDM Max Frequency Weight", 5, 0, 0, null).setHint("If greater than zero, weights will be computed to balance foreground/background frequency imbalance. This parameter limits the maximal weight.");
        BooleanParameter EDMderivatives = new BooleanParameter("EDM derivatives", true).setHint("If true, EDM loss is also computed on 1st order EDM derivatives");
        BooleanParameter CDMderivatives = new BooleanParameter("CDM derivatives", true).setHint("If true, CDM loss is also computed on 1st order CDM derivatives");
        BooleanParameter inputLabelCenter = new BooleanParameter("Use Input Label Center").setHint("If true, the centers from a selected input label will be used for CDM target map instead of the center from the target label");
        IntegerParameter inputLabelIdx = new IntegerParameter("Input Label Idx", -1).setLowerBound(0);
        ConditionalParameter<Boolean> inputLabelCenterCond = new ConditionalParameter<>(inputLabelCenter).setActionParameters(true, inputLabelIdx);
        boolean segmentOnly;

        public SegmentationParameters(String name, boolean segmentOnly) {
            super(name);
            StringBuilder hint = new StringBuilder().append("Defines how center is computed. <ul>");
            for (CENTER_MODE mode : CENTER_MODE.values()) hint.append("<li>").append(mode.toString()).append(": ").append(mode.hint).append("</li>");
            hint.append("</ul>");
            centerMode.setHint(hint.toString());
            setChildren(scaleEDM, EDMmaxFreqWeigh, centerMode, centerDistanceModeCond, inputLabelCenterCond, EDMderivatives, CDMderivatives);
            inputLabelIdx.addValidationFunction(i -> {
                SimpleListParameter<TrainingConfigurationParameter.DatasetParameter> dsList = (SimpleListParameter<TrainingConfigurationParameter.DatasetParameter>) ParameterUtils.getFirstParameterFromParents(p -> p.getName().equals("Dataset List"), i, true);
                if (dsList!=null && dsList.getChildCount()>0) {
                    return i.getIntValue() < dsList.getChildAt(0).getLabelNumber();
                } else return true;
            });
            this.segmentOnly = segmentOnly;
        }

        @Override
        public SegmentationParameters duplicate() {
            SegmentationParameters res = new SegmentationParameters(name, segmentOnly);
            res.setContentFrom(this);
            transferStateArguments(this, res);
            return res;
        }

        @Override
        public Object getPythonConfiguration() {
            JSONObject res = new JSONObject();
            res.put(PythonConfiguration.toSnakeCase(scaleEDM.getName()), scaleEDM.toJSONEntry());
            res.put(PythonConfiguration.toSnakeCase(EDMmaxFreqWeigh.getName()), EDMmaxFreqWeigh.toJSONEntry());
            res.put(PythonConfiguration.toSnakeCase(centerMode.getName()), centerMode.toJSONEntry());
            res.put(PythonConfiguration.toSnakeCase(centerDistanceMode.getName()), centerDistanceMode.toJSONEntry());
            if (centerDistanceMode.getSelectedEnum().equals(CENTER_DISTANCE_MODE.EUCLIDEAN)) res.put(PythonConfiguration.toSnakeCase(cdmLossRadius.getName()), cdmLossRadius.toJSONEntry());
            res.put(PythonConfiguration.toSnakeCase(EDMderivatives.getName()), EDMderivatives.toJSONEntry());
            res.put(PythonConfiguration.toSnakeCase(CDMderivatives.getName()), CDMderivatives.toJSONEntry());
            if (inputLabelCenter.getSelected()) res.put("input_label_center_idx", inputLabelIdx.toJSONEntry());
            if (segmentOnly) res.put("segment_only", segmentOnly);
            return res;
        }
    }

    Parameter[] otherParameters = new Parameter[]{new SegmentationParameters("Segmentation", false), arch};
    Parameter[] testParameters = new Parameter[]{new BoundedNumberParameter("Frame Subsampling", 0, 1, 1, null)};
    TrainingConfigurationParameter configuration = new TrainingConfigurationParameter("Configuration", true, true, trainingParameters, datasetParameters, dataAugmentationParameters, otherDatasetParameters, otherParameters, testParameters)
            .setBatchSize(4).setConcatBatchSize(2).setEpochNumber(1000).setStepNumber(200)
            .setDockerImageRequirements(getDockerImageName(), null, null, null);

    // dataset extraction
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class", -1, false, false)
        .addListener(poc -> {
            ParameterUtils.getParameterFromSiblings(SelectionParameter.class, poc, p->p.getName().equals("Subset")).setSelectionObjectClass(poc.getSelectedClassIdx());
            ParameterUtils.getParameterFromSiblings(CategoryParameter.class, poc, null).setSelectionObjectClass(poc.getSelectedClassIdx());
            ChannelImageParameter chan = ParameterUtils.getParameterFromSiblings(ChannelImageParameter.class, poc, null);
            if (chan.getSelectedIndex() < 0 && ParameterUtils.getExperiment(chan) != null) chan.setChannelFromObjectClass(poc.getSelectedClassIdx());
        })
        .setHint("Select object class of reference segmented objects");
    ChannelImageParameter channel = new ChannelImageParameter("Channel Image", -1).setHint("Input raw image channel");
    ExtractZAxisParameter extractZAxisParameter = new ExtractZAxisParameter(new ExtractZAxisParameter.ExtractZAxis[]{ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE, ExtractZAxisParameter.ExtractZAxis.SINGLE_PLANE}, ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE);
    ObjectClassParameter parentObjectClass = new ObjectClassParameter("Parent Object Class", -1, true, false)
        .setNoSelectionString("Viewfield")
        .setHint("Select object class that will define the frame (usually parent object class)");

    public static class OtherObjectClassParameter extends ConditionalParameterAbstract<Boolean, OtherObjectClassParameter> {
        ObjectClassParameter label = new ObjectClassParameter("Object Class", -1, false, false).addListener( oc -> {
            if (oc.getParent() != null) {
                TextParameter key = ParameterUtils.getParameterFromSiblings(TextParameter.class, oc, null);
                if (key.getValue().isEmpty() && oc.getSelectedIndex() >= 0) key.setValue(oc.getSelectedItemsNames()[0]);
            }
        });
        ChannelImageParameter channel = new ChannelImageParameter("Channel", -1).addListener( c -> {
            if (c.getParent() != null) {
                TextParameter key = ParameterUtils.getParameterFromSiblings(TextParameter.class, c, null);
                if (key.getValue().isEmpty() && c.getSelectedIndex() >= 0) key.setValue(c.getSelectedItemsNames()[0]);
            }
        });
        TextParameter key = new TextParameter("Name", "", false, false);
        ExtractZAxisParameter extractZAxisParameter = new ExtractZAxisParameter(new ExtractZAxisParameter.ExtractZAxis[]{ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE, ExtractZAxisParameter.ExtractZAxis.SINGLE_PLANE}, ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE);

        public OtherObjectClassParameter() {
            this("Type");
        }

        public OtherObjectClassParameter(String name) {
            super(new BooleanParameter(name,  "Channel", "Label", true));
            this.setActionParameters(true, channel, extractZAxisParameter, key);
            this.setActionParameters(false, label, key);
        }

        public int getSelectedChannelOrObjectClass() {
            if (isLabel()) return label.getSelectedClassIdx();
            else return channel.getSelectedIndex();
        }

        public boolean isLabel() {
            return !getActionValue();
        }

        public ExtractZAxisParameter.ExtractZAxisConfig getExtractZAxis() {
            return extractZAxisParameter.getConfig();
        }

    }
    SimpleListParameter<OtherObjectClassParameter> otherOCList = new SimpleListParameter<>("Other Channels", new OtherObjectClassParameter())
            .addValidationFunctionToChildren(g -> {
                String k = g.key.getValue();
                if (k.equals("raw") || k.equals("regionLabels") || k.equals("/raw") || k.equals("/regionLabels")) return false;
                SimpleListParameter<OtherObjectClassParameter> parent = (SimpleListParameter<OtherObjectClassParameter>)g.getParent();
                return parent.getChildren().stream().filter(gg -> g != gg).map(gg -> gg.key.getValue()).noneMatch(kk -> kk.equals(k));
            }).setHint("Other object class label or raw input image to be extracted");

    CategoryParameter extractCategory = new CategoryParameter(false);
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
    GroupParameter extractionParameters = new GroupParameter("ExtractionParameters", objectClass, channel, extractZAxisParameter, otherOCList, extractCategory, extractDims, extendToDims, selModeCond, selectionFilter, spatialDownsampling, subsamplingFactor, subsamplingNumber);

    @Override
    public String getHintText() {
        return "Training for Distnet2D<br/> If you use this method please cite: Ollion, J., Maliet, M., Giuglaris, C., Vacher, E., & Deforet, M. (2024). DistNet2D: Leveraging long-range temporal information for efficient segmentation and tracking. PRXLife";
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
        int compression = PropertyUtils.get("extract_DS_compression", 0);
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
        List<ExtractDatasetUtil.ExtractOCParameters> labelsAndChannels = otherOCList.getActivatedChildren().stream().map(g -> new ExtractDatasetUtil.ExtractOCParameters( g.getSelectedChannelOrObjectClass(), g.isLabel(), g.key.getValue(), g.getExtractZAxis() )).collect(Collectors.toList());
        labelsAndChannels.add(0, new ExtractDatasetUtil.ExtractOCParameters(channel.getSelectedIndex(), false, channel.getSelectedItemsNames()[0], extractZAxisParameter.getConfig()));
        return ExtractDatasetUtil.getDiSTNetDatasetTask(mDAO, selOC, labelsAndChannels, extractCategory.getCategorySelections(), extractCategory.addDefaultCategory(), ArrayUtil.reverse(extractDims.getArrayInt(), true), extendToDims.getSelected(), selections, selectionFilter.getSelectedItem(), outputFile, spatialDownsampling.getIntValue(), subsamplingFactor.getIntValue(), subsamplingNumber.getIntValue(), compression);
    }

    public String getDockerImageName() {
        return "distnet2d";
    }

    @Override
    public DLModelMetadata getDLModelMetadata(String workingDirectory) {
        ArchitectureParameter archP = (ArchitectureParameter)getConfiguration().getOtherParameters()[1];
        boolean next = archP.next.getSelected();
        int frameWindow = archP.frameWindow.getIntValue();
        int nframes = ( next ? 2 : 1) * frameWindow + 1;
        boolean fixedShape = archP.attention.getIntValue()>0 || archP.selfAttention.getIntValue()>0;

        TrainingConfigurationParameter.DatasetParameter p;
        if (getConfiguration().getDatasetList().getChildCount() > 0) {
            if (getConfiguration().getDatasetList().getActivatedChildCount() > 0) p = getConfiguration().getDatasetList().getActivatedChildren().get(0);
            else p = getConfiguration().getDatasetList().getChildAt(0);
        } else p = null;
        DLModelMetadata.DLModelInputParameter[] inputs;
        if (p == null) { // assuming one single input
            inputs = new DLModelMetadata.DLModelInputParameter[1];
            inputs[0] = new DLModelMetadata.DLModelInputParameter("Input")
                    .setChannelNumber(nframes);;
        } else {
            int nchan = p.getChannelNumber();
            int nlab = p.getLabelNumber();
            inputs = new DLModelMetadata.DLModelInputParameter[nchan + nlab * 2];
            for (int i = 0; i<nchan; ++i) {
                inputs[i] = new DLModelMetadata.DLModelInputParameter("Input"+i)
                        .setChannelNumber(nframes)
                        .setScaling(p.getScalingParameter(i).getScaler());
            }
            for (int i = 0; i<nlab * 2; ++i) {
                inputs[i+nchan] = new DLModelMetadata.DLModelInputParameter("Input"+(i+nchan)+ (i%2==0 ? "_EDM" : "_CDM" ) )
                        .setChannelNumber(1);
            }
        }
        for (int i = 0;i<inputs.length; ++i) {
            if (fixedShape) inputs[i].setShape(getConfiguration().getGlobalDatasetParameters().getInputShape());
            else inputs[i].setShape();
        }

        boolean cat = archP.categoryNumber.getIntValue() > 1;
        DLModelMetadata.DLModelOutputParameter[] outputs = new DLModelMetadata.DLModelOutputParameter[cat?6:5];
        outputs[0] = new DLModelMetadata.DLModelOutputParameter("Output0_EDM");
        outputs[1] = new DLModelMetadata.DLModelOutputParameter("Output1_CDM");
        outputs[2] = new DLModelMetadata.DLModelOutputParameter("Output2_dY");
        outputs[3] = new DLModelMetadata.DLModelOutputParameter("Output3_dX");
        outputs[4] = new DLModelMetadata.DLModelOutputParameter("Output4_LM");
        if (cat) outputs[5] = new DLModelMetadata.DLModelOutputParameter("Output4_Cat");
        return new DLModelMetadata()
                .setInputs(inputs)
                .setOutputs(outputs)
                .setContraction(archP.getContraction())
                .addMiscParameters(new BooleanParameter("Next", archP.next.getValue()));
    }

    @Override
    public Parameter[] getParameters() {
        return getConfiguration().getChildParameters();
    }
    enum ARCH_TYPE {BLEND}
    enum ATTENTION_POS_ENC_MODE {EMBEDDING, EMBEDDING_2D, RoPE, RoPE_2D, SINE, SINE_2D}
    public static class ArchitectureParameter extends ConditionalParameterAbstract<ARCH_TYPE, ArchitectureParameter> implements PythonConfiguration {
        // blend mode
        BoundedNumberParameter filters = new BoundedNumberParameter("Feature Filters", 0, 128, 64, 1024).setHint("Number of filters at the feature level");
        BoundedNumberParameter blendingFilterFactor = new BoundedNumberParameter("Blending Filters Factor", 3, 0.5, 0.1, 1).setHint("Number of filters of blending convolution is this factor x number of feature filters x number of frames. Unused if frame window is null");
        BoundedNumberParameter downsamplingNumber = new BoundedNumberParameter("Downsampling Number", 0, 3, 2, 4);
        BooleanParameter skip = new BooleanParameter("Skip Connections", true).setLegacyInitializationValue(false).setHint("Include skip connections to EDM decoder. Note that is early downsampling is True, there will be no skip connection at first level");
        BooleanParameter earlyDownsampling = new BooleanParameter("Early Downsampling", true).setHint("If true, no convolution will be performed at first level. Reduces memory footprint, but may reduce segmentation details");

        IntegerParameter attention = new IntegerParameter("Attention", 0).setLowerBound(0)
                .setLegacyParameter((p,i)->i.setValue(((BooleanParameter)p[0]).getSelected() ? 1 : 0), new BooleanParameter("Attention", false))
                .setHint("Number of heads of the attention layer in the PairBlender module (i.e. attention between each pair of frames). If 0 no attention layer is included. Unused if frame window is null. <br/>If an attention or self-attention layer is included, the input shape is fixed.");
        IntegerParameter selfAttention = new IntegerParameter("Self-Attention", 0).setLowerBound(0)
                .setLegacyParameter((p,i)->i.setValue(((BooleanParameter)p[0]).getSelected() ? 1 : 0), new BooleanParameter("Self-Attention", false))
                .setHint("Include a self-attention layer at the feature layer of the encoder. If 0 no self-attention is included. <br/>If an attention or self-attention layer is included, the input shape is fixed.");
        EnumChoiceParameter<ATTENTION_POS_ENC_MODE> attentionPosEncMode = new EnumChoiceParameter<>("Positional Encoding", ATTENTION_POS_ENC_MODE.values(), ATTENTION_POS_ENC_MODE.RoPE_2D).setLegacyInitializationValue(ATTENTION_POS_ENC_MODE.EMBEDDING_2D).setHint("Positional encoding mode for attention layers");
        BooleanParameter next = new BooleanParameter("Next", true).setHint("Input frame window is symmetrical in future and past");
        BoundedNumberParameter frameWindow= new BoundedNumberParameter("Frame Window", 0, 3, 1, null).setHint("Number of input frames. If Next is enabled, total number of input frame is 2 x FRAME_WINDOW + 1, otherwise FRAME_WINDOW + 1");
        IntegerParameter categoryNumber = new IntegerParameter("Category Number", 0).setLowerBound(0).setHint("If greater than 1, a category will be predicted. <br/>Each dataset must contain an array named <em>category</em> that maps label to a category");
        IntegerParameter nGaps = new IntegerParameter("Gap Number", 0).setLowerBound(0)
                .setHint("Maximal Gap size (in frame number) allowed gap closing procedure at tracking. Must be lower than <em>Input Window</em>.<br>This parameter only impacts model export.")
                .addValidationFunction( g -> frameWindow.getIntValue() > g.getIntValue());

        public ArchitectureParameter(String name, boolean includeInferenceGap, int defaultFrameWindow) {
            super(new EnumChoiceParameter<>(name, ARCH_TYPE.values(), ARCH_TYPE.BLEND));
            if (includeInferenceGap) setActionParameters(ARCH_TYPE.BLEND, next, frameWindow, nGaps, downsamplingNumber, skip, earlyDownsampling, filters, blendingFilterFactor, attention, selfAttention, attentionPosEncMode, categoryNumber);
            else setActionParameters(ARCH_TYPE.BLEND, next, frameWindow, downsamplingNumber, skip, earlyDownsampling, filters, blendingFilterFactor, attention, selfAttention, attentionPosEncMode, categoryNumber);
            frameWindow.setValue(defaultFrameWindow);
            if (defaultFrameWindow == 0) frameWindow.setLowerBound(0);
            attention.addValidationFunction(att -> frameWindow.getIntValue() != 0 || att.getIntValue() == 0);
        }

        public ArchitectureParameter(String name) {
            this(name, true, 3);
        }

        public int getContraction() {
            switch (getActionValue()) {
                case BLEND:
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
        public void initFromJSONEntry(Object json) {
            if (json instanceof JSONObject) {
                JSONObject jsonO = (JSONObject) json;
                if (jsonO.get("action").equals("ENC_DEC")) jsonO.put("action", "BLEND"); // retro-compatibility for DiSTNet2DSegTraining
            }
            super.initFromJSONEntry(json);
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
            if (categoryNumber.getIntValue() > 1) res.put("category_number", categoryNumber.getIntValue());
            switch (getActionValue()) {
                case BLEND:
                default: {
                    res.put("filters", filters.getIntValue());
                    res.put("n_downsampling", downsamplingNumber.getIntValue());
                    res.put("skip_connections", skip.toJSONEntry());
                    res.put("early_downsampling", earlyDownsampling.toJSONEntry());
                    res.put("blending_filter_factor", blendingFilterFactor.getDoubleValue());
                    res.put("attention", attention.getValue());
                    res.put("self_attention", selfAttention.getValue());
                    res.put("attention_positional_encoding", attentionPosEncMode.getSelectedEnum().toString());
                    if (getCurrentParameters().contains(nGaps)) res.put("inference_gap_number", nGaps.toJSONEntry());
                    break;
                }
            }
            return res;
        }
    }
}
