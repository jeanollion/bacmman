package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.plugins.*;
import bacmman.plugins.plugins.pre_filters.BandPass;
import bacmman.processing.Filters;
import bacmman.processing.ImageLabeller;
import bacmman.processing.ImageOperations;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EKLSpotDetector implements Segmenter, TestableProcessingPlugin {
    public final static Logger logger = LoggerFactory.getLogger(EKLSpotDetector.class);

    BooleanParameter useThldFactor = new BooleanParameter("Default Threshold Method", true).setEmphasized(true);
    PluginParameter<Thresholder> thld = new PluginParameter<>("Threshold Method", Thresholder.class, false);
    BoundedNumberParameter thldFactor = new BoundedNumberParameter("Intensity Threshold Factor", 5, 10, 0, null).setEmphasized(true).setHint("Final threshold on intensity is mean(I) * this value");
    ConditionalParameter<Boolean> thldMethod = new ConditionalParameter<>(useThldFactor).setActionParameters(true, thldFactor).setActionParameters(false, thld);
    BoundedNumberParameter localMaxRadius = new BoundedNumberParameter("Local Max Radius", 5, 4, 1, null).setEmphasized(true).setHint("Radius (in pixels) for local max transform");

    BoundedNumberParameter fittingBox = new BoundedNumberParameter("Size of Fitting Box", 0, 5, 2, null).setEmphasized(true).setHint("Radius (in pixels) of square region in which to include data for fitting.");
    BooleanParameter fitEllipse = new BooleanParameter("Fit Ellipse", true).setHint("If False, a circular 2D Gaussian is fitted (more robust but less precise if observed spots are not circular)");
    BooleanParameter fitBackgroundPlane = new BooleanParameter("Fit Background Plane", false).setHint("If False, background is fitted as a simple constant is fitted");

    BoundedNumberParameter maxIterations = new BoundedNumberParameter("Max Iterations", 0, 300, 1, null).setHint("Stop and return after this many iterations if not done.");
    BoundedNumberParameter lambda = new BoundedNumberParameter("Lambda", 6, 0.001, 1e-6, 0.1).setHint("Blend between steepest descent (lambda high) and jump to bottom of quadratic (lambda zero). Start with 0.001.");
    BoundedNumberParameter termEpsilon = new BoundedNumberParameter("Termination accuracy", 6, 0.01, 1e-6, 0.1).setHint("Termination accuracy (0.01)");
    BoundedNumberParameter maxMajor = new BoundedNumberParameter("Maximum Major Axis", 3, 0, 0, null).setEmphasized(true).setHint("Spots with major axis greater than this value will be erased. If 0: not taken into account");
    BoundedNumberParameter minMinor = new BoundedNumberParameter("Minimum Minor Axis", 3, 0, 0, null).setEmphasized(true).setHint("Spots with minor axis lower than this value will be erased. If 0: not taken into account");

    GroupParameter fitParameters = new GroupParameter("Fit Parameters", fittingBox, fitEllipse, fitBackgroundPlane, maxIterations, lambda, termEpsilon, maxMajor, minMinor);

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{thldMethod, localMaxRadius, fitParameters};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        logger.debug("input image: {}", input);
        // compute thld
        double thld;
        if (useThldFactor.getSelected()) {
            double mean = ImageOperations.getMeanAndSigma(input, null, null)[0];
            if (stores!=null) Core.userLog("Image mean: "+mean);
            thld = this.thldFactor.getDoubleValue() * mean;
        } else {
            thld = this.thld.instantiatePlugin().runThresholder(input, parent);
        }
        if (stores!=null) Core.userLog("Threshold: "+thld);
        logger.debug("thld : {}", thld);
        // get local maxima
        ImageByte lm = Filters.localExtrema(input, null, true, thld, null, Filters.getNeighborhood(localMaxRadius.getDoubleValue(), 1, input));
        List<Point> centers = ImageLabeller.labelImageList(lm).stream().map(r -> r.getGeomCenter(false)).collect(Collectors.toList());
        logger.debug("local maxima: {}", centers.size());
        // perform elliptical gaussian fit
        int maxIter = maxIterations.getValue().intValue();
        double lambda = this.lambda.getValue().doubleValue();
        double eps = termEpsilon.getValue().doubleValue();
        Map<Point, double[]> fit = GaussianFit.run(input, centers, localMaxRadius.getDoubleValue()/2, fittingBox.getValue().intValue(), fittingBox.getValue().intValue()*2, fitEllipse.getSelected(), fitBackgroundPlane.getSelected(), true, null, true, true, maxIter, lambda, eps);

        List<Region> regions;
        if (fitEllipse.getSelected()) regions = fit.entrySet().stream().map(e -> GaussianFit.ellipse2DMapper.apply(e.getValue(), fitBackgroundPlane.getSelected(),input)).collect(Collectors.toList());
        else regions = fit.entrySet().stream().map(e -> GaussianFit.spotMapper.apply(e.getValue(), fitBackgroundPlane.getSelected(), input)).collect(Collectors.toList());
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
