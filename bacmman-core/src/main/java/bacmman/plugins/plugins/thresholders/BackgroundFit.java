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

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.Thresholder;
import bacmman.processing.ImageFeatures;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.ThresholderHisto;
import bacmman.plugins.Hint;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import org.apache.commons.math3.fitting.GaussianCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;

/**
 * @author Jean Ollion
 */
public class BackgroundFit implements ThresholderHisto, SimpleThresholder, MultiThreaded, Thresholder, Hint {
    public static boolean debug;
    NumberParameter sigmaFactor = new BoundedNumberParameter("Sigma factor", 3, 10, 0, null).setEmphasized(true).setHint("Multiplication factor applied to background σ to compute the threshold (see module description).");
    
    public BackgroundFit() {
        
    }
    public BackgroundFit(double sigmaFactor) {
        this.sigmaFactor.setValue(sigmaFactor);
    }
    public double getSigmaFactor() {
        return sigmaFactor.getValue().doubleValue();
    }
    @Override
    public String getHintText() {
        return "This method estimates the two first moments of the background pixel intensity: Mean (µ) and Standard deviation (σ), by fitting a half-gaussian on the lower half of the mode of the distribution of pixel intensity" +
                "<br />Resulting Threshold = µ + <em>Sigma Factor</em> * σ<br />" +
                "<br />This method assumes that the mode of the pixel intensity distribution corresponds to the background values and that the lower half of the background peak is not too far from a gaussian distribution" +
                "<br >Caution: this assumption can be wrong if a rotation added many null values on the sides of the images." +
                "<br />Adapted from: T. Panier et al., “Fast functional imaging of multiple brain regions in intact zebrafish larvae using selective plane illumination microscopy” Frontiers in neural circuits, vol. 7. p. 65, 2013";
    }
    
    
    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }
    
    @Override
    public double runThresholderHisto(Histogram histogram) {
        return backgroundFit(histogram, sigmaFactor.getValue().doubleValue(), null);
    }
    
    @Override
    public double runSimpleThresholder(Image input, ImageMask mask) {
        return runThresholderHisto(HistogramFactory.getHistogram(()->Utils.parallel(input.stream(mask, true), parallel)) );
    }

    public static float[] smooth(long[] data, double scale) {
        ImageFloat image = new ImageFloat("", data.length, 1, 1);
        for (int i = 0; i<data.length; ++i) image.setPixel(i, 0, 0, data[i]);
        image = ImageFeatures.gaussianSmooth(image, scale, scale, true);
        return image.getPixelArray()[0];
    }
    public static void smoothInPlace(long[] data, double scale) {
        float[] smoothed = smooth(data, scale);
        for (int i = 0; i<smoothed.length; ++i)  data[i] = Math.round(smoothed[i]);
    }
    public static void fillZeros(long[] data) {
        for (int i = 1; i<data.length-1; ++i) {
            if (data[i]==0) {
                long lowerV = data[i-1];
                int lowerB = i;
                while(i<data.length-2 && data[i]==0) ++i;
                long fillV = (lowerV+data[i])/2;
                for (int j = lowerB; j<i; ++j) data[j]=fillV;
            }
        }
    }
    public static double backgroundFit(Histogram histo, double sigmaFactor) {
        return backgroundFit(histo, sigmaFactor, null, false); 
    }
    public static double backgroundFit(Histogram histo, double sigmaFactor, double[] meanSigma) {
        return backgroundFit(histo, sigmaFactor, meanSigma, false); 
    }
    private static double backgroundFit(Histogram histo, double sigmaFactor, double[] meanSigma, boolean smooth) {
        long t0 = System.currentTimeMillis();
        long t1 = System.currentTimeMillis();
        // get mode -> background
        if (smooth) {
            fillZeros(histo.getData());
            smoothInPlace(histo.getData(), 1);
        }
        int modeIdx = ArrayUtil.max(histo.getData(), 1, histo.getData().length-1);  // if the image has a lot of null values or a saturated values, the mode can be null or the saturated value so avoid borders
        
        double halfWidthIdx = getHalfWidthIdx(histo, modeIdx, true);
        //logger.debug("background fit  : mode idx: {} (value: {}), half width estimation: {}", modeIdx, histo.getValueFromIdx(modeIdx), halfWidthIdx);
        if (Double.isNaN(halfWidthIdx) || modeIdx-halfWidthIdx<2) { // no half width found or half width is too close from mode
            double halfWidthIdx2 = getHalfWidthIdx(histo, modeIdx, false);
            if (Double.isNaN(halfWidthIdx) || (halfWidthIdx2 - modeIdx> modeIdx-halfWidthIdx)) {
                halfWidthIdx = halfWidthIdx2;
                //logger.debug("background fit estimation after peak: mode idx {} value: {}, half width estimation: {}", modeIdx, histo.getValueFromIdx(modeIdx), modeIdx-halfWidthIdx);
            }
        }

        long t2 = System.currentTimeMillis();
        double modeFitIdx;
        int halfHalf = Math.max(4, (int)(halfWidthIdx/2d));
        int startT = modeIdx - halfHalf;
        if (startT>=0 && 2 * modeIdx - halfHalf<histo.getData().length) {
            // gaussian fit on trimmed data to get more precise modal value
            WeightedObservedPoints obsT = new WeightedObservedPoints();
            for (int i = startT; i<=2 * modeIdx - halfHalf; ++i) obsT.add( i, histo.getData()[i]-histo.getData()[startT]);
            double sigma = Math.abs(modeIdx - halfWidthIdx) / Math.sqrt(2*Math.log(2));
            //logger.debug("estimated sigma from half width {}", sigma * histo.binSize);
            try { 
                double[] coeffsT = GaussianCurveFitter.create().withMaxIterations(1000).withStartPoint(new double[]{histo.getData()[modeIdx]-histo.getData()[startT], modeIdx, sigma/2.0}).fit(obsT.toList());
                modeFitIdx = coeffsT[1];
                double stdBckT = coeffsT[2];
                //logger.debug("mode: {}, modeFit: {}", modeIdx, modeFitIdx);
            } catch (Throwable t) {
                //if (!smooth) return backgroundFit(histo.duplicate(), sigmaFactor, meanSigma, true);
                //logger.error("error while looking for mode value", t);
                //throw t;
                modeFitIdx= modeIdx;
            }
        } else modeFitIdx = modeIdx;
        halfWidthIdx = getHalfWidthIdx(histo, modeFitIdx, true);
        if (Double.isNaN(halfWidthIdx)) {
            halfWidthIdx = getHalfWidthIdx(histo, modeIdx, false);
        }
        double sigma = Math.abs(histo.getValueFromIdx(modeFitIdx) - histo.getValueFromIdx(halfWidthIdx)) / Math.sqrt(2*Math.log(2)); // scaled real values
        //logger.debug("estimed sigma from halfwidth (after mode fit) {}, mode: {}, halfWidth {}", sigma, histo.getValueFromIdx(modeFitIdx) , histo.getValueFromIdx(halfWidthIdx) );
        int start = Double.isNaN(halfWidthIdx) ? getMinMonoBefore(histo.getData(), modeIdx) : Math.max(0, (int) (modeFitIdx - 6 * (modeFitIdx-halfWidthIdx)));
        
        // use gaussian fit on lowest half of data 
        long t3 = System.currentTimeMillis();
        WeightedObservedPoints obs = new WeightedObservedPoints();
        for (int i = start; i<=modeIdx; ++i) obs.add(histo.getValueFromIdx(i), histo.getData()[i]);
        obs.add(histo.getValueFromIdx(modeFitIdx), histo.getCountLinearApprox(modeFitIdx));
        for (double i  = modeFitIdx; i<=2 * modeFitIdx - start; ++i) obs.add(histo.getValueFromIdx(i), histo.getCountLinearApprox(2*modeFitIdx-i));  
        try {
            double[] coeffs = GaussianCurveFitter.create().withMaxIterations(1000).withStartPoint(new double[]{histo.getData()[modeIdx], histo.getValueFromIdx(modeIdx), sigma}).fit(obs.toList());
            double meanBck = coeffs[1];
            double stdBck = coeffs[2];
            if (meanSigma!=null) {
                meanSigma[0] = meanBck;
                meanSigma[1] = stdBck;
            }
            double thld = meanBck + sigmaFactor * stdBck;
            //logger.debug("mean : {}, sigma: {} thld: {}", meanBck, stdBck, thld);
            return thld;
        } catch(Throwable t) {
            if (!smooth) return backgroundFit(histo.duplicate(), sigmaFactor, meanSigma, true);
            logger.error("error while fitting to get sigma", t);
            //histo.plotIJ1("BackgroundFIt error mode: "+modeFitIdx+ " sigma: "+sigma, true);
            throw t;
        }
        
        //long t4 = System.currentTimeMillis();
        //logger.debug("mean: {} sigma: {} (from half width: {}), thld: {}, get histo: {} & {}, fit: {} & {}", meanBck, stdBck, sigma, thld, t1-t0, t2-t1, t3-t2, t4-t3);
        
        //long t4 = System.currentTimeMillis();
        //logger.debug("mean: {} sigma: {} (from half width: {}), thld: {}, get histo: {} & {}, fit: {} & {}", meanBck, stdBck, sigma, thld, t1-t0, t2-t1, t3-t2, t4-t3);
        
    }
    
    public static double getHalfWidthIdx(Histogram histo, double mode, boolean before) {
        long[] data = histo.getData();
        double halfH = histo.getCountLinearApprox(mode) / 2d;
        int half = (int)mode;
        if (before) {
            while(half>0 && histo.getData()[half-1]>halfH) --half;
            if (half<=0) return Double.NaN;
            // linear approx between half & half -1
            return half-1 + (halfH - data[half-1]) / (double)(data[half]-data[half-1]) ;
        } else {
            while(half<data.length-1 && data[half+1]>halfH) ++half;
            if (half==data.length-1) return Double.NaN;
            // linear approx between half & half -1
            return half + (halfH - data[half]) / (double)(data[half]-data[half+1]) ;
        }
        
    }
    
    private static int getMinMonoBefore(long[] array, int start) {
        while(start>0 && array[start]>array[start-1]) --start;
        return start;
    }
    
    
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{sigmaFactor};
    }

    
    
}
