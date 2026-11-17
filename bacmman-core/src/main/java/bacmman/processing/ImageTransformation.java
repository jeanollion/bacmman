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
package bacmman.processing;

import bacmman.image.Image;
import bacmman.image.ImageProperties;
import bacmman.image.MutableBoundingBox;
import bacmman.image.PrimitiveType;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.image.TypeConverter;
import net.imglib2.Interval;
import net.imglib2.util.Intervals;
import java.util.Arrays;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.algorithm.interpolation.randomaccess.BSplineCoefficientsInterpolatorFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.outofbounds.OutOfBoundsBorderFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Geometric transformations (flip, 90° turns, translation, rotation in XY plane). Interpolation is performed with ImgLib2.
 * <br>For translation and rotation, output pixels whose source position is not entirely within the input image are set to exactly 0
 * (for interpolating schemes: source position outside [0, size-1] in at least one axis; for NEAREST: nearest source pixel outside the image),
 * so that they can be detected and removed afterwards. Inside the image, interpolation uses repeated border boundary conditions.
 * Values interpolated in integer images are rounded and clipped to the range of the type.
 * @author Jean Ollion
 */
public class ImageTransformation {
    public static enum MainAxis {X, Y, Z};
    public static enum Axis {
        X, Y, Z, XY, XZ, YZ;
        boolean flipX() {return this == X || this == XY || this == XZ;}
        boolean flipY() {return this == Y || this == XY || this == YZ;}
        boolean flipZ() {return this == Z || this == XZ || this == YZ;}
    };
    public enum InterpolationScheme {
        NEAREST(0), LINEAR(1), CUBIC(2), BSPLINE3(3), OMOMS3(4), BSPLINE5(5);
        private final int value;
        InterpolationScheme(int value){
            this.value = value;
        }
        public int getValue(){return value;};
    }
    public static String INTERPOLATION_HINT = "<ul><li>NEAREST: Nearest-neighbor interpolation: values are copied from the input image</li><li>LINEAR: Linear interpolation</li><li>BSPLINE3: Cubic B-spline interpolation</li><li>BSPLINE5: Quintic B-spline interpolation: most accurate, but smooth B-spline interpolation can create values out of the range of the input image near sharp edges</li><li>CUBIC, OMOMS3: legacy options, equivalent to BSPLINE3</li></ul>Pixels whose position in the input image falls outside of the image are set to 0 (for NEAREST: whose nearest input pixel is outside the image)";
    public static Image rotateXY(Image image, double angle, InterpolationScheme interpolation, boolean removeIncompleteRowsAndColumns) {
        if (angle%90==0) {
            if (angle<0) angle = 360+angle;
            return turn(image, (int)angle/90, 0, 0);
        } else {
            Image im =  rotate(image, angle, 0, 0, interpolation, !removeIncompleteRowsAndColumns, false);
            if (removeIncompleteRowsAndColumns) { // largest centered rectangle whose pixels all have their source within the input image
                int[] d = incompleteRowsAndColumns(image.sizeX(), image.sizeY(), angle);
                return im.crop(new MutableBoundingBox(d[0], im.sizeX() - 1 - d[0], d[1], im.sizeY() - 1 - d[1], 0, image.sizeZ() - 1));
            } else return im;
        }
    }
    /**
     * @return number of columns and rows to remove on each side of an image rotated by {@code angle} (with same size as the input image)
     * so that all remaining pixels have their source position within the input image
     */
    public static int[] incompleteRowsAndColumns(int sizeX, int sizeY, double angle) {
        double c = Math.abs(Math.cos(angle * Math.PI / 180)), s = Math.abs(Math.sin(angle * Math.PI / 180));
        double Hx = (sizeX - 1) / 2d, Hy = (sizeY - 1) / 2d; // half sizes in pixel-center coordinates
        // a centered rectangle of half sizes (hx, hy) fits iff c*hx + s*hy <= Hx and s*hx + c*hy <= Hy
        double det = c * c - s * s, hx = -1, hy = -1;
        if (det > 1e-9) { // rectangle touching both pairs of sides (largest area for small angles)
            hx = (c * Hx - s * Hy) / det;
            hy = (c * Hy - s * Hx) / det;
        }
        if (hx < 0 || hy < 0) { // otherwise: largest rectangle with the same aspect ratio as the image
            double k = Math.min(Hx / (c * Hx + s * Hy), Hy / (s * Hx + c * Hy));
            hx = k * Hx;
            hy = k * Hy;
        }
        int dX = Math.min((int)Math.ceil(Hx - hx - 1e-6), (sizeX - 1) / 2);
        int dY = Math.min((int)Math.ceil(Hy - hy - 1e-6), (sizeY - 1) / 2);
        return new int[]{Math.max(0, dX), Math.max(0, dY)};
    }

    /**
     * Rotation around the Z axis (rotation around X or Y axes are not supported)
     * @param zAngle angle in degrees. A positive angle maps the positive X-axis onto the positive Y-axis. Rotation center is the center of the image
     * @param scheme interpolation scheme
     * @param adjustImageSize if true, the output image is enlarged to contain the whole rotated image, otherwise it has the same size as the input image
     * @param antialiasing if true, image is extended with background value (0) instead of repeating the border for interpolation at image borders
     * @return rotated image of same type as input image. Pixels whose source position is not within the input image are set to 0
     */
    public static Image rotate(Image image, double zAngle, double yAngle, double xAngle, InterpolationScheme scheme, boolean adjustImageSize, boolean antialiasing) {
        if (yAngle != 0 || xAngle != 0) throw new UnsupportedOperationException("Only rotation around Z axis is supported");
        double rad = zAngle * Math.PI / 180;
        double cos = Math.cos(rad), sin = Math.sin(rad);
        int sX = image.sizeX(), sY = image.sizeY();
        // computations are performed in calibrated units (same sequence of operations as in previous versions, so that pixels lying exactly at the image limits are treated identically)
        double v = image.getScaleXY() > 0 ? image.getScaleXY() : 1;
        double ixc = 0.5 * v * (sX - 1), iyc = 0.5 * v * (sY - 1);
        int oSX = sX, oSY = sY;
        if (adjustImageSize) {
            double x1 = -ixc - 0.5 * v, y1 = -iyc - 0.5 * v;
            double[] rx = new double[4], ry = new double[4];
            int i = 0;
            for (double x : new double[]{x1, -x1}) for (double y : new double[]{y1, -y1}) {
                rx[i] = x * cos + y * -sin;
                ry[i++] = x * sin + y * cos;
            }
            oSX = round((Arrays.stream(rx).max().getAsDouble() - Arrays.stream(rx).min().getAsDouble()) / v);
            oSY = round((Arrays.stream(ry).max().getAsDouble() - Arrays.stream(ry).min().getAsDouble()) / v);
        }
        double oxc = 0.5 * v * (oSX - 1), oyc = 0.5 * v * (oSY - 1);
        Image res = image.newImage(image.getName(), new SimpleImageProperties(new SimpleBoundingBox(0, oSX-1, 0, oSY-1, 0, image.sizeZ()-1), image.getScaleXY(), image.getScaleZ()));
        for (int z = 0; z<image.sizeZ(); ++z) {
            Image plane = image.sizeZ() == 1 ? image : image.getZPlane(z);
            RealRandomAccess<? extends RealType<?>> ra = interpolate(plane, scheme, antialiasing);
            for (int y = 0; y<oSY; ++y) {
                double dy = y * v - oyc;
                double xcdysin = ixc + dy * sin;
                double ycdycos = iyc + dy * cos;
                for (int x = 0; x<oSX; ++x) {
                    double dx = x * v - oxc;
                    double tx = (xcdysin + dx * cos) / v;
                    double ty = (ycdycos - dx * sin) / v;
                    if (scheme == InterpolationScheme.NEAREST) {
                        int ix = round(tx), iy = round(ty);
                        if (ix >= 0 && ix < sX && iy >= 0 && iy < sY) res.setPixel(x, y, z, image.getPixel(ix, iy, z));
                    } else if (inside(tx, sX) && inside(ty, sY)) {
                        ra.setPosition(clamp(tx, sX), 0);
                        ra.setPosition(clamp(ty, sY), 1);
                        setPixel(res, x, y, z, ra.get().getRealDouble());
                    }
                }
            }
        }
        return (Image)res.setCalibration(image);
    }

    /**
     * Rotation by a multiple of 90 degrees around Z axis. A positive rotation maps the positive X-axis onto the positive Y-axis.
     * Rotations around X and Y axes are not supported
     */
    public static Image turn(Image image, int times90z, int times90y, int times90x) {
        if (times90y % 4 != 0 || times90x % 4 != 0) throw new UnsupportedOperationException("Only rotations around Z axis are supported");
        int k = ((times90z % 4) + 4) % 4;
        if (k == 0) return (Image)image.duplicate().setCalibration(image);
        int sX = image.sizeX(), sY = image.sizeY();
        int oSX = k == 2 ? sX : sY, oSY = k == 2 ? sY : sX;
        Image res = image.newImage(image.getName(), new SimpleImageProperties(new SimpleBoundingBox(0, oSX-1, 0, oSY-1, 0, image.sizeZ()-1), image.getScaleXY(), image.getScaleZ()));
        for (int z = 0; z<image.sizeZ(); ++z) {
            for (int y = 0; y<oSY; ++y) {
                for (int x = 0; x<oSX; ++x) {
                    double v;
                    switch (k) {
                        case 1: v = image.getPixel(y, sY - 1 - x, z); break;
                        case 2: v = image.getPixel(sX - 1 - x, sY - 1 - y, z); break;
                        default: v = image.getPixel(sX - 1 - y, x, z); break;
                    }
                    res.setPixel(x, y, z, v);
                }
            }
        }
        return (Image)res.setCalibration(image);
    }

    /**
     * Translation by (xTrans, yTrans, zTrans) pixels. The value of output pixel at position p is the value of the input image interpolated at position p - trans.
     * Pixels whose source position p - trans is not within the input image are set to 0
     * @return translated image of same type as input
     */
    public static Image translate(Image image, double xTrans, double yTrans, double zTrans, InterpolationScheme scheme) {
        if (Math.abs(xTrans) > image.sizeX()-1) throw new IllegalArgumentException("Translation distance out of range in x-dimension");
        if (Math.abs(yTrans) > image.sizeY()-1) throw new IllegalArgumentException("Translation distance out of range in y-dimension");
        if (Math.abs(zTrans) > Math.max(0, image.sizeZ()-1)) throw new IllegalArgumentException("Translation distance out of range in z-dimension");
        if (xTrans == 0 && yTrans == 0 && zTrans == 0) return (Image)image.duplicate().setCalibration(image);
        int nDim = image.sizeZ() > 1 ? 3 : 2;
        if (nDim == 2 && zTrans != 0) throw new IllegalArgumentException("Translation distance out of range in z-dimension");
        Image res = (Image)image.newImage(image.getName(), new SimpleImageProperties(image)).setCalibration(image).resetOffset();
        int sX = image.sizeX(), sY = image.sizeY(), sZ = image.sizeZ();
        if (scheme == InterpolationScheme.NEAREST) {
            int dX = round(xTrans), dY = round(yTrans), dZ = round(zTrans);
            for (int z = Math.max(0, dZ); z < Math.min(sZ, sZ + dZ); ++z) {
                for (int y = Math.max(0, dY); y < Math.min(sY, sY + dY); ++y) {
                    for (int x = Math.max(0, dX); x < Math.min(sX, sX + dX); ++x) {
                        res.setPixel(x, y, z, image.getPixel(x - dX, y - dY, z - dZ));
                    }
                }
            }
            return res;
        }
        if (image.dimensions().length < 2) throw new IllegalArgumentException("Translation is only supported for 2D and 3D images");
        boolean planeByPlane = nDim == 3 && zTrans == 0; // identical result, avoids interpolating (and padding B-spline coefficients) along Z
        RealRandomAccess<? extends RealType<?>> ra = planeByPlane ? null : interpolate(image, scheme, false);
        double[] trans = new double[]{xTrans, yTrans, zTrans};
        int[][] range = new int[3][];
        int[] size = new int[]{sX, sY, sZ};
        for (int d = 0; d<3; ++d) { // valid output range: source position p - trans within [0, size-1]
            if (trans[d] == 0) range[d] = new int[]{0, size[d]};
            else range[d] = new int[]{Math.max(0, (int)Math.ceil(trans[d] - EPS)), Math.min(size[d], (int)Math.floor(size[d] - 1 + trans[d] + EPS) + 1)};
        }
        for (int z = range[2][0]; z < range[2][1]; ++z) {
            if (planeByPlane) ra = interpolate(image.getZPlane(z), scheme, false);
            else if (nDim == 3) ra.setPosition(clamp(z - zTrans, sZ), 2);
            for (int y = range[1][0]; y < range[1][1]; ++y) {
                ra.setPosition(clamp(y - yTrans, sY), 1);
                for (int x = range[0][0]; x < range[0][1]; ++x) {
                    ra.setPosition(clamp(x - xTrans, sX), 0);
                    setPixel(res, x, y, z, ra.get().getRealDouble());
                }
            }
        }
        return res;
    }

    private static RealRandomAccess<? extends RealType<?>> interpolate(Image image, InterpolationScheme scheme, boolean zeroBoundary) {
        if (image instanceof PrimitiveType.ByteType || image instanceof PrimitiveType.ShortType || image instanceof PrimitiveType.IntType) image = TypeConverter.toFloat(image, null, false); // interpolate in floating point precision
        RandomAccessibleInterval img = (RandomAccessibleInterval)ImgLib2ImageWrapper.toImg(image);
        RandomAccessible ext = zeroBoundary ? Views.extendZero((RandomAccessibleInterval)img) : Views.extendBorder((RandomAccessibleInterval)img);
        switch (scheme) {
            case NEAREST:
                return (RealRandomAccess)Views.interpolate((RandomAccessible)ext, new NearestNeighborInterpolatorFactory()).realRandomAccess();
            case LINEAR:
                return (RealRandomAccess)Views.interpolate((RandomAccessible)ext, new NLinearInterpolatorFactory()).realRandomAccess();
            default: { // B-spline coefficients are computed once on the whole image.
                int order = scheme == InterpolationScheme.BSPLINE5 ? 5 : 3;
                // coefficients are computed on an image padded with the boundary condition, so that the truncated initialization of the recursive filter at the limits of the padded domain has no effect on the image domain
                // margin: the boundary error decays as |pole|^margin (pole -0.268 for cubic, -0.430 for quintic) -> ~1e-6 relative
                long pad = order == 5 ? 16 : 12;
                Interval padded = Intervals.expand(img, pad);
                BSplineCoefficientsInterpolatorFactory factory = new BSplineCoefficientsInterpolatorFactory(ext, padded, order, false, new ArrayImgFactory<>(new FloatType()), // float coefficients: same memory as a float copy of the (padded) image
                        zeroBoundary ? new OutOfBoundsConstantValueFactory<>(new FloatType(0)) : new OutOfBoundsBorderFactory<>());
                return factory.create(img);
            }
        }
    }

    private static final double EPS = 1e-6; // tolerance on source positions (rounding errors)
    private static boolean inside(double pos, int size) {
        return pos >= -EPS && pos <= size - 1 + EPS;
    }
    private static double clamp(double pos, int size) {
        return pos < 0 ? 0 : (pos > size - 1 ? size - 1 : pos);
    }

    private static int round(double d) { // round half up
        return (int)Math.floor(d + 0.5);
    }

    private static void setPixel(Image image, int x, int y, int z, double value) {
        if (image instanceof PrimitiveType.ByteType) value = Math.round(Math.max(0, Math.min(255, value)));
        else if (image instanceof PrimitiveType.ShortType) value = Math.round(Math.max(0, Math.min(65535, value)));
        else if (image instanceof PrimitiveType.IntType) value = Math.round(Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)));
        image.setPixel(x, y, z, value);
    }

    /**
     * Embeds image in a new image of size {@code newImage}, at position (posX, posY, posZ). Other pixels are set to 0
     */
    public static Image resize(Image image, ImageProperties newImage, int posX, int posY, int posZ) {
        Image res = image.newImage(image.getName(), new SimpleImageProperties(new SimpleBoundingBox(0, newImage.sizeX()-1, 0, newImage.sizeY()-1, 0, newImage.sizeZ()-1), image.getScaleXY(), image.getScaleZ()));
        for (int z = Math.max(0, posZ); z < Math.min(res.sizeZ(), posZ + image.sizeZ()); ++z) {
            for (int y = Math.max(0, posY); y < Math.min(res.sizeY(), posY + image.sizeY()); ++y) {
                for (int x = Math.max(0, posX); x < Math.min(res.sizeX(), posX + image.sizeX()); ++x) {
                    res.setPixel(x, y, z, image.getPixel(x - posX, y - posY, z - posZ));
                }
            }
        }
        return (Image)res.setCalibration(image);
    }

    /**
     * Flips image in place along the given axis
     * @return the input image
     */
    public static Image flip(Image image, Axis axis) {
        int sX = image.sizeX(), sY = image.sizeY(), sZ = image.sizeZ();
        boolean fx = axis.flipX(), fy = axis.flipY(), fz = axis.flipZ() && sZ>1;
        if (!fx && !fy && !fz) return image;
        Image source = image.duplicate();
        for (int z = 0; z < sZ; ++z) {
            int z2 = fz ? sZ - 1 - z : z;
            for (int y = 0; y < sY; ++y) {
                int y2 = fy ? sY - 1 - y : y;
                for (int x = 0; x < sX; ++x) image.setPixel(x, y, z, source.getPixel(fx ? sX - 1 - x : x, y2, z2));
            }
        }
        return image;
    }
}
