package bacmman.processing;

import bacmman.utils.ArrayUtil;
import bacmman.utils.geom.Point;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToDoubleBiFunction;

public class Medoid {
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
