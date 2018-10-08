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
package bacmman.utils;

import java.util.stream.IntStream;

/**
 *
 * @author adapted from http://introcs.cs.princeton.edu/java/97data/LinearRegression.java.html
 */
public class LinearRegression { 

    public static double[] run(double[] x, double[] y) { 
        if (x.length!=y.length) throw new IllegalArgumentException("x & y should be of same length");
        if (x.length<=1) return new double[]{Double.NaN, Double.NaN};
        // first pass: compute xbar and ybar
        double sumx = 0.0, sumy = 0.0, sumx2 = 0.0;
        for (int i =0; i<x.length; ++i) {
            sumx  += x[i];
            sumx2 += x[i] * x[i];
            sumy  += y[i];
        }
        double xbar = sumx / x.length;
        double ybar = sumy / x.length;

        // second pass: compute summary statistics
        double xxbar = 0.0, yybar = 0.0, xybar = 0.0;
        for (int i = 0; i < x.length; i++) {
            xxbar += (x[i] - xbar) * (x[i] - xbar);
            yybar += (y[i] - ybar) * (y[i] - ybar);
            xybar += (x[i] - xbar) * (y[i] - ybar);
        }
        double beta1 = xybar / xxbar;
        double beta0 = ybar - beta1 * xbar;
        return new double[] {beta0, beta1};
        //"y   = " + beta1 + " * x + " + beta0
        /*
        // analyze results
        int df = n - 2;
        double rss = 0.0;      // residual sum of squares
        double ssr = 0.0;      // regression sum of squares
        for (int i = 0; i < n; i++) {
            double fit = beta1*x[i] + beta0;
            rss += (fit - y[i]) * (fit - y[i]);
            ssr += (fit - ybar) * (fit - ybar);
        }
        double R2    = ssr / yybar;
        double svar  = rss / df;
        double svar1 = svar / xxbar;
        double svar0 = svar/x.length + xbar*xbar*svar1;
        //"R^2                 = " + R2);
        //"std error of beta_1 = " + Math.sqrt(svar1)
        //"std error of beta_0 = " + Math.sqrt(svar0)
        svar0 = svar * sumx2 / (x.length * xxbar);
        //"std error of beta_0 = " + Math.sqrt(svar0)

        //"SSTO = " + yybar
        //"SSE  = " + rss
        //"SSR  = " + ssr
                */
    }
    public static double[] getResiduals(double[] x, double[] y, double intersect, double slope) {
        return IntStream.range(0, x.length).mapToDouble(i -> y[i] - (intersect + x[i] * slope)).toArray();
    }

}
