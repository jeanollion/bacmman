package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.plugins.*;
import bacmman.plugins.plugins.thresholders.FactorOfMean;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageLabeller;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SPTSpotDetector implements Segmenter, TestableProcessingPlugin {
    public final static Logger logger = LoggerFactory.getLogger(SPTSpotDetector.class);

    PluginParameter<Thresholder> thld = new PluginParameter<>("Threshold Method", Thresholder.class, new FactorOfMean().setFactor(6), false);
    BoundedNumberParameter localMaxRadius = new BoundedNumberParameter("Local Max Radius", 5, 4, 1, null).setEmphasized(true).setHint("Radius (in pixels) for local max transform");
    BoundedNumberParameter lmSmooth = new BoundedNumberParameter("Local Max Smooth", 5, 1, 0, null).setEmphasized(true).setHint("Radius of Gaussian smoothing applied to input image before computing local maxima");

    BoundedNumberParameter fittingBox = new BoundedNumberParameter("Size of Fitting Box", 0, 4, 2, null).setEmphasized(true).setHint("Radius (in pixels) of square region in which to include data for fitting.");
    BoundedNumberParameter clusterDist = new BoundedNumberParameter("Cluster Distance", 5, 7, 2, null).setEmphasized(true).setHint("When several peaks are closer than this distance, they are fitted together, on an area that is the union of area of all close peaks");
    BooleanParameter fitEllipse = new BooleanParameter("Fit Ellipse", true).setHint("If False, a circular 2D Gaussian is fitted (more robust but less precise if observed spots are not circular)");
    BooleanParameter fitBackgroundPlane = new BooleanParameter("Fit Background Plane", false).setHint("If False, background is fitted as a simple constant");

    BoundedNumberParameter maxIterations = new BoundedNumberParameter("Max Iterations", 0, 300, 1, null).setHint("Stop and return after this many iterations if not done.");
    BoundedNumberParameter lambda = new BoundedNumberParameter("Lambda", 6, 0.001, 1e-6, 0.1).setHint("Blend between steepest descent (lambda high) and jump to bottom of quadratic (lambda zero). Start with 0.001.");
    BoundedNumberParameter termEpsilon = new BoundedNumberParameter("Termination accuracy", 6, 0.01, 1e-6, 0.1).setHint("Termination accuracy (0.01)");
    BoundedNumberParameter maxMajor = new BoundedNumberParameter("Maximum Major Axis", 3, 0, 0, null).setEmphasized(true).setHint("Spots with major axis greater than this value will be erased. If circles are fitted, this threshold is compared to the diameter. If 0: not taken into account");
    BoundedNumberParameter minMinor = new BoundedNumberParameter("Minimum Minor Axis", 3, 0, 0, null).setEmphasized(true).setHint("Spots with minor axis lower than this value will be erased. If circles are fitted, this threshold is compared to the diameter. If 0: not taken into account");

    GroupParameter fitParameters = new GroupParameter("Fit Parameters", fittingBox, clusterDist, fitEllipse, fitBackgroundPlane, maxIterations, lambda, termEpsilon);
    GroupParameter filters = new GroupParameter("Filters", maxMajor, minMinor);
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{thld, localMaxRadius, lmSmooth, fitParameters, filters};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        logger.debug("input image: {}", input);
        // compute thld
        double thld = this.thld.instantiatePlugin().runThresholder(input, parent);
        if (stores!=null) Core.userLog("Threshold: "+thld);
        logger.debug("thld : {}", thld);
        // get local maxima
        Image inputLM = lmSmooth.getDoubleValue()>0 ? ImageFeatures.gaussianSmooth(input, lmSmooth.getDoubleValue(), 0, false) : input;
        ImageByte lm = Filters.localExtrema(inputLM, null, true, thld, null, Filters.getNeighborhood(localMaxRadius.getDoubleValue(), 1, input));
        List<Point> centers = ImageLabeller.labelImageList(lm).stream().map(r -> r.getGeomCenter(false)).collect(Collectors.toList());
        logger.debug("local maxima: {}", centers.size());
        // perform elliptical gaussian fit
        int maxIter = maxIterations.getValue().intValue();
        double lambda = this.lambda.getValue().doubleValue();
        double eps = termEpsilon.getValue().doubleValue();
        Map<Point, double[]> fit = GaussianFit.run(input, centers, localMaxRadius.getDoubleValue()/2, Math.max(localMaxRadius.getDoubleValue()/2, 1), fittingBox.getValue().intValue(), clusterDist.getValue().intValue(), fitEllipse.getSelected(), fitBackgroundPlane.getSelected(), true, null, true, true, maxIter, lambda, eps, false);

        List<Region> regions;
        if (fitEllipse.getSelected()) regions = fit.entrySet().stream().map(e -> GaussianFit.ellipse2DMapper.apply(e.getValue(), fitBackgroundPlane.getSelected(),input)).peek(e -> e.setQuality(e.getIntensity())).collect(Collectors.toList());
        else regions = fit.entrySet().stream().map(e -> GaussianFit.spotMapper.apply(e.getValue(), fitBackgroundPlane.getSelected(), input)).peek(e -> e.setQuality(e.getIntensity())).collect(Collectors.toList());
        logger.debug("number of spots before filtering: {}", regions.size());
        // filters
        double maxMajor = this.maxMajor.getValue().doubleValue();
        double minMinor = this.minMinor.getValue().doubleValue();
        if (maxMajor>0 || minMinor>0) {
            Stream<Region> stream = regions.stream();
            if (maxMajor > 0) stream = stream.filter(r -> (r instanceof Ellipse2D) ? ((Ellipse2D) r).getMajor() < maxMajor : ((Spot) r).getRadius()*2 < maxMajor);
            if (minMinor > 0) stream = stream.filter(r -> (r instanceof Ellipse2D) ? ((Ellipse2D) r).getMinor() > minMinor : ((Spot) r).getRadius()*2 > minMinor);
            regions = stream.collect(Collectors.toList());
        }
        logger.debug("number of spots after filtering: {}", regions.size());
        return new RegionPopulation(regions, input);
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }
}
