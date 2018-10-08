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
package bacmman.plugins.object_feature;

import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.data_structure.Region;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import java.util.HashMap;

import bacmman.utils.DoubleStatistics;
import bacmman.utils.geom.Point;

/**
 *
 * @author Jean Ollion
 */
public class IntensityMeasurementCore implements ObjectFeatureCore {
    Image intensityMap, transformedMap;
    HashMap<Region, IntensityMeasurements> values = new HashMap<>();
    
    public void setUp(Image intensityMap, ImageMask mask , PreFilterSequence preFilters) {
        this.intensityMap=intensityMap;    
        if (preFilters==null) this.transformedMap=intensityMap;
        else transformedMap = preFilters.filter(intensityMap, mask);
    }
    public Image getIntensityMap(boolean transformed) {
        return transformed ? transformedMap : intensityMap;
    }
    public IntensityMeasurements getIntensityMeasurements(Region o) {
        IntensityMeasurements i = values.get(o);
        if (i==null) {
            i = new IntensityMeasurements(o);
            values.put(o, i);
        }
        return i;
    }
    
    public class IntensityMeasurements {
        public double mean=Double.NaN, sd=Double.NaN, min=Double.NaN, max=Double.NaN, valueAtCenter=Double.NaN, count=Double.NaN;
        Region o;
        
        public IntensityMeasurements(Region o) {
            this.o=o;
            DoubleStatistics stats = DoubleStatistics.getStats(intensityMap.stream(o.getMask(), o.isAbsoluteLandMark()));
            mean = stats.getAverage();
            sd = stats.getStandardDeviation();
            min = stats.getMin();
            max = stats.getMax();
            count = stats.getCount();
        }
        
        public double getValueAtCenter() {
            if (Double.isNaN(valueAtCenter)) {
                Point center = o.getCenter();
                if (center==null) center = o.getGeomCenter(false);
                this.valueAtCenter = o.isAbsoluteLandMark() ? intensityMap.getPixelWithOffset(center.get(0), center.get(1), center.getWithDimCheck(2)) : intensityMap.getPixel(center.get(0), center.get(1), center.getWithDimCheck(2));
            }
            return valueAtCenter;
        }
    }
}
