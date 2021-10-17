package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.pre_filters.ImageFeature;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.processing.watershed.WatershedTransform;
import ij.process.AutoThresholder;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter.splitInTwo;

public class NucleusEdgeDetector implements Segmenter, Hint, ObjectSplitter, TestableProcessingPlugin {
    ScaleXYZParameter smoothScale = new ScaleXYZParameter("Smooth Scale", 5).setHint("Scale for Gaussian Smooth applied before global scaling. Set 0 as Z-scale for 2D smoothing");
    PluginParameter<Thresholder> threshold_g = new PluginParameter<>("Global Threshold", Thresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setHint("Global threshold applied to the whole image to roughly detect nuclei");
    PluginParameter<Thresholder> threshold_l = new PluginParameter<>("Local Threshold", Thresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setHint("Threshold applied to regions detected after the local watershed procedure");
    PreFilterSequence watershedMapFilters = new PreFilterSequence("Watershed Map").add(new ImageFeature().setFeature(ImageFeature.Feature.GRAD)).setHint("Filter sequence to compute the map on wich the watershed will be performed");
    NumberParameter minSize = new BoundedNumberParameter("Minimum Nucleus Size", 0, 10000, 1, null).setHint("Minimum Nucleus Size (in pixels). This filters the nucleus detected at first stage (with the global threhsold)").setEmphasized(true);
    ScaleXYZParameter borderSize = new ScaleXYZParameter("Crop Margin", 5, 1, false).setDecimalPlaces(0).setParameterName("MarginXY", "MarginZ").setHint("Margin to add when cropping around each nuclei, in case the adjusted nucleus would be bigger than the approximate nucleus");

    public NucleusEdgeDetector() {
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{smoothScale, threshold_g, minSize, borderSize, watershedMapFilters, threshold_l};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        logger.debug("performing smooth");
        Image smooth = input;
        if (smoothScale.getScaleXY()>0) {
            double scaleZ = smoothScale.getScaleZ(input.getScaleXY(), input.getScaleZ());
            if (scaleZ>1) smooth = ImageFeatures.gaussianSmooth(input, smoothScale.getScaleXY(), scaleZ, false);
            else smooth = ImageOperations.applyPlaneByPlane(input, i -> ImageFeatures.gaussianSmooth(input, smoothScale.getScaleXY(), 1, false), true);
        }
        logger.debug("smooth performed");
        RegionPopulation nuclei = SimpleThresholder.run(smooth, threshold_g.instantiatePlugin(), parent);
        logger.debug("global segmentation performed: found {} nuclei", nuclei.getRegions().size());
        if (stores!=null) {
            if (smoothScale.getScaleXY()>0) stores.get(parent).addIntermediateImage("smoothed images", smooth);
            stores.get(parent).addIntermediateImage("global nuclei segmentation", nuclei.getLabelMap());
        }
        int marginXY = (int)borderSize.getScaleXY();
        int marginZ = (int)borderSize.getScaleZ(input.getScaleXY(), input.getScaleZ());
        BoundingBox extent = new SimpleBoundingBox<>(-marginXY, marginXY, -marginXY, marginXY, -marginZ, marginZ);
        List<Region> allRegions = nuclei.getRegions().parallelStream().map(r -> adjustEdges(r, input, extent, nuclei)).collect(Collectors.toList());
        nuclei.getRegions().clear();
        nuclei.getRegions().addAll(allRegions); // TODO manage overlapping regions ?
        nuclei.relabel(true);
        return nuclei;
    }

    private Region adjustEdges(Region r, Image image, BoundingBox extent, RegionPopulation pop) {
        MutableBoundingBox bounds = new MutableBoundingBox(r.getBounds()).extend(extent);
        bounds.trim(image.getBoundingBox().resetOffset());
        Image inputCrop = image.crop(bounds);
        Image watershedMap = watershedMapFilters.filter(inputCrop, null);
        ImageMask maskCrop = pop.getLabelMap().crop(bounds);
        RegionPopulation subRegions = WatershedTransform.watershed(watershedMap, maskCrop, new WatershedTransform.WatershedConfiguration());
        double thld = threshold_l.instantiatePlugin().runThresholder(image, null);
        subRegions.filter(new RegionPopulation.MeanIntensity(thld, true, image));
        subRegions.mergeAllConnected();
        subRegions.keepOnlyLargestObject();
        subRegions.translate(bounds, false);
        return subRegions.getRegions().get(0);
    }

    @Override
    public String getHintText() {
        return "Fast algorithm for precise detection of nucleus edges. Do not split nuclei in contact. <br />A first rough segmentation is applied on the gaussian smooth transform of the image using the global threshold, then a watershed transform is applied on each segmented nucleus to fit edges";
    }

    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        input = object.isAbsoluteLandMark() ? input.cropWithOffset(object.getBounds()) : input.crop(object.getBounds());
        RegionPopulation res= splitInTwo(input, object.getMask(), true, false, splitVerbose);
        res.translate(object.getBounds(), object.isAbsoluteLandMark());
        return res;
    }
    boolean splitVerbose;
    @Override
    public void setSplitVerboseMode(boolean verbose) {
        this.splitVerbose=verbose;
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }
}