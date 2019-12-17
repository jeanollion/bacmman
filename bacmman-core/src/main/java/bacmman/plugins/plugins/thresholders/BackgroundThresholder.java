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
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BlankMask;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.*;
import bacmman.utils.DoubleStatistics;

/**
 * Adapted from Implementation of Kappa Sigma Clipping algorithm by Gaëtan Lehmann, http://www.insight-journal.org/browse/publication/132. 
 * Finds the mean and sigma of the background, and use this two properties to select the pixels significantly different of the background. 
 * Mean and sigma are first computed on the entire image, and a threshold is computed as mean + f * sigma. 
 * This threshold is then used to select the background, and recompute a new threshold with only pixels in the background. 
 * This algorithm shouldn’t converge to a value, so the number of iterations must be provided. 
 * In general, two iterations are used.
 * Variation : added final sigma for final threshold computation
 * @author Jean Ollion
 */
public class BackgroundThresholder implements HintSimple, SimpleThresholder, ThresholderHisto, MultiThreaded {
    public static boolean debug = false;
    NumberParameter sigmaFactor = new BoundedNumberParameter("Sigma factor", 2, 2.5, 0.01, null);
    NumberParameter finalSigmaFactor = new BoundedNumberParameter("Final Sigma factor", 2, 4, 0.01, null).setHint("Sigma factor for last threshold computation");
    NumberParameter iterations = new BoundedNumberParameter("Iteration number", 0, 2, 1, null);
    PluginParameter<SimpleThresholder> startingPoint = new PluginParameter<>("Starting value", SimpleThresholder.class, true).setHint("This value limits the threshold computed at first iteration. Use this parameter when the image contains pixel with high values");
    Parameter[] parameters = new Parameter[]{sigmaFactor, finalSigmaFactor, iterations, startingPoint};

    public static String simpleHint = "This algorithm estimates the mean (µ) and standard deviation (σ) of the background pixel intensity, and use these two parameters to select the pixels significantly different from the background"
            +"<br />This method works only on images in which most pixels are background pixels"
            +"<br />Adapted from Implementation of <em>Kappa Sigma Clipping</em> algorithm by Gaëtan Lehmann, <a href='http://www.insight-journal.org/browse/publication/132'>http://www.insight-journal.org/browse/publication/132</a>";


    @Override
    public String getSimpleHintText() {
        return simpleHint;
    }


    public BackgroundThresholder() {}
    
    public BackgroundThresholder(double sigmaFactor, double finalSigmaFactor, int iterations) {
        this.sigmaFactor.setValue(sigmaFactor);
        this.finalSigmaFactor.setValue(finalSigmaFactor);
        this.iterations.setValue(iterations);
    }
    public BackgroundThresholder setStartingValue(SimpleThresholder thlder) {
        this.startingPoint.setPlugin(thlder);
        return this;
    }
    @Override
    public double runThresholderHisto(Histogram histogram) {
        double firstValue = Double.MAX_VALUE;
        if (this.startingPoint.isOnePluginSet()) {
            if (startingPoint.instantiatePlugin() instanceof ThresholderHisto) {
                firstValue = ((ThresholderHisto)startingPoint.instantiatePlugin()).runThresholderHisto(histogram);
            } else throw new IllegalArgumentException("Starting point should be a thresholder histo");
        }
        return runThresholder(histogram, sigmaFactor.getValue().doubleValue(), finalSigmaFactor.getValue().doubleValue(), iterations.getValue().intValue(), firstValue, null);
    }
    @Override 
    public double runSimpleThresholder(Image input, ImageMask mask) {
        double firstValue = Double.MAX_VALUE;
        if (this.startingPoint.isOnePluginSet()) {
            firstValue = startingPoint.instantiatePlugin().runSimpleThresholder(input, mask);
        }
        return runThresholder(input, mask, sigmaFactor.getValue().doubleValue(), finalSigmaFactor.getValue().doubleValue(), iterations.getValue().intValue(), firstValue, null);
        
    }
    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        ImageMask mask = structureObject!=null?structureObject.getMask():null;
        //return BackgroundThresholder.runSimpleThresholder(input, mask, sigmaFactor.getValue().doubleValue(), finalSigmaFactor.getValue().doubleValue(), iterations.getValue().intValue(), null);
        return runSimpleThresholder(input , mask);
    }
    // slower, more precise
    public static double runThresholder(Image input, ImageMask mask, double sigmaFactor, double lastSigmaFactor, int iterations, double firstValue) {
        return runThresholder(input,mask, sigmaFactor, lastSigmaFactor, iterations, firstValue, null);
    }
    public static double runThresholder(Image input, ImageMask mask, double sigmaFactor, double lastSigmaFactor, int iterations, double firstValue, double[] meanSigma) {
        if (meanSigma!=null && meanSigma.length<2) throw new IllegalArgumentException("Argument Mean Sigma should be null or of size 2 to receive mean and sigma values");
        if (mask==null) mask = new BlankMask(input);
        if (firstValue==Double.NaN) firstValue = Double.MAX_VALUE;
        double lastThreshold = firstValue;
        if (iterations<=0) iterations=1;
        for (int i = 0; i<iterations; i++) {
            double thld = lastThreshold;
            DoubleStatistics stats = DoubleStatistics.getStats(input.stream(mask, true).filter(d->d<thld));
            double mean = stats.getAverage();
            double sigma = stats.getStandardDeviation();
            if (meanSigma!=null) {
                meanSigma[0]=mean;
                meanSigma[1]=sigma;
                if (meanSigma.length>2) meanSigma[2] = stats.getCount();
            }
            
            double newThreshold = i==iterations-1 ? mean + lastSigmaFactor * sigma : mean + sigmaFactor * sigma;
            if (Double.isFinite(firstValue)) newThreshold = Math.min(firstValue, newThreshold);
            if (debug) logger.debug("Kappa Sigma Thresholder: Iteration:"+ i+" Mean Background Value: "+mean+ " Sigma: "+sigma+ " threshold: "+newThreshold);
            if (newThreshold == lastThreshold) return lastThreshold;
            else lastThreshold = newThreshold;
        }
        if (debug) logger.debug("background thlder: {} ms: {}, first value: {}", Math.min(firstValue, lastThreshold), meanSigma, firstValue);
        return Math.min(firstValue, lastThreshold);
    }
    
    public static double runThresholderHisto(Image input, ImageMask mask, double sigmaFactor, double lastSigmaFactor, int iterations, double firstValue, double[] meanSigma) {
        Histogram histo = HistogramFactory.getHistogram(() -> input.stream(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
        return BackgroundThresholder.runThresholder(histo, sigmaFactor, lastSigmaFactor, iterations, firstValue, meanSigma);
    }
    
    public static double runThresholder(Histogram histo, double sigmaFactor, double lastSigmaFactor, int iterations, double firstValue, double[] meanSigma) {
        if (meanSigma!=null && meanSigma.length<2) throw new IllegalArgumentException("Argument Mean Sigma should be null or of size 2 to receive mean and sigma values");
        int firstIdx =  Double.isInfinite(firstValue)||firstValue==Double.MAX_VALUE ? histo.getData().length-1 : (int)histo.getIdxFromValue(firstValue);
        if (firstIdx>histo.getData().length-1) firstIdx=histo.getData().length-1;
        double binInc = 0.245; // empirical correction !
        double lastThreshold = firstIdx;
        double mean=0, sigma=0;
        double count=0;
        long[] data = histo.getData();
        if (iterations<=0) iterations=1;
        for (int i = 0; i<iterations; i++) {
            count=0;
            sigma=0;
            mean=0;
            for (int idx = 0; idx<lastThreshold; idx++) {
                mean+=(idx+binInc)*data[idx];
                count+=data[idx];
            }
            double lastBinCount = histo.getCountLinearApprox(lastThreshold);
            
            mean+=((int)lastThreshold+binInc) * lastBinCount;
            count+=lastBinCount;
            if (count>0) {
                mean = mean/count;
                for (int idx = 0; idx<lastThreshold; idx++) sigma+=Math.pow(mean-(idx+binInc), 2)*data[idx];
                sigma+= Math.pow(mean-((int)lastThreshold+binInc), 2)*lastBinCount;
                sigma= Math.sqrt(sigma/count);
                if (meanSigma!=null) {
                    meanSigma[0]=histo.getValueFromIdx(mean);
                    meanSigma[1]=sigma * histo.getBinSize();
                }
            }
            double newThreshold = i==iterations-1 ? (mean + lastSigmaFactor * sigma) : (mean + sigmaFactor * sigma);
            newThreshold = Math.min(firstIdx, newThreshold);
            if (debug) logger.debug("Kappa Sigma Thresholder HISTO: Iteration: {}, Mean : {}, Sigma : {}, thld: {}, lastBinCount: {} (full bin: {}, delta: {})", i, histo.getValueFromIdx(mean),sigma*histo.getBinSize(),histo.getValueFromIdx(newThreshold), lastBinCount, lastThreshold>0? data[(int)lastThreshold] : "invalidIDX", (lastThreshold-(int)lastThreshold));
            if (newThreshold == lastThreshold) break;
            else lastThreshold = newThreshold;
        }
        if (debug) logger.debug("background thlder HISTO: {}, first idx: {}", histo.getValueFromIdx(Math.min(firstIdx, lastThreshold)), firstIdx);
        return histo.getValueFromIdx(Math.min(firstIdx, lastThreshold));
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }

    
    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }
    
}
