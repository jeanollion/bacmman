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

import bacmman.data_structure.Voxel;
import bacmman.image.ImageFloat;
import bacmman.image.ImageProperties;
import bacmman.image.MutableBoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import bacmman.utils.ArrayUtil;

/**
 *
 * @author Jean Ollion
 */
public class ConicalNeighborhood extends DisplacementNeighborhood {
    double radius;
    double[] radiusZUp, radiusZDown;
    public static ConditionalNeighborhoodZ generateScaleSpaceNeighborhood(double[] radii, boolean excludeCenter) {
        return generateScaleSpaceNeighborhood(radii, radii.length, radii.length, excludeCenter);
    }
    public static ConditionalNeighborhoodZ generateScaleSpaceNeighborhood(double[] radii, int zRangeDown, int zRangeUp, boolean excludeCenter) {
        ConditionalNeighborhoodZ res = new ConditionalNeighborhoodZ(null);
        for (int z = 0; z<radii.length; ++z) {
            int sMin = Math.max(0, z-zRangeDown);
            int sMax = Math.min(radii.length-1, z+zRangeDown);
            res.setNeighborhood(z, new ConicalNeighborhood(radii[z], Arrays.copyOfRange(radii, sMin, z), Arrays.copyOfRange(radii, Math.min(z+1, sMax+1), sMax+1), excludeCenter));
        }
        return res;
    }
    @Override public ConicalNeighborhood duplicate() {
        return new ConicalNeighborhood(radius, radiusZDown, radiusZUp, !(dx[0]==0 && dy[0] == 0 && dz[0]==0));
    }
    /**
     * 3D Conical Neighbourhood around a voxel
     * @param radius in pixel in the XY-axis
     * @param radiusZUp radii for upper planes, starting from closest plane to center
     * @param radiusZDown radii for lower planes, starting from farthest plane to center
     * @param excludeCenter if true, central point can excluded
     * return an array of diplacement from the center
     */
    public ConicalNeighborhood(double radius, double[] radiusZDown, double[] radiusZUp , boolean excludeCenter) {
        this.radiusZUp=radiusZUp;
        this.radiusZDown=radiusZDown;
        this.radius=radius;
        is3D=true;
        List<double[]> coordsXYZD = new ArrayList<>();
        addNeigh(radius, 0, coordsXYZD,  excludeCenter);
        for (int zz = 1; zz <= radiusZUp.length; zz++) addNeigh(radiusZUp[zz-1], zz, coordsXYZD, false);
        for (int zz = 1; zz <= radiusZDown.length; zz++) addNeigh(radiusZDown[radiusZDown.length-zz], -zz, coordsXYZD, false);
        Collections.sort(coordsXYZD, (a1, a2)->Double.compare(a1[3], a2[3]));
        distances = new float[coordsXYZD.size()];
        dx= new int[coordsXYZD.size()];
        dy= new int[coordsXYZD.size()];
        dz= new int[coordsXYZD.size()];
        values=new double[coordsXYZD.size()];
        valuesInt=new int[coordsXYZD.size()];
        for (int i = 0; i<coordsXYZD.size(); ++i) {
            double[] c = coordsXYZD.get(i);
            dx[i] = (int)c[0];
            dy[i] = (int)c[1];
            dz[i] = (int)c[2];
            distances[i] = (float)c[3];
        }
    }
    private static void addNeigh(double radius, int zz, List<double[]> coordsXYZD, boolean excludeCenter) {
        int rad = (int)(radius+0.5);
        double rad2 = radius*radius;
        for (int yy = -rad; yy <= rad; yy++) {
            for (int xx = -rad; xx <= rad; xx++) {
                double d2 = yy * yy + xx * xx;
                if (d2 <= rad2 && (!excludeCenter || d2 > 0)) {	//exclusion du point central
                    double[] coords = new double[4];
                    coordsXYZD.add(coords);
                    coords[0] = xx;
                    coords[1]= yy;
                    coords[2]= zz;
                    coords[3] =  Math.sqrt(d2+zz*zz); // take into acount zz to sort from center
                }
            }
        }
    }

    public double getRadiusXY() {
        return radius;
    }

    @Override public MutableBoundingBox getBoundingBox() {
        int r = (int) radius;
        return new MutableBoundingBox(-r, r, -r, r, -this.radiusZDown.length, this.radiusZUp.length);
    }
    
    @Override public String toString() {
        String res = "Conlical Neighborhood3D: radiusXY:"+radius+ "radiusZUp: "+this.radiusZUp.length+ " radiusZDown: "+this.radiusZDown.length+" [";
        for (int i = 0; i<dx.length; ++i) res+="dx:"+dx[i]+",dy:"+dy[i]+",dz:"+dz[i]+";";
        return res+"]";
    }
    
    public void addVoxels(Voxel v, ImageProperties p, ArrayList<Voxel> res) {
        int xx, yy;
        if (is3D) { 
            int zz;
            for (int i = 0; i<dx.length; ++i) {
                xx=v.x+dx[i];
                yy=v.y+dy[i];
                zz=v.z+dz[i];
                if (p.contains(xx, yy, zz)) res.add(new Voxel(xx, yy, zz));
            }
        } else {
            for (int i = 0; i<dx.length; ++i) {
                xx=v.x+dx[i];
                yy=v.y+dy[i];
                if (p.contains(xx, yy, v.z)) res.add(new Voxel(xx, yy, v.z));
            }
        }
    }

    public boolean is3D() {
        return is3D;
    }
    
    public ImageFloat drawNeighborhood(ImageFloat output) {
        int centerXY, centerZ;
        if (output == null) {
            int radXYMax = (int)(Math.max(radiusZUp.length>0 ? radiusZUp[ArrayUtil.max(radiusZUp)]:0, radiusZDown.length>0 ? radiusZDown[ArrayUtil.max(radiusZDown)]:0)+0.5);
            centerXY=radXYMax;
            centerZ=this.radiusZDown.length;
            output = new ImageFloat("3D Conical Neighborhood: XY:", radXYMax*2+1, radXYMax*2+1, this.radiusZDown.length+this.radiusZUp.length+1);
        } else {
            centerXY = output.sizeX()/2+1;
            centerZ = output.sizeZ()/2+1;
        }
        for (int i = 0; i<this.dx.length;++i) output.setPixel(centerXY+dx[i], centerXY+dy[i], centerZ+dz[i], i+1); //distances[i]
        return output;
    }

    
    
}
