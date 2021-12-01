package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.plugins.Segmenter;
import bacmman.plugins.Thresholder;
import bacmman.plugins.plugins.thresholders.ConstantValue;
import bacmman.processing.Filters;
import bacmman.processing.ImageOperations;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.processing.neighborhood.CylindricalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SpatzcellsSpotSegmenter implements Segmenter {
    ObjectClassParameter rawOC = new ObjectClassParameter("Raw Object Class", -1, true, false).setAutoConfiguration(ObjectClassParameter.defaultAutoConfiguration()).setEmphasized(true).setHint("Select the object class associated to the raw image. In case no object class is selected the raw image of the current object class will be used for fitting. In all case, the pre-filtered image of the current object class is used for local-maxima detection only");
    PluginParameter<Thresholder> localMaximaThreshold = new PluginParameter<>("Threshold", Thresholder.class, new ConstantValue(), false).setHint("Threshold value over which local maxima are included in the fitting procedure").setEmphasized(true);
    BoundedNumberParameter localMaximaRadius = new BoundedNumberParameter("Local Maxima Radius", 3, 1.5, 1, null).setHint("Related to peak connectivity in the original algorithm. Number of neighboring pixels the maxima is relative to. <br />Set 1 for a 4-connectivity and 1.5 for a 8-connectivity. Set a higher value for larger spots").setEmphasized(true);
    BoundedNumberParameter maxSpotXYDistance = new BoundedNumberParameter("Maximum Spot x-y Distance", 1, 2, 0, null).setHint("Maximum xy distance (in pixels) within which spots can be matched, when matching spots across z-slices.");
    BoundedNumberParameter minZSliceNumber = new BoundedNumberParameter("Minimum Z-slice Number", 0, 2, 1, null).setEmphasized(true).setHint("Minimum number of z-slices which spots are required to appear consecutively.");
    BoundedNumberParameter fittingBox = new BoundedNumberParameter("Size of Fitting Box", 0, 5, 2, null).setEmphasized(true).setHint("Radius (in pixels) of square region in which to include data for fitting.");

    enum SPOT_RADIUS_ESTIMATION {ACQUISITION_PARAMETERS, CONSTANT_VALUE}
    EnumChoiceParameter<SPOT_RADIUS_ESTIMATION> spotRadiusEstimation = new EnumChoiceParameter<>("Spot Initial Radius Estimation Method", SPOT_RADIUS_ESTIMATION.values(), SPOT_RADIUS_ESTIMATION.ACQUISITION_PARAMETERS).setEmphasized(true);
    BoundedNumberParameter wavelength = new BoundedNumberParameter("Wavelength", 0, 632, 200, 800).setEmphasized(true).setHint("Wavelength (in nm) of the acquired light used to estimate the 2D Gaussian fitting parameters.");
    BoundedNumberParameter na = new BoundedNumberParameter("Numerical Aperture", 3, 1.45, 0.1, 2).setEmphasized(true).setHint("Numerical aperture used to estimate 2D Gaussian fitting parameters.");
    BoundedNumberParameter spotRadius = new BoundedNumberParameter("Typical Spot Radius", 2, 2.5, 1, null).setEmphasized(true).setHint("Typical spot radius (in pixels) use to initialize 2D Gaussian fitting parameters.");
    BooleanParameter useCalibration = new BooleanParameter("Use Calibration", false).setHint("Get pixel size from image calibration").setEmphasized(true);
    BoundedNumberParameter pixelSize = new BoundedNumberParameter("Pixel Size", 3, 106, 1, null).setHint("Pixel size (nm)").setEmphasized(true);
    ConditionalParameter<Boolean> useCalibrationCond = new ConditionalParameter<>(useCalibration).setActionParameters(false, pixelSize);
    ConditionalParameter<SPOT_RADIUS_ESTIMATION> spotRadiusEstimationCond = new ConditionalParameter<>(spotRadiusEstimation).setActionParameters(SPOT_RADIUS_ESTIMATION.ACQUISITION_PARAMETERS, wavelength, na, useCalibrationCond).setActionParameters(SPOT_RADIUS_ESTIMATION.CONSTANT_VALUE, spotRadius);

    BooleanParameter fitCenterAndAxesOnFilteredImage = new BooleanParameter("Fit Center And Axis On Filtered Image", true).setEmphasized(true).setHint("If true the center and axis of the ellipse will be fitted on the filtered image (used to detect local maxima) and afterwards the intensity only is fit on the raw image. <br />If false, center, axis and intensity are fit on the raw image");
    BooleanParameter fitEllipse = new BooleanParameter("Fit Ellipse", true).setHint("If False, a circular 2D Gaussian is fitted (more robust but less precise if observed spots are not circular)");
    BooleanParameter fitBackgroundPlane = new BooleanParameter("Fit Background Plane", true).setHint("If False, background is fitted as a simple constant is fitted");
    BoundedNumberParameter maxIterations = new BoundedNumberParameter("Max Iterations", 0, 300, 1, null).setHint("Stop and return after this many iterations if not done.");
    BoundedNumberParameter lambda = new BoundedNumberParameter("Lambda", 6, 0.001, 1e-6, 0.1).setHint("Blend between steepest descent (lambda high) and jump to bottom of quadratic (lambda zero). Start with 0.001.");
    BoundedNumberParameter termEpsilon = new BoundedNumberParameter("Termination accuracy", 6, 0.01, 1e-6, 0.1).setHint("Termination accuracy (0.01)");
    GroupParameter fitParameters = new GroupParameter("Fit Parameters", fitEllipse, fitBackgroundPlane, maxIterations, lambda, termEpsilon);

    BoundedNumberParameter maxMajor = new BoundedNumberParameter("Maximum Major Axis", 3, 0, 0, null).setEmphasized(true).setHint("Spots with major axis greater than this value will be erased. If 0: not taken into account");
    BoundedNumberParameter minMinor = new BoundedNumberParameter("Minimum Minor Axis", 3, 0, 0, null).setEmphasized(true).setHint("Spots with minor axis lower than this value will be erased. If 0: not taken into account");
    BoundedNumberParameter minIntensity = new BoundedNumberParameter("Minimum Intensity", 3, 0, 0, null).setEmphasized(true).setHint("Spots with Intensity lower than this value will be erased. If 0: not taken into account");
    GroupParameter filters = new GroupParameter("Filters", maxMajor, minMinor, minIntensity).setEmphasized(true).setHint("Filters to erase outliers spots");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{rawOC, localMaximaThreshold, localMaximaRadius, maxSpotXYDistance, minZSliceNumber, fittingBox, spotRadiusEstimationCond, fitCenterAndAxesOnFilteredImage, fitParameters, filters};
    }

    public double getTypicalSpotRadius(double pixelSizeInMicrons) {
        switch (spotRadiusEstimation.getSelectedEnum()) {
            case CONSTANT_VALUE:
            default: {
                return spotRadius.getValue().doubleValue();
            } case ACQUISITION_PARAMETERS: {
                double value = 0.61 * wavelength.getValue().doubleValue() / (na.getValue().doubleValue());
                if (useCalibration.getSelected()) return value / (1000 * pixelSizeInMicrons);
                return value / pixelSize.getValue().doubleValue();
            }
        }
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        int rawOC = this.rawOC.getSelectedClassIdx();
        Image raw = rawOC<0 ? parent.getRawImage(objectClassIdx) : parent.getRawImage(rawOC);
        if (rawOC>=0) assert raw.sizeZ() == input.sizeZ() : "Slice number of raw object class do not match with current object class";
        Image fitImage = this.fitCenterAndAxesOnFilteredImage.getSelected() ? input : raw;
        // get 2D local maxima
        Function<Image, Image> lmFun = im ->  Filters.localExtrema(im, null, true, parent.getMask(), Filters.getNeighborhood(1.5, 1, im));
        Image localMaxima = ImageOperations.applyPlaneByPlane(input, lmFun);
        // remove lm under threshold
        double threshold = localMaximaThreshold.instantiatePlugin().runThresholder(input, parent);
        BoundingBox.LoopPredicate lp = (x, y, z) -> input.getPixel(x, y, z)<threshold;
        ImageMask.loop(localMaxima, (x, y, z)->localMaxima.setPixel(x, y, z, 0), lp);

        // match 2D lm among slices
        Neighborhood n = new CylindricalNeighborhood(maxSpotXYDistance.getValue().doubleValue(), 1, 0, true);
        List<Region> regionsLM = Arrays.asList(ImageLabeller.labelImage(localMaxima, n));
        Map<Integer, List<Point>> allLMPerSlice = regionsLM.stream().flatMap(r -> r.getVoxels().stream()).map(v -> Point.asPoint((Offset)v)).collect(Collectors.groupingBy(p -> (int)p.get(2)));

        // filter lm with too few slices
        int minZSliceNumber = this.minZSliceNumber.getValue().intValue();
        if (minZSliceNumber>1) regionsLM = regionsLM.stream().filter(r -> r.getVoxels().size()>=minZSliceNumber).collect(Collectors.toList());
        logger.debug("number of local extrema found: {}", regionsLM.size());

        // among regionsLM get slice of max intensity
        Map<Region, Point> maxSlice = regionsLM.stream().collect(Collectors.toMap(Function.identity(), r -> Point.asPoint((Offset)r.getVoxels().stream().max(Comparator.comparingDouble(v -> input.getPixel(v.x, v.y, v.z))).get())));
        //maxSlice.forEach((r, p)-> logger.debug("region: {} max = {}", r.getVoxels(), p));
        // per slice : fit only maximum points + neighboring points
        Map<Integer, List<Point>> lmMaxPerSlice = maxSlice.values().stream().collect(Collectors.groupingBy(p -> (int)p.get(2)));
        double minDist = fittingBox.getValue().doubleValue();
        double typicalRad = getTypicalSpotRadius(input.getScaleXY());
        Map<Point, double[]> fit = new HashMap<>();
        int maxIter = maxIterations.getValue().intValue();
        double lambda = this.lambda.getValue().doubleValue();
        double eps = termEpsilon.getValue().doubleValue();
        lmMaxPerSlice.forEach((slice, lmMax) -> {
            List<Point> lmClose;
            if (allLMPerSlice.containsKey(slice)) {
                ToDoubleFunction<Point> minDistFun = p -> lmMax.stream().min(Comparator.comparingDouble(pp -> pp.distSq(p))).get().dist(p);
                lmClose = allLMPerSlice.get(slice).stream().filter(p -> minDistFun.applyAsDouble(p)<=minDist).collect(Collectors.toList());
            } else lmClose = new ArrayList<>();
            logger.debug("slice: {}, run max: {} with close spots: {}", slice, lmMax, lmClose);
            Map<Point, double[]> fitSlice = GaussianFit.run(fitImage.getZPlane(slice), lmClose, typicalRad, fittingBox.getValue().intValue(), fittingBox.getValue().intValue(), fitEllipse.getSelected(), fitBackgroundPlane.getSelected(), true, null, true, true, maxIter, lambda, eps);
            if (fitCenterAndAxesOnFilteredImage.getSelected()) { // fit only intensity on raw image
                fitSlice = GaussianFit.run(raw.getZPlane(slice), lmClose, typicalRad, fittingBox.getValue().intValue(), fittingBox.getValue().intValue(), fitEllipse.getSelected(), fitBackgroundPlane.getSelected(), true, fitSlice, false, false, maxIter, lambda, eps);
            }
            logger.debug("slice: {} resulting fit: {}", slice, Utils.toStringMap(fitSlice, Object::toString, p->new GaussianFit.FitParameter(p, 2, fitEllipse.getSelected(), fitBackgroundPlane.getSelected()).toString()));
            for (Point p : lmMax) {
                logger.debug("adding fit: {} @ {}", fitSlice.get(p), p);
                fit.put(p, fitSlice.get(p));
            }
        });

        // convert fitted parameter to Region objects
        MutableBoundingBox bds=new MutableBoundingBox(input.getZPlane(0));
        IntFunction<ImageProperties> getIP = z -> new SimpleImageProperties(bds.setzMin(z).setzMax(z), input.getScaleXY(), input.getScaleZ());
        List<Region> regions;
        if (fitEllipse.getSelected()) regions = fit.entrySet().stream().map(e -> GaussianFit.ellipse2DMapper.apply(e.getValue(), fitBackgroundPlane.getSelected(),getIP.apply(e.getKey().zMin()))).collect(Collectors.toList());
        else regions = fit.entrySet().stream().map(e -> GaussianFit.spotMapper.apply(e.getValue(), fitBackgroundPlane.getSelected(), getIP.apply(e.getKey().zMin()))).collect(Collectors.toList());
        if (input.sizeZ()>1) regions.forEach(r -> r.setIs2D(false)); // 3D objects: localized in 3D space
        regions.forEach(r -> logger.debug("region: B = {}, C = {}, I= {} Radius: {}", r.getBounds(), r.getCenter(), (r instanceof Ellipse2D) ? ((Ellipse2D)r).getIntensity():((Spot)r).getIntensity(), (r instanceof Ellipse2D) ? ((Ellipse2D)r).getMajor():((Spot)r).getRadius()));

        // filters
        double maxMajor = this.maxMajor.getValue().doubleValue();
        double minMinor = this.minMinor.getValue().doubleValue();
        double minIntensity = this.minIntensity.getValue().doubleValue();
        if (maxMajor>0 || minMinor>0 || minIntensity>0) {
            Stream<Region> stream = regions.stream();
            if (maxMajor > 0) stream = stream.filter(r -> (r instanceof Ellipse2D) ? ((Ellipse2D) r).getMajor() < maxMajor : ((Spot) r).getRadius() < maxMajor);
            if (minMinor > 0) stream = stream.filter(r -> (r instanceof Ellipse2D) ? ((Ellipse2D) r).getMinor() > minMinor : ((Spot) r).getRadius() > minMinor);
            if (minIntensity > 0) stream = stream.filter(r -> (r instanceof Ellipse2D) ? ((Ellipse2D) r).getIntensity() > minIntensity : ((Spot) r).getIntensity() > minIntensity);
            regions = stream.collect(Collectors.toList());
        }
        return new RegionPopulation(regions, input);
    }

}
