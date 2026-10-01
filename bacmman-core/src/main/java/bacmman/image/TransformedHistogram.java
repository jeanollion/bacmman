/*
 * Copyright (C) 2026 Jean Ollion
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

import org.json.simple.JSONObject;

import java.util.stream.IntStream;

/**
 * Histogram whose bins have a constant size in a transformed space y = f(x): log(x - offset) or (x - offset)^exponent.
 * In value space, bin width increases with the value (f is concave), so that a narrow peak at low values (e.g. background) and a wide tail of high values (e.g. bright objects) are both resolved with populated bins.
 * {@link #getMin()} and {@link #getBinSize()} are expressed in transformed space; all methods returning values ({@link #getValueFromIdx(double)}, {@link #getBinCenter(int)}, quantiles, mode, min/max values...) return values in the original space.
 * @author Jean Ollion
 */
public class TransformedHistogram extends Histogram {
    public enum TRANSFORM {LOG, POWER}
    private TRANSFORM transform;
    private double exponent, offset;

    /**
     * @param binSize bin size in transformed space
     * @param min lower edge of first bin in transformed space
     * @param exponent exponent of the POWER transform (ignored for LOG)
     * @param offset values are transformed as f(x - offset)
     */
    public TransformedHistogram(long[] data, double binSize, double min, TRANSFORM transform, double exponent, double offset) {
        super(data, binSize, min);
        this.transform = transform;
        this.exponent = exponent;
        this.offset = offset;
    }

    public TRANSFORM getTransform() {
        return transform;
    }
    public double getExponent() {
        return exponent;
    }
    public double getOffset() {
        return offset;
    }

    public static double transform(double value, TRANSFORM transform, double exponent, double offset) {
        double d = value - offset;
        switch (transform) {
            case LOG:
            default:
                return Math.log(d);
            case POWER:
                return d <= 0 ? 0 : Math.pow(d, exponent);
        }
    }

    public static double inverse(double y, TRANSFORM transform, double exponent, double offset) {
        switch (transform) {
            case LOG:
            default:
                return Math.exp(y) + offset;
            case POWER:
                return Math.pow(Math.max(0, y), 1 / exponent) + offset;
        }
    }

    public double transform(double value) {
        return transform(value, transform, exponent, offset);
    }

    public double inverse(double y) {
        return inverse(y, transform, exponent, offset);
    }

    /**
     * @return histogram in transformed space: same bins (data array is shared), considered as linear. It is the histogram of the transformed values. A threshold t computed on it corresponds to the value {@link #inverse(double)}(t)
     */
    public Histogram getTransformedSpaceHistogram() {
        return new Histogram(getData(), getBinSize(), getMin());
    }

    @Override
    protected Histogram newInstance(long[] data, double binSize, double min) {
        return new TransformedHistogram(data, binSize, min, transform, exponent, offset);
    }

    /**
     * @return position idx in transformed space
     */
    public double getTransformedValueFromIdx(double idx) {
        return super.getValueFromIdx(idx);
    }

    @Override
    public double getValueFromIdx(double idx) {
        return inverse(super.getValueFromIdx(idx));
    }

    @Override
    public double getIdxFromValue(double value) {
        double y = transform(value);
        if (!(y > getMin())) return 0; // also handles values <= offset for LOG
        int idx = (int) Math.floor((y - getMin()) / getBinSize() + 1e-9);
        return Math.min(idx, getData().length - 1);
    }

    /**
     * @return center of the bin in transformed space
     */
    public double getTransformedBinCenter(int idx) {
        return super.getBinCenter(idx);
    }

    public double[] getTransformedBinCenters() {
        return super.getBinCenters();
    }

    /**
     * @return value corresponding to the center of the bin in transformed space
     */
    @Override
    public double getBinCenter(int idx) {
        return inverse(super.getBinCenter(idx));
    }

    @Override
    public double[] getBinCenters() {
        return IntStream.range(0, getData().length).mapToDouble(this::getBinCenter).toArray();
    }

    /**
     * @return width of the bin in value space
     */
    public double getBinWidth(int idx) {
        return getValueFromIdx(idx + 1) - getValueFromIdx(idx);
    }

    @Override
    public double getMean(int fromIncluded, int toExcluded) {
        double sum = 0, count = 0;
        for (int i = fromIncluded; i<toExcluded; ++i) {
            sum += getData()[i] * getBinCenter(i);
            count += getData()[i];
        }
        return sum / count;
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res = (JSONObject)super.toJSONEntry();
        res.put("transform", transform.toString());
        res.put("exponent", exponent);
        res.put("offset", offset);
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        super.initFromJSONEntry(jsonEntry);
        JSONObject o = (JSONObject) jsonEntry;
        transform = TRANSFORM.valueOf((String)o.get("transform"));
        exponent = ((Number)o.get("exponent")).doubleValue();
        offset = ((Number)o.get("offset")).doubleValue();
    }
}
