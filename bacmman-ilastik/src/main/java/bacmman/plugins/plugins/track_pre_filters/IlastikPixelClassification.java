package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.input_image.InputImages;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.*;
import bacmman.processing.ilastik.PixelClassificationRunner;
import org.ilastik.ilastik4ij.hdf5.Hdf5DataSetWriter;

import java.io.IOException;
import java.util.TreeMap;

public class IlastikPixelClassification implements TrackPreFilter, Filter, ConfigurableTransformation, DLMetadataConfigurable, Hint {
    MLModelFileParameter project = new MLModelFileParameter("Ilastik Project").setFileChooserOption(FileChooser.FileChooserOption.FILE_ONLY).setValidDirectory(MLModelFileParameter.isIlastikProject).setEmphasized(true).setHint("Select the Ilastik project file (.ilp)");

    BooleanParameter autocontext = new BooleanParameter("Autocontext", true).setEmphasized(true).setHint("Use autocontext instead of pixel classification");
    BoundedNumberParameter classIdx = new BoundedNumberParameter("Class Index", 0, 0, 0, null).setEmphasized(true);

    @Override
    public String getHintText() {
        return "PixelClassification using Ilastik. <br/>From FIJI: ilastik update site must be enabled. <br/>Set Illastik directory and memory and thread limit from the menu Plugin > ilastik > Configure ilastik executable location.<br/>If you use ilastik for your research please cite their article.";
    }

    enum DATA_TYPE {UINT8(8), UINT16(16), FLOAT32(32);
        final int bitDepth;
        DATA_TYPE(int i) {bitDepth=i;}
    }
    EnumChoiceParameter<DATA_TYPE> projectInputDType = new EnumChoiceParameter<>("Input Data Type", DATA_TYPE.values(), DATA_TYPE.FLOAT32).setEmphasized(true).setHint("Data type of input image the project was trained with. Currently Ilastik requires that prediction are made with same data type");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{project, classIdx, autocontext, projectInputDType};
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        TextParameter dType = metadata.getOtherParameter("InputDataType", TextParameter.class);
        if (dType!=null) {
            String dtype = dType.getValue();
            if (dtype.equalsIgnoreCase(DATA_TYPE.UINT8.toString())) projectInputDType.setSelectedEnum(DATA_TYPE.UINT8);
            else if (dtype.equalsIgnoreCase(DATA_TYPE.UINT16.toString())) projectInputDType.setSelectedEnum(DATA_TYPE.UINT16);
            else if (dtype.equalsIgnoreCase(DATA_TYPE.FLOAT32.toString())) projectInputDType.setSelectedEnum(DATA_TYPE.FLOAT32);
        }
        BooleanParameter ac = metadata.getOtherParameter("Autocontext", BooleanParameter.class);
        if (ac!=null) autocontext.setSelected(ac.getSelected());
        NumberParameter cI = metadata.getOtherParameter("ClassIndex", NumberParameter.class);
        if (cI!=null) classIdx.setValue(cI.getIntValue());
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        DATA_TYPE dType = projectInputDType.getSelectedEnum();
        Image[] toProcess= preFilteredImages.values().stream().map(i -> TypeConverter.convert(i, dType.bitDepth)).toArray(Image[]::new);
        try {
            Image[] processed = PixelClassificationRunner.run(toProcess, autocontext.getSelected(), project.getModelFilePath(), classIdx.getLongValue());
            int i = 0;
            for (SegmentedObject o : preFilteredImages.keySet()) preFilteredImages.put(o, processed[i++]);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    Image[] processedFrames;
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        /*Image[][][] input = new Image[][][]{{{image}}};
        Image[] out = predict(input);
        return out[0];*/
        return processedFrames.length>1 ? processedFrames[timePoint] : processedFrames[0];
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) { // TODO mini batch
        if (project.needsToDownloadModel()) project.getModelFile();
        int nFrames = inputImages.getFrameNumber();
        Image[] input = new Image[nFrames];
        DATA_TYPE dType = projectInputDType.getSelectedEnum();
        for (int t = 0; t < nFrames; ++t) input[t] = TypeConverter.convert(inputImages.getImage(channelIdx, t), dType.bitDepth);
        try {
            processedFrames = PixelClassificationRunner.run(input, autocontext.getSelected(), project.getModelFilePath(), classIdx.getLongValue());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return processedFrames!=null;
    }

    @Override
    public boolean highMemory() {
        return false;
    }
}
