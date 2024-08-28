package bacmman.configuration.parameters;

import bacmman.core.Task;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ExtractRawDatasetParameter extends GroupParameterAbstract<ExtractRawDatasetParameter> {
    public enum FRAME_CHOICE_MODE {ALL, RANDOM, FLUO_SIGNAL}
    public final ExtractZAxisParameter extractZ = new ExtractZAxisParameter();
    public final BoundedNumberParameter nFrames = new BoundedNumberParameter("Number of frame per position", 0, 10, 1, null);
    public final EnumChoiceParameter<FRAME_CHOICE_MODE> frameChoiceMode = new EnumChoiceParameter<>("Frame Choice", FRAME_CHOICE_MODE.values(), FRAME_CHOICE_MODE.RANDOM);
    public final ChannelImageParameter frameChoiceChannelImage = new ChannelImageParameter("Channel Image", 0).setIncludeDuplicatedChannels(false);
    final ConditionalParameter<FRAME_CHOICE_MODE> frameChoiceCond = new ConditionalParameter<>(frameChoiceMode)
            .setActionParameters(FRAME_CHOICE_MODE.FLUO_SIGNAL, frameChoiceChannelImage, nFrames)
            .setActionParameters(FRAME_CHOICE_MODE.RANDOM, nFrames);
    public final BoundedNumberParameter xMin = new BoundedNumberParameter("X start", 0, 0, 0, null);
    public final BoundedNumberParameter xSize = new BoundedNumberParameter("X size", 0, 0, 0, null);
    public final BoundedNumberParameter yMin = new BoundedNumberParameter("Y start", 0, 0, 0, null);
    public final BoundedNumberParameter ySize = new BoundedNumberParameter("Y size", 0, 0, 0, null);
    public final BoundedNumberParameter zMin = new BoundedNumberParameter("Z start", 0, 0, 0, null);
    public final BoundedNumberParameter zSize = new BoundedNumberParameter("Z size", 0, 0, 0, null);
    public final GroupParameter bounds = new GroupParameter("Crop Image", xMin, xSize, yMin, ySize, zMin, zSize);
    public final FileChooser outputFile;
    public final ChannelImageParameter extractChannelImage;
    public final PositionParameter positions;
    final boolean multipleChannels;

    public ExtractRawDatasetParameter(String name) { // contructor for GUI
        super(name);
        this.multipleChannels=true;
        this.extractChannelImage = null;
        positions = null;
        outputFile = new FileChooser("Output File", FileChooser.FileChooserOption.FILE_ONLY, false)
                .setRelativePath(false)
                .mustExist(false)
                .setHint("Set file where dataset will be extracted. If file exists and is of same format, data will be appended to the file");
        this.setChildren(outputFile, frameChoiceCond, bounds, extractZ);
    }

    public ExtractRawDatasetParameter(String name, boolean multipleChannels) { // constructor with all parameters
        super(name);
        outputFile = null;
        this.multipleChannels = multipleChannels;
        this.extractChannelImage = new ChannelImageParameter("Channel Image", false, multipleChannels).setIncludeDuplicatedChannels(false);
        this.positions = new PositionParameter("Positions", false, true);
        this.setChildren(extractChannelImage, positions, frameChoiceCond, bounds, extractZ);
    }

    @Override
    public ExtractRawDatasetParameter duplicate() {
        ExtractRawDatasetParameter res = outputFile != null ? new ExtractRawDatasetParameter(name) : new ExtractRawDatasetParameter(name, multipleChannels);
        ParameterUtils.setContent(res.children, this.children);
        transferStateArguments(this, res);
        return res;
    }

    public SimpleBoundingBox getBounds() {
        return new SimpleBoundingBox(xMin.getValue().intValue(), xMin.getValue().intValue() + xSize.getValue().intValue() - 1, yMin.getValue().intValue(), yMin.getValue().intValue() + ySize.getValue().intValue() - 1, zMin.getValue().intValue(), zMin.getValue().intValue() + zSize.getValue().intValue() - 1);
    }

    public List<Integer> getFrames(InputImages images, int channel) {
        int nFrames = this.nFrames.getValue().intValue();
        switch (frameChoiceMode.getSelectedEnum()) {
            default:
            case RANDOM:
                List<Integer> choice = IntStream.range(0, images.getFrameNumber()).mapToObj(i -> i).collect(Collectors.toList());
                Collections.shuffle(choice);
                return choice.stream().limit(nFrames).collect(Collectors.toList());
            case FLUO_SIGNAL:
                return InputImages.chooseNImagesWithSignal(images, channel, nFrames);
            case ALL:
                return IntStream.range(0, images.getFrameNumber()).boxed().collect(Collectors.toList());
        }
    }

    public void setDefaultValues(String outputFile, int[] channels, BoundingBox bounds, FRAME_CHOICE_MODE mode, int nFrames, Task.ExtractZAxis zAXis, int extractZPlaneIdx) {
        if (outputFile != null && this.outputFile!=null) this.outputFile.setSelectedFilePath(outputFile);
        if (channels != null && this.extractChannelImage!=null) this.extractChannelImage.setSelectedIndices(channels);
        if (bounds != null) {
            xMin.setValue(bounds.xMin());
            xSize.setValue(bounds.sizeX());
            yMin.setValue(bounds.yMin());
            ySize.setValue(bounds.sizeY());
            zMin.setValue(bounds.zMin());
            zSize.setValue(bounds.sizeZ());
        }
        frameChoiceMode.setSelectedEnum(mode);
        this.nFrames.setValue(nFrames);
        extractZ.setExtractZDim(zAXis);
        extractZ.setPlaneIdx(extractZPlaneIdx);
        frameChoiceChannelImage.setSelectedClassIdx(-1);
    }
}
