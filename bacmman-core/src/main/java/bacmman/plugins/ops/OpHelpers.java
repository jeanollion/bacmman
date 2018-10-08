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
package bacmman.plugins.ops;

import bacmman.configuration.parameters.BoundedNumberParameter;

import java.util.List;
import net.imagej.ops.OpInfo;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccessible;
import org.scijava.ItemIO;
import org.scijava.module.ModuleItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 *
 * @author Jean Ollion
 */
public class OpHelpers {
    // need further version of IJ2 to work
    
    public static final Logger logger = LoggerFactory.getLogger(OpHelpers.class);
    final OpService service;
    public OpHelpers(OpService service) {
        this.service=service;
    }
    public static List<ModuleItem<?>> inputs(OpInfo info) {
        List<ModuleItem<?>> inputs = new ArrayList<>();
        for (ModuleItem<?> i : info.cInfo().inputs()) inputs.add(i);
        return inputs;
    }
    public static List<ModuleItem<?>> outputs(OpInfo info) {
        List<ModuleItem<?>> inputs = new ArrayList<>();
        for (ModuleItem<?> i : info.cInfo().outputs()) inputs.add(i);
        return inputs;
    }
    public static OpParameter[] getParameters(OpInfo info) {
        return inputs(info).stream()
                .filter(p -> p.getIOType()==ItemIO.INPUT)
                .map(p->mapParameter(p)).filter(p->p!=null)
                .toArray(l->new OpParameter[l]);
    }
    public static OpParameter mapParameter(ModuleItem<?> param) {
        OpParameter res=null;
        if (param.getType()==double.class || param.getType()==Double.class) { // get generic type ? 
            Double lower = (Double)param.getMinimumValue();
            Double upper = (Double)param.getMaximumValue();
            logger.debug("lower: {} upper: {}, default: {}", param.getMinimumValue(), param.getMaximumValue(), param.getDefaultValue());
            res =  new BoundedNumberParameter(param.getName(), 10, (Double)param.getDefaultValue(), lower, upper);
        } if (param.getType()==long.class || param.getType()==Long.class) { // get generic type ? 
            Long lower = (Long)param.getMinimumValue();
            Long upper = (Long)param.getMaximumValue();
            logger.debug("lower: {} upper: {}, default: {}", param.getMinimumValue(), param.getMaximumValue(), param.getDefaultValue());
            res =  new BoundedNumberParameter(param.getName(), 0, (Long)param.getDefaultValue(), lower, upper);
        } if (param.getType()==int.class || param.getType()==Integer.class) { // get generic type ? 
            Integer lower = (Integer)param.getMinimumValue();
            Integer upper = (Integer)param.getMaximumValue();
            logger.debug("lower: {} upper: {}, default: {}", param.getMinimumValue(), param.getMaximumValue(), param.getDefaultValue());
            res =  new BoundedNumberParameter(param.getName(), 0, (Integer)param.getDefaultValue(), lower, upper);
        }
        // TODO make ints, boolean, string, choice, Arrays! (fixed or user defined size? list?) make special for outofbounds: type choice parameter that can create an outofbound factory
        if (res!=null) res.setModuleItem(param);
        logger.debug("param: {} ({}), could be converted ? {}", param.getName(), param.getType().getSimpleName(), res!=null);
        return res;
    }
    // TODO: make populate arguments, including non parameters (input). 
    // TODO: make a function for filters (Binary), thresholds (Unary), segmenters? (Binary)
    
    public static boolean isImageInput(ModuleItem<?> param) {
        return RandomAccessible.class.isAssignableFrom(param.getType());
    }
    
}
