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

import bacmman.data_structure.Region;
import bacmman.image.*;

import java.util.Map;
import java.util.stream.DoubleStream;

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
    static int sizeLimitMedian = 512 * 512;
    Image intensityMap, transformedMap;
    Map<Region, IntensityMeasurements> values = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(IntensityMeasurements::new);
    protected Map<Region, Region> regionMapSlice = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(r -> {
        if (getZ() >= 0 && !r.is2D()) return r.intersectWithZPlane(getZ(), false, false);
        else return r;
    });
    int z = -1;
    protected int getZ() {return z;}
    public IntensityMeasurementCore limitToZ(int z) {
        this.z = z;
        return this;
    }
    public int getZPlane() {
        return z;
    }
    public void setUp(Image intensityMap, Image transformedMap) {
        this.intensityMap=intensityMap;    
        if (transformedMap==null) this.transformedMap=intensityMap;
        else this.transformedMap = transformedMap;
    }
    public Image getIntensityMap(boolean transformed) {
        return transformed ? transformedMap : intensityMap;
    }
    public IntensityMeasurements getIntensityMeasurements(Region o) {
        if (z>=0 && o!=null) o = regionMapSlice.get(o);
        return values.get(o);
    }
    
    public class IntensityMeasurements {
        public double mean=Double.NaN, sd=Double.NaN, min=Double.NaN, max=Double.NaN, valueAtCenter=Double.NaN, median=Double.NaN, count=Double.NaN;
        Region o;
        
        public IntensityMeasurements(Region o) {
            this.o=o;
            if (o==null) {
                count = 0;
                return;
            }
            if (!o.getBounds().isValid()) throw new RuntimeException("invalid bounds");
            DoubleStatistics stats = DoubleStatistics.getStats(stream());
            mean = stats.getAverage();
            sd = stats.getStandardDeviation();
            min = stats.getMin();
            max = stats.getMax();
            count = stats.getCount();
        }

        public DoubleStream stream() {
            ImageMask m = o.getMask();
            if (o.is2D() && transformedMap.sizeZ()>1 && !(m instanceof ImageMask2D)) m = new ImageMask2D(m);
            return transformedMap.stream(m, o.isAbsoluteLandMark());
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

            if (Double.isNaN(median)) {
                if (o.getBounds().volume() > sizeLimitMedian) { // volume is large: use histogram
                    Histogram h = HistogramFactory.getHistogram(this::stream);
                    if (h.count() >= sizeLimitMedian/2 ) this.median = h.getQuantiles(0.5)[0];
                }
                if (Double.isNaN(median)) this.median = BasicMeasurements.getQuantileValue(o, transformedMap, 0.5)[0];
            }
            return median;
        }
    }
}
