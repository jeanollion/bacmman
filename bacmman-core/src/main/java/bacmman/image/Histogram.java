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
package bacmman.image;

import static bacmman.utils.Utils.parallel;

import bacmman.utils.ArrayUtil;
import bacmman.utils.JSONSerializable;
import bacmman.utils.JSONUtils;
import ij.gui.Plot;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class Histogram implements JSONSerializable  {

    private long[] data;
    private double  min;
    private double binSize;

    public Histogram(int[] data, double binSize, double min) {
        this.data = Arrays.stream(data).mapToLong(i -> i).toArray();
        this.binSize = binSize;
        this.min=min;
    }
    public Histogram(long[] data, double[] minAndMax) {
        this.data = data;
        this.min = minAndMax[0];
        binSize = data.length > 1 ? ( minAndMax[1] - minAndMax[0]) / (double)(data.length - 1) : minAndMax[1] - minAndMax[0]; // max is included in last bin, consistent with HistogramFactory
    }
    public Histogram(long[] data, double binSize, double min) {
        this.data = data;
        this.binSize = binSize;
        this.min=min;
    }

    public long[] getData() {
        return data;
    }
    public double[] getBinCenters() {
        double start = getBinSize()/2 + getMin();
        return IntStream.range(0, data.length).mapToDouble(i -> start + i * binSize).toArray();
    }
    public double getMin() {
        return min;
    }

    public double getBinSize() {
        return binSize;
    }

    public Histogram duplicate() {
        return duplicate(0, data.length);
    }
    /**
     * @return center of the last non-empty bin (for integer histograms built by {@link HistogramFactory}, the maximal value)
     */
    public double getMaxValue() {
        return getBinCenter(getMaxNonNullIdx());
    }

    /**
     * @return center of the first non-empty bin (for integer histograms built by {@link HistogramFactory}, the minimal value)
     */
    public double getMinValue() {
        return getBinCenter(getMinNonNullIdx());
    }

    public Histogram duplicate(int fromIdxIncluded, int toIdxExcluded) {
        long[] dup = new long[data.length];
        System.arraycopy(data, fromIdxIncluded, dup, fromIdxIncluded, toIdxExcluded-fromIdxIncluded);
        return newInstance(dup, binSize, min);
    }

    /**
     * Creates a histogram of the same type (e.g. same transform for subclasses) with other data and binning
     * @param binSize bin size, in the same unit as {@link #getBinSize()}
     * @param min lower edge of the first bin, in the same unit as {@link #getMin()}
     */
    protected Histogram newInstance(long[] data, double binSize, double min) {
        return new Histogram(data, binSize, min);
    }
    
    public void add(Histogram other) {
        for (int i = 0; i < data.length; ++i) data[i]+=other.data[i];
    }
    public void remove(Histogram other) {
        for (int i = 0; i < data.length; ++i) data[i]-=other.data[i];
    }
    
    /**
     * @param idx bin index, can be fractional
     * @return value at position idx: integer idx corresponds to the lower edge of the bin (use {@link #getBinCenter(int)} for the value represented by a bin)
     */
    public double getValueFromIdx(double idx) {
        return idx * binSize + min;
    }
    public double getIdxFromValue(double value) {
        if (value<=min) return 0;
        int idx = (int) Math.floor((value - min) / binSize + 1e-9); // bin containing value, consistent with HistogramFactory
        if (idx>=data.length) return data.length-1;
        return idx;
    }
    public double getMeanIdx(int fromIncluded, int toExcluded) {
        double count = 0;
        double meanIdx = 0;
        for (int i = fromIncluded; i<toExcluded; ++i) {
            meanIdx+=data[i] * i;
            count+=data[i];
        }
        return meanIdx/count;
    }
    /**
     * @return mean of the values of bins [fromIncluded; toExcluded), each bin being represented by its center
     */
    public double getMean(int fromIncluded, int toExcluded) {
        return getValueFromIdx(getMeanIdx(fromIncluded, toExcluded) + 0.5);
    }
    public long count(int fromIncluded, int toExcluded) {
        long count = 0;
        for (int i = fromIncluded; i<toExcluded; ++i) count+=data[i];
        return count;
    }
    public long count() {
        long sum = 0;
        for (long i : data) sum+=i;
        return sum;
    }
    public int getMaxNonNullIdx() {
        int i = data.length-1;
        if (data[i]==0) while(i>0 && data[i-1]==0) --i;
        return i;
    }
    public int getMinNonNullIdx() {
        int i = 0;
        if (data[i]==0) while(i<data.length-1 && data[i+1]==0) ++i;
        return i;
    }
    // removed leading and trailing zeros if any

    /**
     *
     * @return new histogram instance without leading & trailing zeros if any, same instance else.
     */
    public Histogram getShortenedHistogram() {
        int minIdx = getMinNonNullIdx();
        int maxIdx = getMaxNonNullIdx();
        if (minIdx>0 || maxIdx<data.length-1) {
            return newInstance(Arrays.copyOfRange(data, minIdx, maxIdx+1), binSize, min + minIdx * binSize);
        } else return this;
    }
    public void removeSaturatingValue(double countThlFactor, boolean highValues) {
        if (highValues) {
            int i = getMaxNonNullIdx();
            if (i>0) {
                //logger.debug("remove saturating value: {} (prev: {}, i: {})", data[i], data[i-1], i);
                if (data[i]>data[i-1]*countThlFactor) data[i]=0;
            }
        } else {
            int i = getMinNonNullIdx();
            if (i<data.length-1) {
                //logger.debug("remove saturating value: {} (prev: {}, i: {})", data[i], data[i-1], i);
                if (data[i]>data[i+1]*countThlFactor) data[i]=0;
            }
        }
    }
    public double[] getQuantiles(double... quantile) {
        int gcount = 0;
        for (long i : data) gcount += i;
        double[] res = new double[quantile.length];
        for (int i = 0; i<res.length; ++i) {
            if (quantile[i]<=0) res[i] = getMinValue();
            else if (quantile[i]>=1) res[i]=getMaxValue();
            else {
                long count = gcount;
                double limit = count * (1 - quantile[i]); // 1- ?
                if (limit >= count) {
                    res[i] = getValueFromIdx(0);
                    continue;
                }
                count = data[data.length - 1];
                int idx = data.length - 1;
                while (count < limit && idx > 0) {
                    idx--;
                    count += data[idx];
                }
                double idxInc = (data[idx] != 0) ? (count - limit) / (data[idx]) : 0; //lin approx
                res[i] = getValueFromIdx(idx + idxInc);
            }
        }
        return res;
    }

    public double getBinCenter(int idx) {
        return min + (idx + 0.5) * binSize;
    }

    /**
     * Quantile of the values contained in bins [fromIncluded; toExcluded), with linear interpolation within bins
     * @return quantile value, NaN if the range is empty
     */
    public double getQuantile(double quantile, int fromIncluded, int toExcluded) {
        long total = count(fromIncluded, toExcluded);
        if (total == 0) return Double.NaN;
        double target = quantile * total;
        long cum = 0;
        for (int i = fromIncluded; i<toExcluded; ++i) {
            if (data[i] == 0) continue;
            if (cum + data[i] >= target) return getValueFromIdx(i + (target - cum) / data[i]);
            cum += data[i];
        }
        return getValueFromIdx(toExcluded);
    }

    public double getMode() {
        int maxbin = ArrayUtil.max(data);
        return getBinCenter(maxbin);
    }

    public double getModeExcludingTailEnds(int excludeLeft, int excludeRight) {
        if (excludeLeft<0) excludeLeft = 0;
        if (excludeRight<0) excludeRight = 0;
        int maxbin = ArrayUtil.max(data, excludeLeft, data.length-excludeRight);
        return getBinCenter(maxbin);
    }

    public double getCountLinearApprox(double histoIdx) {
        if (histoIdx<=0) return data[0];
        if (histoIdx>=data.length-1) return data[data.length-1];
        int idx = (int)histoIdx;
        return data[idx] + (data[idx+1]-data[idx]) * (histoIdx-idx);
    }
    public void eraseRange(int fromIncluded, int toExcluded) {
        Arrays.fill(data, fromIncluded, toExcluded, 0);
    }
    /**
     * 
     * @param title
     * @param xValues if false: histogram index will be displayed in x axis
     */
    public void plotIJ1(String title, boolean xValues) {
        float[] values = new float[data.length];
        float[] x = new float[values.length];
        for (int i = 0; i<values.length; ++i) {
            values[i] = data[i];
            x[i] = xValues ? (float)getBinCenter(i) : i;
        }
        new Plot(title, "value", "count", x, values).show();
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("data", JSONUtils.toJSONArray(data));
        res.put("min", min);
        res.put("binSize", binSize);
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject o = (JSONObject) jsonEntry;
        data= JSONUtils.fromLongArray((JSONArray)(o).get("data"));
        min = ((Number)o.get("min")).doubleValue();
        binSize = ((Number)o.get("binSize")).doubleValue();
    }
}
