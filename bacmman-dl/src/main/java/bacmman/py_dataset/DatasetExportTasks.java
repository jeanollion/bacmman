package bacmman.py_dataset;

import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.configuration.parameters.TrainingConfigurationParameter;
import bacmman.core.DatasetExportTask;
import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.plugins.feature_extractor.*;
import bacmman.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dataset export tasks used to train deep learning models
 */
public class DatasetExportTasks {
    public static DatasetExportTask getPixMClassDatasetTask(MasterDAO mDAO, int[] channelIndices, int[] objectClasses, ExtractZAxisParameter.ExtractZAxisConfig extractZ, List<String> selections, String outputFile, int compression) throws IllegalArgumentException {
        if (objectClasses.length!=2 && objectClasses.length!=3) throw new IllegalArgumentException("Select 2 or 3 object classes: background, foreground (and contours)");
        if (!Utils.objectsAllHaveSameProperty(Arrays.stream(objectClasses).boxed().collect(Collectors.toList()), mDAO.getExperiment().experimentStructure::getParentObjectClassIdx)) {
            throw new IllegalArgumentException("All selected object classes must have same parent object class");
        }
        DatasetExportTask resultingTask = new DatasetExportTask(mDAO);
        List<FeatureExtractor.Feature> features = new ArrayList<>();
        ExperimentStructure xp = mDAO.getExperiment().experimentStructure;
        String[] channelNames = xp.getChannelNames();
        for (int c : channelIndices) features.add(new FeatureExtractor.Feature( channelNames[c], new RawImage().setExtractZDim(extractZ).setByChannel(true), c));
        features.add(new FeatureExtractor.Feature( new MultiClass(objectClasses).setExtractZ(extractZ), objectClasses[0] ));

        int[] dims = new int[]{0, 0};
        int[] eraseContoursOC = new int[0];
        resultingTask.setParameters(outputFile, selections, features, dims, TrainingConfigurationParameter.RESIZE_MODE.NONE, -1, eraseContoursOC, false, 1, 1, 1, compression);
        return resultingTask;
    }

    public static class ExtractOCParameters {
        final int idx;
        final boolean isLabel;
        final String name;
        final ExtractZAxisParameter.ExtractZAxisConfig zAxis;

        public ExtractOCParameters(int idx, boolean isLabel, String name, ExtractZAxisParameter.ExtractZAxisConfig zAxis) {
            this.idx = idx;
            this.isLabel = isLabel;
            this.name = name;
            this.zAxis = zAxis;
        }

        public FeatureExtractor.Feature getFeatureExtractor() {
            if (isLabel) return new FeatureExtractor.Feature(name, new Labels().setExtractZDim(zAxis), idx);
            else return new FeatureExtractor.Feature(name, new RawImage().setByChannel(true).setExtractZDim(zAxis), idx);
        }
    }

    public static DatasetExportTask getDiSTNetDatasetTask(MasterDAO mDAO, int objectClass, List<ExtractOCParameters> labelsAndChannels, List<String> categorySelection, boolean addDefaultCategory, int[] outputDimensions, TrainingConfigurationParameter.RESIZE_MODE resizeMode, List<String> selections, String selectionFilter, String outputFile, int spatialDownSampling, int subSamplingFactor, int subSamplingNumber, int compression) throws IllegalArgumentException {
        DatasetExportTask resultingTask = new DatasetExportTask(mDAO);
        List<FeatureExtractor.Feature> features = new ArrayList<>(3 + labelsAndChannels.size());
        for (ExtractOCParameters oc : labelsAndChannels) features.add(oc.getFeatureExtractor());
        features.add(new FeatureExtractor.Feature( new Labels(), objectClass, selectionFilter ));
        features.add(new FeatureExtractor.Feature( new PreviousLinks(), objectClass, selectionFilter ));
        if (categorySelection != null && !categorySelection.isEmpty()) {
            features.add(new FeatureExtractor.Feature( new Category( categorySelection, addDefaultCategory ), objectClass, selectionFilter));
        }
        int[] eraseContoursOC = new int[0];
        resultingTask.setParameters(outputFile, selections, features, outputDimensions, resizeMode, objectClass, eraseContoursOC, true, spatialDownSampling, subSamplingFactor, subSamplingNumber, compression);
        return resultingTask;
    }

    public static DatasetExportTask getDiSTNetSegDatasetTask(MasterDAO mDAO, int objectClass, List<ExtractOCParameters> labelsAndChannels, List<String> categorySelection, boolean addDefaultCategory, int[] outputDimensions, TrainingConfigurationParameter.RESIZE_MODE resizeMode, List<String> selections, String selectionFilter, String outputFile, boolean timelapse, int spatialDownSampling, int compression) throws IllegalArgumentException {
        DatasetExportTask resultingTask = new DatasetExportTask(mDAO);
        List<FeatureExtractor.Feature> features = new ArrayList<>(2 + labelsAndChannels.size());
        for (ExtractOCParameters oc : labelsAndChannels) features.add(oc.getFeatureExtractor());
        features.add(new FeatureExtractor.Feature( new Labels(), objectClass, selectionFilter ));
        if (categorySelection != null && !categorySelection.isEmpty()) {
            features.add(new FeatureExtractor.Feature( new Category( categorySelection, addDefaultCategory ), objectClass, selectionFilter));
        }
        int[] eraseContoursOC = new int[0];
        resultingTask.setParameters(outputFile, selections, features, outputDimensions, resizeMode, objectClass, eraseContoursOC, timelapse, spatialDownSampling, 1, 1, compression);
        return resultingTask;
    }

}
