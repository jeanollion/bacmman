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
package bacmman.utils.geom;

import net.imglib2.RealLocalizable;

/**
 *
 * @author Jean Ollion
 */
public class GeomUtils {
    public static double distSqXY(RealLocalizable p1, RealLocalizable p2) {
        double res= 0;
        for (int i = 0; i<2; ++i) res+=Math.pow(p1.getDoublePosition(i)-p2.getDoublePosition(i), 2);
        return res;
    }
    public static double distXY(RealLocalizable p1, RealLocalizable p2) {
        return Math.sqrt(distSqXY(p1, p2));
    }
    public static double distSq(RealLocalizable p1, RealLocalizable p2) {
        double res= 0;
        for (int i = 0; i<Math.min(p1.numDimensions(), p2.numDimensions()); ++i) res+=Math.pow(p1.getDoublePosition(i)-p2.getDoublePosition(i), 2);
        return res;
    }
    public static double dist(RealLocalizable p1, RealLocalizable p2) {
        return Math.sqrt(distSq(p1, p2));
    }
}
