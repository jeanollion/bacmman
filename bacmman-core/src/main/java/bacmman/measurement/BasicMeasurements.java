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
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.utils.ArrayUtil;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.DoubleStream;

/**
 *
 * @author Jean Ollion
 */
public class BasicMeasurements {
    public static double getSum(Region object, Image image) {
        double value=0;
        if (object.isAbsoluteLandMark()) {
            if (object.voxelsCreated()) for (Voxel v : object.getVoxels()) value+=image.getPixelWithOffset(v.x, v.y, v.z);
            else {
                double[] sum = new double[1];
                ImageMask.loopWithOffset(object.getMask(), (x, y, z)->{sum[0]+=image.getPixelWithOffset(x, y, z);});
                return sum[0];
            }
        }
        else {
            if (object.voxelsCreated()) for (Voxel v : object.getVoxels()) value+=image.getPixel(v.x, v.y, v.z);
            else {
                double[] sum = new double[1];
                ImageMask.loop(object.getMask(), (x, y, z)->{sum[0]+=image.getPixel(x, y, z);});
                return sum[0];
            }
        }
        return value;
    }
    public static double getMeanValue(Region object, Image image) {
        double sum = getSum(object, image);
        if (object.voxelsCreated()) return sum/(double)object.getVoxels().size();
        else return sum/(double)object.getMask().count();
    }
    public static double getMeanValue(Collection<Voxel> voxels, Image image, boolean voxelsInAbsoluteLandMark) {
        double value=0;
        if (voxelsInAbsoluteLandMark) for (Voxel v : voxels) value+=image.getPixelWithOffset(v.x, v.y, v.z);
        else for (Voxel v : voxels) value+=image.getPixel(v.x, v.y, v.z);
        return value/(double)voxels.size();
    }
    public static double[] getMeanSdValue(Region object, Image image) {
        if (object.voxelsCreated()) {
            double value=0;
            double value2=0;
            double tmp;
            if (object.isAbsoluteLandMark()) {
                for (Voxel v : object.getVoxels()) {
                    tmp=image.getPixelWithOffset(v.x, v.y, v.z);
                    value+=tmp;
                    value2+=tmp*tmp;
                }
            } else {
                for (Voxel v : object.getVoxels()) {
                    tmp=image.getPixel(v.x, v.y, v.z);
                    value+=tmp;
                    value2+=tmp*tmp;
                }
            }
            if (!object.getVoxels().isEmpty()) {
                value/=(double)object.getVoxels().size();
                value2/=(double)object.getVoxels().size();
                return new double[]{value, Math.sqrt(value2-value*value)};
            } else return new double[]{Double.NaN, Double.NaN};
        } else {
            double[] vv2 = new double[2];
            if (object.isAbsoluteLandMark()) {
                ImageMask.loopWithOffset(object.getMask(), (x, y, z)->{
                    double v =image.getPixelWithOffset(x, y, z);
                    vv2[0]+=v;
                    vv2[1]+=v*v;
                });
            } else {
                ImageMask.loop(object.getMask(), (x, y, z)->{
                    double v =image.getPixel(x, y, z);
                    vv2[0]+=v;
                    vv2[1]+=v*v;
                });
            }
            double count = object.getMask().count();
            vv2[0]/=count;
            vv2[1]/=count;
            return new double[]{vv2[1], Math.sqrt(vv2[1]-vv2[0]*vv2[0])};
        }
    }

    /**
     * 
     * @param foreground
     * @param background
     * @param image
     * @return [SNR, Mean ForeGround, Mean BackGround, Sd Background]
     */
    public static double[] getSNR(Collection<Voxel> foreground, Collection<Voxel> background, Image image, boolean voxelsAreInAbsoluteLandMark) { 
        if (foreground.isEmpty() || background.isEmpty()) return null;
        Set<Voxel> bck = new HashSet<> (background);
        bck.removeAll(foreground);
        double[] sdMeanBack = getMeanSdValue(new Region(bck, 1, false, 1, 1).setIsAbsoluteLandmark(voxelsAreInAbsoluteLandMark), image);
        double fore = getMeanValue(foreground, image, voxelsAreInAbsoluteLandMark);
        return new double[] {(fore - sdMeanBack[0]) / sdMeanBack[1], fore, sdMeanBack[0], sdMeanBack[1]};
        
    }
    public static double getMaxValue(Region object, Image image) {
        if (object.voxelsCreated()) {
            double max=Double.NEGATIVE_INFINITY;
            if (object.isAbsoluteLandMark()) {
                for (Voxel v : object.getVoxels()) if (image.getPixelWithOffset(v.x, v.y, v.z)>max) max = image.getPixelWithOffset(v.x, v.y, v.z);
            }
            else {
                for (Voxel v : object.getVoxels()) if (image.getPixel(v.x, v.y, v.z)>max) max = image.getPixel(v.x, v.y, v.z);
            }
            return max;
        } else {
            double[] max = new double[]{Double.NEGATIVE_INFINITY};
            if (object.isAbsoluteLandMark()) ImageMask.loopWithOffset(object.getMask(), (x, y, z)->{if (image.getPixelWithOffset(x, y, z)>max[0]) max[0] = image.getPixelWithOffset(x, y, z);});
            else ImageMask.loopWithOffset(object.getMask(), (x, y, z)->{if (image.getPixel(x, y, z)>max[0]) max[0] = image.getPixel(x, y, z);});
            return max[0];
        }
    }
    public static double getMinValue(Region object, Image image) {
        if (object.voxelsCreated()) {
            double min=Double.POSITIVE_INFINITY;
            if (object.isAbsoluteLandMark()) {
                for (Voxel v : object.getVoxels()) if (image.getPixelWithOffset(v.x, v.y, v.z)<min) min = image.getPixelWithOffset(v.x, v.y, v.z);
            } else {
                for (Voxel v : object.getVoxels()) if (image.getPixel(v.x, v.y, v.z)<min) min = image.getPixel(v.x, v.y, v.z);
            }
            return min;
        } else {
            double[] min = new double[]{Double.POSITIVE_INFINITY};
            if (object.isAbsoluteLandMark()) ImageMask.loopWithOffset(object.getMask(), (x, y, z)->{if (image.getPixelWithOffset(x, y, z)<min[0]) min[0] = image.getPixelWithOffset(x, y, z);});
            else ImageMask.loopWithOffset(object.getMask(), (x, y, z)->{if (image.getPixel(x, y, z)<min[0]) min[0] = image.getPixel(x, y, z);});
            return min[0];
        }
    }
    public static double[] getQuantileValue(Region object, Image image, double... quantiles) {
        if (quantiles.length==0 || object.getVoxels().isEmpty()) return new double[0];
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
    
}
