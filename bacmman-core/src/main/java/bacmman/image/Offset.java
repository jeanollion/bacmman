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


/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public interface Offset<T extends Offset<T>> {
    int xMin();
    int yMin();
    int zMin();
    int getIntPosition(int dim);
    Offset<T> resetOffset();
    Offset<T> reverseOffset();
    Offset<T> translate(Offset other);
    Offset<T> translate(int dX, int dY, int dZ);
    static boolean offsetNull(Offset offset) {
        return offset.xMin()==0 && offset.yMin() == 0 && offset.zMin()==0;
    }
    Offset<T> duplicate();
}
