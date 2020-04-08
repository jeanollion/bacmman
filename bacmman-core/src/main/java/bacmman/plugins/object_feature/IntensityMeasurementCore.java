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
import java.util.Map;

import bacmman.measurement.BasicMeasurements;
import bacmman.utils.DoubleStatistics;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class IntensityMeasurementCore {
    private final static Logger logger = LoggerFactory.getLogger(IntensityMeasurementCore.class);
    Image intensityMap, transformedMap;
    Map<Region, IntensityMeasurements> values = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(o -> new IntensityMeasurements(o));
    
    public void setUp(Image intensityMap, Image transformedMap) {
        this.intensityMap=intensityMap;    
        if (transformedMap==null) this.transformedMap=intensityMap;
        else this.transformedMap = transformedMap;
    }
    public Image getIntensityMap(boolean transformed) {
        return transformed ? transformedMap : intensityMap;
    }
    public IntensityMeasurements getIntensityMeasurements(Region o) {
        return values.get(o);
    }
    
    public class IntensityMeasurements {
        public double mean=Double.NaN, sd=Double.NaN, min=Double.NaN, max=Double.NaN, valueAtCenter=Double.NaN, median=Double.NaN, count=Double.NaN;
        Region o;
        
        public IntensityMeasurements(Region o) {
            this.o=o;
            DoubleStatistics stats = DoubleStatistics.getStats(transformedMap.stream(o.getMask(), o.isAbsoluteLandMark()));
            mean = stats.getAverage();
            sd = stats.getStandardDeviation();
            min = stats.getMin();
            max = stats.getMax();
            count = stats.getCount();
        }
        
        public double getValueAtCenter() {
            if (Double.isNaN(valueAtCenter)) {
                Point center = o.getCenter();
                if (center==null) center = o.getMassCenter(transformedMap, false);
                if (o.isAbsoluteLandMark()) {
                    center.ensureWithinBounds(transformedMap);
                    this.valueAtCenter = transformedMap.getPixelWithOffset(center.get(0), center.get(1), center.getWithDimCheck(2));
                } else {
                    center.ensureWithinBounds(transformedMap.getBoundingBox().resetOffset());
                    this.valueAtCenter = transformedMap.getPixel(center.get(0), center.get(1), center.getWithDimCheck(2));
                }
            }
            return valueAtCenter;
        }
        public double getMedian() {
            if (Double.isNaN(median)) this.median = BasicMeasurements.getQuantileValue(o, transformedMap, 0.5)[0];
            return median;
        }
    }
}
