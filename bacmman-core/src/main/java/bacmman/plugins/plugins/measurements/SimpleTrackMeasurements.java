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

import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class SimpleTrackMeasurements implements Measurement, Hint {
    protected ObjectClassParameter structure = new ObjectClassParameter("Objects", -1, false, false);
    protected Parameter[] parameters = new Parameter[]{structure};
    
    
    public SimpleTrackMeasurements(){}
    
    public SimpleTrackMeasurements(int structure){
        this.structure.setSelectedIndex(structure);
    }
    
    @Override public int getCallObjectClassIdx() {
        return structure.getSelectedClassIdx();
    }

    @Override public boolean callOnlyOnTrackHeads() {
        return true;
    }

    @Override public List<MeasurementKey> getMeasurementKeys() {
        int structureIdx = structure.getSelectedClassIdx();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject("TrackHeadIndices", structureIdx));
        res.add(new MeasurementKeyObject("TrackLength", structureIdx));
        res.add(new MeasurementKeyObject("TrackObjectCount", structureIdx));
        res.add(new MeasurementKeyObject("ParentTrackHeadIndices", structureIdx));
        res.add(new MeasurementKeyObject("TrackErrorNext", structureIdx));
        res.add(new MeasurementKeyObject("TrackErrorPrev", structureIdx));
        return res;
    }

    @Override public void performMeasurement(SegmentedObject object) {
        String th = SegmentedObjectUtils.getIndices(object.getTrackHead());
        String pth = object.isRoot() ? Measurements.NA_STRING : SegmentedObjectUtils.getIndices(object.getParent().getTrackHead());
        List<SegmentedObject> track = SegmentedObjectUtils.getTrack(object);
        int tl = track.get(track.size()-1).getFrame() - object.getFrame()+1;
        for (SegmentedObject o : track) {
            o.getMeasurements().setValue("TrackLength", tl);
            o.getMeasurements().setValue("TrackObjectCount", track.size());
            o.getMeasurements().setStringValue("TrackHeadIndices", th);
            o.getMeasurements().setStringValue("ParentTrackHeadIndices", pth);
            o.getMeasurements().setValue("TrackErrorNext", o.hasTrackLinkError(false, true));
            o.getMeasurements().setValue("TrackErrorPrev", o.hasTrackLinkError(true, false));
        }
    }

    @Override public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "Collection of measurement on object tracks. <br />Measures: <ul><li>the track length (frame of last object - frame of first object + 1 )</li><li>the number of object in the track (that can differ from track length if there are gaps</li><li>the indices of the track head</li><li>The indices of the parent track head (e.g. first element of track)</li><li>For each object of the track, the tracking errors on link with previous / next elements (according to the tracker)</li></ul>";
    }
        
}
