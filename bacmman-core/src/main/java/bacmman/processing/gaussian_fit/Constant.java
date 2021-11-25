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
 * A constant function.
 * <p>
 * This fitting target function is defined over dimension <code>n</code>, by the
 * following <code>1</code> parameters:
 * 
 * <pre>
 * k = 0      - C
 * </pre>
 *
 * @author Jean Ollion
 */
public class Constant implements FitFunction {
	public Constant() {
	}

	@Override
	public String toString() {
		return "Constant function C";
	}

	@Override
	public final double val(final double[] x, final double[] a) {
		return a[0];
	}

	@Override
	public final double grad(final double[] x, final double[] a, final int k) {
		if (k==0) return 1; // constant
		throw new IllegalArgumentException("K < 1");
	}

	@Override
	public final double hessian(final double[] x, final double[] a, int rIn, int cIn) {
		return 0;
	}

}
