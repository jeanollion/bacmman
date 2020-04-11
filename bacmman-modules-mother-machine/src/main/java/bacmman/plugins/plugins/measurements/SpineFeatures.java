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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.HintSimple;
import bacmman.plugins.Measurement;
import bacmman.plugins.MultiThreaded;
import bacmman.processing.bacteria_spine.BacteriaSpineCoord;
import bacmman.processing.bacteria_spine.BacteriaSpineFactory;
import bacmman.processing.bacteria_spine.BacteriaSpineLocalizer;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static bacmman.data_structure.SegmentedObjectUtils.getContainer;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.Size.SCALED_TT;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineLength.SPINE_DEF;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineLength.spineLengthAlgo;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineLength.spineLengthTT;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineWidth.spineWidthAlgo;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineWidth.spineWidthTT;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineWidth.validTT;

/**
 *
 * @author Jean Ollion
 */
public class SpineFeatures implements Measurement, MultiThreaded, Hint, HintSimple {
    protected ObjectClassParameter bacteria = new ObjectClassParameter("Bacteria", -1, false, false);
    protected BooleanParameter scaled = new BooleanParameter("Scaled", "Unit", "Pixel", false).setHint(SCALED_TT);
    protected Parameter[] parameters = new Parameter[]{bacteria, scaled};

    public SpineFeatures() {}
    public SpineFeatures(int bacteriaIdx) {
        this.bacteria.setSelectedIndex(bacteriaIdx);
    }
    @Override
    public String getHintText() {
        return "<ul><li><em>SpineLength</em>: " + spineLengthTT + spineLengthAlgo + "</li><li><em>SpineWidth</em>: " + spineWidthTT + spineWidthAlgo + "</li></ul>" + validTT+"<br />"+ SPINE_DEF;
    }
    @Override
    public String getSimpleHintText() {
        return "<ul><li><em>SpineLength</em>: " + spineLengthTT  + "</li><li><em>SpineWidth</em>: " + spineWidthTT + "</li></ul>" + validTT ;
    }
    public SpineFeatures setScaled(boolean scaled) {
        this.scaled.setSelected(scaled);
        return this;
    }
    @Override
    public int getCallObjectClassIdx() {
        return bacteria.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return true;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject("SpineWidth", bacteria.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("SpineLength", bacteria.getSelectedClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        double scale = scaled.getSelected() ? parentTrackHead.getScaleXY() : 1d;
        int objectClassIdx = bacteria.getSelectedClassIdx();
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead, false);
        Utils.parallele(parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)), parallel).forEach(e-> {
            double[] lengthAndWidth = BacteriaSpineFactory.getSpineLengthAndWidth(e.getRegion());
            e.getMeasurements().setValue("SpineLength", lengthAndWidth[0]*scale);
            e.getMeasurements().setValue("SpineWidth", lengthAndWidth[1]*scale);
        });
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    boolean parallel = false;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel= parallel;
    }

}
