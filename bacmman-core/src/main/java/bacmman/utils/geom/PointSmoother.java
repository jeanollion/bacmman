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
public class PointSmoother<T extends Point<T>> {
    @FunctionalInterface  private interface GaussFunction {public double getCoeff(double distSq);}
    final GaussFunction gaussCoeff;
    final double limit, coeff0;
    double sum;
    int numDim;
    T currentVector;
    public PointSmoother(double sigma) {
        double sig2 = sigma * sigma * 2;
        double coeff = 1/Math.sqrt(sig2 * Math.PI);
        gaussCoeff = x2 -> coeff * Math.exp(-x2/sig2);
        limit = gaussCoeff.getCoeff(9*sigma*sigma);
        coeff0 = gaussCoeff.getCoeff(0);
    }
    public void init(T start, boolean duplicate) {
        if (duplicate) currentVector = start.duplicate();
        else currentVector = start;
        currentVector.multiply(coeff0);
        sum = coeff0;
        numDim = currentVector.numDimensions();
    }
    public boolean add(T v, double dist) {
        return addDistSq(v, dist*dist);
    }
    public boolean addDistSq(T v, double distSq) {
        double c = gaussCoeff.getCoeff(distSq);
        if (c<=limit) return false;
        sum+=c;
        currentVector.add(v, c);
        return true;
    }
    public boolean addRealLocalizable(RealLocalizable loc, double dist) {
        return addRealLocalizableDistSq(loc, dist*dist);
    }
    public boolean addRealLocalizableDistSq(RealLocalizable loc, double distSq) {
        double c = gaussCoeff.getCoeff(distSq);
        if (c<=limit) return false;
        sum+=c;
        for (int i = 0; i<numDim; ++i) currentVector.addDim(loc.getDoublePosition(i)*c, i);
        return true;
    }
    public T getSmoothed() {
        currentVector.multiply(1d/sum);
        return currentVector;
    }

}
