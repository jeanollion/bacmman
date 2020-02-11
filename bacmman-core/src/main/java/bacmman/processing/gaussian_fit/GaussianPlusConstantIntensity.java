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

import net.imglib2.algorithm.localization.FitFunction;

/**
 * A n-dimensional, symmetric Gaussian peak function.
 * <p>
 * This fitting target function is defined over dimension <code>n</code>, by the
 * following <code>2</code> parameters:
 * 
 * <pre>
 * k = 0 A
 * k = 1 C
 * </pre>
 * 
 * with
 * 
 * <pre>
 * f(x) = A × exp( - S ) + C
 * </pre>
 * 
 * and
 * 
 * <pre>
 * S = b × ∑ (xᵢ - x₀ᵢ)²
 * </pre>
 * 
 * @author Jean Ollion, from Jean-Yves Tinevez <jeanyves.tinevez@gmail.com> - 2013
 */
public class GaussianPlusConstantIntensity implements FitFunction {
	final boolean fitConstant;
	public GaussianPlusConstantIntensity(boolean fitConstant) {
		this.fitConstant=fitConstant;
	}
	/*
	 * METHODS
	 */

	@Override
	public String toString() {
		return "Gaussian function A × exp( -b × ∑ (xᵢ - x₀ᵢ)² + C)";
	}

	@Override
	public final double val(final double[] x, final double[] a) {
		return a[a.length-3] * E(x, a) + a[a.length-1];
	}
	// TODO: if A = N / (2 * pi * Sigma) b and A are dependent and derivatives' formula are wrong!
	/**
	 * Partial derivatives indices are ordered as follow:
	 * <pre>k = 0..n-1  - x_i (with i = k)
	 *k = n       - A
	 *k = n+1     - b</pre> 
	 */
	@Override
	public final double grad(final double[] x, final double[] a, final int k) {
		final int ndims = x.length;
		if (k == a.length-3) {
			// With respect to A
			return E(x, a);

		} else if (k < ndims) {
			// With respect to xi
			return 0;

		} else if (k== a.length-2 ){
			// With respect to b
			return 0;
		} else {// With respect to C
                    return fitConstant ? 1 : 0;
                }
	}

	@Override
	public final double hessian(final double[] x, final double[] a, int rIn, int cIn)
	{
		return 0;

	}

	/*
	 * PRIVATE METHODS
	 */

	private static final double S(final double[] x, final double[] a) {
		double sum = 0;
		for (int i = 0; i < x.length; i++) sum += Math.pow(x[i] - a[i], 2);
		return a[a.length-2] * sum;
	}

	private static final double E(final double[] x, final double[] a) {
		return Math.exp(- S(x,a));
	}


}
