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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PixMClass implements DockerDLTrainer {
    Parameter[] trainingParameters = new Parameter[]{TrainingConfigurationParameter.getPatienceParameter(40), TrainingConfigurationParameter.getEpsilonRangeParameter(1e-7, 1e-7), TrainingConfigurationParameter.getValidationStepParameter(100), TrainingConfigurationParameter.getValidationFreqParameter(1)};
    Parameter[] datasetParameters = new Parameter[]{TrainingConfigurationParameter.getLossMaxWeightParameter(10), new IntegerParameter("Min Annotated Pixel Number", 100).setLowerBound(0).setHint("If greater than zero, each batch item will contain at least this amount of annotated pixels. To do so, several batches may be combined.")};
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
    ExtractZAxisParameter extractZAxisParameter = new ExtractZAxisParameter(new ExtractZAxisParameter.ExtractZAxis[]{ExtractZAxisParameter.ExtractZAxis.BATCH, ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE, ExtractZAxisParameter.ExtractZAxis.SINGLE_PLANE}, ExtractZAxisParameter.ExtractZAxis.BATCH);

    GroupParameter extractionParameters = new GroupParameter("ExtractionParameters", extractChannels, extractClasses, extractZAxisParameter, selModeCond);

    TrainingConfigurationParameter configuration = new TrainingConfigurationParameter("Configuration", true, false, trainingParameters, datasetParameters, dataAugmentationParameters, otherDatasetParameters, null, null)
            .setEpochNumber(500).setStepNumber(100).setDockerImageRequirements(getDockerImageName(), null, null, null);
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
        DLModelMetadata.DLModelInputParameter[] inputs = new DLModelMetadata.DLModelInputParameter[this.configuration.getChannelNumber()];
        for (int i = 0; i<inputs.length; ++i) inputs[i] = new DLModelMetadata.DLModelInputParameter("Input")
            .setChannelNumber(1).setShape(0)
            .setScaling(configuration.getDatasetList().getChildAt(0).getScalingParameter(i).getScaler());
        DLModelMetadata.DLModelOutputParameter output = new DLModelMetadata.DLModelOutputParameter("Output");
        return new DLModelMetadata()
            .setInputs(inputs)
            .setOutputs(output)
            .setContraction(16); // TODO change when architecture is parametrized
    }

}
