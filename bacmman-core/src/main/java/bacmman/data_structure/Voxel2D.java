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
package bacmman.data_structure;

/**
 *
 * @author Jean Ollion
 */
public class Voxel2D extends Voxel {
    
    public Voxel2D(int x, int y) {
        super(x, y, 0);
    }
    
    public Voxel2D(int x, int y, int z) {
        super(x, y, z);
    }

    public Voxel2D(int x, int y, int z, double value) {
        super(x, y, z, value);
    }
    
    public Voxel2D(int x, int y, float value) {
        super(x, y, 0, value);
    }
    
    public Voxel toVoxel() {
        return new Voxel(x, y, z, value);
    }
    @Override
    public Voxel2D toVoxel2D() {
        return this;
    }
    @Override
    public boolean equals(Object other) {
        if (other instanceof Voxel) {
            Voxel otherV = (Voxel)other;
            return x==otherV.x && y==otherV.y;
        } else return false;
    }
    
    
    @Override
    public String toString() {
        return "Voxel2D{" + "x=" + x + ", y=" + y + ", z=" + z + ", value=" + value + '}';
    }
}
