package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.core.Core;
import bacmman.image.*;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.TestableOperation;
import bacmman.plugins.Transformation;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.processing.ImageOperations;
import bacmman.utils.ArrayUtil;
import ij.gui.Plot;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.IntStream;

import static bacmman.utils.Utils.parallele;

public class TransmitedLightZStackCorrelation implements Transformation, TestableOperation, DevPlugin {
    public final static Logger logger = LoggerFactory.getLogger(TransmitedLightZStackCorrelation.class);
    BoundedNumberParameter tileSize = new BoundedNumberParameter("Tile Size", 0, 30, 5, null);
    BoundedNumberParameter scale = new BoundedNumberParameter("Z-scale", 5, 5, 1, null).setEmphasized(true);
    PluginParameter<SimpleThresholder> thresholder = new PluginParameter<>("Threshold", SimpleThresholder.class, new IJAutoThresholder(), false);
    BoundedNumberParameter tileThreshold = new BoundedNumberParameter("Include Tiles Threshold", 5, 0.2, 0, 1);
    BoundedNumberParameter excludeFirstPlanes = new BoundedNumberParameter("Exclude First Planes", 0, 0, 0, null);
    BoundedNumberParameter excludeLastPlanes = new BoundedNumberParameter("Exclude Last Planes", 0, 0, 0, null);

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scale, tileSize, thresholder, tileThreshold, excludeFirstPlanes, excludeLastPlanes};
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (excludeFirstPlanes.getValue().intValue()>0 || excludeLastPlanes.getValue().intValue()>0) {
            int idxStart = excludeFirstPlanes.getValue().intValue();
            int idxLast = image.sizeZ() - excludeFirstPlanes.getValue().intValue();
            assert idxLast > idxStart;
            image = Image.mergeZPlanes(image.splitZPlanes(idxStart, idxLast));
            logger.debug("Z-place: Idx first: {}, last: {}, total planes: {}", idxStart, idxLast, image.sizeZ());
            if (testMode.testSimple()) {
                Core.showImage(image.setName("z-stack after frames exclusion @ frame: "+timePoint));
            }
        }
        Image sdImage = getSDProjection(image);
        double thld = thresholder.instantiatePlugin().runSimpleThresholder(sdImage, null);
        ImageMask mask = new ThresholdMask(sdImage, thld, true, false);
        ZPlane zPlane = getFocusPlane(image, tileSize.getValue().intValue(), mask, tileThreshold.getValue().doubleValue());
        if (testMode.testSimple()) {
            Core.showImage(sdImage.setName("sd z-projection @ frame: "+timePoint));
            Core.showImage(bacmman.image.TypeConverter.toByteMask(mask, null, 1).setName("Mask @"+timePoint));
            logger.debug("zPlane : {} * x + {} * y + c = z", zPlane.a, zPlane.b, zPlane.c);
            double[] corners = zPlane.getCorners(image);
            logger.debug("zPlane corners: [0; 0]={}, [0; {}]={}, [{}; 0]={}, [{}; {}]={}", corners[0], image.yMax(), corners[1], image.xMax(), corners[2], image.xMax(), image.yMax(), corners[3]);
        }
        if (testMode.testExpert()) {
            Core.showImage(zPlane.getZPlane(image).setName("focus plane @ frame: "+timePoint));
        }
        double scale = this.scale.getValue().doubleValue();
        double c1 = - 1 / ( Math.pow(scale, 3) * Math.sqrt(2 * Math.PI) );
        double c2 = - 1 / (2 * scale * scale);
        double[] zMinMax = zPlane.getZMinMax(image);
        int zMin = Math.max((int)(zMinMax[0] - 2 * scale + 0.5), 0);
        int zMax = Math.min((int)(zMinMax[1] + 2 * scale + 0.5), image.zMax()-1);
        logger.debug("z=[{};{}]", zMin, zMax);
        return convolveZ(image, zPlane, c1, c2, zMin, zMax);
    }
    public static ZPlane getFocusPlane(Image zStack, int tileSize, ImageMask mask, double tileThreshold) {
        double[][] points = Arrays.stream(tileImage(zStack.sizeX(), zStack.sizeY(), tileSize)).parallel()
                .filter(bound -> getMaskProportion(mask, bound)>=tileThreshold)
                .map(bound -> new double[]{bound.xMean(), bound.yMean(), getFocus(zStack, bound)})
                .toArray(double[][]::new);
        return new ZPlane(points);
    }
    private static MutableBoundingBox[] tileImage(int sizeX, int sizeY, int tileSize) {
        int nX = sizeX / tileSize, nY =  sizeY/tileSize;
        MutableBoundingBox[] res = new MutableBoundingBox[nX*nY];
        int idx = 0;
        for (int ix = 0; ix<nX; ++ix) {
            int x = ix * tileSize;
            for (int iy = 0; iy<nY; ++iy) {
                int y = iy * tileSize;
                res[idx++] = new MutableBoundingBox(x, x+tileSize, y, y+tileSize, 0, 0);
            }
        }
        return res;
    }
    private static double getMaskProportion(ImageMask mask, SimpleBoundingBox bounds) {
        double[] count = new double[1];
        BoundingBox.loop(bounds, (x, y, z) -> ++count[0], mask::insideMask);
        return count[0] / bounds.getSizeXYZ();
    }
    private static int getFocus(Image zStack, MutableBoundingBox bound) {
        double[] sd = IntStream.range(0, zStack.sizeZ()).mapToDouble(z-> getSD(zStack, bound.setzMin(z).setzMax(z))).toArray();
        float[] sdSmooth = ArrayUtil.toFloat(sd);
        ArrayUtil.gaussianSmooth(sdSmooth, 3);
        return ArrayUtil.min(sdSmooth);
    }
    private static double getSD(Image zStack, MutableBoundingBox bound) {
        double[] s_s2 = new double[2];
        double size = bound.getSizeXYZ();
        BoundingBox.loop(bound, (x, y, z) -> {
            double v = zStack.getPixel(x, y, z);
            s_s2[0]+=v;
            s_s2[1]+=v*v;
        });
        s_s2[0]/= size;
        return Math.sqrt(s_s2[1] / size - s_s2[0] * s_s2[0]);
    }

    public static Image getSDProjection(Image zStack) {
        Image res= new ImageFloat("SDProj", zStack.sizeX(), zStack.sizeY(), 1);
        int sizeZ = zStack.sizeZ();
        BoundingBox.loop(res, (x, y, z) -> {
            double s=0, s2=0, v;
            for (int zz = 0; zz<sizeZ; ++zz) {
                v = zStack.getPixel(x, y, zz);
                s+=v;
                s2+=v*v;
            }
            s /= sizeZ;
            res.setPixel(x, y, z, Math.sqrt(s2 / sizeZ - s * s));
        });
        return res;
    }

    static class ZPlane {
        // equation ax + ay + c = z
        double a, b, c, avgZ;
        public ZPlane(double[][] points) {
            //https://stackoverflow.com/questions/20699821/find-and-draw-regression-plane-to-a-set-of-points
            RealMatrix A = MatrixUtils.createRealMatrix(Arrays.stream(points).map( p -> new double[]{p[0], p[1], 1}).toArray(double[][]::new));
            RealMatrix B = MatrixUtils.createRealMatrix(Arrays.stream(points).map( p -> new double[]{p[2]}).toArray(double[][]::new));
            logger.debug("A rows: {}, cols: {} / B: rows: {} cols: {}", A.getRowDimension(), A.getColumnDimension(), B.getRowDimension(), B.getColumnDimension());
            RealMatrix At = A.transpose();
            // fit = (A.T * A).I * A.T * B
            RealMatrix fit = MatrixUtils.inverse(At.multiply(A)).multiply(At).multiply(B);
            a = fit.getEntry(0, 0);
            b = fit.getEntry(1, 0);
            c = fit.getEntry(2, 0);
            avgZ = Arrays.stream(points).mapToDouble(p->p[2]).average().getAsDouble();
        }
        public double getZ(int x, int y) {
            return a*x+b*y + c;
        }
        public Image getZPlane(Image zStack) {
            Image res= new ImageFloat("SDProj", zStack.sizeX(), zStack.sizeY(), 1);
            BoundingBox.loop(res, (x, y, z) -> {
                res.setPixel(x, y, z, zStack.getPixel(x, y, getZ(x, y)));
            });
            return res;
        }
        public double[] getCorners(BoundingBox bounds) {
            return new double[]{getZ(0, 0), getZ(0, bounds.yMax()), getZ(bounds.xMax(), 0), getZ(bounds.xMax(), bounds.yMax())};
        }
        public double[] getZMinMax(BoundingBox bounds) {
            double[] corners = getCorners(bounds);
            double zMin = Math.min(Math.min(corners[0], corners[1]), Math.min(corners[2], corners[3]));
            double zMax = Math.max(Math.max(corners[0], corners[1]), Math.max(corners[2], corners[3]));
            return new double[]{zMin, zMax};
        }

    }

    public static double convolveZ(Image im, int xy, double z, double c1, double c2, int zMin, int zMax) {
        double res = 0;
        int zI = (int)Math.round(z);
        double delta = z-zI;
        for (int zz = zMin; zz<=zMax; ++zz) {
            double dZ = zz-zI-delta;
            res += dZ * Math.exp(dZ*dZ*c2) * im.getPixel(xy, zz);
        }
        return res * c1;
    }

    public static ImageFloat convolveZ(Image in, ZPlane z, double c1, double c2, int zMin, int zMax) {
        ImageFloat res = new ImageFloat("convZ", in.sizeX(), in.sizeY(), 1);
        res.setCalibration(in);
        res.translate(in);
        int sX = in.sizeX();
        parallele(IntStream.range(0, in.sizeXY()), true).forEach( xy-> {
            int x = xy%sX;
            int y = xy/sX;
            res.setPixel(xy, 0, convolveZ(in, xy, z.getZ(x, y), c1, c2, zMin, zMax));
        });
        return res;
    }
    TEST_MODE testMode=TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TEST_MODE testMode) {this.testMode=testMode;}

}

