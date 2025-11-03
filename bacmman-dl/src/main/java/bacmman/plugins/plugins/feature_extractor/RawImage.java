package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;

public class RawImage implements FeatureExtractor, FeatureExtractor.FeatureExtractorConfigurableZDim<RawImage>, Hint {
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);
    ExtractZAxisParameter extractZ = new ExtractZAxisParameter();
    boolean byChannel = false;
    public ExtractZAxisParameter.ExtractZAxis getExtractZDim() {
        return this.extractZ.getExtractZDim();
    }

    @Override
    public RawImage setExtractZDim(ExtractZAxisParameter.ExtractZAxisConfig zAxis) {
        this.extractZ.fromConfig(zAxis);
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
        if (extractZ.getExtractZDim().equals(ExtractZAxisParameter.ExtractZAxis.CHANNEL)) return res; // handled later
        else return extractZ.getConfig().handleZ(res);
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
