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
import bacmman.image.ImageFloat;

import static bacmman.utils.Utils.parallele;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class RadonProjection {

    public static void radonProject(Image image, int z, double angle, float[] proj, boolean parallel) {
        double sintab = Math.sin((double) angle * Math.PI / 180 - Math.PI / 2);
        double costab = Math.cos((double) angle * Math.PI / 180 - Math.PI / 2);

        // Project each pixel in the image
        int Xcenter = image.sizeX() / 2;
        int Ycenter = image.sizeY() / 2;
        
        //if no. scans is greater than the image minimal dimension, then scale will be <1
        double scale = Math.min(image.sizeX(), image.sizeY()) / (double)proj.length;

        double sang = Math.sqrt(2) / 2;
        double a = -costab / sintab;
        double aa = 1 / a;
        if (Math.abs(sintab) > sang) {
            parallele(IntStream.range(0, proj.length), parallel).forEach(projIdx -> {
                int N = projIdx - proj.length / 2;
                double b = (N - costab - sintab) / sintab;
                b *= scale;
                double val = 0;
                double n = 0;
                for (int x = -Xcenter; x < Xcenter; x++) {
                    
                    //linear interpolation
                    int y = (int) Math.round(a * x + b);
                    double weight = Math.abs((a * x + b) - Math.ceil(a * x + b));

                    if (y >= -Ycenter && y + 1 < Ycenter) {
                        val += (1 - weight) * image.getPixel(x+Xcenter, y + Ycenter, z)  + weight * image.getPixel(x+Xcenter, y + Ycenter + 1, z);
                        ++n;
                    }

                    
                }
                if (n>0) proj[projIdx] = (float) ((val / Math.abs(sintab)) / n);
                else proj[projIdx] = Float.NaN;

            });
        }
        if (Math.abs(sintab) <= sang) {
            parallele(IntStream.range(0, proj.length), parallel).forEach(projIdx -> {
                int N = projIdx - proj.length / 2;
                double bb = (N - costab - sintab) / costab;
                bb = bb * scale;
                //IJ.write("bb="+bb+" ");
                double n = 0;
                double val = 0;
                for (int y = -Ycenter; y < Ycenter; y++) {
                    int x = (int) Math.round(aa * y + bb);
                    double weight = Math.abs((aa * y + bb) - Math.ceil(aa * y + bb));
                    if (x >= -Xcenter && x + 1 < Xcenter) {
                        val += (1 - weight) * image.getPixel(x+Xcenter, y + Ycenter, z)
                                + weight * image.getPixel(x+Xcenter + 1, y + Ycenter, z);
                        ++n;
                    }
                }
                if (n>0) proj[projIdx] = (float) ((val / Math.abs(costab))/n);
                else proj[projIdx] = Float.NaN;
            });
        }
    }
    
    public static ImageFloat getSinogram(Image image, double angleMin, double angleMax, double stepSize, int projSize) {
        double[] angles = getAngleArray(angleMin, angleMax, stepSize);
        ImageFloat res = new ImageFloat("sinogram", angles.length, projSize, 1);
        float[] proj = new float[projSize];
        for (int i = 0; i<angles.length; ++i) {
            radonProject(image, 0, angles[i], proj, true);
            for (int j = 0; j<projSize; ++j) if (proj[j]!=Float.NaN) res.setPixel(i, j, 0, proj[j]);
        }
        return res;
    }
    
    
    
    public static double[] getAngleArray(double ang1, double ang2, double stepsize) {
        if (ang1>ang2) {double temp = ang1; ang1=ang2; ang2=temp;}
        double[] angles = new double [(int)(Math.abs(ang2-ang1) / stepsize + 0.5d)]; 
        angles[0]=ang1;
        for (int i=1; i<angles.length; ++i) angles[i]=(angles[i-1]+stepsize)%360;
        return angles;
    }
    
    
    
    
    
    private static void paste(float[] source, ImageFloat dest, int x) {
        float[] pixDest = dest.getPixelArray()[0];
        for (int i = 0; i<source.length; ++i) pixDest[x + i*dest.sizeX()] = source[i];
    }

    public static float max(float[] array) {
        if (array.length == 0) return 0;
        int idx = 0;
        while(idx<array.length && array[idx]==Float.NaN) idx++;
        if (idx==array.length) return 0;
        float max = array[idx];
        for (int i = idx+1; i < array.length; ++i) {
            if (array[i]!=Float.NaN && array[i] > max) {
                max = array[i];
            }
        }
        return max;
    }

    public static double var(float[] array) {
        if (array.length == 0) return 0;
        double sum = 0;
        double sum2 = 0;
        int count=0;
        for (int i = 0; i < array.length; ++i) {
            if (array[i]!=Float.NaN) {
                sum += array[i];
                sum2 += array[i] * array[i];
                ++count;
            }
        }
        if (count==0) return 0;
        sum /= (double) count;
        sum2 /= (double) count;
        return sum2 - sum * sum;
    }
    
    
    
    
}
