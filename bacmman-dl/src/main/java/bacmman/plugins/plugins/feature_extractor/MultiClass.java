package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.plugins.FeatureExtractor;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.plugins.plugins.feature_extractor.RawImage.handleZ;

public class MultiClass implements FeatureExtractor {
    EnumChoiceParameter<Task.ExtractZAxis> extractZ = new EnumChoiceParameter<>("Extract Z", Task.ExtractZAxis.values(), Task.ExtractZAxis.IMAGE3D);
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    ConditionalParameter<Task.ExtractZAxis> extractZCond = new ConditionalParameter<>(extractZ)
            .setActionParameters(Task.ExtractZAxis.SINGLE_PLANE, plane)
            .setHint("Choose how to handle Z-axis: <ul><li>Image3D: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>SINGLE_PLANE: a single plane is extracted, defined in <em>Plane Index</em> parameter</li><li>MIDDLE_PLANE: the middle plane is extracted</li><li>BATCH: tensor are treated as 2D images </li></ul>");;

    ObjectClassParameter classes = new ObjectClassParameter("Classes", -1, false, true).setEmphasized(true).setHint("Select class to be extracted. A label will be assigned to each class in the defined order");
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int[] resampleDimensions) {
        List<ImageMask> images = IntStream.of(classes.getSelectedIndices()).mapToObj(oc -> resampledPopulations.get(oc).get(parent).getLabelMap()).collect(Collectors.toList());
        ImageByte res = new ImageByte("", images.get(0));
        for (int i = 0; i<images.size(); ++i) {
            ImageMask mask = images.get(i);
            int label = i + 1;
            ImageMask.loop(mask, (x, y, z)->res.setPixel(x, y, z, label));
        }
        return handleZ(res, extractZ.getSelectedEnum(), plane.getIntValue());
    }

    @Override
    public InterpolatorFactory interpolation() {
        return new NearestNeighborInterpolatorFactory();
    }

    @Override
    public String defaultName() {
        return "classes";
    }

    @Override
    public Task.ExtractZAxis getExtractZDim() {
        return extractZ.getSelectedEnum();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{classes, extractZCond};
    }
}
