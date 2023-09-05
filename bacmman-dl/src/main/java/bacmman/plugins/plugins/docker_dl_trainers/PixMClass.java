package bacmman.plugins.plugins.docker_dl_trainers;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SelectionOperations;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.DLModelMetadata;
import bacmman.plugins.DockerDLTrainer;
import bacmman.py_dataset.ExtractDatasetUtil;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PixMClass implements DockerDLTrainer {
    Parameter[] trainingParameters = new Parameter[]{TrainingConfigurationParameter.getNEpochParameter(500), TrainingConfigurationParameter.getPatienceParameter(40), TrainingConfigurationParameter.getLearningRateParameter(2e-4), TrainingConfigurationParameter.getMultiprocessingWorkerParameter(8)};
    Parameter[] datasetParameters = new Parameter[0];
    SimpleListParameter<ScalingParameter> scaling = new SimpleListParameter<>("Scaling", new ScalingParameter("Scaling")).setHint("Input channel scaling parameter (one per channel or one for all channels)")
            .addValidationFunction(TrainingConfigurationParameter.channelNumberValidation(true))
            .setNewInstanceNameFunction((l, i) -> "Channel "+i).setChildrenNumber(1);
    Parameter[] dataAugmentationParameters = new Parameter[]{scaling, new ElasticDeformParameter("Elastic Deform")};

    ChannelImageParameter extractChannels = new ChannelImageParameter("Channel", new int[0]).unique().setHint("Select object class associated to the channel that will be used for segmentation");
    ObjectClassParameter extractClasses = new ObjectClassParameter("Classification classes", new int[0], false).unique()
            .setHint("Select object classes that represent background, foreground (and contour)").addValidationFunction(oc -> oc.getSelectedIndices().length>=2);
    enum SELECTION_MODE {NEW, EXISTING}
    EnumChoiceParameter<SELECTION_MODE> selMode = new EnumChoiceParameter<>("Selection", SELECTION_MODE.values(), SELECTION_MODE.NEW).setHint("Which subset of the current dataset should be included into the extracted dataset. EXISTING: choose previously defined selection. NEW: will generate a selection");
    PositionParameter extractPos = new PositionParameter("Position", true, true).setHint("Position to include in extracted dataset. If no position is selected, all position will be included.");
    SelectionParameter extractSel = new SelectionParameter("Selection", false, true);
    ConditionalParameter<SELECTION_MODE> selModeCond = new ConditionalParameter<>(selMode)
            .setActionParameters(SELECTION_MODE.EXISTING, extractSel)
            .setActionParameters(SELECTION_MODE.NEW, extractPos);
    Parameter[] datasetExtractionParameters = new Parameter[] {extractChannels, extractClasses, selModeCond};
    TrainingConfigurationParameter configuration = new TrainingConfigurationParameter("Configuration", true, true, trainingParameters, datasetParameters, dataAugmentationParameters);
    @Override
    public Parameter[] getParameters() {
        return getConfiguration().getChildParameters();
    }

    @Override
    public void setReferencePath(Path refPath) {
        configuration.setReferencePath(refPath);
    }

    @Override
    public TrainingConfigurationParameter getConfiguration() {
        return configuration;
    }

    @Override
    public Parameter[] getDatasetExtractionParameters() {
        return datasetExtractionParameters;
    }

    @Override
    public Task getDatasetExtractionTask(MasterDAO mDAO, String outputFile) {
        int[] selOC = extractClasses.getSelectedIndices();
        List<String> selections;
        switch (selMode.getSelectedEnum()) {
            case NEW:
            default: {
                int parentOC = mDAO.getExperiment().experimentStructure.getParentObjectClassIdx(selOC[0]);
                String[] selectedPositions = extractPos.getSelectedPosition(true);
                Selection s = SelectionOperations.createSelection("PixMClass_dataset", Arrays.asList(selectedPositions), parentOC, mDAO);
                SelectionOperations.nonEmptyFilter(s, mDAO.getExperiment().experimentStructure);
                mDAO.getSelectionDAO().store(s);
                selections = Collections.singletonList(s.getName());
                break;
            }
            case EXISTING: {
                selections = Arrays.asList(extractSel.getSelectedItems());
                break;
            }
        }
        return ExtractDatasetUtil.getPixMClassDatasetTask(mDAO, extractChannels.getSelectedIndices(), selOC, selections, outputFile, 0);
    }
    protected ScalingParameter getScalingParameter(int channelIdx) { // configured scaling parameter is located in TrainingConfigurationParameter . Using first dataset by default.
        SimpleListParameter<ScalingParameter> scaler = (SimpleListParameter<ScalingParameter>)configuration.getDatasetList().getChildAt(0).getDataAugmentationParameters().get(0);
        return scaler.getChildAt(channelIdx);
    }
    @Override
    public String getDockerImageName() {
        return "pixmclass";
    }

    @Override
    public DLModelMetadata getDLModelMetadata() {
        DLModelMetadata.DLModelInputParameter[] inputs = new DLModelMetadata.DLModelInputParameter[this.configuration.getChannelNumber()];
        for (int i = 0; i<inputs.length; ++i) inputs[i] = new DLModelMetadata.DLModelInputParameter("Input")
            .setChannelNumber(1).setShape(0)
            .setScaling(getScalingParameter(i).getScaler());
        DLModelMetadata.DLModelOutputParameter output = new DLModelMetadata.DLModelOutputParameter("Output");
        return new DLModelMetadata()
            .setInputs(inputs)
            .setOutputs(output)
            .setContraction(16); // TODO change when architecture is parametrized
    }

}
