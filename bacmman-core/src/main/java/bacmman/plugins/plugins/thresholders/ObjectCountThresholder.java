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
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.BlankMask;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Thresholder;
import bacmman.processing.Filters;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.processing.watershed.WatershedTransform.FusionCriterion;
import bacmman.processing.watershed.WatershedTransform.PropagationCriterion;
import bacmman.processing.watershed.WatershedTransform.Spot;
import static bacmman.processing.watershed.WatershedTransform.watershed;
import bacmman.utils.ArrayUtil;

/**
 *
 * @author Jean Ollion
 */
public class ObjectCountThresholder implements Thresholder, DevPlugin {
    NumberParameter maxObjectNumber = new BoundedNumberParameter("Max. object number", 0, 10, 1, null);
    BooleanParameter descendingIntensities = new BooleanParameter("Brights spots", true);
    Parameter[] parameters = new Parameter[]{maxObjectNumber, descendingIntensities};
    public static boolean debug = false;
    
    public ObjectCountThresholder() {}
    
    public ObjectCountThresholder(int maxObjectCount) {
        this.maxObjectNumber.setValue(maxObjectCount);
    }
    
    public ObjectCountThresholder setMaxObjectNumber(int max) {
        this.maxObjectNumber.setValue(max);
        return this;
    }
    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        ImageMask mask = structureObject!=null ? structureObject.getMask() : new BlankMask( input);
        return runThresholder(input, mask);
    }
    
    public double runThresholder(Image input, ImageMask mask) {
        
        ImageByte seeds = Filters.localExtrema(input, null, descendingIntensities.getSelected(), descendingIntensities.getSelected() ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY, mask, Filters.getNeighborhood(1.5, 1, input));
        boolean bright = descendingIntensities.getSelected();
        if (debug) Core.showImage(seeds);
        int max = maxObjectNumber.getValue().intValue();
        Histogram objectCountHisto = new Histogram(new long[256], input.getMinAndMax(mask));
        
        FusionCriterion f = new FusionCriterion() {
            WatershedTransform instance;
            @Override
            public void setUp(WatershedTransform instance) {
                this.instance=instance;
            }
            @Override
            public boolean checkFusionCriteria(WatershedTransform.Spot s1, WatershedTransform.Spot s2, Voxel currentVoxel) {
                return true;
            }
        };
        PropagationCriterion p = new PropagationCriterion() {
            WatershedTransform instance;
            @Override
            public void setUp(WatershedTransform instance) {
                this.instance=instance;
            }
            @Override
            public boolean continuePropagation(Voxel currentVox, Voxel nextVox) {
                double v = bright ? Math.max(currentVox.value, nextVox.value) : Math.min(currentVox.value, nextVox.value);
                int idx = (int)objectCountHisto.getIdxFromValue(v);
                if (objectCountHisto.data[idx]==0) objectCountHisto.data[idx] = getSpotNumber(instance, v, bright);
                if (objectCountHisto.data[idx]>=max) { // stop propagation
                    instance.getHeap().clear();
                    return false;
                } 
                return true;
            }
        };
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(descendingIntensities.getSelected()).lowConectivity(false).propagationCriterion(p).fusionCriterion(f).propagation(WatershedTransform.PropagationType.DIRECT);
        watershed(input, mask, seeds,config);
        if (debug) {
            objectCountHisto.plotIJ1("objects", debug);
        }
        int i = ArrayUtil.getFirstOccurence(objectCountHisto.data, objectCountHisto.data.length-1, 0, v->v>=max);
        if (objectCountHisto.data[i]==max && i<objectCountHisto.data.length) ++i;
        double value = objectCountHisto.getValueFromIdx(i);
        if (debug) logger.debug("thld: {} (idx:{})", value, i);
        return value;
    }

    private static int getSpotNumber(WatershedTransform instance, double value, boolean bright) {
        int count = 0;
        for (Spot s : instance.getSpotArray()) {
            if (s!=null) {
                if (s.voxels.size()>1) count++;
                else if (!s.voxels.isEmpty() && s.voxels.iterator().next().value>value==bright) ++count; 
            }
        }
        return count;
    }
    /*private static double getMeanSpatialMoment(WatershedTransform instance) {
        
    }*/
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
}
