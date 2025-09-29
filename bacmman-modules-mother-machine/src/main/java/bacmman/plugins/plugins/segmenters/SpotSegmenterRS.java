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
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.plugins.plugins.thresholders.ConstantValue;
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
public class SpotSegmenterRS implements Segmenter, TrackConfigurable<SpotSegmenterRS>, ManualSegmenter, ObjectSplitter, TestableProcessingPlugin, Hint, HintSimple, MultiThreaded {
    public static boolean debug = false;
    public final static Logger logger = LoggerFactory.getLogger(SpotSegmenterRS.class);
    // scales
    NumberParameter smoothScale = new BoundedNumberParameter("Smooth globalScale", 5, 1.5, 0, 5).setHint("Scale (in pixels) for gaussian smooth <br />Configuration hint: determines the <em>Gaussian</em> image displayed in test mode");
    BoundedNumberParameter radius = new BoundedNumberParameter("Radius", 1, 5, 1, null);
    ArrayNumberParameter radii = new ArrayNumberParameter("Radial Symmetry Radii", 0, radius).setSorted(true).setValue(1, 2, 3, 4).setHint("Radii used in the transformation. <br />Low values tend to add noise and detect small objects, high values tend to remove details and detect large objects");
    NumberParameter typicalSigma = new BoundedNumberParameter("Typical sigma", 1, 2, 1, null).setHint("Typical sigma of spot when fitted by a gaussian. Gaussian fit will be performed on an area of span 2 * σ +1 around the center. When two (or more) spot have spans that overlap, they are fitted together");
    ScaleXYZParameter maxLocalRadius = new ScaleXYZParameter("Max local radius", 1.5, 1.5 * 3, false).setNumberParameters(1, null, 1, true, true).setHint("Radius of local maxima filter for seed detection step. Increasing the value will decrease false positive spots but decrease the capacity to segment close spots. <br/> This parameter also defines the z-anisotropy ratio used for gaussian fit and gaussian and radial symmetry transform");
    enum NORMALIZATION_MODE {NO_NORMALIZATION, GLOBAL, PER_CELL, PER_CELL_CENTER}
    EnumChoiceParameter<NORMALIZATION_MODE> normMode = new EnumChoiceParameter<>("Intensity normalization", NORMALIZATION_MODE.values(), NORMALIZATION_MODE.GLOBAL)
            .setHint("Normalization of the input intensity, will influence the values of <em>Radial Symmetry Threshold</em> and <em>Seed Threshold</em>.<br>STDEV and STDEV of intensity is computed within the cell, normalization is I -> (I - MEAN) / STDEV. <br>If a thresholder is set, only values under the threshold are considered to compute MEAN and STDEV. <br>GLOBAL mode uses a median STDEV in the parent cell line. <br>PER_CELL_CENTER only removes MEAN (caution: before 2025 march 19, this mode was called NO_NORM)");
    PluginParameter<bacmman.plugins.SimpleThresholder> normThresholder = new PluginParameter<>("Foreground Removal Thld", bacmman.plugins.SimpleThresholder.class, new BackgroundThresholder(6, 6, 2), true);
    ConditionalParameter<NORMALIZATION_MODE> normCond = new ConditionalParameter<>(normMode).setActionParameters(NORMALIZATION_MODE.GLOBAL, normThresholder).setActionParameters(NORMALIZATION_MODE.PER_CELL, normThresholder).setActionParameters(NORMALIZATION_MODE.PER_CELL_CENTER, normThresholder);
    NumberParameter maxSigma = new BoundedNumberParameter("Sigma Filter", 2, 4, 1, null);
    IntervalParameter sigmaRange = new IntervalParameter("Sigma Filter", 3, 0, null, 1, 5)
            .setLegacyParameter((p, i)->i.setValue(((NumberParameter)p[0]).getDoubleValue(), 1), maxSigma)
            .setHint("Spot with a sigma value (from the gaussian fit) outside this range will be erased.");
    enum LOCAL_MAX_MODE {RadialSymetry, Gaussian}

    public EnumChoiceParameter<LOCAL_MAX_MODE> localMaxMode = new EnumChoiceParameter<>("Local Max Image", LOCAL_MAX_MODE.values(), LOCAL_MAX_MODE.Gaussian).setHint("On which image local max filter will be run to detects seeds");

    //NumberParameter symmetryThresholdLegacy = new NumberParameter<>("Radial Symmetry Threshold", 2, 5).setEmphasized(true) // was 2.25
    PluginParameter<Thresholder> symmetryThreshold = new PluginParameter<>("Radial Symmetry Threshold", Thresholder.class, new BackgroundThresholder(3, 3, 2), false).setEmphasized(true).setHint("Radial Symmetry threshold for selection of watershed seeds.<br />Higher values tend to increase false negative detections and decrease false positive detection.<br /><br />Radial Symmetry transform allows to highlight spots in an image by estimating the local radial symmetry. Implementation of the algorithm described in Loy & Zelinsky, IEEE, 2003<br />  Configuration hint: refer to the <em>Radial Symmetry</em> image displayed in test mode");
    PluginParameter<Thresholder> intensityThreshold = new PluginParameter<>("Seed Threshold", Thresholder.class, new BackgroundThresholder(3, 3, 2), false).setEmphasized(true).setHint("Threshold on gaussian for selection of watershed seeds.<br /> Higher values tend to increase false negative detections and decrease false positive detections.<br />Configuration hint: refer to <em>Gaussian</em> image displayed in test mode"); // was 1.6
    NumberParameter minOverlap = new BoundedNumberParameter("Min Overlap %", 1, 20, 0, 100).setHint("When the center of a spot (after gaussian fit) is located oustide the parent object class (e.g. bacteria), the spot is erased if the overlap percentage with the bacteria is inferior to this value. (0%: spots are never erased)");

    enum QUALITY {sqGR, sqIR, GR, IR, G, I}
    EnumChoiceParameter<QUALITY> quality = new EnumChoiceParameter<>("Quality Formula", QUALITY.values(), QUALITY.IR).setLegacyInitializationValue(QUALITY.sqGR).setHint("G = gaussian value at center, I = fit Intensity (amplitude of fitted  gaussian), R = radial symetry transform value at center, sq = square root. e.g.: sqIR = sqrt(I x R)");

    Parameter[] parameters = new Parameter[]{symmetryThreshold, intensityThreshold, normCond, radii, smoothScale, localMaxMode, maxLocalRadius, typicalSigma, sigmaRange, minOverlap, quality};
    ProcessingVariables pv = new ProcessingVariables();
    boolean planeByPlane = false; // TODO set as parameter for "true" 3D images
    protected static String toolTipAlgo = "<br /><br /><em>Algorithmic Details</em>:<ul>"
            + "<li>Spots are detected by performing a gaussian fit on raw intensity at the location of <em>seeds</em>, defined as the regional maxima of the Radial Symmetry transform, within the mask of the segmentation parent. Selected seeds have a Radial Symmetry value larger than <em>Radial Symmetry Seed Threshold</em> and a Gaussian value superior to <em>Seed Threshold</em></li>"
            +"<li>A Gaussian Fit is performed on each spot: see <em>Typical Sigma</em> parameter. Segmented spots can be Filtered according to their radii: see <em>Sigma Filter</em> parameter</li>"
            + "<li>A quality parameter defined as √(Radial Symmetry x Gaussian) at the center of the spot is computed (e.g. used in <em>NestedSpotTracker</em>)</li></ul>" +
            "<br />In order to increase robustness to variations in the background fluorescence in bacteria, the input image can be first normalized by subtracting the mean value and dividing by the standard-deviation value of the background signal within the cell. See <em>Intensity normalization parameter</em><br/>Radial Symmetry & Gaussian transforms are then computed on the normalized image.";
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

    public SpotSegmenterRS() {}


    /**
     * See {@link #run(Image, int, SegmentedObject, Thresholder, Thresholder)}
     * @param input
     * @param objectClassIdx
     * @param parent
     * @return 
     */
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        return run(input, objectClassIdx, parent, symmetryThreshold.instantiatePlugin(), intensityThreshold.instantiatePlugin());
    }
    // testable
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }

    private static class ProcessingVariables {
        Image input;
        Image smooth, radialSymmetry;
        ImageByte localMax;
        boolean symScaled, smoothScaled;
        double[] ms;
        double smoothScale;
        double globalScale = Double.NaN;
        public void initPV(Image input, ImageMask mask, double smoothScale, NORMALIZATION_MODE norm, SimpleThresholder thlder) {
            this.input=input;
            this.smoothScale=smoothScale;
            if (ms == null) {
                if (NORMALIZATION_MODE.NO_NORMALIZATION.equals(norm)) {
                    ms = new double[]{0, 1};
                } else {
                    if (thlder instanceof BackgroundThresholder) {
                        ms = new double[2];
                        BackgroundThresholder bthlder  = (BackgroundThresholder)thlder;
                        BackgroundThresholder.runThresholder(input, mask, bthlder.getSigma(), bthlder.getFinalSigma(), bthlder.getIterations(), Double.MAX_VALUE, bthlder.symmetrical(), ms);
                    } else if (thlder != null) {
                        double thld = thlder.runSimpleThresholder(input, mask);
                        ms = ImageOperations.getMeanAndSigma(input, mask, d -> d<=thld);
                    } else {
                        ms = ImageOperations.getMeanAndSigma(input, mask, null);
                    }
                }
                if (NORMALIZATION_MODE.GLOBAL.equals(norm) && !Double.isNaN(globalScale)) ms[1] = globalScale;
                if (NORMALIZATION_MODE.PER_CELL_CENTER.equals(norm)) ms[1] = 1;
            }
        }

        public ImageByte getLocalMaxMap() {
            if (localMax==null) throw new RuntimeException("LocalMax map not initialized");
            if (!localMax.sameDimensions(input)) localMax = localMax.cropWithOffset(input.getBoundingBox());
            return localMax;
        }

        public Image getScaledInput() {
            return ImageOperations.affineOperation2WithOffset(input, null, 1/ms[1], -ms[0]).setName("Scaled Input");
        }
        protected Image getSmoothedMap() {
            if (smooth==null) throw new RuntimeException("Smooth map not initialized");
            if (!smoothScaled) {
                if (!smooth.sameDimensions(input)) smooth = smooth.cropWithOffset(input.getBoundingBox()); // map was computed on parent that differs from segmentation parent
                double s = Math.max(1, smoothScale);
                ImageOperations.affineOperation2WithOffset(smooth, smooth, s/ms[1], -ms[0]);
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
     * @param thresholderSeeds minimal laplacian value to segment a spot
     * @param intensityThresholder minimal gaussian value to segment a spot
     * @return segmented spots
     */
    public RegionPopulation run(Image input, int objectClassIdx, SegmentedObject parent, Thresholder thresholderSeeds, Thresholder intensityThresholder) {
        ImageMask parentMask = parent.getMask().sizeZ()!=input.sizeZ() ? new ImageMask2D(parent.getMask()) : parent.getMask();
        this.pv.initPV(input, parentMask, smoothScale.getValue().doubleValue(), normMode.getSelectedEnum(), normThresholder.instantiatePlugin());
        if (pv.smooth==null || pv.getRadialSymmetryMap()==null) throw new RuntimeException("Spot Segmenter not parametrized");//setMaps(computeMaps(input, input));
        //logger.debug("get maps..");
        Image smooth = pv.getSmoothedMap();
        Image radSym = pv.getRadialSymmetryMap();

        double thresholdSeeds = thresholderSeeds.runThresholder(radSym, parent);
        double intensityThreshold = intensityThresholder.runThresholder(smooth, parent);

        if (stores!=null) {
            if (!(thresholderSeeds instanceof ConstantValue)) Core.userLog("Radial Symmetry Threshold: "+thresholdSeeds);
            if (!(intensityThresholder instanceof ConstantValue)) Core.userLog("Intensity Threshold: "+intensityThreshold);
        }

        //logger.debug("local max...");
        final ImageByte seedMap = pv.getLocalMaxMap();
        //logger.debug("get seeds...");
        List<Point> seeds = new ArrayList<>();
        BoundingBox.loop(new SimpleBoundingBox(seedMap).resetOffset(),
                (x, y, z)->seeds.add(new Point(x, y, z)),
                (x, y, z)->seedMap.insideMask(x, y, z)
                        && smooth.getPixel(x, y, z)>=intensityThreshold
                        && radSym.getPixel(x, y, z)>=thresholdSeeds, parallel);
        seeds.removeIf(Objects::isNull);
        //logger.debug("get seeds done (parallel: {})", parallel);
        if (stores!=null) {
            //Image seeMapDisplay = new ImageByte("Seeds", seedMap);
            //seeds.forEach(p -> seeMapDisplay.setPixel(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2), 1));
            //stores.get(parent).addIntermediateImage("Seeds", seeMapDisplay);
            stores.get(parent).addIntermediateImage("Gaussian", smooth);
            stores.get(parent).addIntermediateImage("RadialSymmetryTransform", radSym);
        }
        List<Spot> segmentedSpots;
        Image fitImage = getParent(parent, objectClassIdx).getPreFilteredImage(objectClassIdx); // we need full image to fit. In case segmentation is occurring in segmentation parent, then fit will occur in parent

        BiConsumer<List<Spot>, List<Point>> removeSpotsFarFromSeeds = (spots, seedList) -> { // filter by distance from original seed
            Map<Spot, Double> distSqFromSeed = IntStream.range(0, spots.size()).mapToObj(i->i).collect(Collectors.toMap(spots::get, i->spots.get(i).getCenter().distSq(seedList.get((i)))));
            double maxDistSq = Math.pow(2 * typicalSigma.getValue().doubleValue(), 2);
            List<Spot> toRemove = distSqFromSeed.entrySet().stream().filter(e->e.getValue()>=maxDistSq || Double.isNaN(e.getValue()) || Double.isInfinite(e.getValue()) ).map(Map.Entry::getKey).collect(Collectors.toList());
            spots.removeAll(toRemove);
        };

        if (planeByPlane && seedMap.sizeZ()>1) {
            segmentedSpots = new ArrayList<>();
            List<Image> fitImagePlanes = fitImage.splitZPlanes();
            List<Image> smoothPlanes = smooth.splitZPlanes();
            List<Image> radSymPlanes = radSym.splitZPlanes();
            IntStream.range(0, fitImagePlanes.size()).forEachOrdered(z -> {
                List<Point> seedsZ = seeds.stream().filter(p -> p.get(2)==z).collect(Collectors.toList());
                List<Spot> segmentedSpotsZ = fitAndSetQuality(radSymPlanes.get(z), smoothPlanes.get(z), fitImagePlanes.get(z), seedsZ, seedsZ, typicalSigma.getDoubleValue(), typicalSigma.getDoubleValue() * getAnisotropyRatio(input), quality.getSelectedEnum(), parallel);
                removeSpotsFarFromSeeds.accept(segmentedSpotsZ, seedsZ);
                segmentedSpots.addAll(segmentedSpotsZ);
            });
            segmentedSpots.forEach(s->s.setIs2D(false));
        } else {
            //logger.debug("gaussian fit...");
            segmentedSpots =fitAndSetQuality(radSym, smooth, fitImage, seeds, seeds, typicalSigma.getDoubleValue(), typicalSigma.getDoubleValue() * getAnisotropyRatio(input), quality.getSelectedEnum(), parallel);
            //logger.debug("gaussian fit done.");
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
        double[] sigmaRange = this.sigmaRange.getValuesAsDouble();
        segmentedSpots.removeIf(s->s.getRadius()>sigmaRange[1] || s.getRadius()<sigmaRange[0]);
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
    private static List<Spot> fitAndSetQuality(Image radSym, Image smoothedIntensity, Image fitImage, List<Point> allSeeds, List<Point> seedsToSpots, double typicalSigma, double typicalSigmaZ, QUALITY formula, boolean parallel) {
        if (seedsToSpots.isEmpty()) return Collections.emptyList();
        Offset off = !fitImage.sameDimensions(radSym) ? new SimpleOffset(radSym).translate(fitImage.getBoundingBox().reverseOffset()) : null;
        if (off!=null) allSeeds.forEach(p->p.translate(off)); // translate point to fitImages bounds
        long t0 = System.currentTimeMillis();
        GaussianFit.GaussianFitConfig config = new GaussianFit.GaussianFitConfig(typicalSigma, typicalSigmaZ, true).setMaxCenterDisplacement(Math.max(1.5, typicalSigma/2))
                .setFittingBoxRadius((int)Math.ceil(2*typicalSigma+1))
                .setCoFitDistance(2*typicalSigma+1);
        Map<Point, double[]> fit = GaussianFit.run(fitImage, allSeeds, config, null, parallel);
         long t1 = System.currentTimeMillis();
        //logger.debug("spot fitting: {}ms / spot", ((double)(t1-t0))/allSeeds.size());
        List<Spot> res = seedsToSpots.stream().map(fit::get).filter(Objects::nonNull).map(d -> GaussianFit.spotMapper.apply(d, false, fitImage))
                .filter(s -> !Double.isNaN(s.getRadius()) || s.getRadius()<1)
                .filter(s -> !Double.isNaN(s.getIntensity()))
                .filter(s -> s.getCenter().isValid())
                .filter(s-> s.getBounds().isValid())
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
            double R = radSym.getPixel(center.get(0), center.get(1), zz);
            double I = o.getIntensity();
            double G = smoothedIntensity.getPixel(center.get(0), center.get(1), zz);
            double Q;
            switch (formula) {
                case I:
                    Q = I;
                    break;
                case G:
                    Q = G;
                    break;
                case IR:
                    Q = I * R;
                    break;
                case GR:
                    Q = G * R;
                    break;
                case sqGR:
                    Q = Math.sqrt(I * R);
                    break;
                case sqIR:
                default:
                    Q = Math.sqrt(G * R);
                    break;
            }
            o.setQuality(Q);
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
        this.pv.initPV(input, segmentationMask, smoothScale.getValue().doubleValue(), normMode.getSelectedEnum(), normThresholder.instantiatePlugin());
        if (pv.smooth==null || pv.radialSymmetry==null) setMaps(computeMaps(parent.getRawImage(objectClassIdx), input, parent.getMask(), false), Double.NaN);
        else logger.debug("manual seg: maps already set!");
        Image radialSymmetryMap = pv.getRadialSymmetryMap();
        Image smooth = pv.getSmoothedMap();
        Image fitImage = getParent(parent, objectClassIdx).getPreFilteredImage(objectClassIdx);
        List<Point> allObjects = parent.getChildren(objectClassIdx)
                .map(o->o.getRegion().getCenter().duplicate().translateRev(parent.getBounds()))
                .collect(Collectors.toList());

        allObjects.addAll(seedObjects);
        List<Spot> segmentedSpots = fitAndSetQuality(radialSymmetryMap, smooth, fitImage, allObjects, seedObjects, typicalSigma.getDoubleValue(), typicalSigma.getDoubleValue() * getAnisotropyRatio(input), quality.getSelectedEnum(), parallel);
        RegionPopulation pop = new RegionPopulation(segmentedSpots, smooth);
        pop.sortBySpatialOrder(ObjectOrderTracker.IndexingOrder.YXZ);
        if (verboseManualSeg) {
            Image seedMap = new ImageByte("seeds from: "+input.getName(), input);
            for (Point p : seedObjects) seedMap.setPixel(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2), 1);
            //Core.showImage(seedMap);
            Core.showImage(radialSymmetryMap.setName("Radial Symmetry (watershedMap). "));
            Core.showImage(smooth.setName("Smoothed Scale: "+smoothScale.getValue().doubleValue()));
        }
        return pop;
    }

    protected double getAnisotropyRatio(Image input) {
        return maxLocalRadius.getScaleZ(input.getScaleXY(), input.getScaleZ()) / maxLocalRadius.getScaleXY();
    }

    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
        Image wsMap = FastRadialSymmetryTransformUtil.runTransform(input, radii.getArrayDouble(), FastRadialSymmetryTransformUtil.fluoSpotKappa, false, FastRadialSymmetryTransformUtil.GRADIENT_SIGN.POSITIVE_ONLY, 1.5, 1, 0, parallel, 1.5, 1.5 * getAnisotropyRatio(input));
        wsMap = object.isAbsoluteLandMark() ? wsMap.cropWithOffset(object.getBounds()) : wsMap.crop(object.getBounds());
        RegionPopulation res =  WatershedObjectSplitter.splitInTwoSeedSelect(wsMap, object.getMask(), true, true, manualSplitVerbose, parallel);
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
    public TrackConfigurer<SpotSegmenterRS> run(int structureIdx, List<SegmentedObject> parentTrack) {
        if (parentTrack.isEmpty()) return (p, s) -> {};
        Function<SegmentedObject, ImageMask> getParentMask = p -> {
            int segParent = p.getExperimentStructure().getSegmentationParentObjectClassIdx(structureIdx);
            if (segParent == p.getStructureIdx()) return p.getMask();
            return p.getChildRegionPopulation(segParent).getLabelMap();
        };
        Map<SegmentedObject, Image[]> parentMapImages = parentTrack.stream().collect(Collectors.toMap(p->p, p->computeMaps(p.getRawImage(structureIdx), p.getPreFilteredImage(structureIdx), getParentMask.apply(p), true)));
        return (p, s) -> s.setMaps(parentMapImages.get(p), getScale(structureIdx, parentTrack));
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    protected Image[] computeMaps(Image rawSource, Image filteredSource, ImageMask parentMask, boolean localMax) {
        double smoothScale = this.smoothScale.getValue().doubleValue();
        Image[] maps = new Image[3];
        Function<Image, Image> gaussF = smoothScale > 0 ? f->ImageDerivatives.gaussianSmooth(f, ImageDerivatives.getScaleArray(smoothScale, smoothScale * getAnisotropyRatio(rawSource), rawSource), parallel).setName("gaussian: "+smoothScale) : f -> TypeConverter.toFloat(f, null, true);
        maps[0] = planeByPlane && filteredSource.sizeZ()>1 && smoothScale > 0 ? ImageOperations.applyPlaneByPlane(filteredSource, gaussF) : gaussF.apply(filteredSource); //
        Function<Image, Image> symF = f->FastRadialSymmetryTransformUtil.runTransform(f, radii.getArrayDouble(), FastRadialSymmetryTransformUtil.fluoSpotKappa, false, FastRadialSymmetryTransformUtil.GRADIENT_SIGN.POSITIVE_ONLY, 0.5,1, 0, parallel, 1.5, 1.5 * getAnisotropyRatio(rawSource));
        maps[1] = planeByPlane && filteredSource.sizeZ()>1 ? ImageOperations.applyPlaneByPlane(filteredSource, symF) : symF.apply(filteredSource);
        if (parentMask.sizeZ() == 1 && filteredSource.sizeZ() > 1) parentMask = new ImageMask2D(parentMask);
        if (localMax) {
            Neighborhood n = Filters.getNeighborhood(maxLocalRadius.getScaleXY(), maxLocalRadius.getScaleZ(rawSource.getScaleXY(), rawSource.getScaleZ()), filteredSource);
            switch (localMaxMode.getSelectedEnum()) {
                case RadialSymetry:
                default:
                    maps[2] = Filters.localExtrema(maps[1], null, true, parentMask, n, false);
                    break;
                case Gaussian:
                    maps[2] = Filters.localExtrema(maps[0], null, true, parentMask, n, false);
            }
        }
        return maps;
    }

    private double getScale(int structureIdx, List<SegmentedObject> parentTrack) {
        switch (normMode.getSelectedEnum()) {
            case NO_NORMALIZATION:
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
                SimpleThresholder thlder = normThresholder.instantiatePlugin();
                return ArrayUtil.median(allParents.parallel().mapToDouble(p -> {
                    Image input = p.getParent(parentIdx).getPreFilteredImage(structureIdx);
                    ImageMask mask = p.getMask();
                    double[] ms;
                    if (thlder instanceof BackgroundThresholder) {
                        ms = new double[2];
                        BackgroundThresholder bthlder  = (BackgroundThresholder)thlder;
                        double thld = BackgroundThresholder.runThresholder(input, mask, bthlder.getSigma(), bthlder.getFinalSigma(), bthlder.getIterations(), Double.MAX_VALUE, bthlder.symmetrical(), ms);
                    } else if (thlder != null) {
                        double thld = thlder.runSimpleThresholder(input, mask);
                        ms = ImageOperations.getMeanAndSigma(input, mask, d -> d<=thld);
                    } else {
                        ms = ImageOperations.getMeanAndSigma(input, mask, null);
                    }
                    return ms[1];
                }).toArray());
            }
        }
    }
    
    protected void setMaps(Image[] maps, double scale) {
        if (maps==null) return;
        if (maps.length!=3) throw new IllegalArgumentException("Maps should be of length 2");
        this.pv.smooth=maps[0];
        this.pv.radialSymmetry=maps[1];
        this.pv.globalScale = scale;
        this.pv.localMax = maps[2]==null? null : (ImageByte)maps[2];
    }

    boolean parallel = false;

    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }
}
