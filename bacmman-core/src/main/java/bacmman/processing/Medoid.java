package bacmman.processing;

import bacmman.data_structure.CoordCollection;
import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;
import bacmman.image.ImageMask;
import bacmman.image.Offset;
import bacmman.utils.ArrayUtil;
import bacmman.utils.geom.Point;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleBiFunction;

public class Medoid {
    public static Point computeMedoid(Region region) {
        double ratio = region.getScaleXY()<=0 || region.getScaleZ()<=0 ? 1 : region.getScaleZ() / region.getScaleXY();
        if (region.voxelsCreated()) {
            ToDoubleBiFunction<Voxel, Voxel> distanceFunction = region.is2D() ? (v1, v2) -> Math.sqrt(Math.pow(v1.x-v2.x, 2)+Math.pow(v1.y-v2.y, 2)) :
                    (v1, v2) -> Math.sqrt(Math.pow(v1.x-v2.x, 2)+Math.pow(v1.y-v2.y, 2)+Math.pow(v1.z-v2.z, 2) * ratio);
            Voxel v = computeMedoid(region.getVoxels(), distanceFunction);
            return Point.asPoint((Offset)v);
        }
        return computeMedoid(region.getMask(), ratio);
    }
    public static Point computeMedoid(ImageMask mask, double sizeZRatio) {
        CoordCollection.ListCoordCollection points = CoordCollection.create(mask);
        double s = points.size();
        float[] sum = new float[points.size()];
        int[] c1 = new int[3];
        int[] c2 = new int[3];
        DoubleSupplier distance = points.sizeZ() == 1 ? ()->Math.sqrt(Math.pow(c1[0]-c2[0], 2)+Math.pow(c1[1]-c2[1], 2)) :
                ()->Math.sqrt(Math.pow(c1[0]-c2[0], 2)+Math.pow(c1[1]-c2[1], 2)+Math.pow(c1[2]-c2[2], 2)*sizeZRatio);
        for (int i = 0; i<sum.length-1; ++i) {
            for (int j = i+1; j<sum.length; ++j) {
                points.getCoord(i, c1);
                points.getCoord(j, c2);
                double d = distance.getAsDouble() / s; // divide by s -> avoid overflow
                sum[i]+=d;
                sum[j]+=d;
            }
        }
        int i = ArrayUtil.min(sum);
        points.getCoord(i, c1);
        return new Point(c1[0], c1[1], c1[2]).translate(mask);
    }
    public static <T> T computeMedoid(Collection<T> points, ToDoubleBiFunction<T, T> distanceFunction) {
        double s = points.size();
        float[] sum = new float[points.size()];
        List<T> pointList = (points instanceof List) ? (List<T>)points:new ArrayList<>(points);
        for (int i = 0; i<sum.length-1; ++i) {
            for (int j = i+1; j<sum.length; ++j) {
                double d = distanceFunction.applyAsDouble(pointList.get(i), pointList.get(j)) / s; // divide by s -> avoid overflow
                sum[i]+=d;
                sum[j]+=d;
            }
        }
        int i = ArrayUtil.min(sum);
        return pointList.get(i);
    }
    public static <T extends RealLocalizable> T computeMedoid(Collection<T> points) {
        return computeMedoid(points, (p1, p2)-> Math.sqrt(Point.distSq(p1, p2)));
    }
}
