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
package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Measurement;
import bacmman.plugins.plugins.transformations.SelectBestFocusPlane;
import bacmman.utils.LinearRegression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;


/**
 *
 * @author Jean Ollion
 */
public class XZSlope implements Measurement {
    protected ObjectClassParameter microchannel = new ObjectClassParameter("Object Class", 0, false, false).setHint("Select object class corresponding to microchannel");
    NumberParameter gradientScale = new BoundedNumberParameter("Gradient Scale", 5, 2, 0, 10);
    PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters");
    NumberParameter smoothScale = new BoundedNumberParameter("Smooth Scale", 5, 2, 1, 10);
    NumberParameter precisionFactor = new BoundedNumberParameter("precision Factor", 0, 2, 1, 10);

    @Override
    public int getCallObjectClassIdx() {
        return microchannel.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        int mcOCIdx = microchannel.getSelectedClassIdx();
        int parentIdx = microchannel.getParentObjectClassIdx();
        return new ArrayList<MeasurementKey>(){{add(new MeasurementKeyObject("XZSlope", parentIdx));add(new MeasurementKeyObject("FocusPlane", mcOCIdx));}};
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        int mcOCIDx = microchannel.getSelectedClassIdx();
        double gradientScale = this.gradientScale.getDoubleValue();
        double smoothScale = this.smoothScale.getDoubleValue();
        int precisionFactor = this.precisionFactor.getIntValue();
        List<SegmentedObject> mcs = object.getChildren(mcOCIDx).collect(Collectors.toList());;
        if (mcs.size()<2) return;
        Collections.sort(mcs, Comparator.comparingInt(o -> o.getBounds().xMin()));
        double[] values = mcs.stream().mapToDouble(mc -> SelectBestFocusPlane.getBestFocusPlane(mc.getRawImage(mcOCIDx).splitZPlanes(), gradientScale, smoothScale, precisionFactor, null, true, null, preFilters)).toArray();
        double[] xPos = mcs.stream().mapToDouble(mc -> mc.getBounds().xMean() * object.getScaleXY()).toArray();
        for (int i = 0; i<mcs.size(); ++i) {
            mcs.get(i).getMeasurements().setValue("FocusPlane", values[i]);
            values[i] *= object.getScaleZ();
        }
        logger.debug("focus planes: {}", values);
        // filter out null values (empty microchannels)
        int nNull = (int)DoubleStream.of(values).filter(d->d<=0).count();
        if (nNull>0) {
            double[] newValues = new double[values.length - nNull];
            double[] newX = new double[values.length - nNull];
            int idx = 0;
            for (int i = 0; i<values.length; ++i) {
                if (values[i]>0) {
                    newValues[idx] = values[i];
                    newX[idx] = xPos[i];
                    ++idx;
                }
            }
            values = newValues;
            xPos = newX;
        }
        double[] reg = LinearRegression.run(xPos, values);
        //double value = (right-left) * object.getScaleZ() / ((oRight.getBounds().xMean()-oLeft.getBounds().xMean()) * object.getScaleXY());
        logger.debug("slope: {} (scale XY: {}, Z: {})", reg[1], object.getScaleXY(), object.getScaleZ());
        object.getMeasurements().setValue("XZSlope", reg[1] * 100);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{microchannel, preFilters, gradientScale,smoothScale, precisionFactor};
    }

    @Override
    public String getHintText() {
        return "Compute slope along X axis (left-right), perpendicular to objective axis";
    }
}
