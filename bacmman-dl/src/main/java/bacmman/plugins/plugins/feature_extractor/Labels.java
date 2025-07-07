package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.Map;

import static bacmman.configuration.parameters.ExtractZAxisParameter.handleZ;

public class Labels implements FeatureExtractor, Hint {
    ExtractZAxisParameter extractZ = new ExtractZAxisParameter();

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{extractZ};
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        Image res= resampledPopulations.get(objectClassIdx).get(parent).getLabelMap();
        return handleZ(res, extractZ.getExtractZDim(), extractZ.getPlaneIdx(), false);
    }

    public ExtractZAxisParameter.ExtractZAxis getExtractZDim() {
        return this.extractZ.getExtractZDim();
    }

    @Override
    public InterpolatorFactory interpolation() {
        return new NearestNeighborInterpolatorFactory();
    }

    @Override
    public String defaultName() {
        return "regionLabels";
    }

    @Override
    public String getHintText() {
        return "Extract a label image, with 0 as background";
    }
}
