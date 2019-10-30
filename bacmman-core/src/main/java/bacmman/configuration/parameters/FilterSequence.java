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
import bacmman.image.ImageProperties;

import java.util.Collection;

import bacmman.plugins.Filter;

/**
 *
 * @author Jean Ollion
 */
public class FilterSequence extends PluginParameterList<Filter, FilterSequence> {

    public FilterSequence(String name) {
        super(name, "Transformation", Filter.class, false);
    }
    
    public Image filter(Image input)  {
        ImageProperties prop = input.getProperties();
        for (Filter t : get()) {
            input = t.applyTransformation(0, 0, input);
        }
        input.setCalibration(prop);
        if (input.sameDimensions(prop)) input.resetOffset().translate(prop);
        return input;
    }
    @Override public FilterSequence add(Filter... instances) {
        super.add(instances);
        return this;
    }
    
    @Override public FilterSequence add(Collection<Filter> instances) {
        super.add(instances);
        return this;
    }
    @Override
    public FilterSequence duplicate() {
        FilterSequence res = new FilterSequence(name);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
}
