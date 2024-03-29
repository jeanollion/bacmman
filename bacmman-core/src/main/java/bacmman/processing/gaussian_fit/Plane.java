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

/**
 * A n-dimensional, plane function.
 * <p>
 * This fitting target function is defined over dimension <code>n</code>, by the
 * following <code>2n+1</code> parameters:
 * 
 * <pre>
 * k = 0..n-1  - a₀ᵢ
 * k = n      - C
 * </pre>
 * 
 * with
 * 
 * <pre>
 * f(x) = C + ∑ aᵢ * (xᵢ - x₀ᵢ)
 * </pre>
 * 
 * and
 * 
 * <pre>
 * x₀ᵢ center of the dataset
 * </pre>
 * 
 * @author Jean Ollion
 */
public class Plane implements FitFunctionScalable {
	public Plane() {
	}

	@Override
	public String toString() {
		return "Plane function C + ∑ aᵢ * (xᵢ - x₀ᵢ)";
	}

	@Override
	public final double val(final double[] x, final double[] a) {
		double sum = a[a.length-1];
		for (int i = 0; i < x.length; i++) sum += x[i] * a[i];
		return sum;
	}

	@Override
	public final double grad(final double[] x, final double[] a, final int k) {
		if (k==a.length-1) return 1; // constant
		else return x[k] - a[k];
	}

	@Override
	public final double hessian(final double[] x, final double[] a, int rIn, int cIn) {
		return 0;
	}

	@Override
	public void scaleIntensity(double[] parameters, double center, double scale, boolean normalize) {
		if (normalize) {
			for (int dim = 0; dim<parameters.length-1; ++dim) parameters[dim] /= scale;
			parameters[parameters.length-1] = (parameters[parameters.length-1] - center) / scale;
		} else {
			for (int dim = 0; dim<parameters.length-1; ++dim) parameters[dim] *= scale;
			parameters[parameters.length-1] = parameters[parameters.length-1] * scale + center;
		}
	}

	@Override
	public int getNParameters(int nDims) {
		return nDims + 1;
	}

	@Override
	public boolean isValid(double[] initialParameters, double[] parameters) {
		return true;
	}

	@Override
	public FitFunctionCustom setPositionLimit(double[] centerRange) {
		return this;
	}

	@Override
	public FitFunctionCustom setSizeLimit(double sizeLimit) {
		return this;
	}
}
