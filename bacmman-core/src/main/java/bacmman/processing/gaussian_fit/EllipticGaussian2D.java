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
package bacmman.processing.gaussian_fit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A 2-dimensional, Ellipse Gaussian peak function.
 * <p>
 * This fitting target function is defined over dimension <code>2</code>, by the
 * following <code>6</code> parameters:
 * 
 * <pre>
 * k = 0..1    - x₀ᵢ (with i = k)
 * k = 2       - a
 * k = 3 	   - b
 * k = 4	   - c
 * k = 5       - A
 * </pre>
 * 
 * with
 * 
 * <pre>
 * f(x) = A × exp( S )
 * </pre>
 * 
 * and
 * 
 * <pre>
 * S = - [ a × (xᵢ - x₀ᵢ)² + b × (yᵢ - y₀ᵢ)² + 2c × (xᵢ - x₀ᵢ) * (yᵢ - y₀ᵢ) ]
 * </pre>
 * 
 * @author Jean Ollion
 */
public class EllipticGaussian2D implements FitFunctionCustom, FitFunctionUntrainableParameters, FitFunctionScalable {
	public static final Logger logger = LoggerFactory.getLogger(EllipticGaussian2D.class);
	double maxSemiMajorAxis = Double.NaN;
	double eps = Double.NaN;;
	final boolean backgroundIsFitApart;
	double[] centerRange;
	public EllipticGaussian2D(boolean backgroundIsFitApart) {
		this.backgroundIsFitApart=backgroundIsFitApart;
	}

	public int[] getUntrainableIndices(int nDims, boolean fitCenter, boolean fitAxis) {
		if (fitCenter && fitAxis) return new int[0];
		if (!fitCenter && fitAxis) return new int[]{0, 1};
		if (fitCenter) return new int[]{2, 3, 4};
		return new int[]{0, 1, 2, 3, 4};
	}
	/*
	 * METHODS
	 */

	@Override
	public String toString() {
		return "Ellipse Gaussian function A × exp( - a × (xᵢ - x₀)² - b × (yᵢ - y₀)² - 2c × (x - x₀)(y - y₀) )";
	}

	@Override
	public final double val(final double[] x, final double[] a) {
		return a[5] * E(x, a);
	}

	/**
	 * Partial derivatives indices are ordered as follows:
	 * <pre>
	 * k = 0..1    - x₀ᵢ (with i = k)
	 * k = 2       - a
	 * k = 3 	   - b
	 * k = 4	   - c
	 * k = 5       - A
	 *k = n+1     - b</pre> 
	 */
	@Override
	public EllipticGaussian2D setSizeLimit(double maxSemiMajorAxis) {
		this.maxSemiMajorAxis = maxSemiMajorAxis;
		this.eps = 1/Math.pow(maxSemiMajorAxis, 2);
		return this;
	}
	public EllipticGaussian2D setPositionLimit(double[] centerRange) {
		this.centerRange = centerRange;
		return this;
	}
	@Override
	public boolean isValid(double[] initialParameters, double[] a) {
		if (!Double.isNaN(eps)) {
			// AB>C*C so that Major axis is defined (A+B-Q^0.5 > 0) add eps
			if (a[2] * a[3] <= a[4] * a[4] + (a[2] + a[3]) * eps) return false;
		}
		if (centerRange!=null) {
			for (int i = 0; i<2; ++i) {
				if (Math.abs(initialParameters[i] - a[i])>centerRange[i]) return false;
			}
		}
		return true;
	}

	@Override
	public final double grad(final double[] x, final double[] a, final int k) {
		if (k < 5) {
			return dS(x, a, k) * val(x, a);
			//if (k==4 && !isValid(a)) return 0; //-grad? // TODO study this option to avoid invalid ellipses
			//return grad;
		} else if (k == 5) {
			return E(x, a); // With respect to A
		} else throw new RuntimeException("k must be inferior or equal to 5");
	}

	@Override
	public final double hessian(final double[] x, final double[] a, int rIn, int cIn) {
		int r = rIn;
		int c = cIn;
		if (c < r) {
			int tmp = c;
			c = r;
			r = tmp;
		} // Ensure c >= r, top right half the matrix
		if (c<=4) {
			// d²G/dCdR = G * ( d²S / dCdR + dS/dC * dS/dR)
			double d = dS(x, a, c) * dS(x, a, r);
			if (c<2) {
				if (r==c) d+=-2*a[c+2]; //dx2 or dy2
				else d+=-2*a[4]; // dxdy
			} else if (c<4 && r<2) { //dab * dxy
				if (r+2==c) d+=2 * (x[r] - a[r]);
			} else if (c==4 && r<2) { // dc * dxy
				int other = r==0 ? 1 : 0;
				d+=2 * (x[other] - a[other]);
			}
			// dS/dadb, dS/dadc .. = 0
			double hess =  d * val(x, a);
			//if (!isValid(a)) return 0; // TODO study this option to avoid invalid ellipses
			return hess;
		} else if (c == 5) {
			if (r==c) return 0;
			return dS(x, a, r) * E(x, a); // ==grad/A
		}
		return 0;
		//throw new RuntimeException("c and r must be inferior or equal to 5");
	}

	/*
	 * PRIVATE METHODS
	 */

	private static final double S(final double[] x, final double[] a) {
		double dx = x[0] - a[0], dy = x[1] - a[1];
		return - (a[2] * dx * dx + a[3] * dy * dy + 2 * a[4] * dx * dy);
	}

	private static final double E(final double[] x, final double[] a) {
		return Math.exp(S(x,a));
	}

	private static final double dS(final double[] x, final double[] a, final int k) {
		if (k<2) { // With respect to center x₀
			int other = k == 0 ? 1 : 0;
			return 2 * (a[k + 2] * (x[k] - a[k]) + a[4] * (x[other] - a[other]));
		} else if (k<4) { // With respect to a or b
			return - Math.pow(x[k-2] - a[k-2], 2);
		} else if (k==4) { // With respect to c
			return - 2 * (x[0] - a[0]) * (x[1] - a[1]);
		} else throw new IllegalArgumentException("K must be <5");
	}

	@Override
	public void scaleIntensity(double[] parameters, double center, double scale, boolean normalize) {
		if (backgroundIsFitApart) {
			if (normalize) parameters[5] = parameters[5] / scale;
			else parameters[5] = parameters[5] * scale;
		} else {
			if (normalize) parameters[5] = (parameters[5] - center) / scale;
			else parameters[5] = parameters[5] * scale + center;
		}
	}

	@Override
	public int getNParameters(int nDims) {
		if (nDims!=2) throw new IllegalArgumentException("Only valid in 2D");
		return 6;
	}
}
