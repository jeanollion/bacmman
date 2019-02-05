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
package bacmman.plugins.plugins.track_post_filter;

import bacmman.data_structure.*;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import ij.process.AutoThresholder;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ThresholdMask;
import bacmman.plugins.Hint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import bacmman.plugins.TrackPostFilter;

/**
 *
 * @author Jean Ollion
 */
public class RemoveSaturatedMicrochannels implements TrackPostFilter, Hint {
    @Override
    public String getHintText() {
        return "Removes microchannel tracks that contain saturated pixels (defined by the  <em>Min percentage of saturated pixel</em> parameter).<br />Only valid if preprocessed images were subject to a saturation transformation on the detection channel where microchannels are segmented.";
    }
    BoundedNumberParameter minPercentageOfSaturatedObjects = new BoundedNumberParameter("Min. percentage of track", 0, 10, 0, 100).setHint("If the track has more than this proportion of saturated objects, it will be removed");
    BoundedNumberParameter minPercentageOfSaturatedPixels = new BoundedNumberParameter("Min. percentage of saturated pixel", 0, 10, 1, 100).setHint("If an object has more than this proportion of saturated pixel it will be considered as a saturated object (see <em>Min percentage of track</em> parameter)");
    Parameter[] parameters = new Parameter[]{minPercentageOfSaturatedPixels, minPercentageOfSaturatedObjects};
    public RemoveSaturatedMicrochannels() {}
    public RemoveSaturatedMicrochannels(double percentageOfSaturatedObjects, double percentageOfSaturatedPixels) {
        this.minPercentageOfSaturatedObjects.setValue(percentageOfSaturatedObjects);
        this.minPercentageOfSaturatedPixels.setValue(percentageOfSaturatedPixels);
    }
    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        
        List<SegmentedObject> objectsToRemove = new ArrayList<>();
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        for (Entry<SegmentedObject, List<SegmentedObject>> e : allTracks.entrySet()) {
            if (isSaturated(e.getValue())) objectsToRemove.addAll(e.getValue());
        }
        //logger.debug("remove track trackLength: #objects to remove: {}", objectsToRemove.size());
        if (!objectsToRemove.isEmpty()) SegmentedObjectEditor.deleteObjects(null, objectsToRemove, SegmentedObjectEditor.ALWAYS_MERGE, factory, editor);
    }
    private boolean isSaturated(List<SegmentedObject> track) {
        int saturatedObjectCount = 0;
        double stauratedObjectThld = track.size() * minPercentageOfSaturatedObjects.getValue().doubleValue() / 100d;
        for (SegmentedObject o : track) {
            if (isSaturated(o)) {
                ++saturatedObjectCount;
                if (saturatedObjectCount>=stauratedObjectThld) return true;
            }
        }
        return false;
    }
    private boolean isSaturated(SegmentedObject o) {
        // get mask of bacteria using Otsu threshold
        Image image = o.getRawImage(o.getStructureIdx());
        Histogram hist = HistogramFactory.getHistogram(()->image.stream().parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        double thld = IJAutoThresholder.runThresholder(AutoThresholder.Method.Otsu, hist);
        ThresholdMask mask = new ThresholdMask(image, thld, true, false);
        double stauratedPixelsThld =  mask.count() * minPercentageOfSaturatedPixels.getValue().doubleValue() / 100d;
        int i = hist.getMaxNonNullIdx();
        if (testMode) logger.debug("test saturated object: {}: total bact pixels: {} (thld: {}) sat pixel limit: {}, max value count: {} max value: {} ", o, mask.count(), thld, stauratedPixelsThld, i>0?hist.data[i]:0, hist.getValueFromIdx(i) );
        return (i>0 && hist.data[i]>stauratedPixelsThld);
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    boolean testMode;
    public void setTestMode(boolean testMode) {this.testMode=testMode;}

    
}
