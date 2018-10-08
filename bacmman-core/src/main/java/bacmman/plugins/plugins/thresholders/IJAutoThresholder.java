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
package bacmman.plugins.plugins.thresholders;

import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import ij.process.AutoThresholder;
import ij.process.AutoThresholder.Method;
import bacmman.image.BlankMask;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.ThresholderHisto;
import bacmman.plugins.ops.ImgLib2HistogramWrapper;
import net.imagej.ops.threshold.AbstractComputeThresholdHistogram;
import net.imglib2.histogram.Histogram1d;
import net.imglib2.type.numeric.RealType;

/**
 *
 * @author Jean Ollion
 */
public class IJAutoThresholder implements SimpleThresholder, ThresholderHisto {
    ChoiceParameter method = new ChoiceParameter("Method", AutoThresholder.getMethods(), AutoThresholder.Method.Otsu.toString(), false);
    
    public IJAutoThresholder setMethod(AutoThresholder.Method method) {
        this.method.setValue(method.toString());
        return this;
    }
    
    @Override 
    public double runSimpleThresholder(Image input, ImageMask mask) {
        return runThresholder(input, mask, Method.valueOf(method.getSelectedItem()));
    }
    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        ImageMask mask = structureObject!=null?structureObject.getMask():new BlankMask(input);
        return runSimpleThresholder(input, mask);
    }
    
    public static double runThresholder(Image input, ImageMask mask, Method method) {
        return runThresholder(input, mask, null, method);
    }
    
    @Override
    public double runThresholderHisto(Histogram histogram) {
        return runThresholder(Method.valueOf(method.getSelectedItem()), histogram);
    }
    
    public static double runThresholder(Image input, ImageMask mask, MutableBoundingBox limits, Method method) {
        Histogram histo = HistogramFactory.getHistogram(()->mask==null ? input.stream(): input.stream(mask, true), 256);
        histo.removeSaturatingValue(4, true);
        return runThresholder(method, histo);
    }
    
    public static double runThresholder(Method method, Histogram histo) {
        if (method==null) return Double.NaN;
        if (histo.data.length!=256) return runThresholderIJ2(method, histo);
        else {
            AutoThresholder at = new AutoThresholder();
            double thld = at.getThreshold(method, histo.data);
            return histo.getValueFromIdx(thld);
        }
        
    }
    
    public static double runThresholderIJ2(Method method, Histogram histo) {
        Histogram1d histoIJ2 = ImgLib2HistogramWrapper.wrap(histo);
        AbstractComputeThresholdHistogram thlder=null;
        switch(method) {
            case Otsu:
                thlder = new net.imagej.ops.threshold.otsu.ComputeOtsuThreshold();
                break;
            case Huang:
                thlder = new net.imagej.ops.threshold.huang.ComputeHuangThreshold<>();
                break;
            case Intermodes:
                thlder = new net.imagej.ops.threshold.intermodes.ComputeIntermodesThreshold<>();
                break;
            case IsoData:
                thlder = new net.imagej.ops.threshold.isoData.ComputeIsoDataThreshold<>();
                break;
            case Li:
                thlder = new net.imagej.ops.threshold.li.ComputeLiThreshold<>();
                break;
            case MaxEntropy:
                thlder = new net.imagej.ops.threshold.maxEntropy.ComputeMaxEntropyThreshold<>();
                break;
            case Mean:
                thlder = new net.imagej.ops.threshold.mean.ComputeMeanThreshold<>();
                break;
            case MinError:
                thlder = new net.imagej.ops.threshold.minError.ComputeMinErrorThreshold<>();
                break;
            case Minimum:
                thlder = new net.imagej.ops.threshold.minimum.ComputeMinimumThreshold<>();
                break;
            case Moments:
                thlder = new net.imagej.ops.threshold.moments.ComputeMomentsThreshold<>();
                break;
            case Percentile:
                thlder = new net.imagej.ops.threshold.percentile.ComputePercentileThreshold<>();
                break;
            case RenyiEntropy:
                thlder = new net.imagej.ops.threshold.renyiEntropy.ComputeRenyiEntropyThreshold<>();
                break;
            case Shanbhag:
                thlder = new net.imagej.ops.threshold.shanbhag.ComputeShanbhagThreshold<>();
                break;
            case Triangle:
                thlder = new net.imagej.ops.threshold.triangle.ComputeTriangleThreshold<>();
                break;
            case Yen:
                thlder = new net.imagej.ops.threshold.yen.ComputeYenThreshold<>();
                break;
            default:
                throw new IllegalArgumentException("Invalid threshold method");
        }
        RealType res = thlder.createOutput(histoIJ2);
        // fix for compatibility with version 0.3 / 0.4
        java.lang.reflect.Method m;
        try {
            m = thlder.getClass().getMethod("compute", Histogram1d.class, RealType.class); // version 0.4
        } catch (NoSuchMethodException e) {
            try {
                m = thlder.getClass().getMethod("compute1", Histogram1d.class, RealType.class); // version 0.3
            } catch(NoSuchMethodException ee) {
                throw new RuntimeException(ee);
            }
        }
        try {
            m.invoke(thlder, histoIJ2, res);
        } catch (Throwable t) { 
            throw new RuntimeException(t);
        }
        return res.getRealDouble();
    }
    
    @Override    
    public Parameter[] getParameters() {
        return new Parameter[]{method};
    }

    public boolean does3D() {
        return true;
    }

    
    
}
