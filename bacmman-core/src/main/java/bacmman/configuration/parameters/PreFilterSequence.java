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
package bacmman.configuration.parameters;

import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.ImageProperties;
import java.util.Collection;
import bacmman.plugins.PreFilter;
import bacmman.plugins.HistogramScaler;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class PreFilterSequence extends PluginParameterList<PreFilter, PreFilterSequence> {
    HistogramScaler scaler;
    public PreFilterSequence(String name) {
        super(name, "Pre-Filter", PreFilter.class);
    }
    public PreFilterSequence setScaler(HistogramScaler scaler) {
        this.scaler = scaler;
        return this;
    }
    public Image filter(Image input, ImageMask mask) {
        ImageProperties prop = input.getProperties();
        boolean first = true;
        if (scaler!=null) {
            if (scaler.isConfigured()) throw new RuntimeException("Scaler not configured");
            input = scaler.scale(input);
            first = false;
        }
        for (PreFilter p : get()) {
            input = p.runPreFilter(input, mask, !first);
            first = false;
            //logger.debug("prefilter: {}", p.getClass().getSimpleName());
        }
        input.setCalibration(prop);
        if (input.sameDimensions(prop)) input.resetOffset().translate(prop);
        return input;
    }
    @Override public PreFilterSequence removeAll() {
        this.removeAllElements();
        return this;
    }
    @Override public PreFilterSequence add(PreFilter... instances) {
        super.add(instances);
        return this;
    }
    
    @Override public PreFilterSequence add(Collection<PreFilter> instances) {
        super.add(instances);
        return this;
    }
    public String toStringElements() {
        return Utils.toStringList(children, p -> p.pluginName);
    }
    @Override 
    public PreFilterSequence setHint(String tip){
        super.setHint(tip);
        return this;
    }
    @Override
    public PreFilterSequence duplicate() {
        PreFilterSequence res = new PreFilterSequence(name);
        res.setContentFrom(this);
        return res;
    }
}
