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

import bacmman.image.ImageByte;
import bacmman.image.MutableBoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class CylindricalNeighborhood extends DisplacementNeighborhood {
    double radius;
    double radiusZUp, radiusZDown;
    
    /**
     * 3D Cylindrical Neighbourhood around a voxel, with Z-axis as height
     * @param radiusXY in pixel in the XY-axis
     * @param heightZ height of the cilinder in pixel in the Z-axis
     * @param excludeCenter if true, central point can excluded
     * return an array of diplacement from the center
     */
    public CylindricalNeighborhood(double radiusXY, double heightZ, boolean excludeCenter) {
        this(radiusXY, heightZ, heightZ, excludeCenter);
    }
    @Override public CylindricalNeighborhood duplicate() {
        return new CylindricalNeighborhood(radius, radiusZUp, radiusZDown, !(dx[0]==0 && dy[0] == 0 && dz[0]==0));
    }
    public CylindricalNeighborhood(double radiusXY, double heightZDown, double heightZUp, boolean excludeCenter) {
        this.radius=radiusXY;
        this.radiusZUp=heightZUp;
        this.radiusZDown=heightZDown;
        is3D=radiusZUp>0 || radiusZDown>0;
        int rad = (int) (radius + 0.5d);
        int radZUp = (int) (radiusZUp + 0.5d);
        int radZDown = (int) (radiusZDown + 0.5d);
        List<double[]> coordsXYZD = new ArrayList<>();
        double rad2 = radius * radius;
        for (int yy = -rad; yy <= rad; yy++) {
            for (int xx = -rad; xx <= rad; xx++) {
                double d2 = yy * yy + xx * xx;
                if (d2 <= rad2) {
                    for (int zz = -radZDown; zz <= radZUp; zz++) {
                        if (!excludeCenter || d2 > 0 || zz!=0) {//exclusion du point central
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
        }

        Collections.sort(coordsXYZD, (a1, a2)->Double.compare(a1[3], a2[3]));
        distances = new float[coordsXYZD.size()];
        dx= new int[coordsXYZD.size()];
        dy= new int[coordsXYZD.size()];
        dz= new int[coordsXYZD.size()];
        values=new float[coordsXYZD.size()];
        for (int i = 0; i<coordsXYZD.size(); ++i) {
            double[] c = coordsXYZD.get(i);
            dx[i] = (int)c[0];
            dy[i] = (int)c[1];
            dz[i] = (int)c[2];
            distances[i] = (float)c[3];
        }
    }
    
    @Override public String toString() {
        if (this.is3D) {
            String res = "3D CylindricalNeighborhood: XY:"+this.radius+" Z (down):"+radiusZDown+" Z (up):"+radiusZUp+" [";
            for (int i = 0; i<dx.length; ++i) res+="dx:"+dx[i]+",dy:"+dy[i]+",dz:"+dz[i]+";";
            return res+"]";
        } else {
            String res = "Cylindrical Neighborhood2D: radius:"+radius+ " [";
            for (int i = 0; i<dx.length; ++i) res+="dx:"+dx[i]+",dy:"+dy[i]+";";
            return res+"]";
        }
        
    }
    
    @Override public MutableBoundingBox getBoundingBox() {
        int r = (int) radius;
        return new MutableBoundingBox(-r, r, -r, r, -(int)radiusZDown, (int)radiusZUp);
    }
    
    public ImageByte drawNeighborhood(ImageByte output) {
        int centerXY, centerZ;
        if (output == null) {
            int radXY = (int)(this.radius+0.5);
            int radZUp = (int)(this.radiusZUp+0.5);
            int radZDown = (int)(this.radiusZDown+0.5);
            centerXY=radXY;
            centerZ=radZDown;
            if (is3D) output = new ImageByte("3D CylindricalNeighborhood: XY:"+this.radius+" Z (down):"+radZDown+" Z (up):"+radZUp, radXY*2+1, radXY*2+1, (radZUp+radZDown)+1);
            else output = new ImageByte("2D CylindricalNeighborhood: XY:"+this.radius, radXY*2+1, radXY*2+1, 1);
        } else {
            centerXY = output.sizeX()/2+1;
            centerZ = output.sizeZ()/2+1;
        }
        if (is3D) for (int i = 0; i<this.dx.length;++i) {
            //logger.debug("set pix: x: {}/{}, y: {}/{}, z: {}/{}", centerXY+dx[i], output.getSizeX()-1, centerXY+dy[i], output.getSizeY()-1,  centerZ+dz[i], output.getSizeZ()-1);
            output.setPixel(centerXY+dx[i], centerXY+dy[i], centerZ+dz[i], 1);
        }
        else for (int i = 0; i<this.dx.length;++i) output.setPixel(centerXY+dx[i], centerXY+dy[i], 0, 1);
        return output;
    }
    
}
