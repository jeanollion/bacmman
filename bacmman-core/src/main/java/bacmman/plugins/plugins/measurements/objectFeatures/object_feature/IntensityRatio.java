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

import bacmman.data_structure.Region;
import bacmman.plugins.object_feature.IntensityMeasurementCore;

/**
 *
 * @author Jean Ollion
 */
public class IntensityRatio extends SNR {
    
    
    @Override public double performMeasurement(Region object) {
        if (core==null) synchronized(this) {setUpOrAddCore(null, null);}
        Region parentObject; 
        if (foregroundMapBackground==null) parentObject = super.parent.getRegion();
        else parentObject=this.foregroundMapBackground.get(object);
        if (parentObject==null) return 0;
        IntensityMeasurementCore.IntensityMeasurements iParent = super.core.getIntensityMeasurements(parentObject);
        double fore = super.core.getIntensityMeasurements(object).mean;
        return fore/iParent.mean ;
    }

    @Override public String getDefaultName() {
        return "IntensityRatio";
    }

    @Override
    public String getHintText() {
        return "Estimation of the intensity ratio between an object and another object that contains it";
    }
}
