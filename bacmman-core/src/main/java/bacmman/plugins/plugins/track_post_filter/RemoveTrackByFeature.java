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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import bacmman.data_structure.StructureObjectEditor;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.TrackPostFilter;
import static bacmman.plugins.plugins.track_post_filter.PostFilter.MERGE_POLICY_TT;
import bacmman.utils.ArrayUtil;
import bacmman.utils.MultipleException;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;
import static bacmman.utils.Utils.parallele;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class RemoveTrackByFeature implements TrackPostFilter, Hint {
    PluginParameter<ObjectFeature> feature = new PluginParameter<>("Feature", ObjectFeature.class, false).setHint("Feature computed on each object of the track");
    ChoiceParameter statistics = new ChoiceParameter("Statistics", new String[]{"Mean", "Median", "Quantile"}, "mean", false);
    NumberParameter quantile = new BoundedNumberParameter("Quantile", 3, 0.5, 0, 1);
    ConditionalParameter statCond = new ConditionalParameter(statistics).setActionParameters("Quantile", quantile).setHint("Statistics to summarize the distribution of computed features");
    NumberParameter threshold = new NumberParameter("Threshold", 4, 0);
    BooleanParameter keepOverThreshold = new BooleanParameter("Keep over threshold", true).setHint("If true, track will be removed if the statitics value is under the threshold");
    ChoiceParameter mergePolicy = new ChoiceParameter("Merge Policy", Utils.toStringArray(PostFilter.MERGE_POLICY.values()), PostFilter.MERGE_POLICY.ALWAYS_MERGE.toString(), false).setHint(MERGE_POLICY_TT);
    
    @Override
    public String getHintText() {
        return "Compute a feature on each object of a track, then a statistic on the distribution, and compare it to a threshold, in order to decided if the track should be removed or not";
    }
    
    
    public RemoveTrackByFeature setMergePolicy(PostFilter.MERGE_POLICY policy) {
        mergePolicy.setSelectedItem(policy.toString());
        return this;
    }
    
    public RemoveTrackByFeature setFeature(ObjectFeature feature, double thld, boolean keepOverThld) {
        this.feature.setPlugin(feature);
        this.threshold.setValue(thld);
        this.keepOverThreshold.setSelected(keepOverThld);
        return this;
    }
    public RemoveTrackByFeature setQuantileValue(double quantile) {
        this.statistics.setSelectedIndex(2);
        this.quantile.setValue(quantile);
        return this;
    }
    public RemoveTrackByFeature setStatistics(int stat) {
        this.statistics.setSelectedIndex(stat);
        return this;
    }
    
    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) throws MultipleException {
        if (!feature.isOnePluginSet()) return;
        Map<Region, Double> valueMap = new ConcurrentHashMap<>();
        // compute feature for each object, by parent
        Consumer<SegmentedObject> exe = p -> {
            SegmentedObject parent = (SegmentedObject)p;
            RegionPopulation pop = parent.getChildRegionPopulation(structureIdx);
            ObjectFeature f = feature.instanciatePlugin();
            f.setUp(parent, structureIdx, pop);
            Map<Region, Double> locValueMap = pop.getRegions().stream().collect(Collectors.toMap(o->o, o-> f.performMeasurement(o)));
            valueMap.putAll(locValueMap);
        };
        ThreadRunner.executeAndThrowErrors(parallele(parentTrack.stream(), true), exe);
        // resume in one value per track
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        List<SegmentedObject> objectsToRemove = new ArrayList<>();
        for (List<SegmentedObject> track : allTracks.values()) {
            List<Double> values = Utils.transform(track, so->valueMap.get(so.getRegion()));
            double value;
            switch (statistics.getSelectedIndex()) {
                case 0:
                    value = ArrayUtil.mean(values);
                    break;
                case 1:
                    value = ArrayUtil.median(values);
                    break;
                default:
                    value = ArrayUtil.quantile(values, quantile.getValue().doubleValue());
                    break;
            }
            if (keepOverThreshold.getSelected()) {
                if (value<threshold.getValue().doubleValue()) objectsToRemove.addAll(track);
            } else {
                if (value>threshold.getValue().doubleValue()) objectsToRemove.addAll(track);
            }
        }
        BiPredicate<SegmentedObject, SegmentedObject> mergePredicate = PostFilter.MERGE_POLICY.valueOf(mergePolicy.getSelectedItem()).mergePredicate;
        if (!objectsToRemove.isEmpty()) StructureObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor); // only delete
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{feature, statCond, threshold, keepOverThreshold, mergePolicy};
    }

    
}
