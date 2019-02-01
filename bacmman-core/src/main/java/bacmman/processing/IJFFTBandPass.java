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
package bacmman.processing;

import bacmman.image.Image;
import bacmman.image.wrappers.IJImageWrapper;
import ij.ImagePlus;
import ij.process.ImageProcessor;

/**
 *
 * @author Jean Ollion
 */
public class IJFFTBandPass {
    public static Image bandPass(Image input, double min, double max, int stripes, double stripeTolerance) {
        return ImageOperations.applyPlaneByPlane(input, i->bandPass2D(i, min, max, stripes, stripeTolerance));
    }
    private static Image bandPass2D(Image input, double min, double max, int stripes, double stripeTolerance) {
        if (max<=min) throw new IllegalArgumentException("Max radius should be superior to Min radius");
        if (min<0) throw new IllegalArgumentException("Min should be >=0");
        ImagePlus ip = IJImageWrapper.getImagePlus(input);
        FftBandPassFilter fftBandPassFilter = new FftBandPassFilter(ip, min, max, stripes, stripeTolerance);
        
        ImageProcessor imp=fftBandPassFilter.run(ip.getProcessor());
        Image res = IJImageWrapper.wrap(new ImagePlus("FFT of "+input.getName(), imp));
        res.setCalibration(input).resetOffset().translate(input);
        return res;
    }
    public static Image suppressHorizontalStripes(Image input) {
        ImagePlus ip = IJImageWrapper.getImagePlus(input);
        FftBandPassFilter fftBandPassFilter = new FftBandPassFilter(ip, 0, 200, 1, 0);
        
        ImageProcessor imp=fftBandPassFilter.run(ip.getProcessor());
        Image res= IJImageWrapper.wrap(new ImagePlus("FFT of "+input.getName(), imp));
        res.setCalibration(input).resetOffset().translate(input);
        return res;
    }
}
