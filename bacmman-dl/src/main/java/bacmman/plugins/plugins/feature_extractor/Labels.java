package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.Map;

import static bacmman.plugins.plugins.feature_extractor.RawImage.handleZ;

public class Labels implements FeatureExtractor, Hint {
    EnumChoiceParameter<Task.ExtractZAxis> extractZ = new EnumChoiceParameter<>("Extract Z", Task.ExtractZAxis.values(), Task.ExtractZAxis.IMAGE3D);
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    ConditionalParameter<Task.ExtractZAxis> extractZCond = new ConditionalParameter<>(extractZ)
            .setActionParameters(Task.ExtractZAxis.SINGLE_PLANE, plane)
            .setHint("Choose how to handle Z-axis: <ul><li>Image3D: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>SINGLE_PLANE: a single plane is extracted, defined in <em>Plane Index</em> parameter</li><li>MIDDLE_PLANE: the middle plane is extracted</li><li>BATCH: tensor are treated as 2D images </li></ul>");;

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{extractZCond};
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        Image res= resampledPopulation.get(parent).getLabelMap();
        return handleZ(res, extractZ.getSelectedEnum(), plane.getIntValue());
    }

    public Task.ExtractZAxis getExtractZDim() {
        return this.extractZ.getSelectedEnum();
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
