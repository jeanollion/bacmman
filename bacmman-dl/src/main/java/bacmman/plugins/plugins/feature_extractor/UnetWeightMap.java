package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.Hint;
import bacmman.processing.EDT;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Arrays;
import java.util.Map;

public class UnetWeightMap implements FeatureExtractor, Hint {
    BoundedNumberParameter sigma = new BoundedNumberParameter("Sigma", 2, 5, 0.1, null).setHint("Controls the value between segmented regions");
    BoundedNumberParameter wo = new BoundedNumberParameter("wo", 2, 10, 0, null).setHint("Controls the value between regions. If 0: value at contours of a segmented region do not depend on neighboring segmented regions.");
    BooleanParameter eraseConoutrs = new BooleanParameter("Erase contours", false).setHint("Set contours of segmented regions to zero");
    BoundedNumberParameter limitClassFrequencyRatio = new BoundedNumberParameter("Limit class frequency ratio", 1, 0, 0, null).setHint("If a value greater than 0 is set, the class frequency ratio is limited to this ratio");
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);
    ExtractZAxisParameter extractZ = new ExtractZAxisParameter();
    @Override
    public String getHintText() {
        return "Extract a weight map as described in the UNet original publication: a weight that depends on the distance of the neighboring object between objects, and a weight that depend on the proportion of object in the whole image inside the objects";
    }

    @FunctionalInterface
    private interface GetWeight {
        double apply(int x, int y, int z);
    }
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        RegionPopulation pop = resampledPopulations.get(objectClassIdx).get(parent);
        // compute class frequency
        double foreground = pop.getRegions().stream().mapToDouble(r->r.size()).sum();
        int total = pop.getImageProperties().sizeXY();
        double background = total - foreground;
        double[] wc = new double[]{1, background  / foreground};
        double limitWC = limitClassFrequencyRatio.getValue().doubleValue();
        if (limitWC>0) wc[1] = limitWC;

        ImageInteger allRegions = pop.getLabelMap();
        double sigma = this.sigma.getValue().doubleValue();
        double wo = this.wo.getValue().doubleValue();
        boolean eraseContours = this.eraseConoutrs.getSelected();
        ImageFloat[] edms = wo==0 || pop.getRegions().size()<=1 ? new ImageFloat[0] : pop.getRegions().stream().map(r -> {
            ImageByte mask = new ImageByte("", pop.getImageProperties());
            r.draw(mask, 1, mask);
            return EDT.transform(mask, false, 1, 1, false);
        }).toArray(ImageFloat[]::new);
        double s2 = sigma * sigma * 2;
        GetWeight getWeight = (x, y, z) -> {
            if (allRegions.insideMask(x, y, z)) return wc[1];
            if (edms.length>1) {
                double[] minDists = Arrays.stream(edms).mapToDouble(edm -> edm.getPixel(x, y, z)).sorted().limit(2).toArray();
                return wc[0] + wo * Math.exp( - Math.pow(minDists[0] + minDists[1], 2) / s2);
            } else return wc[0];
        };
        ImageFloat res = new ImageFloat("weight map", pop.getImageProperties());
        BoundingBox.loop(res, (x, y, z) -> res.setPixel(x, y, z, getWeight.apply(x, y, z)));
        if (eraseContours) {
            pop.getRegions().forEach(r -> {
                r.getContour().forEach(v -> res.setPixel(v.x, v.y, v.z, 0));
            });
        }
        if (extractZ.getExtractZDim().equals(ExtractZAxisParameter.ExtractZAxis.CHANNEL)) return res; // handled later
        else return extractZ.getConfig().handleZ(res);
    }

    public ExtractZAxisParameter.ExtractZAxis getExtractZDim() {
        return extractZ.getExtractZDim();
    }

    @Override
    public InterpolatorFactory interpolation() {
        return interpolation.getInterpolation();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{sigma, wo, eraseConoutrs, limitClassFrequencyRatio, interpolation, extractZ};
    }

    @Override
    public String defaultName() {
        return "weightMap";
    }
}
