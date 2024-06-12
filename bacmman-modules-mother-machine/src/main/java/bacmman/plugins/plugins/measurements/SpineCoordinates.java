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
import bacmman.processing.bacteria_spine.BacteriaSpineCoord;
import bacmman.processing.bacteria_spine.BacteriaSpineLocalizer;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Measurement;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.Hint;
import bacmman.utils.MultipleException;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import static bacmman.data_structure.SegmentedObjectUtils.getContainer;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.Size.SCALED_TT;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineLength.SPINE_DEF;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SpineCoordinates implements Measurement, MultiThreaded, Hint {
    protected ObjectClassParameter bacteria = new ObjectClassParameter("Bacteria", -1, false, false);
    protected ObjectClassParameter spot = new ObjectClassParameter("Spot", -1, false, false);
    protected BooleanParameter scaled = new BooleanParameter("Scaled", "Unit", "Pixel", false).setHint(SCALED_TT);
    protected BooleanParameter setSpineLengthToParent = new BooleanParameter("Set SpineLength to parent", true);
    protected Parameter[] parameters = new Parameter[]{bacteria, spot, scaled, setSpineLengthToParent};
    
    public SpineCoordinates() {}
    public SpineCoordinates(int spotIdx, int bacteriaIdx) {
        this.spot.setSelectedIndex(spotIdx);
        this.bacteria.setSelectedIndex(bacteriaIdx);
    }
    @Override
    public String getHintText() {
        return "Computes the spine coordinates (see spine definition below) of a spot (of class defined in the <em>Spot</em> parameter) in a bacteria (of class defined in the <em>Bacteria</em> parameter). <ul><li><em>SpineCurvilinearCoord</em> is the coordinate along the bacteria longitudinal axis</li><li><em>SpineRadialCoord is the coordinate perpendicular to the longitudinal axis (negative on the left side)</em></li><li><em>SpineLength is the total spine length between the two pole</em></li><li><em>SpineRadius is the width of the bacteria at the position of the spot</em></li></ul><br />The center of the Spot object is by default the center defined by the segmenter, if no center was defined, the mass center will used<br />The object class defined in the <em>Bacteria</em> parameter must correspond to regular rod-shaped objects. If objects are not regular (presence of holes, thickness of less than 3 pixels) results are not defined. If needed use regularization such as binary close and fill holes. <br /><br />"+SPINE_DEF;
    }
    public SpineCoordinates setScaled(boolean scaled) {
        this.scaled.setSelected(scaled);
        return this;
    }
    @Override
    public int getCallObjectClassIdx() {
        return spot.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return true;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject("SpineCurvilinearCoord", spot.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("SpineRadialCoord", spot.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("SpineLength", spot.getSelectedClassIdx()));
        if (setSpineLengthToParent.getSelected()) res.add(new MeasurementKeyObject("SpineLength", bacteria.getSelectedClassIdx())); // also set to bacteria
        res.add(new MeasurementKeyObject("SpineRadius", spot.getSelectedClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        double scale = scaled.getSelected() ? parentTrackHead.getScaleXY() : 1d;
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Map<SegmentedObject, SegmentedObject> spotMapBacteria = new ConcurrentHashMap<>();
        parentTrack.parallelStream().forEach(parent -> {
            Stream<SegmentedObject> stream = parent.getChildren(spot.getSelectedClassIdx());
            Map<SegmentedObject, SegmentedObject> sMb = Utils.toMapWithNullValues(stream, Function.identity(), oo -> getContainer(oo.getRegion(), parent.getChildren(bacteria.getSelectedClassIdx()), null), false);
            if (sMb!=null) spotMapBacteria.putAll(sMb);
        });
        MultipleException me = new MultipleException();
        Map<SegmentedObject, BacteriaSpineLocalizer> bacteriaMapLocalizer = Utils.toMapWithNullValues(Utils.parallel(spotMapBacteria.values().stream(), true), b->b, Utils.applyREx(b-> new BacteriaSpineLocalizer(b.getRegion())), true, me);
        Utils.parallel(spotMapBacteria.entrySet().stream(), parallel).forEach(e-> {
            Point center = e.getKey().getRegion().getCenter();
            if (center==null) center = e.getKey().getRegion().getGeomCenter(false);
            BacteriaSpineCoord coord = e.getValue()==null? null : bacteriaMapLocalizer.get(e.getValue()).getSpineCoord(center);
            if (coord==null) {
                e.getKey().getMeasurements().setValue("SpineCurvilinearCoord", null);
                e.getKey().getMeasurements().setValue("SpineRadialCoord", null);
                e.getKey().getMeasurements().setValue("SpineLength", null);
                if (setSpineLengthToParent.getSelected()) e.getValue().getMeasurements().setValue("SpineLength", null); // also set to bacteria
                e.getKey().getMeasurements().setValue("SpineRadius", null);
            } else {
                e.getKey().getMeasurements().setValue("SpineCurvilinearCoord", coord.curvilinearCoord(false)*scale);
                e.getKey().getMeasurements().setValue("SpineRadialCoord", coord.radialCoord(false)*scale);
                e.getKey().getMeasurements().setValue("SpineLength", coord.spineLength()*scale);
                if (setSpineLengthToParent.getSelected()) e.getValue().getMeasurements().setValue("SpineLength", coord.spineLength()*scale); // also set to bacteria
                e.getKey().getMeasurements().setValue("SpineRadius", coord.spineRadius()*scale); // radius at spot position
            }
        });
        if (!me.isEmpty()) throw me;
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }

    
    
}
