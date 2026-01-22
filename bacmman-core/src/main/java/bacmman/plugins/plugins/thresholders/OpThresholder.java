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
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.imagej.ops.OpInfo;
import net.imglib2.histogram.Histogram1d;
import net.imglib2.type.numeric.RealType;
import org.scijava.module.ModuleItem;
import bacmman.plugins.ops.ImgLib2HistogramWrapper;
import bacmman.plugins.ops.OpHelpers;
import static bacmman.plugins.ops.OpHelpers.inputs;
import bacmman.plugins.ops.OpParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class OpThresholder  { //implements ThresholderHisto, SimpleThresholder 
    public final static Logger logger = LoggerFactory.getLogger(OpThresholder.class);
    ChoiceParameter method;
    ConditionalParameter<String> cond;
    Map<String, OpInfo> allOps;
    public OpThresholder() {
        init();
    }

    private void init() {
        Collection<OpInfo> allOpsInfos = Core.getOpService().infos();
        logger.debug("OpThresholder methods: {}", allOpsInfos.stream().map(o->o.getSimpleName()).toArray());
        // keep only commands that have exactly 1 number as output and as input 1 histogram and no image
        allOpsInfos.removeIf(o-> {
                if (!o.isNamespace("threshold") ||"apply".equals(o.getSimpleName())) return true;
                ModuleItem<?> out=null;
                for (ModuleItem<?> oo : o.cInfo().outputs()) {
                    if (out==null) out = oo;
                    else return true;
                }
                if (out==null) return true;
                if (!(out.getType()==RealType.class || Number.class.isAssignableFrom(out.getType()))) return true;
                List<ModuleItem<?>> inputs = inputs(o);
                if (!inputs.stream().anyMatch(m -> m.getType() == Histogram1d.class)) return true; // one histogram as param
                if (inputs.stream().anyMatch(param -> OpHelpers.isImageInput(param))) return true; // no image as param
                return false;
        });
        
        //net.imagej.ops.threshold.ApplyThresholdMethod;
        //allOps = new TreeMap<>(allOpsInfos.stream().collect(Collectors.toMap(o->o.getName(), o->o)));
        
        allOps = new TreeMap<>();
        for (OpInfo info : allOpsInfos) {
            if (allOps.containsKey(info.getSimpleName())) {
                logger.debug("duplicate key: {} with: {}", info, allOps.get(info.getSimpleName()));
            } 
            allOps.put(info.getSimpleName(), info);
        }
        logger.debug("OpThresholder methods: {}", allOps.keySet());
        method = new ChoiceParameter("Threshold method", allOps.keySet().toArray(new String[allOps.size()]), null, false);
        cond = new ConditionalParameter(method);
        cond.addListener(p->{
            ConditionalParameter<String> cond = (ConditionalParameter)p;
            String m = cond.getActionableParameter().getValue();
            if (cond.getParameters(m)==null) cond.setActionParameters(m, OpHelpers.getParameters(allOps.get(m)));
        });
        
    }
    //@Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        return runSimpleThresholder(input, structureObject==null?null:structureObject.getMask());
    }

    //@Override
    public Parameter[] getParameters() {
        if (cond==null) throw new IllegalArgumentException("Op Service no set, parameters not initiated");
        return new Parameter[]{cond};
    }
    //@Override
    public double runSimpleThresholder(Image image, ImageMask mask) {
        return runThresholderHisto(HistogramFactory.getHistogram(() -> image.stream(mask, true)));
    }
    //@Override
    public double runThresholderHisto(Histogram histogram) {
        String m = method.getSelectedItem();
        OpInfo info = allOps.get(m);
        List<ModuleItem<?>> inputs = inputs(info);

        Map<String, Object> values = cond.getParameters(m).stream().collect(Collectors.toMap(o->((OpParameter)o).getModuleItem().getName(), o->((OpParameter)o).getValue()));
        Object[] args = new Object[inputs.size()];
        for (int i = 0; i<inputs.size(); ++i) {
            ModuleItem<?> param = inputs.get(i);
            if (param.getType() == Histogram1d.class) {
                args[i] = ImgLib2HistogramWrapper.wrap(histogram);
            } else {
                args[i] = values.get(param.getName());
                logger.debug("parameter: {}, value: {}", param.getName(), args[i]);
            }
        }
        Object res = Core.getOpService().run(info.getName(), args);
        if (res instanceof RealType) return ((RealType)res).getRealDouble();
        else if (res instanceof Number) return ((Number)res).doubleValue();
        else throw new RuntimeException("Op did not return number: method:"+m);
                
    }

    

}
