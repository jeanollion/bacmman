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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DiSTNet2DCatTraining extends DiSTNet2DSegTraining {

    public DiSTNet2DCatTraining() {
        this.extractCategory.setActionValue(true);
        this.extractCategory.addValidationFunction(CategoryParameter::getSelected);
        segmentationParam = new DiSTNet2DTraining.SegmentationParameters( false, false);
        otherParameters = new Parameter[]{segmentationParam, arch};
        configuration = new TrainingConfigurationParameter("Configuration", true, true, trainingParameters, datasetParameters, dataAugmentationParameters, otherDatasetParameters, otherParameters, testParameters)
                .setBatchSize(4).setConcatBatchSize(2).setEpochNumber(500).setStepNumber(200)
                .setDockerImageRequirements(getDockerImageName(), null, null, null);
    }

    @Override
    public String getHintText() {
        return "Training for category prediction neural network (based on Distnet2D)<br/> If you use this method please cite: Ollion, J., Maliet, M., Giuglaris, C., Vacher, E., & Deforet, M. (2023). DistNet2D: Leveraging long-range temporal information for efficient segmentation and tracking. PRXLife";
    }

    @Override
    public DLModelMetadata getDLModelMetadata(String workingDirectory) {
        DiSTNet2DTraining.ArchitectureParameter archP = (DiSTNet2DTraining.ArchitectureParameter)getConfiguration().getOtherParameters()[1];
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
            inputs[0] = new DLModelMetadata.DLModelInputParameter("Input").setChannelNumber(nframes);
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
                    .setChannelNumber(nframes);
            }
        }
        for (int i = 0;i<inputs.length; ++i) {
            if (fixedShape) inputs[i].setShape(getConfiguration().getGlobalDatasetParameters().getInputShape());
            else inputs[i].setShape();
        }
        DLModelMetadata.DLModelOutputParameter[] outputs = new DLModelMetadata.DLModelOutputParameter[1];
        outputs[1] = new DLModelMetadata.DLModelOutputParameter("Output0_Category");
        return new DLModelMetadata()
                .setInputs(inputs)
                .setOutputs(outputs)
                .setContraction(archP.getContraction())
                .addMiscParameters(new BooleanParameter("Next", archP.next.getValue()));
    }
}
