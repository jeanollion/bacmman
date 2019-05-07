package bacmman.processing;

import bacmman.core.Core;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.utils.DoubleStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Jean Ollion
 * Adapted for spot detection from : Loy, G., & Zelinsky, A. (2003). Fast radial symmetry for detecting points of interest. IEEE Transactions on Pattern Analysis and Machine Intelligence, 25(8), 959-973.
 */
public class FastRadialSymmetryTransformUtil {
    public final static Logger logger = LoggerFactory.getLogger(FastRadialSymmetryTransformUtil.class);
    public enum GRADIENT_SIGN {POSITIVE_ONLY, NEGATIVE_ONLY, BOTH}
    public static class Kappa {
        private final double[] sortedRadii;
        private final double[] kappa;
        public double getKappa(double radius) {
            int i = Arrays.binarySearch(sortedRadii, radius);
            if (i>=0) {
                return kappa[i];
            } else { // linear interpolation + saturation for out-of-bound
                int ip = -i-1;
                if (ip==0) return kappa[0];
                if (ip==sortedRadii.length) return kappa[sortedRadii.length-1];
                return kappa[ip-1] + (kappa[ip]-kappa[ip-1]) * (radius-sortedRadii[ip-1]) / (sortedRadii[ip]-sortedRadii[ip-1]);
            }
        }
        public Kappa(double[] sortedRadii, double[] kappa) {
            this.sortedRadii=sortedRadii;
            this.kappa=kappa;
            if (sortedRadii.length!= kappa.length) throw new IllegalArgumentException("arrays should have same length");
        }
    }
    public static Kappa defaultKappa = new Kappa(new double[]{1, 2}, new double[]{8, 9.9});
    public static Kappa fluoSpotKappa = new Kappa(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, new double[]{8, 12.334, 13.489, 14.440, 12.910, 11.668, 10.623, 9.987, 9.564, 9.071, 8.785, 8.515, 8.343, 8.216, 8.022});
    /**
     *
     * @param input image to compute transform on
     * @param radii radius of successive neighborhoods in pixels
     * @param useOrientationOnly if true: only use the gradient orientation and ignore the gradient magnitude
     * @param gradientSign == -1 --> only negative / flag == 1 --> only positive / otherwise --> both
     * @param alpha alpha increase the symmetry condition (higher -> more symmetric)
     * @param smallGradientThreshold proportion of  discarded gradients (lower than this value)
     * @return
     */
    public static Image runTransform(Image input, double[] radii, Kappa kappaFunction, boolean useOrientationOnly, GRADIENT_SIGN gradientSign, double alpha, double smallGradientThreshold, double... gradientScale){
        double scaleZ = gradientScale.length>1?gradientScale[1]:gradientScale[0]*input.getScaleXY()/input.getScaleZ();
        double ratioZ = (scaleZ / gradientScale[0]);
        ImageFloat[] grad = ImageFeatures.getGradient(input, gradientScale[0], scaleZ, false);
        Image gradX = grad[0];
        Image gradY = grad[1];
        Image gradZ = grad.length>2 ? grad[2] : null;
        Image gradM = new ImageFloat("", input).resetOffset();

        if (gradZ==null) BoundingBox.loop(gradM, (x, y, z)->((ImageFloat)gradM).setPixel(x, y, z, (float)(Math.sqrt(Math.pow(gradX.getPixel(x, y, z), 2)+Math.pow(gradY.getPixel(x, y, z), 2))))); // computes gradient magnitude
        else {
            BoundingBox.loop(gradM, (x, y, z)->((ImageFloat)gradM).setPixel(x, y, z, (float)(Math.sqrt(Math.pow(gradX.getPixel(x, y, z), 2)+Math.pow(gradY.getPixel(x, y, z), 2)+Math.pow(gradZ.getPixel(x, y, z), 2)))));
        }
        double[] mm = gradM.getMinAndMax(null);
        Image Omap = new ImageFloat("", input);
        Image Mmap = useOrientationOnly ? null : new ImageFloat("", input);
        Image F = new ImageFloat("", input);
        Image output = new ImageFloat("", input);
        // over all radii that are in the set
        for (int i = 0; i < radii.length; i++) {
            if (i>0) {
                ImageOperations.fill(Omap, 0, null);
                if (Mmap!=null) ImageOperations.fill(Mmap, 0, null);
            }
            double radius = radii[i];
            double kappa = kappaFunction.getKappa(radius);
            // get the O and M maps
            computeOandMmapNoSaturation(Omap, Mmap, gradX, gradY, gradZ, gradM, radius, smallGradientThreshold>0 ? smallGradientThreshold * (mm[1]-mm[0]) + mm[0] : 0, gradientSign);
            // computeOandMmap(Omap, Mmap, gradX, gradY, gradZ, gradM, radius, (float)kappa, smallGradientThreshold>0 ? smallGradientThreshold * (mm[1]-mm[0]) + mm[0] : 0, gradientSign);

            // symmetry measure at this radius value (not smoothed)
            if (Mmap==null) BoundingBox.loop(gradM, (x, y, z) -> F.setPixel(x, y, z, Math.signum(Omap.getPixel(x, y, z)) * Math.pow(Math.abs(Omap.getPixel(x, y, z)/kappa),alpha)));
            else BoundingBox.loop(gradM, (x, y, z) -> F.setPixel(x, y, z, (float)((Mmap.getPixel(x, y, z)/kappa) * Math.pow(Math.abs(Omap.getPixel(x, y, z)/kappa),alpha))));

            Image smoothed = ImageFeatures.gaussianSmooth(F, 0.25*radius, 0.25*radius * ratioZ, true);
            ImageOperations.addImage(output, smoothed, output, radius); // multiplied by radius so that sum of all elements of the kernel is radius
        }
        return output;
    }


    private static void computeOandMmap(Image Omap, Image Mmap, Image gradX, Image gradY, Image gradZ, Image gradM, double radius, float kappa, double gradientThld, GRADIENT_SIGN gradientSign){
        // go over all pixels and create the O and M maps
        BoundingBox.LoopFunction fun3D = (x, y, z)-> {
            int xg = Math.round((float)radius*gradX.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            int yg = Math.round((float)radius*gradY.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            int zg = Math.round((float)radius*gradZ.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            // compute the 'positively' and/or 'negatively' affected pixels
            if (!GRADIENT_SIGN.NEGATIVE_ONLY.equals(gradientSign)){
                int posx = x+xg, posy = y+yg, posz = z+zg;
                if (posx<Omap.sizeX() && posy<Omap.sizeY() && posx>=0 && posy>=0 && posz>=0 && posz<Omap.sizeZ()) {
                    Omap.setPixel(posx, posy, posz, (Omap.getPixel(posx, posy, posz) + 1 > kappa) ? kappa : Omap.getPixel(posx, posy, z) + 1);
                    if (Mmap != null)  Mmap.setPixel(posx, posy, posz, Mmap.getPixel(posx, posy, posz) + gradM.getPixel(x, y, z));
                }
            }

            if (!GRADIENT_SIGN.POSITIVE_ONLY.equals(gradientSign)){
                int negx = x-xg, negy = y-yg, negz=z-zg;
                if (negx<Omap.sizeX() && negy<Omap.sizeY() && negx>=0 && negy>=0 && negz>=0 && negz<Omap.sizeZ()) {
                    Omap.setPixel(negx, negy, z, (Omap.getPixel(negx, negy, negz) - 1 < -kappa) ? -kappa : Omap.getPixel(negx, negy, negz) - 1);
                    if (Mmap != null) Mmap.setPixel(negx, negy, negz, Mmap.getPixel(negx, negy, negz) - gradM.getPixel(x, y, z));
                }
            }
        };
        BoundingBox.LoopFunction fun2D = (x, y, z)-> {
            int xg = Math.round((float)radius*gradX.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            int yg = Math.round((float)radius*gradY.getPixel(x, y, z)/gradM.getPixel(x, y, z));

            // compute the 'positively' and/or 'negatively' affected pixels
            if (!GRADIENT_SIGN.NEGATIVE_ONLY.equals(gradientSign)){
                int posx = x+xg, posy = y+yg;
                if (posx<Omap.sizeX() && posy<Omap.sizeY() && posx>=0 && posy>=0) {
                    Omap.setPixel(posx, posy, z, (Omap.getPixel(posx, posy, z) + 1 > kappa) ? kappa : Omap.getPixel(posx, posy, z) + 1);
                    if (Mmap != null)  Mmap.setPixel(posx, posy, z, Mmap.getPixel(posx, posy, z) + gradM.getPixel(x, y, z));
                }
            }

            if (!GRADIENT_SIGN.POSITIVE_ONLY.equals(gradientSign)){
                int negx = x-xg, negy = y-yg;
                if (negx<Omap.sizeX() && negy<Omap.sizeY() && negx>=0 && negy>=0) {
                    Omap.setPixel(negx, negy, z, (Omap.getPixel(negx, negy, z) - 1 < -kappa) ? -kappa : Omap.getPixel(negx, negy, z) - 1);
                    if (Mmap != null) Mmap.setPixel(negx, negy, z, Mmap.getPixel(negx, negy, z) - gradM.getPixel(x, y, z));
                }
            }
        };
        if (gradientThld>0) BoundingBox.loop(gradM, gradZ==null ? fun2D: fun3D, (x, y, z)->gradM.getPixel(x, y, z)>gradientThld,false);
        else BoundingBox.loop(gradM, gradZ==null ? fun2D: fun3D,false);
    }

    private static void computeOandMmapNoSaturation(Image Omap, Image Mmap, Image gradX, Image gradY, Image gradZ, Image gradM, double radius, double gradientThld, GRADIENT_SIGN gradientSign){
        // go over all pixels and create the O and M maps
        BoundingBox.LoopFunction fun3D = (x, y, z)-> {
            int xg = Math.round((float)radius*gradX.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            int yg = Math.round((float)radius*gradY.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            int zg = Math.round((float)radius*gradZ.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            // compute the 'positively' and/or 'negatively' affected pixels
            if (!GRADIENT_SIGN.NEGATIVE_ONLY.equals(gradientSign)){
                int posx = x+xg, posy = y+yg, posz = z+zg;
                if (posx<Omap.sizeX() && posy<Omap.sizeY() && posx>=0 && posy>=0 && posz>=0 && posz<Omap.sizeZ()) {
                    Omap.setPixel(posx, posy, posz, Omap.getPixel(posx, posy, posz) + 1);
                    if (Mmap != null)  Mmap.setPixel(posx, posy, posz, Mmap.getPixel(posx, posy, posz) + gradM.getPixel(x, y, z));
                }
            }

            if (!GRADIENT_SIGN.POSITIVE_ONLY.equals(gradientSign)){
                int negx = x-xg, negy = y-yg, negz=z-zg;
                if (negx<Omap.sizeX() && negy<Omap.sizeY() && negx>=0 && negy>=0 && negz>=0 && negz<Omap.sizeZ()) {
                    Omap.setPixel(negx, negy, negz, Omap.getPixel(negx, negy, negz) - 1);
                    if (Mmap != null) Mmap.setPixel(negx, negy, negz, Mmap.getPixel(negx, negy, negz) - gradM.getPixel(x, y, z));
                }
            }
        };
        BoundingBox.LoopFunction fun2D = (x, y, z)-> {
            int xg = Math.round((float)radius*gradX.getPixel(x, y, z)/gradM.getPixel(x, y, z));
            int yg = Math.round((float)radius*gradY.getPixel(x, y, z)/gradM.getPixel(x, y, z));

            // compute the 'positively' and/or 'negatively' affected pixels
            if (!GRADIENT_SIGN.NEGATIVE_ONLY.equals(gradientSign)){
                int posx = x+xg, posy = y+yg;
                if (posx<Omap.sizeX() && posy<Omap.sizeY() && posx>=0 && posy>=0) {
                    Omap.setPixel(posx, posy, z, Omap.getPixel(posx, posy, z) + 1);
                    if (Mmap != null)  Mmap.setPixel(posx, posy, z, Mmap.getPixel(posx, posy, z) + gradM.getPixel(x, y, z));
                }
            }

            if (!GRADIENT_SIGN.POSITIVE_ONLY.equals(gradientSign)){
                int negx = x-xg, negy = y-yg;
                if (negx<Omap.sizeX() && negy<Omap.sizeY() && negx>=0 && negy>=0) {
                    Omap.setPixel(negx, negy, z, Omap.getPixel(negx, negy, z) - 1);
                    if (Mmap != null) Mmap.setPixel(negx, negy, z, Mmap.getPixel(negx, negy, z) - gradM.getPixel(x, y, z));
                }
            }
        };
        // do not consider gradients with too small magnitudes
        if (gradientThld>0) BoundingBox.loop(gradM, gradZ==null ? fun2D: fun3D, (x, y, z)->gradM.getPixel(x, y, z)>gradientThld,false);
        else BoundingBox.loop(gradM, gradZ==null ? fun2D: fun3D,false);
    }


    // methods used to calibrate kappa(radius)
    public static double[][] getKappaAVG_STD_MAX(Stream<Image> images, double[] radii) {
        List<double[]> max = images
                .parallel()
                .map(im ->  computeOrientationMaps(im, radii))
                .collect(Collectors.toList());
        List<double[]> stat = IntStream.range(0, radii.length).mapToObj(i -> {
            DoubleStatistics stats = DoubleStatistics.getStats(max.stream().mapToDouble(d->d[i]));
            return new double[]{stats.getAverage(), stats.getStandardDeviation(), stats.getMax()};
        }).collect(Collectors.toList());
        double[] AVG = stat.stream().mapToDouble(d->d[0]).toArray();
        double[] STD = stat.stream().mapToDouble(d->d[1]).toArray();
        double[] MAX = stat.stream().mapToDouble(d->d[2]).toArray();
        return new double[][]{AVG, STD, MAX};
    }

    private static double[] computeOrientationMaps(Image input, double[] radii) {
        ImageFloat[] grad = ImageFeatures.getGradient(input, 1.5, 1.5*input.getSizeXY()/input.getScaleZ(), false); // which gradient scale should be chosen ? should sobel filter be chosen as in the origial publication ?
        Image gradX = grad[0];
        Image gradY = grad[1];
        Image gradZ = grad.length>2 ? null : grad[2];
        Image gradM = new ImageFloat("", input).resetOffset();

        BoundingBox.loop(gradM, (x, y, z)->((ImageFloat)gradM).setPixel(x, y, z, (float)(Math.sqrt(Math.pow(gradX.getPixel(x, y, z), 2)+Math.pow(gradY.getPixel(x, y, z), 2))))); // computes gradient magnitude
        double[] res = new double[radii.length];
        for (int i = 0; i<radii.length; ++i) {
            Image Omap = new ImageFloat("", input);
            FastRadialSymmetryTransformUtil.computeOandMmap(Omap, null, gradX, gradY, gradZ, gradM, radii[i], Float.MAX_VALUE, 0, GRADIENT_SIGN.BOTH);
            res[i] = Omap.getMinAndMax(null)[1];
        }
        return res;
    }
}
