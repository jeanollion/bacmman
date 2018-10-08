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
package bacmman.processing.neighborhood;

/**
 *
 * @author Jean Ollion
 */
public class NeighborhoodFactory {
    

    public static int[][] getNeighbourhood3D(float radius, float radiusZ, float thickness) {
        if (radiusZ > 0 && radiusZ < 1) {
            radiusZ = 1;
        }
        //IJ.log("neigh: XY:"+radius+" Z:"+radiusZ+ " Thickness:"+thickness);
        float r = (float) radius / radiusZ;
        int rad = (int) (radius + 0.5f);
        int radZ = (int) (radiusZ + 0.5f);
        int[][] temp = new int[3][(2 * rad + 1) * (2 * rad + 1) * (2 * radZ + 1)];
        //float[] tempDist = new float[temp[0].length];
        int count = 0;
        float rad2 = radius * radius;
        float radMin = (radius >= thickness) ? (float) Math.pow(radius - thickness, 2) : Float.MIN_VALUE; //:exclusion du point central
        for (int zz = -radZ; zz <= radZ; zz++) {
            for (int yy = -rad; yy <= rad; yy++) {
                for (int xx = -rad; xx <= rad; xx++) {
                    float d2 = zz * r * zz * r + yy * yy + xx * xx;
                    if (d2 <= rad2 && d2 > radMin && !((xx == 0) && (yy == 0) && (zz == 0))) {
                        temp[0][count] = xx;
                        temp[1][count] = yy;
                        temp[2][count] = zz;
                        //tempDist[count] = (float) Math.sqrt(d2);
                        //IJ.log("neigh: X:"+xx+" Y:"+yy+ " Z:"+zz);
                        count++;
                    }
                }
            }
        }
        int[][] res = new int[3][count];
        System.arraycopy(temp[0], 0, res[0], 0, count);
        System.arraycopy(temp[1], 0, res[1], 0, count);
        System.arraycopy(temp[2], 0, res[2], 0, count);
        //ij.IJ.log("Neigbor: Radius:"+radius+ " radiusZ"+radiusZ+ " count:"+count);
        return res;
    }

    public static int[][] getHalfNeighbourhood(float radius, float radiusZ, float thickness) {
        if (radiusZ > 0 && radiusZ < 1) {
            radiusZ = 1;
        }
        //IJ.log("neigh: XY:"+radius+" Z:"+radiusZ+ " Thickness:"+thickness);
        float r = (float) radius / radiusZ;
        int rad = (int) (radius + 0.5f);
        int radZ = (int) (radiusZ + 0.5f);
        int[][] temp = new int[3][(2 * rad + 1) * (2 * rad + 1) * (2 * radZ + 1)];
        //float[] tempDist = new float[temp[0].length];
        int count = 0;
        float rad2 = radius * radius;
        float radMin = (radius >= thickness) ? (float) Math.pow(radius - thickness, 2) : Float.MIN_VALUE; //exclusion du point central
        for (int zz = 0; zz <= radZ; zz++) {
            for (int yy = (zz == 0) ? 0 : -rad; yy <= rad; yy++) {
                for (int xx = (zz == 0 && yy == 0) ? -rad + 1 : -rad; xx <= rad; xx++) {
                    float d2 = zz * r * zz * r + yy * yy + xx * xx;
                    if (d2 <= rad2 && d2 > radMin) {
                        temp[0][count] = xx;
                        temp[1][count] = yy;
                        temp[2][count] = zz;
                        //tempDist[count] = (float) Math.sqrt(d2);
                        //IJ.log("neigh: X:"+xx+" Y:"+yy+ " Z:"+zz);
                        count++;
                    }
                }
            }
        }
        int[][] res = new int[3][count];
        System.arraycopy(temp[0], 0, res[0], 0, count);
        System.arraycopy(temp[1], 0, res[1], 0, count);
        System.arraycopy(temp[2], 0, res[2], 0, count);
        //ij.IJ.log("Neigbor: Radius:"+radius+ " radiusZ"+radiusZ+ " count:"+count);
        return res;
    }
    
    
}
