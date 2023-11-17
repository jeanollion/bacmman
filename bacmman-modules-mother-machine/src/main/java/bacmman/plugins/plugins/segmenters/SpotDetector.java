/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.plugins.plugins.trackers.ObjectOrderTracker;
import bacmman.processing.*;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.ArrayUtil;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SpotDetector implements Segmenter, TrackConfigurable<SpotDetector>, ManualSegmenter, ObjectSplitter, TestableProcessingPlugin, Hint, HintSimple {
    public static boolean debug = false;
    public final static Logger logger = LoggerFactory.getLogger(SpotDetector.class);
    // scales
    NumberParameter smoothScale = new BoundedNumberParameter("Smooth globalScale", 1, 1.5, 1, 5).setHint("Scale (in pixels) for gaussian smooth <br />Configuration hint: determines the <em>Gaussian</em> image displayed in test mode");
    BoundedNumberParameter radius = new BoundedNumberParameter("Radius", 1, 5, 1, null);
    ArrayNumberParameter radii = new ArrayNumberParameter("Radial Symmetry Radii", 0, radius).setSorted(true).setValue(1, 2, 3, 4).setHint("Radii used in the transformation. <br />Low values tend to add noise and detect small objects, high values tend to remove details and detect large objects");
    NumberParameter typicalSigma = new BoundedNumberParameter("Typical sigma", 1, 2, 1, null).setHint("Typical sigma of spot when fitted by a gaussian. Gaussian fit will be performed on an area of span 2 * σ +1 around the center. When two (or more) spot have spans that overlap, they are fitted together");
    ScaleXYZParameter maxLocalRadius = new ScaleXYZParameter("Max local radius", 1.5, 1.5, false).setNumberParameters(1, null, 1, true, true).setHint("Radius of local maxima filter for seed detection step. Increasing the value will decrease false positive spots but decrease the capacity to segment close spots");
    enum NORMALIZATION_MODE {NO_NORM, GLOBAL, PER_CELL}
    EnumChoiceParameter<NORMALIZATION_MODE> normMode = new EnumChoiceParameter<>("Intensity normalization", NORMALIZATION_MODE.values(), NORMALIZATION_MODE.GLOBAL).setHint("Normalization of the input intensity, will influence the values of <em>Radial Symmetry Threshold</em> and <em>Seed Threshold</em>");
    NumberParameter maxSigma = new BoundedNumberParameter("Sigma Filter", 2, 4, 1, null).setHint("Spot with a sigma value (from the gaussian fit) superior to this value will be erased.");
    NumberParameter symmetryThreshold = new NumberParameter<>("Radial Symmetry Threshold", 2, 0.3).setEmphasized(true).setHint("Radial Symmetry threshold for selection of watershed seeds.<br />Higher values tend to increase false negative detections and decrease false positive detection.<br /><br />Radial Symmetry transform allows to highlight spots in an image by estimating the local radial symmetry. Implementation of the algorithm described in Loy & Zelinsky, IEEE, 2003<br />  Configuration hint: refer to the <em>Radial Symmetry</em> image displayed in test mode"); // was 2.25
    NumberParameter intensityThreshold = new NumberParameter<>("Seed Threshold", 2, 1.2).setEmphasized(true).setHint("Threshold on gaussian for selection of watershed seeds.<br /> Higher values tend to increase false negative detections and decrease false positive detections.<br />Configuration hint: refer to <em>Gaussian</em> image displayed in test mode"); // was 1.6
    NumberParameter minOverlap = new BoundedNumberParameter("Min Overlap %", 1, 20, 0, 100).setHint("When the center of a spot (after gaussian fit) is located oustide a bacteria, the spot is erased if the overlap percentage with the bacteria is inferior to this value. (0%: spots are never erased)");

    Parameter[] parameters = new Parameter[]{symmetryThreshold, intensityThreshold, normMode, radii, smoothScale, maxLocalRadius, typicalSigma, maxSigma, minOverlap};
    ProcessingVariables pv = new ProcessingVariables();
    boolean planeByPlane = false; // TODO set as parameter for "true" 3D images
    protected static String toolTipAlgo = "<br /><br /><em>Algorithmic Details</em>:<ul>"
            + "<li>Spots are detected by performing a gaussian fit on raw intensity at the location of <em>seeds</em>, defined as the regional maxima of the Radial Symmetry transform, within the mask of the segmentation parent. Selected seeds have a Radial Symmetry value larger than <em>Radial Symmetry Seed Threshold</em> and a Gaussian value superior to <em>Seed Threshold</em></li>"
            + "<li>A quality parameter defined as √(Radial Symmetry x Gaussian) at the center of the spot is computed (used in <em>NestedSpotTracker</em>)</li></ul>" +
            "<br />In order to increase robustness to variations in the background fluorescence in bacteria, the input image is first normalized by subtracting the mean value and dividing by the standard-deviation value of the background signal within the cell. Radial Symmetry & Gaussian transforms are then computed on the normalized image.";
    protected static String toolTipDispImage = "<br /><br />Images displayed in test mode:" +
            "<ul><li><em>Gaussian</em>: Gaussian transform applied to the normalized input image.<br />This image can be used to tune the <em>Seed Threshold</em> parameter, which should be lower than the intensity of the center of the spots and larger than the background intracellular fluorescence on the <em>Gaussian</em> transformed image.</li>" +
            "<li><em>Radial Symmetry</em>: Radial Symmetry transform applied to the normalized input image.<br />This image can be used to tune the <em>Radial Symmetry Seed Threshold</em> parameter, which should be lower than the intensity of the center of the spots and larger than the background intracellular fluorescence on the <em>Radial Symmetry</em> transformed image.<br />This image can also be used to tune the <em>Propagation Threshold</em> parameter, which value should be lower than the intensity inside the spots and larger than the background intracellular fluorescence on the <em>Radial Symmetry</em> transformed image</li>";
    protected static String toolTipDispImageAdvanced = "<li><em>Seeds</em>: Selected seeds for gaussian fit</li></ul>";
    protected static String toolTipSimple ="<b>Fluorescence Spot Detection</b>.<br />" +
            "Segments spot-like objects in fluorescence images using a criterion on the Gaussian and the Radial Symmetry transforms. <br />If spot detection is not satisfying try changing the <em>Seed Threshold</em> and/or <em>Radial Symmetry Seed Threshold</em>. ";

    // tool tip interface
    @Override
    public String getHintText() {
        return toolTipSimple+toolTipAlgo+toolTipDispImage+toolTipDispImageAdvanced;
    }

    @Override
    public String getSimpleHintText() {
        return toolTipSimple + toolTipDispImage+ "</ul>";
    }

    public SpotDetector() {}

    public SpotDetector(double thresholdSeeds, double thresholdPropagation, double thresholdIntensity) {
        this.intensityThreshold.setValue(thresholdIntensity);
        this.symmetryThreshold.setValue(thresholdSeeds);
    }
    
    public SpotDetector setThresholdSeeds(double threshold) {
        this.symmetryThreshold.setValue(threshold);
        return this;
    }
    
    public SpotDetector setIntensityThreshold(double threshold) {
        this.intensityThreshold.setValue(threshold);
        return this;
    }

    /**
     * See {@link #run(Image, int, SegmentedObject, double, double)}
     * @param input
     * @param objectClassIdx
     * @param parent
     * @return 
     */
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        return run(input, objectClassIdx, parent, symmetryThreshold.getValue().doubleValue(), intensityThreshold.getValue().doubleValue());
    }
    // testable
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }

    private static class ProcessingVariables {
        Image input;
        Image smooth, radialSymmetry;
        boolean symScaled, smoothScaled;
        double[] ms;
        double smoothScale;
        double globalScale =Double.NaN;
        public void initPV(Image input, ImageMask mask, double smoothScale) {
            this.input=input;
            this.smoothScale=smoothScale;
            if (ms == null) {
                //BackgroundFit.debug=debug;
                ms = new double[2];
                //double thld = BackgroundFit.backgroundFit(HistogramFactory.getHistogram(()->input.stream(mask, true), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS), 5, ms);
                double thld = BackgroundThresholder.runThresholder(input, mask, 2, 2, 3, Double.MAX_VALUE, ms); // more robust than background fit because too few values to make histogram

                //if (true) logger.debug("scaling thld: {} mean & sigma: {}, global sigma: {}", thld, ms, globalScale);
                if (!Double.isNaN(globalScale)) ms[1] = globalScale;
            }
        }
        public Image getScaledInput() {
            return ImageOperations.affineOperation2WithOffset(input, null, 1/ms[1], -ms[0]).setName("Scaled Input");
        }
        protected Image getSmoothedMap() {
            if (smooth==null) throw new RuntimeException("Smooth map not initialized");
            if (!smoothScaled) {
                if (!smooth.sameDimensions(input)) smooth = smooth.cropWithOffset(input.getBoundingBox()); // map was computed on parent that differs from segmentation parent
                ImageOperations.affineOperation2WithOffset(smooth, smooth, smoothScale/ms[1], -ms[0]);
                smoothScaled=true;
            }
            return smooth;
        }
        
        protected Image getRadialSymmetryMap() {
            if (radialSymmetry==null) throw new RuntimeException("Radial Symmetry map not initialized");
            if (!symScaled) {
                if (!radialSymmetry.sameDimensions(input)) radialSymmetry = radialSymmetry.cropWithOffset(input.getBoundingBox()); // map was computed on parent that differs from segmentation parent
                ImageOperations.affineOperation2WithOffset(radialSymmetry, radialSymmetry, 1/ms[1], 0);
                symScaled =true;
            }
            return radialSymmetry;
        }
    }
    /**
     * Spots are detected using a seeded watershed algorithm in the laplacian transform
     * Input image is scaled by removing the mean value and dividing by the standard-deviation value of the background within the segmentation parent
     * Seeds are set on regional maxima of the laplacian transform, within the mask of {@param parent}, with laplacian value superior to {@param thresholdSeeds} and gaussian value superior to {@param intensityThreshold}
     * If several scales are provided, the laplacian globalScale space will be computed (3D for 2D input, and 4D for 3D input) and the seeds will be 3D/4D local extrema in the globalScale space in order to determine at the same time their globalScale and spatial localization
     * Watershed propagation is done within the mask of {@param parent} until laplacian values reach {@param thresholdPropagation}
     * A quality parameter in computed as √(radial symmetry x gaussian) at the center of the spot
     * @param input pre-filtered image from which spots will be detected
     * @param parent segmentation parent
     * @param thresholdSeeds minimal laplacian value to segment a spot
     * @param intensityThreshold minimal gaussian value to segment a spot
     * @return segmented spots
     */
    public RegionPopulation run(Image input, int objectClassIdx, SegmentedObject parent, double thresholdSeeds, double intensityThreshold) {
        ImageMask parentMask = parent.getMask().sizeZ()!=input.sizeZ() ? new ImageMask2D(parent.getMask()) : parent.getMask();
        if (this.parentSegTHMapmeanAndSigma!=null) pv.ms = parentSegTHMapmeanAndSigma.get((parent).getTrackHead());
        this.pv.initPV(input, parentMask, smoothScale.getValue().doubleValue()) ;
        if (pv.smooth==null || pv.getRadialSymmetryMap()==null) throw new RuntimeException("Mutation Segmenter not parametrized");//setMaps(computeMaps(input, input));
        
        Image smooth = pv.getSmoothedMap();
        Image radSym = pv.getRadialSymmetryMap();
        Neighborhood n = Filters.getNeighborhood(maxLocalRadius.getScaleXY(), maxLocalRadius.getScaleZ(input.getScaleXY(), input.getScaleZ()), radSym);
        ImageByte seedMap = Filters.localExtrema(radSym, null, true, parentMask, n); // TODO from radialSymetry map of smoothed image ? parameter ?

        List<Point> seeds = new ArrayList<>();
        BoundingBox.loop(new SimpleBoundingBox(seedMap).resetOffset(),
                (x, y, z)->seeds.add(new Point(x, y, z)),
                (x, y, z)->seedMap.insideMask(x, y, z) && smooth.getPixel(x, y, z)>=intensityThreshold && radSym.getPixel(x, y, z)>=thresholdSeeds);


        if (stores!=null) {
            ImageOperations.fill(seedMap, 0, null);
            seeds.forEach(p -> seedMap.setPixel(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2), 1));
            stores.get(parent).addIntermediateImage("Seeds", seedMap);
            stores.get(parent).addIntermediateImage("Gaussian", smooth);
            stores.get(parent).addIntermediateImage("RadialSymmetryTransform", radSym);
        }
        List<Spot> segmentedSpots;
        Image fitImage = getParent(parent, objectClassIdx).getPreFilteredImage(objectClassIdx); // we need full image to fit. In case segmentation is occurring in segmentation parent, then fit will occur in parent

        BiConsumer<List<Spot>, List<Point>> removeSpotsFarFromSeeds = (spots, seedList) -> { // filter by distance from original seed
            Map<Spot, Double> distSqFromSeed = IntStream.range(0, spots.size()).mapToObj(i->i).collect(Collectors.toMap(spots::get, i->spots.get(i).getCenter().distSq(seedList.get((i)))));
            double maxDistSq = Math.pow(2 * typicalSigma.getValue().doubleValue(), 2);
            spots.removeAll(distSqFromSeed.entrySet().stream().filter(e->e.getValue()>=maxDistSq || Double.isNaN(e.getValue()) || Double.isInfinite(e.getValue()) ).map(e->e.getKey()).collect(Collectors.toList()));
        };

        if (seedMap.sizeZ()>1) { // fit 3D not taking into account anisotropy
            segmentedSpots = new ArrayList<>();
            List<Image> fitImagePlanes = fitImage.splitZPlanes();
            List<Image> smoothPlanes = smooth.splitZPlanes();
            List<Image> radSymPlanes = radSym.splitZPlanes();
            IntStream.range(0, fitImagePlanes.size()).forEachOrdered(z -> {
                List<Point> seedsZ = seeds.stream().filter(p -> p.get(2)==z).collect(Collectors.toList());
                List<Spot> segmentedSpotsZ = fitAndSetQuality(radSymPlanes.get(z), smoothPlanes.get(z), fitImagePlanes.get(z), seedsZ, seedsZ, typicalSigma.getValue().doubleValue());
                removeSpotsFarFromSeeds.accept(segmentedSpotsZ, seedsZ);
                segmentedSpots.addAll(segmentedSpotsZ);
            });
            segmentedSpots.forEach(s->s.setIs2D(false));
        } else {
            segmentedSpots =fitAndSetQuality(radSym, smooth, fitImage, seeds, seeds, typicalSigma.getValue().doubleValue());
            removeSpotsFarFromSeeds.accept(segmentedSpots, seeds);
        }
        // remove spots with center outside mask
        double minOverlap = this.minOverlap.getValue().doubleValue()/100d;
        if (minOverlap>0) {
            segmentedSpots.removeIf(s -> {
                if (!parentMask.contains(s.getCenter()) || !parentMask.insideMask(s.getCenter().getIntPosition(0), s.getCenter().getIntPosition(1), (int) (s.getCenter().getWithDimCheck(2) + 0.5))) {
                    //logger.debug("spot center outside mask. overlap: {}, size: {}, ratio: {}", s.getOverlapArea(parent.getRegion(), parent.getBounds(), null), s.size(), s.getOverlapArea(parent.getRegion(), parent.getBounds(), null) / s.size());
                    return (s.getOverlapArea(parent.getRegion(), parent.getBounds(), null) / s.size()) < minOverlap;
                }
                return false;
            });
        }
        // filter by radius
        segmentedSpots.removeIf(s->s.getRadius()>maxSigma.getValue().doubleValue());

        RegionPopulation pop = new RegionPopulation(segmentedSpots, smooth);
        pop.sortBySpatialOrder(ObjectOrderTracker.IndexingOrder.YXZ);
        return pop;
    }
    public static void removeCloseSpots(List<Region> regions, double minDist) {
        for (int i = 0; i<regions.size()-1; ++i) {
            for (int j=i+1; j<regions.size(); ++j) {
                logger.debug("i: {}, j: {}, dist: {}, would remove last: {}", i, j, regions.get(i).getCenter().dist(regions.get(j).getCenter()), regions.get(i).getQuality()>=regions.get(j).getQuality());
                if (regions.get(i).getCenter().dist(regions.get(j).getCenter())<=minDist) {
                    if (regions.get(i).getQuality()>=regions.get(j).getQuality()) {
                        regions.remove(j);
                        --j;
                    } else {
                        regions.set(i, regions.get(j));
                        regions.remove(j);
                        j=i;
                    }
                }
            }
        }
    }
    private static List<Spot> fitAndSetQuality(Image radSym, Image smoothedIntensity, Image fitImage, List<Point> allSeeds, List<Point> seedsToSpots, double typicalSigma) {
        if (seedsToSpots.isEmpty()) return Collections.emptyList();
        Offset off = !fitImage.sameDimensions(radSym) ? new SimpleOffset(radSym).translate(fitImage.getBoundingBox().reverseOffset()) : null;
        if (off!=null) allSeeds.forEach(p->p.translate(off)); // translate point to fitImages bounds
        long t0 = System.currentTimeMillis();
        GaussianFit.GaussianFitConfig config = new GaussianFit.GaussianFitConfig(typicalSigma, false, true).setMaxCenterDisplacement(Math.max(1.5, typicalSigma/2)).setFittingBoxRadius((int)Math.ceil(4*typicalSigma+1)).setCoFitDistance(4*typicalSigma+1);
        Map<Point, double[]> fit = GaussianFit.run(fitImage, allSeeds, config, null, false);
         long t1 = System.currentTimeMillis();
        //logger.debug("spot fitting: {}ms / spot", ((double)(t1-t0))/allSeeds.size());
        List<Spot> res = seedsToSpots.stream().map(fit::get).filter(Objects::nonNull).map(d -> GaussianFit.spotMapper.apply(d, false, fitImage))
                .filter(s -> !Double.isNaN(s.getRadius()) || s.getRadius()<1)
                .filter(s -> !Double.isNaN(s.getIntensity()))
                .filter(s -> s.getCenter().isValid())
                .filter(s->s.getBounds().isValid())
                .collect(Collectors.toList());
        if (off!=null) {
            allSeeds.forEach(p->p.translateRev(off)); // translate back
            Offset rev = off.reverseOffset();
            res.forEach(p->p.translate(rev));
        }
        BoundingBox bounds = radSym.getBoundingBox().resetOffset();
        for (Spot o : res) { // quality criterion : sqrt (smooth * radSym)
            Point center = bounds.contains(o.getCenter()) ? o.getCenter() : o.getCenter().duplicate().ensureWithinBounds(bounds);
            double zz = center.numDimensions()>2?center.get(2):0;
            o.setQuality(Math.sqrt(radSym.getPixel(center.get(0), center.get(1), zz) * smoothedIntensity.getPixel(center.get(0), center.get(1), zz)));
        }
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }


    protected boolean verboseManualSeg;
    public void setManualSegmentationVerboseMode(boolean verbose) {
        this.verboseManualSeg=verbose;
    }

    private static SegmentedObject getParent(SegmentedObject parent, int objectClassIdx) {
        int parentIdx = parent.getExperimentStructure().getParentObjectClassIdx(objectClassIdx);
        if (parent.getStructureIdx()==parentIdx) return parent;
        else return parent.getParent(parentIdx);
    }

    @Override
    public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedObjects) {
        this.pv.initPV(input, segmentationMask, smoothScale.getValue().doubleValue()) ;
        if (pv.smooth==null || pv.radialSymmetry==null) setMaps(computeMaps(parent.getRawImage(objectClassIdx), input), Double.NaN);
        else logger.debug("manual seg: maps already set!");
        Image radialSymmetryMap = pv.getRadialSymmetryMap();
        Image smooth = pv.getSmoothedMap();
        Image fitImage = getParent(parent, objectClassIdx).getPreFilteredImage(objectClassIdx);
        List<Point> allObjects = parent.getChildren(objectClassIdx)
                .map(o->o.getRegion().getCenter().duplicate().translateRev(parent.getBounds()))
                .collect(Collectors.toList());

        allObjects.addAll(seedObjects);
        List<Spot> segmentedSpots = fitAndSetQuality(radialSymmetryMap, smooth, fitImage, allObjects, seedObjects, typicalSigma.getValue().doubleValue());
        RegionPopulation pop = new RegionPopulation(segmentedSpots, smooth);
        pop.sortBySpatialOrder(ObjectOrderTracker.IndexingOrder.YXZ);
        if (verboseManualSeg) {
            Image seedMap = new ImageByte("seeds from: "+input.getName(), input);
            for (Point p : seedObjects) seedMap.setPixel(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2), 1);
            Core.showImage(seedMap);
            Core.showImage(radialSymmetryMap.setName("Radial Symmetry (watershedMap). "));
            Core.showImage(smooth.setName("Smoothed Scale: "+smoothScale.getValue().doubleValue()));
        }
        return pop;
    }

    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        Image wsMap = FastRadialSymmetryTransformUtil.runTransform(input, radii.getArrayDouble(), FastRadialSymmetryTransformUtil.fluoSpotKappa, false, FastRadialSymmetryTransformUtil.GRADIENT_SIGN.POSITIVE_ONLY, 1.5, 1, 0);
        wsMap = object.isAbsoluteLandMark() ? wsMap.cropWithOffset(object.getBounds()) : wsMap.crop(object.getBounds());
        RegionPopulation res =  WatershedObjectSplitter.splitInTwoSeedSelect(wsMap, object.getMask(), true, true, manualSplitVerbose);
        res.translate(object.getBounds(), object.isAbsoluteLandMark());
        return res;
    }

    boolean manualSplitVerbose;
    @Override
    public void setSplitVerboseMode(boolean verbose) {
        manualSplitVerbose=verbose;
    }

    // track parametrizable
    /**
     * Compute Maps on parent image 
     * {@link #runSegmenter(Image, int, SegmentedObject) } is supposed to be called from bacteria, thus to avoid border effect gaussian smooth and laplacian transform should be computed on microchannel images
     * @param structureIdx
     * @param parentTrack
     * @return 
     */
    @Override
    public TrackConfigurer<SpotDetector> run(int structureIdx, List<SegmentedObject> parentTrack) {
        if (parentTrack.isEmpty()) return (p, s) -> {};
        Map<SegmentedObject, Image[]> parentMapImages = parentTrack.stream().parallel().collect(Collectors.toMap(p->p, p->computeMaps(p.getRawImage(structureIdx), p.getPreFilteredImage(structureIdx))));
        return (p, s) -> s.setMaps(parentMapImages.get(p), getScale(structureIdx, parentTrack));
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    Map<SegmentedObject, double[]> parentSegTHMapmeanAndSigma;
    protected Image[] computeMaps(Image rawSource, Image filteredSource) {
        double smoothScale = this.smoothScale.getValue().doubleValue();
        Image[] maps = new Image[2];
        Function<Image, Image> gaussF = f->ImageFeatures.gaussianSmooth(f, smoothScale, false).setName("gaussian: "+smoothScale);
        maps[0] = planeByPlane && filteredSource.sizeZ()>1 ? ImageOperations.applyPlaneByPlane(filteredSource, gaussF) : gaussF.apply(filteredSource); //
        Function<Image, Image> symF = f->FastRadialSymmetryTransformUtil.runTransform(f, radii.getArrayDouble(), FastRadialSymmetryTransformUtil.fluoSpotKappa, false, FastRadialSymmetryTransformUtil.GRADIENT_SIGN.POSITIVE_ONLY, 0.5,1, 0, 1.5);
        maps[1] = planeByPlane && filteredSource.sizeZ()>1 ? ImageOperations.applyPlaneByPlane(filteredSource, symF) : symF.apply(filteredSource);
        return maps;
    }
    private double getScale(int structureIdx, List<SegmentedObject> parentTrack) {
        switch (normMode.getSelectedEnum()) {
            case NO_NORM:
            default: {
                return 1;
            }
            case PER_CELL: {
                return Double.NaN;
            }
            case GLOBAL: {
                int segmentationParentIdx = parentTrack.get(0).getExperimentStructure().getSegmentationParentObjectClassIdx(structureIdx);
                int parentIdx = parentTrack.get(0).getStructureIdx();
                Stream<SegmentedObject> allParents = parentIdx==segmentationParentIdx ? parentTrack.stream() : SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), segmentationParentIdx);
                return ArrayUtil.median(allParents.parallel().mapToDouble(p -> {
                    double[] ms = new double[3];
                    BackgroundThresholder.runThresholder(p.getParent(parentIdx).getPreFilteredImage(structureIdx), p.getMask(), 2, 2, 3, Double.MAX_VALUE, ms);
                    return ms[1];
                }).toArray());
            }
        }
    }
    
    protected void setMaps(Image[] maps, double scale) {
        if (maps==null) return;
        if (maps.length!=2) throw new IllegalArgumentException("Maps should be of length 2");
        this.pv.smooth=maps[0];
        this.pv.radialSymmetry=maps[1];
        this.pv.globalScale = scale;
    }
    
}
