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
package bacmman.plugins.plugins.transformations;

import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;

import java.util.List;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Hint;

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class SaturateHistogramHyperfluoBacteria implements ConfigurableTransformation, Hint {
    NumberParameter maxSignalProportion = new BoundedNumberParameter("Maximum Saturated Signal Amount Proportion", 5, 0.02, 0, 1).setHint("Condition on amount of signal for detection of hyper-fluorescent bacteria: <br />Total amount of foreground signal / amount of hyper-fluorescent signal &lt; this threshold");
    NumberParameter minSignalRatio = new BoundedNumberParameter("Minimum Signal Ratio", 2, 10, 2, null).setHint("Condition on signal value for detection of hyper-fluorescent bacteria: <br />Mean Hyper-fluorescent signal / Mean Foreground signal > this threshold");
    
    Parameter[] parameters = new Parameter[]{maxSignalProportion, minSignalRatio};
    double saturateValue= Double.NaN;
    boolean configured = false;
    
    public SaturateHistogramHyperfluoBacteria setForegroundProportion(double maxSignalAmountProportion, double minSignalRatio) {
        this.maxSignalProportion.setValue(maxSignalAmountProportion);
        this.minSignalRatio.setValue(minSignalRatio);
        return this;
    }
    @Override
    public String getHintText() {
        return "Automatically detects the presence of bacteria with very high fluorescence intensity, and saturates the images containing those bacteria (i.e. sets the pixels with values above an automatically computed threshold to the value of the threshold).";
    }
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        List<Image> allImages = Arrays.asList(InputImages.getImageForChannel(inputImages, channelIdx, true));
        if (allImages.isEmpty()) {
            logger.error("No image");
            return;
        } else logger.debug("saturate histo: images: {}", allImages.size());
        long t0 = System.currentTimeMillis();
        
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(allImages).parallel(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
        double[] bckMuStd = new double[2];
        double bckThld = BackgroundFit.backgroundFit(histo, 10, bckMuStd);
        Histogram histoFore = histo.duplicate((int)histo.getIdxFromValue(bckThld)+1, histo.getData().length);
        double foreThld = histoFore.getQuantiles(0.5)[0];
        
        double satThld = bckMuStd[0] + (foreThld - bckMuStd[0]) * this.minSignalRatio.getValue().doubleValue();
        double satSignal = 0, totalSignal = 0;
        if (satThld<histo.getMaxValue()) {
            // condition on signal amount
            satSignal = histo.count((int)histo.getIdxFromValue(satThld), histo.getData().length);
            totalSignal = histo.count((int)histo.getIdxFromValue(bckThld), histo.getData().length);
            logger.debug("sat signal proportion: {}, ", satSignal / totalSignal);
            if (maxSignalProportion.getValue().doubleValue() > satSignal / totalSignal) {
                saturateValue = satThld;
            } else {
                saturateValue = histoFore.getQuantiles(1-maxSignalProportion.getValue().doubleValue())[0];
                satSignal = histo.count((int)histo.getIdxFromValue(saturateValue), histo.getData().length);
                logger.debug("sat value to reach maximal proportion: {}, ", saturateValue);
            }
        }
         
        long t1 = System.currentTimeMillis();
        configured = true;
        logger.debug("SaturateHistoAuto: {}, bck : {}, thld: {}, fore: {}, saturation thld: {}, saturation proportion: {}, image range: {} computation time {}ms",saturateValue, bckMuStd[0], bckThld, foreThld, satThld, satSignal/totalSignal, new double[]{histo.getMinValue(), histo.getMaxValue()}, t1-t0);
    }
    
    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return configured;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (Double.isNaN(saturateValue)) return image;
        SaturateHistogram.saturate(saturateValue, saturateValue, image);
        return image;
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

}
