package bacmman.processing.gaussian_fit;

import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.image.*;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.utils.geom.Point;
import ij.ImageJ;
import net.imglib2.algorithm.localization.FitFunction;
import net.imglib2.algorithm.localization.Gaussian;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TestGaussianFit {
    public final static Logger logger = LoggerFactory.getLogger(TestGaussianFit.class);
    private static double[] backgroundPlaneParameters = new double[] {0., -0., 10};
    private static double[] ellipse1Parameters = new double[] {10, 10, 10, 5, 30 * Math.PI / 180, 150}; // cx, cy, M(a), m(b), Theta(c), A
    private static double[] ellipse2Parameters = new double[] {15, 15, 8, 6, 120 * Math.PI / 180, 200};
    private static double[] ellipse3Parameters = new double[] {50, 60.5, 11, 4, 180 * Math.PI / 180, 300};
    private static double[][] ellipseParameters = new double[][]{ellipse1Parameters, ellipse2Parameters, ellipse3Parameters};
    private static double[] spot2D1Parameters = new double[] {80, 10, 200, 5}; // cx, cy, A, radius(b)
    private static double[][] spot2DParameters = new double[][]{spot2D1Parameters};

    private static double[] convertEllipseParametersToFitParameters(double[] params) {
        double a = params[2]/2;
        double b = params[3]/2;
        double theta = params[4];
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double[] res = new double[6];
        res[0] = params[0];
        res[1] = params[1];
        res[2] = Math.pow(cos/a, 2) + Math.pow(sin/b, 2);
        res[3] = Math.pow(sin/a, 2) + Math.pow(cos/b, 2);
        res[4] = cos * sin * (1/(a*a) - 1/(b*b));
        res[5] = params[5];
        return res;
    }

    private static double[] convertSpotParametersToFitParameters(double[] params) {
        double[] res = Arrays.copyOf(params, params.length);
        res[res.length-1] = 1 / (Math.pow(res[res.length-1], 2));
        return res;
    }

    public static List<Region> fit(Image image, double threshold, boolean ellipses, boolean plane) {
        ImageByte localExtrema = Filters.localExtrema(image, null, true, null, Filters.getNeighborhood(1.5, 1.5, image));
        BoundingBox.LoopPredicate lp = (x, y, z) -> image.getPixel(x, y, z)<threshold;
        ImageMask.loop(localExtrema, (x, y, z)->localExtrema.setPixel(x, y, z, 0), lp);
        List<Region> regions = Arrays.asList(ImageLabeller.labelImage(localExtrema));
        logger.debug("number of local extrema found: {}", regions.size());
        List<Point> peaks = regions.stream().map(o -> o.getMassCenter(image, false)).collect(Collectors.toList());
        //logger.debug("peaks found: {}", peaks);
        Map<Point, double[]> fit = GaussianFit.run(image, peaks, 5, 10, 10, ellipses, plane, true, null, true, true, 300, 0.001, 0.01);
        //logger.debug("number of fitted objects: {}", fit.size());
        //fit.forEach((c, p ) -> logger.debug("point: {}, parameters: {}", c, p));

        List<Region> result;
        if (ellipses) result = fit.values().stream().map(v -> GaussianFit.ellipse2DMapper.apply(v, false, image)).collect(Collectors.toList());
        else result = fit.values().stream().map(v -> GaussianFit.spotMapper.apply(v, false, image)).collect(Collectors.toList());
        for (int i = 0; i<result.size(); ++i) result.get(i).setLabel(i+1);
        return result;
    }



    public static Image fitAndDrawLabels(Image image, double threshold, boolean ellipses, boolean plane) {
        List<Region> regions = fit(image, threshold, ellipses, plane);
        logger.debug("fitted spots: {}", regions);
        RegionPopulation pop = new RegionPopulation(regions, image);
        return pop.getLabelMap();
    }

    public static Image generateImage(boolean ellipses, boolean plane) {
        Image im = new ImageFloat("Synthetic data", 100, 100, 1);
        // add background plane
        if (plane) {
            Plane bp = new Plane();
            draw(bp, backgroundPlaneParameters, im);
        }
        FitFunction peak = ellipses ? new EllipticGaussian2D() : new Gaussian();
        if (ellipses) for (double[] p : ellipseParameters) draw(peak, convertEllipseParametersToFitParameters(p), im);
        else for (double[] p : spot2DParameters) draw(peak, convertSpotParametersToFitParameters(p), im);
        im = addNoise(5, im);

        return im;
    }

    private static void draw(FitFunction fun, double[] params, Image image) {
        BoundingBox.loop(image, (x, y, z) -> {
            image.addPixel(x, y, z, fun.val(new double[]{x, y}, params));
        });
    }

    private static Image addNoise(double std, Image image) {
        java.util.Random r = new java.util.Random();
        BoundingBox.loop(image, (x, y, z) -> {
            image.addPixel(x, y, z, r.nextGaussian() * std);
        });
        //return image;
        return ImageFeatures.gaussianSmoothScaleIndep(image, 1, 1, true);
        //return ImageFeatures.gaussianSmooth(image, 1.5, 1, true);
    }
}
