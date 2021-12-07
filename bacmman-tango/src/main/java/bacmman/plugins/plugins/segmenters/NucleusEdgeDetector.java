package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
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

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter.splitInTwo;

public class NucleusEdgeDetector implements Segmenter, Hint, ObjectSplitter, TestableProcessingPlugin {
    ScaleXYZParameter smoothScale = new ScaleXYZParameter("Smooth Scale", 5).setHint("Scale for Gaussian Smooth applied before global scaling. Set 0 as Z-scale for 2D smoothing");
    PluginParameter<Thresholder> threshold_g = new PluginParameter<>("Global Threshold", Thresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setHint("Global threshold applied to the whole image to roughly detect nuclei");
    PluginParameter<Thresholder> threshold_l = new PluginParameter<>("Local Threshold", Thresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false).setHint("Threshold applied to regions detected after the local watershed procedure");
    PreFilterSequence watershedMapFilters = new PreFilterSequence("Watershed Map").add(new ImageFeature().setFeature(ImageFeature.Feature.GRAD).setScale(5)).setHint("Filter sequence to compute the map on which the watershed will be performed");
    NumberParameter minSize = new BoundedNumberParameter("Minimum Nucleus Size", 0, 10000, 1, null).setHint("Minimum Nucleus Size (in pixels). This filters the nucleus detected at first stage (with the global threhsold)").setEmphasized(true);
    ScaleXYZParameter borderSize = new ScaleXYZParameter("Crop Margin", 7, 1, false).setDecimalPlaces(0).setParameterName("MarginXY", "MarginZ").setHint("Margin to add when cropping around each nuclei, in case the adjusted nucleus would be bigger than the approximate nucleus");
    public NucleusEdgeDetector() {
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{smoothScale, threshold_g, minSize, borderSize, watershedMapFilters, threshold_l};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        logger.debug("performing smooth");
        Image smooth = getSmoothedImage(input);

        logger.debug("smooth performed");
        RegionPopulation nuclei = SimpleThresholder.run(smooth, threshold_g.instantiatePlugin(), parent);
        logger.debug("global segmentation performed: found {} nuclei", nuclei.getRegions().size());
        nuclei.getRegions().forEach(Region::clearVoxels); // saves memory
        int marginXY = (int)borderSize.getScaleXY();
        int marginZ = (int)borderSize.getScaleZ(input.getScaleXY(), input.getScaleZ());
        BoundingBox extent = new SimpleBoundingBox<>(-marginXY, marginXY, -marginXY, marginXY, -marginZ, marginZ);
        if (stores!=null) {
            if (smoothScale.getScaleXY()>0) stores.get(parent).addIntermediateImage("smoothed images", smooth);
            stores.get(parent).addIntermediateImage("global nuclei segmentation", nuclei.getLabelMap());
            stores.get(parent).addMisc("Show local segmentation intermediate images", l -> {
                if (l.size() != 1) Core.userLog("Select one and only one nucleus");
                SegmentedObject n = l.get(0);
                BoundingBox b = n.getBounds().duplicate().translate(parent.getBounds().duplicate().reverseOffset());
                Region region = nuclei.getRegions().stream().max(Comparator.comparingInt(r -> BoundingBox.intersect(r.getBounds(), b) ? BoundingBox.getIntersection(r.getBounds(), b).getSizeXYZ() : 0)).orElse(null); // max overlapping region
                if (region!=null) adjustEdges(region, input, extent, nuclei, stores.get(parent).imageDisp);
            });
        }
        if (stores!=null) return nuclei;
        List<Region> allRegions = nuclei.getRegions().stream().map(r -> adjustEdges(r, input, extent, nuclei, null)).filter(Objects::nonNull).collect(Collectors.toList());
        return new RegionPopulation(allRegions, parent.getMaskProperties());
    }
    private Image getSmoothedImage(Image input) {
        if (smoothScale.getScaleXY()>0) {
            double scaleZ = smoothScale.getScaleZ(input.getScaleXY(), input.getScaleZ());
            if (scaleZ>1) return ImageFeatures.gaussianSmooth(input, smoothScale.getScaleXY(), scaleZ, false);
            else return ImageOperations.applyPlaneByPlane(input, i -> ImageFeatures.gaussianSmooth(input, smoothScale.getScaleXY(), 1, false), true);
        }
        return input;
    }
    private Region adjustEdges(Region r, Image image, BoundingBox extent, RegionPopulation pop, Consumer<Image> imageDisplayer) {
        MutableBoundingBox bounds = new MutableBoundingBox(r.getBounds()).extend(extent);
        bounds.trim(image.getBoundingBox().resetOffset());
        Image inputCrop = image.crop(bounds);
        Image watershedMap = watershedMapFilters.filter(inputCrop, null);
        if (imageDisplayer!=null) {
            logger.debug("adjust edges region: {}, bounds: {}", r.getLabel(), r.getBounds());
            imageDisplayer.accept(inputCrop.setName("Cropped image"));
            imageDisplayer.accept(watershedMap.setName("Watershed Map"));
            imageDisplayer.accept(pop.getLabelMap().crop(bounds).setName("Initial segmentation"));
        }
        RegionPopulation subRegions = WatershedTransform.watershed(watershedMap, null, new WatershedTransform.WatershedConfiguration().lowConectivity(false));
        if (imageDisplayer!=null) imageDisplayer.accept(EdgeDetector.generateRegionValueMap(subRegions, inputCrop).setName("Partitions before thresholding"));
        double thld = threshold_l.instantiatePlugin().runThresholder(inputCrop, null);
        //subRegions.filter(new RegionPopulation.MeanIntensity(thld, true, inputCrop));
        subRegions.filter(new RegionPopulation.QuantileIntensity(thld, true, inputCrop));
        if (imageDisplayer!=null) imageDisplayer.accept(EdgeDetector.generateRegionValueMap(subRegions, inputCrop).setName("Partitions after thresholding"));
        subRegions.mergeAllConnected();
        if (imageDisplayer!=null) imageDisplayer.accept(subRegions.getLabelMap().duplicate("Partition after merging"));
        subRegions.keepOnlyLargestObject();
        if (imageDisplayer!=null) imageDisplayer.accept(subRegions.getLabelMap().duplicate("Partition after keeping largest object"));
        subRegions.translate(bounds, false);
        if (subRegions.getRegions().isEmpty()) return null;
        Region res = subRegions.getRegions().get(0);
        res.clearVoxels(); // saves memory
        return res;
    }

    @Override
    public String getHintText() {
        return "Fast algorithm for precise detection of 3D nucleus edges. Do not split nuclei in contact. <br />A first rough segmentation is applied on the gaussian smooth transform of the image using the global threshold, then a watershed transform is applied on each segmented nucleus to fit edges";
    }

    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        input = object.isAbsoluteLandMark() ? input.cropWithOffset(object.getBounds()) : input.crop(object.getBounds());
        input = getSmoothedImage(input);
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