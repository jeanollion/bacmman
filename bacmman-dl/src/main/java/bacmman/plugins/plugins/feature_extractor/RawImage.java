package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.Hint;
import ij.plugin.Raw;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;

public class RawImage implements FeatureExtractor, Hint {
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);
    ExtractZAxisParameter extractZ = new ExtractZAxisParameter();
    boolean byChannel = false;
    public Task.ExtractZAxis getExtractZDim() {
        return this.extractZ.getExtractZDim();
    }

    public RawImage setExtractZ(Task.ExtractZAxis mode, int planeIdx) {
        this.extractZ.setPlaneIdx(planeIdx);
        this.extractZ.setExtractZDim(mode);
        return this;
    }

    public RawImage setExtractZ(Task.ExtractZAxis mode) {
        this.extractZ.setExtractZDim(mode);
        return this;
    }

    public RawImage setInterpolation(InterpolationParameter.INTERPOLATION interpolation) {
        this.interpolation.setActionValue(interpolation);
        return this;
    }

    public RawImage setByChannel(boolean byChannel) {
        this.byChannel = byChannel;
        return this;
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        Image res = byChannel ? parent.getRawImageByChannel(objectClassIdx) : parent.getRawImage(objectClassIdx);
        return ExtractZAxisParameter.handleZ(res, extractZ.getExtractZDim(), extractZ.getPlaneIdx());
    }

    @Override
    public InterpolatorFactory interpolation() {
        return interpolation.getInterpolation();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{interpolation, extractZ};
    }

    @Override
    public String defaultName() {
        return "raw";
    }

    @Override
    public String getHintText() {
        return "Extracts the grayscale image from channel associated to the selected object class after the pre-processing step";
    }
}
