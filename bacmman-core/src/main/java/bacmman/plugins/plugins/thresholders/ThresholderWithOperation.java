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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.ThresholderHisto;
import bacmman.plugins.Hint;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class ThresholderWithOperation implements ThresholderHisto, SimpleThresholder, Hint {
    PluginParameter<ThresholderHisto> thresholder = new PluginParameter<>("Thresholder", ThresholderHisto.class, new BackgroundFit(10), false).setHint("Threshold method");
    NumberParameter quantile = new BoundedNumberParameter("Quantile", 5, 0.25, 0, 1);
    BooleanParameter overThld = new BooleanParameter("Perform stat over threshold", true);
    enum STAT {MEAN, QUANTILE};
    ChoiceParameter stat = new ChoiceParameter("Statistics", Utils.toStringArray(STAT.values()), STAT.QUANTILE.toString(), false);
    ConditionalParameter cond = new ConditionalParameter(stat).setActionParameters(STAT.QUANTILE.toString(), overThld,quantile ).setActionParameters(STAT.MEAN.toString(), overThld);
    
    @Override
    public String getHintText() {
        return "First compute a threshold using <em>Thresholder<em> method. Then compute a statistics relative to this threshold, defined in <em>Statistics</em>";
    }
    
    @Override
    public double runThresholderHisto(Histogram histogram) {
        double thld =thresholder.instanciatePlugin().runThresholderHisto(histogram);
        int idx = (int)histogram.getIdxFromValue(thld);
        Histogram hist = overThld.getSelected() ? histogram.duplicate(idx, histogram.data.length): histogram.duplicate(0, idx);
        switch(STAT.valueOf(stat.getSelectedItem())) {
            case QUANTILE:
            default :
                return hist.getQuantiles(quantile.getValue().doubleValue())[0];
            case MEAN:
                return hist.getValueFromIdx(hist.getMeanIdx(0, hist.data.length));
        }
        
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{thresholder, cond};
    }

    @Override
    public double runSimpleThresholder(Image image, ImageMask mask) {
        return runThresholderHisto(HistogramFactory.getHistogram(()->image.stream(mask, true), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
    }

    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        return runSimpleThresholder(input, structureObject.getMask());
    }

    
    
}
