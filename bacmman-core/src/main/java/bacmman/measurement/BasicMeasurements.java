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
package bacmman.measurement;

import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.utils.ArrayUtil;
import java.util.Collection;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class BasicMeasurements {
    protected static double getSum(Region object, Image image, boolean mean) {
        double[] value = new double[2];
        BoundingBox.LoopFunction fun = object.isAbsoluteLandMark() ? (x, y, z) -> {
            value[0]+=image.getPixelWithOffset(x, y, z);
            value[1]+=1;
        } : (x, y, z) -> {
            value[0]+=image.getPixel(x, y, z);
            value[1]+=1;
        };
        object.loop(fun, image);
        return mean ? value[0]/value[1] : value[0];
    }

    public static double[] getMeanSdValue(Region object, Image image) {
        double[] vv2 = new double[3];
        BoundingBox.LoopFunction fun = object.isAbsoluteLandMark() ? (x, y, z) -> {
            double v =image.getPixelWithOffset(x, y, z);
            vv2[0]+=v;
            vv2[1]+=v*v;
            vv2[2]+=1;
        } : (x, y, z) -> {
            double v =image.getPixel(x, y, z);
            vv2[0]+=v;
            vv2[1]+=v*v;
            vv2[2]+=1;
        };
        object.loop(fun, image);
        vv2[0]/=vv2[2];
        vv2[1]/=vv2[2];
        return new double[]{vv2[1], Math.sqrt(vv2[1]-vv2[0]*vv2[0])};
    }

    public static double getSum(Region object, Image image) {
        return getSum(object, image, false);
    }

    public static double getMeanValue(Region object, Image image) {
        return getSum(object, image, true);
    }

    public static double getMeanValue(Collection<Voxel> voxels, Image image, boolean voxelsInAbsoluteLandMark) {
        double value=0;
        if (voxelsInAbsoluteLandMark) for (Voxel v : voxels) value+=image.getPixelWithOffset(v.x, v.y, v.z);
        else for (Voxel v : voxels) value+=image.getPixel(v.x, v.y, v.z);
        return value/(double)voxels.size();
    }

    public static double getMaxValue(Collection<Voxel> voxels, Image image, boolean voxelsInAbsoluteLandMark) {
        if (voxelsInAbsoluteLandMark) return voxels.stream().mapToDouble(v -> image.getPixelWithOffset(v.x, v.y, v.z)).max().orElse(Double.NaN);
        else return voxels.stream().mapToDouble(v -> image.getPixel(v.x, v.y, v.z)).max().orElse(Double.NaN);
    }

    public static double getMinValue(Collection<Voxel> voxels, Image image, boolean voxelsInAbsoluteLandMark) {
        if (voxelsInAbsoluteLandMark) return voxels.stream().mapToDouble(v -> image.getPixelWithOffset(v.x, v.y, v.z)).min().orElse(Double.NaN);
        else return voxels.stream().mapToDouble(v -> image.getPixel(v.x, v.y, v.z)).min().orElse(Double.NaN);
    }

    public static double getMaxValue(Region object, Image image) {
        double[] max = new double[]{Double.NEGATIVE_INFINITY};
        BoundingBox.LoopFunction fun = object.isAbsoluteLandMark() ? (x, y, z)-> {
            double v = image.getPixelWithOffset(x, y, z);
            if (v>max[0]) max[0] = v;
        } : (x, y, z)-> {
            double v = image.getPixel(x, y, z);
            if (v>max[0]) max[0] = v;
        };
        object.loop(fun, image);
        return max[0];
    }

    public static double getMinValue(Region object, Image image) {
        double[] min = new double[]{Double.POSITIVE_INFINITY};
        BoundingBox.LoopFunction fun = object.isAbsoluteLandMark() ? (x, y, z)-> {
            double v = image.getPixelWithOffset(x, y, z);
            if (v<min[0]) min[0] = v;
        } : (x, y, z)-> {
            double v = image.getPixel(x, y, z);
            if (v<min[0]) min[0] = v;
        };
        object.loop(fun, image);
        return min[0];
    }

    public static double[] getQuantileValue(Region object, Image image, double... quantiles) {
        if (image == null) throw new RuntimeException("Null image");
        if (quantiles.length==0) return new double[0];
        if (quantiles.length==1 && quantiles[0]<=0) return new double[]{getMinValue(object, image)};
        if (quantiles.length==1 && quantiles[0]>=1) return new double[]{getMaxValue(object, image)};
        DoubleStream stream;
        if (object.voxelsCreated()) {
            if (object.isAbsoluteLandMark()) stream = object.getVoxels().stream().mapToDouble(v->image.getPixelWithOffset(v.x, v.y, v.z));
            else stream = object.getVoxels().stream().mapToDouble(v->image.getPixel(v.x, v.y, v.z));
        } else stream = image.stream(object.getMask(), object.isAbsoluteLandMark()).sorted();
        stream = stream.sorted();
        return ArrayUtil.quantiles(stream.toArray(), quantiles);
    }

    public static double[] getQuantileValue(Collection<Voxel> voxels, Image image, boolean voxelsInAbsoluteLandMark, double... quantiles) {
        if (quantiles.length==0) return new double[0];
        if (voxels.isEmpty()) return IntStream.range(0, quantiles.length).mapToDouble(i -> Double.NaN).toArray();
        if (quantiles.length==1 && quantiles[0]<=0) return new double[]{getMinValue(voxels, image, voxelsInAbsoluteLandMark)};
        if (quantiles.length==1 && quantiles[0]>=1) return new double[]{getMaxValue(voxels, image ,voxelsInAbsoluteLandMark)};
        DoubleStream stream;
        if (voxelsInAbsoluteLandMark) stream = voxels.stream().mapToDouble(v->image.getPixelWithOffset(v.x, v.y, v.z));
        else stream = voxels.stream().mapToDouble(v->image.getPixel(v.x, v.y, v.z));
        stream = stream.sorted();
        return ArrayUtil.quantiles(stream.toArray(), quantiles);
    }

}
