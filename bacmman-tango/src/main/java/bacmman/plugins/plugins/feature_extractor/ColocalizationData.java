package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.SimpleImageProperties;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.FeatureExtractorOneEntryPerInstance;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ColocalizationData implements FeatureExtractorOneEntryPerInstance, Hint {
    SimpleListParameter<ObjectClassParameter> channels = new SimpleListParameter<>("Channels to Extract", 0, new ObjectClassParameter("Object Class"))
            .setChildrenNumber(2)
            .setHint("Choose object classes associated to the channels to be extracted. Note that each object class can only be selected once")
            .addValidationFunction(l -> l.getActivatedChildren().stream().mapToInt(ObjectClassOrChannelParameter::getSelectedClassIdx).distinct().count() == l.getActivatedChildren().size());
    SimpleListParameter<EVFParameter> evfList = new SimpleListParameter<>("EVF", new EVFParameter("EVF Parameters")).setHint("If items are added to this list, Eroded Volume Fraction (EVF) will be computed for each pixel and returned as an additional channel");
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        if (objectClassIdx != parent.getStructureIdx()) throw new IllegalArgumentException("invalid object class: should correspond to parent selection that has object class==: "+parent.getStructureIdx());
        List<Image> images = channels.getActivatedChildren().stream().mapToInt(ObjectClassOrChannelParameter::getSelectedClassIdx)
                .mapToObj(parent::getRawImage).collect(Collectors.toList());
        for (EVFParameter p : evfList.getActivatedChildren()) {
            images.add(p.computeEVF(parent));
        }
        int maxBD = images.stream().mapToInt(Image::getBitDepth).max().getAsInt();
        int count = (int)parent.getRegion().size();
        Image res = Image.createImage(Selection.indicesToString(SegmentedObjectUtils.getIndexTree(parent)), maxBD, new SimpleImageProperties(count, images.size(), 1, 1, 1));
        int[] idx = new int[1];
        ImageMask.loop(parent.getMask(), (x, y, z) -> {
            for (int c = 0; c<images.size(); ++c) res.setPixel(idx[0], c, 0, images.get(c).getPixel(x, y, z));
            ++idx[0];
        });
        return res;
    }
    public Task.ExtractZAxis getExtractZDim() {
        return Task.ExtractZAxis.IMAGE3D;
    }

    @Override
    public InterpolatorFactory interpolation() {
        return null;
    }

    @Override
    public String defaultName() {
        return "ColocalizationData";
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{channels, evfList};
    }

    @Override
    public String getHintText() {
        return "Extracts values of several channels within segmented objects. The segmented object class must correspond to the object class of the selected selection. " +
                "<br/>For each object O of the selection, one entry will be created in the output file. The path of the entry is selection-name/dataset-name/position-name/feature-name/segmented-object-name" +
                "<br/>The Y-axis corresponds to the selected channels in the parameter <em>Channels to Extract</em>(in the defined order). The X-axis correspond to the different locations of O, thus the size along X-axis corresponds to the volume of the O";
    }
}