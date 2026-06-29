package bacmman.plugins.plugins.docker_dl_trainers;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SelectionOperations;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.DLModelMetadata;
import bacmman.plugins.DockerDLTrainer;
import bacmman.py_dataset.ExtractDatasetUtil;
import bacmman.ui.PropertyUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

import static bacmman.configuration.parameters.InputShapesParameter.getInputShapeParameter;

public class PixMClass implements DockerDLTrainer, DockerDLTrainer.MixedPrecision, DockerDLTrainer.TestPredict {
    BooleanParameter mixedPrecision = TrainingConfigurationParameter.getMixedPrecisionParameter(true);
    EnumChoiceParameter<TrainingConfigurationParameter.EXPORT_PRECISION> exportPrecision = TrainingConfigurationParameter.getExportPrecisionParameter();
    Parameter[] trainingParameters = new Parameter[]{TrainingConfigurationParameter.getStartEpochParameter(), TrainingConfigurationParameter.getValidationStepParameter(100), TrainingConfigurationParameter.getValidationFreqParameter(1), new TrainingConfigurationParameter.CategoryLossParameter("Loss Parameters", "category_loss_parameters", true, true, true, false), mixedPrecision, exportPrecision};
    Parameter[] datasetParameters = new Parameter[]{new IntegerParameter("Min Annotated Pixel Number", 100).setLowerBound(0).setHint("If greater than zero, each batch item will contain at least this amount of annotated pixels. To do so, several batches may be combined.")};
    Parameter[] dataAugmentationParameters = new Parameter[]{new ElasticDeformParameter("Elastic Deform"), new IlluminationParameter("Illumination Transform")};
    Parameter[] otherDatasetParameters = new Parameter[]{new TrainingConfigurationParameter.InputSizerParameter("Input Images", TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.RANDOM_TILING, TrainingConfigurationParameter.RESIZE_OPTION.CONSTANT_SIZE)};

    ChannelImageParameter extractChannels = new ChannelImageParameter("Channel", new int[0]).unique().setHint("Select object class associated to the channel that will be used for segmentation. Channel name in the extracted dataset are identical as the selected channels image");
    ObjectClassParameter extractClasses = new ObjectClassParameter("Classification classes", new int[0], false).unique()
            .setHint("Select object classes that represent background, foreground (and contour)").addValidationFunction(oc -> oc.getSelectedIndices().length>=2);
    ObjectClassParameter extractParentClass = new ObjectClassParameter("Parent Class", -1, true, false)
        .setNoSelectionString("ViewField").setHint("Class that will define bounds of the extracted images");
    EnumChoiceParameter<SELECTION_MODE> selMode = new EnumChoiceParameter<>("Selection", SELECTION_MODE.values(), SELECTION_MODE.NEW).setHint("Which subset of the current dataset should be included into the extracted dataset. EXISTING: choose previously defined selection. NEW: will generate a selection (it will include only annotated images)");
    PositionParameter extractPos = new PositionParameter("Position", true, true).setHint("Position to include in extracted dataset. If no position is selected, all position will be included.");
    SelectionParameter extractSel = new SelectionParameter("Selection", false, true);
    ConditionalParameter<SELECTION_MODE> selModeCond = new ConditionalParameter<>(selMode)
            .setActionParameters(SELECTION_MODE.EXISTING, extractSel)
            .setActionParameters(SELECTION_MODE.NEW, extractParentClass, extractPos);
    ExtractZAxisParameter extractZAxisParameter = new ExtractZAxisParameter(new ExtractZAxisParameter.ExtractZAxis[]{ExtractZAxisParameter.ExtractZAxis.IMAGE3D, ExtractZAxisParameter.ExtractZAxis.BATCH, ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE, ExtractZAxisParameter.ExtractZAxis.SINGLE_PLANE}, ExtractZAxisParameter.ExtractZAxis.IMAGE3D);
    ArchitectureParameter arch = new ArchitectureParameter("Architecture");

    GroupParameter extractionParameters = new GroupParameter("ExtractionParameters", extractChannels, extractClasses, extractZAxisParameter, selModeCond);

    TrainingConfigurationParameter configuration = new TrainingConfigurationParameter("Configuration", true, false, true, trainingParameters, datasetParameters, dataAugmentationParameters, otherDatasetParameters, new Parameter[]{arch}, null)
            .setEpochNumber(500).setStepNumber(100).setDockerImageRequirements(getDockerImageName(), null, null, null);

    public PixMClass() {
        configuration.getDatasetList().addValidationFunctionToChildren(d -> d.getChannelNumber() == arch.inputNumber.getIntValue());
        arch.channelNumberSupplier = () -> configuration.getChannelNumber(); // for legacy initialization
        arch.inputNumber.addValidationFunction(i -> {
            for (TrainingConfigurationParameter.DatasetParameter p : configuration.getDatasetList().getChildren()) {
                if (i.getIntValue() != p.getChannelNumber()) return false;
            }
            return true;
        });
    }

    @Override
    public boolean mixedPrecision() {
        return mixedPrecision.getSelected();
    }

    @Override
    public boolean exportFP16() {
        switch (exportPrecision.getSelectedEnum()) {
            case FP16:
                return true;
            case FP32:
                return false;
            case AUTO:
            default:
                return mixedPrecision();
        }
    }

    @Override
    public String minimalScriptVersion() {
        return "1.1.5";
    }

    @Override
    public Parameter[] getParameters() {
        return getConfiguration().getChildParameters();
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
        int[] selOC = extractClasses.getSelectedIndices();
        List<String> selections;
        switch (selMode.getSelectedEnum()) {
            case NEW:
            default: {
                int parentOC = extractParentClass.getSelectedClassIdx(); //mDAO.getExperiment().experimentStructure.getParentObjectClassIdx(selOC[0]);
                String[] selectedPositions = extractPos.getSelectedPosition(true);
                Selection s = SelectionOperations.createSelection("PixMClass_dataset", Arrays.asList(selectedPositions), parentOC, mDAO);
                logger.debug("filter out object from {}", s.getAllElementsAsStream().count());
                SelectionOperations.nonEmptyFilter(s, extractClasses.getSelectedClassIdx());
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
        return ExtractDatasetUtil.getPixMClassDatasetTask(mDAO, extractChannels.getSelectedIndices(), selOC, extractZAxisParameter.getConfig(), selections, outputFile, compression);
    }

    public String getDockerImageName() {
        return "pixmclass";
    }

    @Override
    public DLModelMetadata getDLModelMetadata(String workingDirectory) {
        ArchitectureParameter archP = (ArchitectureParameter)getConfiguration().getOtherParameters()[0];
        DLModelMetadata.DLModelInputParameter[] inputs = new DLModelMetadata.DLModelInputParameter[archP.inputNumber.getIntValue()];
        for (int i = 0; i<inputs.length; ++i) inputs[i] = new DLModelMetadata.DLModelInputParameter("Input")
            .setChannelNumber(1).setShape(0)
            .setScaling(configuration.getDatasetList().getChildAt(0).getScalingParameter(i).getScaler());
        DLModelMetadata.DLModelOutputParameter output = new DLModelMetadata.DLModelOutputParameter("Output");
        return new DLModelMetadata()
            .setInputs(inputs)
            .setOutputs(output)
            .setContraction(archP.getContraction());
    }

    enum ARCH_TYPE {UNET}
    public static class ArchitectureParameter extends ConditionalParameterAbstract<ARCH_TYPE, ArchitectureParameter> implements PythonConfiguration, ParameterWithLegacyInitialization<ArchitectureParameter, ARCH_TYPE> {
        IntegerParameter inputNumber = new IntegerParameter("Input Number", 1).setLowerBound(1).setHint("Input number. Must be consistent with dataset input channel");
        IntegerParameter classNumber = new IntegerParameter("Class Number", 3).setLowerBound(2).setHint("Number of classes to predict (usually 3: background, foreground and contours or 2: background and foreground). Must be consistent with dataset");
        BoundedNumberParameter filters = new BoundedNumberParameter("Feature Filters", 0, 256, 32, 1024).setHint("Number of filters at the feature level");
        BoundedNumberParameter filtersMin = new BoundedNumberParameter("Min. Filters", 0, 32, 8, 1024).setHint("Minimum Number of filters at all levels. <br>For each level L, the number of filter is filters / 2**(n_downsampling - l). This parameter ensures a minimum value for filters.");
        BoundedNumberParameter downsamplingNumber = new BoundedNumberParameter("Downsampling Number", 0, 4, 2, 5);
        BooleanParameter skip = new BooleanParameter("Skip Connections", true).setLegacyInitializationValue(false).setHint("If true, skip connections are included at all levels otherwise skip connection at first level is omited. Skip connection at first level increase the details");
        BooleanParameter maxpool = new BooleanParameter("Downsampling Mode", "Maxpool", "Stride", false);
        TrainingConfigurationParameter.ActivationParameter activation = TrainingConfigurationParameter.getActivationParameter();
        enum NORM {BATCH_NORM, WGN}
        EnumChoiceParameter<NORM> norm = new EnumChoiceParameter<>("Normalization", NORM.values(), NORM.WGN).setLegacyParameter((p, n)->{if (((BooleanParameter)p[0]).getSelected()) {n.setValue(NORM.BATCH_NORM);}}, new BooleanParameter("Batch Norm", true)).setHint("<b>Normalization</b> — normalization layer applied after convolutions to\n" +
                "  stabilize and speed up training.<br><br>\n" +
                "\n" +
                "  <b>batch_norm</b> (Batch Normalization): normalizes each channel using the\n" +
                "  mean/variance computed <i>across the batch</i>, and keeps a running average of\n" +
                "  these statistics for inference.\n" +
                "  <ul>\n" +
                "    <li>+ Standard, fast, cheap; excellent with large batch sizes (typical in 2D).</li>\n" +
                "    <li>&minus; Statistics are noisy when the batch is small (common in 3D with large\n" +
                "        patches).</li>\n" +
                "    <li>&minus; Uses stored \"population\" statistics at inference, which can differ from\n" +
                "        training and make deep 3D networks collapse to a uniform/blank output.</li>\n" +
                "  </ul>\n" +
                "\n" +
                "  <b>wgn</b> (Window Group Normalization): normalizes each feature map using\n" +
                "  statistics computed <i>from the input itself</i>, locally, within a sliding\n" +
                "  spatial window (no batch, no stored averages).\n" +
                "  <ul>\n" +
                "    <li>+ Behaves identically during training and inference &rarr; no collapse.</li>\n" +
                "    <li>+ Independent of batch size (ideal for 3D / small batches).</li>\n" +
                "    <li>+ Size-invariant: a small training tile and a full image are normalized\n" +
                "        consistently.</li>\n" +
                "    <li>&minus; More computation than batch_norm (sliding-window pooling).</li>\n" +
                "    <li>&minus; Has a window-size parameter to set.</li>\n" +
                "  </ul>\n" +
                "\n" +
                "  <b>Recommended:</b> <b>batch_norm</b> for 2D / large batches, <b>wgn</b> for 3D\n" +
                "  or small batches.");
        enum NORM_SCOPE {NO_NORM, ALL, PER_BLOCK, RESAMPLE}
        EnumChoiceParameter<NORM_SCOPE> normScope = new EnumChoiceParameter<>("Normalization Scope", NORM_SCOPE.values(), NORM_SCOPE.NO_NORM).setHint("<b>Normalization scope</b> — which convolutions actually receive a normalization\n" +
                "  layer. Fewer layers = faster and lighter; more layers = generally more stable.\n" +
                "  The residual (skip) connections and the output layer are never normalized.\n" +
                "  <ul>\n" +
                "    <li><b>all</b>: every convolution is normalized (most stable; standard recipe,\n" +
                "        e.g. nnU-Net). Recommended default.</li>\n" +
                "    <li><b>per_block</b>: a single normalization per block, placed at the block\n" +
                "        entry (first convolution). Lighter; transformer/ConvNeXt-style.</li>\n" +
                "    <li><b>resample</b>: a single normalization per block, placed at the\n" +
                "        resolution change (the convolution feeding the down-sampling, and the\n" +
                "        up-sampling convolution). Lightest non-empty option.</li>\n" +
                "    <li><b>none</b>: no normalization at all (fastest, but training is usually less\n" +
                "        stable and may fail to converge).</li>\n" +
                "  </ul>");

        IntSupplier channelNumberSupplier;

        protected ArchitectureParameter(String name) {
            super(new EnumChoiceParameter<>(name, ARCH_TYPE.values(), ARCH_TYPE.UNET));
            setActionParameters(ARCH_TYPE.UNET, classNumber, inputNumber, downsamplingNumber, filters, filtersMin, skip, maxpool, activation, norm, normScope);
        }

        public int getContraction() {
            switch (getActionValue()) {
                case UNET:
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
            res.put("n_classes", classNumber.getIntValue());
            res.put("n_inputs", inputNumber.getIntValue());
            res.put("normalization", norm.getSelectedEnum().toString().toLowerCase());
            res.put("normalization_scope", normScope.getSelectedEnum().toString().toLowerCase());
            res.put(activation.getPythonConfigurationKey(), activation.getPythonConfiguration());
            JSONArray sc = new JSONArray();
            if (!skip.getSelected()) sc.add(0);

            switch (getActionValue()) {
                case UNET:
                default: {
                    res.put("maxpool", maxpool.getSelected());
                    res.put("skip_omit", sc);
                    res.put("n_downsampling", downsamplingNumber.getIntValue());
                    res.put("filters", filters.getIntValue());
                    res.put("filters_min", filtersMin.getIntValue());
                    break;
                }
            }
            return res;
        }

        // legacy init
        @Override
        public void legacyInit() {
            if (channelNumberSupplier != null) inputNumber.setValue(channelNumberSupplier.getAsInt());
            skip.setValue(false);
        }
    }

}
