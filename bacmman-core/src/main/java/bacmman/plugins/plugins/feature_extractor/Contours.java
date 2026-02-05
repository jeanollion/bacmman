package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.FeatureExtractorConfigurable;
import net.imglib2.interpolation.InterpolatorFactory;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Contours implements FeatureExtractorConfigurable, FeatureExtractor.FeatureExtractorOneEntryPerTrack {
    Map<SegmentedObject, ImageShort> coords;
    static int fillValue = 65535;
    // layout: each object track one entry T, N, 2 ou 3
    // 2D case: T, N, 2;  T = tracklength, N = max object contour length, 2={x, y}. if contour length differ between timepoint tensor is filled with 65535 value
    // 3D case: T, N, 3;  T = tracklength, N = max sumZ(object contour length), 2={x, y, z} : for each slice the contour is flatten along the N axis.
    @Override
    public void configure(Stream<SegmentedObject> parentTrack, int objectClassIdx) {
        // object class idx must be identical to parent track idx
        Map<SegmentedObject, ImageShort> coordinates = parentTrack.collect(Collectors.toMap(Function.identity(), p -> p.getRegion().getRoi().getFlattenExternalContoutCoordinates() )); //.setLocDelta(p.getBounds().xMin(), p.getBounds().yMin())
        Map<SegmentedObject, Integer> maxContourSizePerTrack = SegmentedObjectUtils.splitByTrackHead(coordinates.keySet()).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream().map(coordinates::get).mapToInt(SimpleImageProperties::sizeY).max().getAsInt()));
        coords = coordinates.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
            ImageShort c = e.getValue();
            int max = maxContourSizePerTrack.get(e.getKey().getTrackHead());
            if (c.sizeY() == max) return c;
            else {
                ImageShort res = c.crop(new SimpleBoundingBox(0, c.sizeX()-1, 0, max-1, 0, 0));
                for (int x = 0; x<res.sizeX(); ++x) { // pad with fill value
                    for (int y = c.sizeY(); y < max; y++) {
                        res.setPixel(x, y, 0, fillValue);
                    }
                }
                return res;
            }
        }));
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        if (objectClassIdx != parent.getStructureIdx()) throw new IllegalArgumentException("invalid object class: should correspond to parent selection that has object class==: "+parent.getStructureIdx());
        return coords.get(parent).setName(Selection.indicesString(parent));
    }

    @Override
    public InterpolatorFactory interpolation() {
        return null;
    }

    @Override
    public String defaultName() {
        return "Contours";
    }

    @Override
    public ExtractZAxisParameter.ExtractZAxis getExtractZDim() {
        return ExtractZAxisParameter.ExtractZAxis.MIDDLE_PLANE;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

}
