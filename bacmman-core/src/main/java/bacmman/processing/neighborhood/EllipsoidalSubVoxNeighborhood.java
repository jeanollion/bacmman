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

import bacmman.image.Image;

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class EllipsoidalSubVoxNeighborhood {
    Coordinate[] coords;
    double radius, radiusZ;
    boolean is3D;
    int valueCount;
    float[] values;
    float[] distances;
    /**
     * 2D Circular Neighbourhood around a voxel
     * @param radius in pixel in the XY-axis
     * an array of diplacement from the center. 
     * @param excludeCenter if true, central point can excluded
     */
    public EllipsoidalSubVoxNeighborhood(double radius, boolean excludeCenter) { 
        this.radius = radius;
        this.radiusZ=radius;
        is3D=false;
        int rad = (int) (radius + 0.5f);
        Coordinate[] temp = new Coordinate[(2 * rad + 1) * (2 * rad + 1)];
        int count = 0;
        double rad2 = radius * radius;
        double rad2Ext = (radius+0.5) * (radius + 0.5);
        for (int yy = -rad; yy <= rad; yy++) {
            for (int xx = -rad; xx <= rad; xx++) {
                float d2 = yy * yy + xx * xx;
                if (d2 <= rad2Ext && (!excludeCenter || d2 > 0)) {	//exclusion du point central
                    if (d2<= rad2) {
                        temp[count] = new Coordinate(xx, yy, 0, Math.sqrt(d2));
                    } else { // approximation: point le plus proche 
                        double d = Math.sqrt(d2);
                        float factor = (float) (rad / d);
                        temp[count] = new Coordinate(xx * factor, yy * factor, 0f, radius);
                    }
                    count++;
                }
            }
        }
        this.coords=new Coordinate[count];
        System.arraycopy(temp, 0, coords, 0, count);
        Arrays.sort(coords);
        this.values=new float[count];
        this.distances= new float[count];
        for (int i = 0; i<count; ++i) distances[i] = (float)coords[i].distance;
        Image.logger.debug("Neighborhood: {}", (Object[])coords);
        for (Coordinate c : coords) Image.logger.debug("Neighborhood: {}", c);
    }
    
    /**
     * 3D Elipsoidal Neighbourhood around a voxel
     * @param radius in pixel in the XY-axis
     * @param radiusZ in pixel in the Z-axis
     * @param excludeCenter if true, central point can excluded
     * return an array of diplacement from the center
     */
    public EllipsoidalSubVoxNeighborhood(double radius, double radiusZ, boolean excludeCenter) {
        this.radius=radius;
        this.radiusZ=radiusZ;
        is3D=true;
        double r = (double) radius / radiusZ;
        int rad = (int) (radius + 0.5f);
        int radZ = (int) (radiusZ + 0.5f);
        Coordinate[] temp = new Coordinate[(2 * rad + 1) * (2 * rad + 1) * (2 * radZ + 1)];
        int count = 0;
        double rad2 = radius * radius;
        double rad2Ext = (radius+0.5) * (radius + 0.5);
        for (int zz = -radZ; zz <= radZ; zz++) {
            for (int yy = -rad; yy <= rad; yy++) {
                for (int xx = -rad; xx <= rad; xx++) {
                    double d2 = zz * r * zz * r + yy * yy + xx * xx;
                    if (d2 <= rad2Ext && (!excludeCenter || d2 > 0)) {	//exclusion du point central
                        if (d2<= rad2) {
                            temp[count] = new Coordinate(xx, yy, zz, Math.sqrt(d2));
                        } else { // approximation: point le plus proche 
                            double d = Math.sqrt(d2);
                            double factor = (rad / d);
                            temp[count] = new Coordinate((float)(xx * factor), (float)(yy * factor), (float)(zz * factor / r), radius);
                        }
                        count++;
                    }
                }
            }
        }

        this.coords=new Coordinate[count];
        System.arraycopy(temp, 0, coords, 0, count);
        Arrays.sort(coords);
        this.values=new float[count];
        this.distances= new float[count];
        for (int i = 0; i<count; ++i) distances[i] = (float)coords[i].distance;
    }
    
    //@Override 
    public void setPixels(int x, int y, int z, Image image) {
        valueCount=0;
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<coords.length; ++i) {
                xx=x+coords[i].x;
                yy=y+coords[i].y;
                zz=z+coords[i].z;
                if (image.contains(xx, yy, zz)) {
                    if (coords[i].floating) {
                        if (xx+1<image.sizeX() && yy+1<image.sizeY() && zz+1<image.sizeZ()) values[valueCount++]=image.getPixelLinInter(xx, yy, zz, coords[i].dx, coords[i].dy, coords[i].dz);
                    }
                    else values[valueCount++]=image.getPixel(xx, yy, zz);
                }
            }
        } else {
            for (int i = 0; i<coords.length; ++i) {
                xx=x+coords[i].x;
                yy=y+coords[i].y;
                if (image.contains(xx, yy, 0)) {
                    if (coords[i].floating) {
                        if (xx+1<image.sizeX() && yy+1<image.sizeY()) values[valueCount++]=image.getPixelLinInterXY(xx, yy, 0, coords[i].dx, coords[i].dy);
                    }
                    else values[valueCount++]=image.getPixel(xx, yy, 0);
                }
            }
        }
    }
    
    //@Override 
    public int getSize() {return coords.length;}

    //@Override 
    public float[] getPixelValues() {
        return values;
    }

    //@Override 
    public int getValueCount() {
        return valueCount;
    }
    //@Override 
    public float[] getDistancesToCenter() {
        return distances;
    }

    //@Override 
    public double getRadiusXY() {
        return radius;
    }

    //@Override 
    public double getRadiusZ() {
        return radiusZ;
    }
    
    private class Coordinate implements Comparable<Coordinate> {
        int x, y, z;
        float dx, dy, dz;
        double distance;
        boolean floating;
        public Coordinate(int x, int y, int z, double distance) {
            this.x=x;
            this.y=y;
            this.z=z;
            this.dx=0;
            this.dy=0;
            this.dz=0;
            this.distance=distance;
            floating = false;
        }
        public Coordinate(int x, int y, int z, float dx, float dy, float dz, double distance) {
            this.x=x;
            this.y=y;
            this.z=z;
            this.dx=dx;
            this.dy=dy;
            this.dz=dz;
            this.distance=distance;
            floating = dx!=0 || dy!=0 || dz!=0;
        }
        public Coordinate(float x, float y, float z, double distance) {
            this.x=(int)x;
            this.y=(int)y;
            this.z=(int)z;
            this.dx=x-this.x;
            this.dy=y-this.y;
            this.dz=z-this.z;
            this.distance=distance;
            floating = dx!=0 || dy!=0 || dz!=0;
        }
        @Override public String toString() {
            return "x:"+(x+dx)+" y:"+(y+dy)+ " z:"+(z+dz)+ " distance:"+distance;
        }
        public int compareTo(Coordinate o) {
            return Double.compare(distance, o.distance);
        }
    }
}
