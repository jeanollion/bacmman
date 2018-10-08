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
package bacmman.image;


public class BlankMask extends SimpleImageProperties<BlankMask> implements ImageMask<BlankMask> {
    public BlankMask(int sizeX, int sizeY, int sizeZ, int offsetX, int offsetY, int offsetZ, double scaleXY, double scaleZ) {
        super(new SimpleBoundingBox(offsetX, offsetX+sizeX-1, offsetY, offsetY+sizeY-1, offsetZ, offsetZ+sizeZ-1), scaleXY, scaleZ);
    }

    public BlankMask(int sizeX, int sizeY, int sizeZ) {
        this(sizeX, sizeY, sizeZ, 0, 0, 0, 1, 1);
    }
    
    public BlankMask(ImageProperties properties) {
        super(properties);
    }
    
    public BlankMask(BoundingBox bounds, double scaleXY, double scaleZ) {
        super(bounds, scaleXY, scaleZ);
    }
    
    @Override
    public boolean insideMask(int x, int y, int z) {
        return true; // contains should already be checked
        //return (x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return true; // contains should already be checked
        //return (xy >= 0 && xy < sizeXY && z >= 0 && z < sizeZ);
    }
    
    @Override public int count() {
        return getSizeXYZ();
    }
    
    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return true; // contains should already be checked
        //x-=offsetX; y-=offsetY; z-=offsetZ;
        //return (x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ);
    }
    @Override
    public boolean insideMaskWithOffset(int xy, int z) {
        return true; // contains should already be checked
        //xy-=offsetXY;  z-=offsetZ;
        //return (xy >= 0 && xy < sizeXY &&  z >= 0 && z < sizeZ);
    }


    @Override
    public BlankMask duplicateMask() {
        return new BlankMask(this, scaleXY, scaleZ);
    }

}
