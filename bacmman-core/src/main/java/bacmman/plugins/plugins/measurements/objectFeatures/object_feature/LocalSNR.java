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
package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.image.ImageByte;
import bacmman.image.Offset;
import bacmman.image.SimpleOffset;
import bacmman.image.TypeConverter;
import bacmman.measurement.BasicMeasurements;
import bacmman.processing.Filters;
import bacmman.processing.ImageOperations;
import bacmman.plugins.Plugin;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class LocalSNR extends SNR {
    public final static Logger logger = LoggerFactory.getLogger(LocalSNR.class);
    protected BoundedNumberParameter localBackgroundRadius = new BoundedNumberParameter("Local background radius", 1, 8, 0, null).setHint("Defines the local background area, by dilating the foreground region with this radius and removing the foreground region from the dilated region");
    public static boolean debug=false;
    @Override public Parameter[] getParameters() {return new Parameter[]{intensity, backgroundStructure, formula, foregroundFormula, dilateExcluded, erodeBorders, localBackgroundRadius};}
    public LocalSNR() {}
    
    public LocalSNR(int backgroundStructureIdx) {
        super(backgroundStructureIdx);
    }
    public LocalSNR setLocalBackgroundRadius(double backgroundRadius) {
        localBackgroundRadius.setValue(backgroundRadius);
        return this;
    }
    @Override public double performMeasurement(final Region object) {
        if (core==null) synchronized(this) {setUpOrAddCore(null, null);}
        Offset offset = object.isAbsoluteLandMark() ? new SimpleOffset(0, 0, 0) : super.parent.getBounds();
        final Region backgroundObject; 
        if (foregroundMapBackground==null) backgroundObject = super.parent.getRegion();
        else backgroundObject=this.foregroundMapBackground.get(object);
        if (backgroundObject==null) return Double.NaN;
        
        // create mask
        ImageByte localBackgroundMask  = TypeConverter.toByteMask(object.getMask(), null, 1).setName("mask:");
        localBackgroundMask.translate(offset); // so that local background mask is in absolute landmark
        localBackgroundMask = Filters.binaryMax(localBackgroundMask, null, Filters.getNeighborhood(localBackgroundRadius.getValue().doubleValue(), localBackgroundMask), false, true, false);
        ImageOperations.andWithOffset(localBackgroundMask, backgroundObject.getMask(), localBackgroundMask); // do not dilate outside background mask
        Region bck = new Region(localBackgroundMask, 1, true).setIsAbsoluteLandmark(true);
        IntensityMeasurementCore.IntensityMeasurements fore = super.core.getIntensityMeasurements(object);
        IntensityMeasurementCore.IntensityMeasurements back = super.core.getIntensityMeasurements(bck);
        double d = getValue(getForeValue(fore), getBackValue(back), back.sd);
        if (debug) {
            logger.debug("SNR local object: {}, value: {}, rad: {}, count: {}, objectCount: {}, fore: {}, fore sd:{}, back: {}, sd: {}", object.getLabel(), d, localBackgroundRadius.getValue().doubleValue(), localBackgroundMask.count(), object.getMask().count(), fore.mean, fore.sd, getBackValue(back), back.sd);
            //Core.showImage(localBackgroundMask);
        }
        return d;
        
    }
    
    @Override 
    public String getDefaultName() {
        return "LocalSNR";
    }


    @Override
    public String getHintText() {
        return "Estimation of the signal-to-noise ratio on the area of a segmented object, using the local background in an area around the segmented object";
    }
}
