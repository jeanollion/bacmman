package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
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

import static bacmman.configuration.parameters.ExtractZAxisParameter.handleZ;

public class MultiClass implements FeatureExtractor {
    ExtractZAxisParameter extractZ = new ExtractZAxisParameter();
    ObjectClassParameter classes = new ObjectClassParameter("Classes", -1, false, true).setEmphasized(true).setHint("Select class to be extracted. A label will be assigned to each class in the defined order");

    public MultiClass() {}

    public MultiClass(int[] objectClasses) {
        classes.setSelectedIndices(objectClasses);
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        List<ImageMask> images = IntStream.of(classes.getSelectedIndices()).mapToObj(oc -> resampledPopulations.get(oc).get(parent).getLabelMap()).collect(Collectors.toList());
        ImageByte res = new ImageByte("", images.get(0));
        for (int i = 0; i<images.size(); ++i) {
            ImageMask mask = images.get(i);
            int label = i + 1;
            ImageMask.loop(mask, (x, y, z)->res.setPixel(x, y, z, label));
        }
        return handleZ(res, extractZ.getExtractZDim(), extractZ.getPlaneIdx(), false);
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
    public ExtractZAxisParameter.ExtractZAxis getExtractZDim() {
        return extractZ.getExtractZDim();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{classes, extractZ};
    }
}
