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

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import bacmman.data_structure.SegmentedObjectEditor;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TrackPostFilter;
import static bacmman.plugins.plugins.track_post_filter.PostFilter.MERGE_POLICY_TT;

import bacmman.plugins.object_feature.ObjectFeatureWithCore;
import bacmman.utils.ArrayUtil;
import bacmman.utils.MultipleException;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;
import static bacmman.utils.Utils.parallel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class RemoveTrackByFeature implements TrackPostFilter, Hint {
    public enum STAT {Mean, Median, Quantile}
    PluginParameter<ObjectFeature> feature = new PluginParameter<>("Feature", ObjectFeature.class, false).setEmphasized(true).setHint("Feature computed on each object of the track");
    PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters").setHint("All features computed on image intensity will be computed on the image filtered by the operation defined in this parameter.");

    EnumChoiceParameter<STAT> statistics = new EnumChoiceParameter("Statistics", STAT.values(), STAT.Mean);
    NumberParameter quantile = new BoundedNumberParameter("Quantile", 3, 0.5, 0, 1);
    ConditionalParameter<STAT> statCond = new ConditionalParameter<>(statistics).setActionParameters(STAT.Quantile, quantile).setHint("Statistics to summarize the distribution of computed features");
    NumberParameter threshold = new NumberParameter<>("Threshold", 4, 0).setEmphasized(true);
    BooleanParameter keepOverThreshold = new BooleanParameter("Keep over threshold", true).setEmphasized(true).setHint("If true, track will be removed if the statitics value is under the threshold");
    EnumChoiceParameter<PostFilter.MERGE_POLICY> mergePolicy = new EnumChoiceParameter<>("Merge Policy", PostFilter.MERGE_POLICY.values(), PostFilter.MERGE_POLICY.ALWAYS_MERGE).setHint(MERGE_POLICY_TT);
    
    @Override
    public String getHintText() {
        return "<b>Removes tracks according to a user-defined criterion</b><br />Computes a user-defined feature (e.g. max, mean… see the <em>Feature</em> parameter) for each object of a track, then computes a user-defined statistics (e.g. mean, median… see the <em>Statistics</em> parameter) on the distribution of the objects’ feature, and compares it to a threshold (see the <em>Threshold</em> parameter), in order to decide if the track should be removed or not ";
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }
    
    public RemoveTrackByFeature setMergePolicy(PostFilter.MERGE_POLICY policy) {
        mergePolicy.setSelectedEnum(policy);
        return this;
    }
    
    public RemoveTrackByFeature setFeature(ObjectFeature feature, double thld, boolean keepOverThld) {
        this.feature.setPlugin(feature);
        this.threshold.setValue(thld);
        this.keepOverThreshold.setSelected(keepOverThld);
        return this;
    }
    public RemoveTrackByFeature setQuantileValue(double quantile) {
        this.statistics.setSelectedEnum(STAT.Quantile);
        this.quantile.setValue(quantile);
        return this;
    }
    public RemoveTrackByFeature setStatistics(STAT stat) {
        this.statistics.setSelectedEnum(stat);
        return this;
    }
    
    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) throws MultipleException {
        if (!feature.isOnePluginSet()) return;
        Map<Region, Double> valueMap = new ConcurrentHashMap<>();
        BiFunction<Image, ImageMask, Image> pf = (im, mask) -> preFilters.filter(im,mask);
        // compute feature for each object, by parent
        Consumer<SegmentedObject> exe = parent -> {
            RegionPopulation pop = parent.getChildRegionPopulation(structureIdx);
            ObjectFeature f = feature.instantiatePlugin();
            f.setUp(parent, structureIdx, pop);
            if (f instanceof ObjectFeatureWithCore) ((ObjectFeatureWithCore)f).setUpOrAddCore(null, pf);
            Map<Region, Double> locValueMap = pop.getRegions().stream().collect(Collectors.toMap(o->o, f::performMeasurement));
            valueMap.putAll(locValueMap);
        };
        ThreadRunner.executeAndThrowErrors(Utils.parallel(parentTrack.stream(), true), exe);
        // resume in one value per track
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx, true, true);
        List<SegmentedObject> objectsToRemove = new ArrayList<>();
        for (List<SegmentedObject> track : allTracks.values()) {
            List<Double> values = Utils.transform(track, so->valueMap.get(so.getRegion()));
            double value;
            switch (statistics.getSelectedEnum()) {
                case Mean:
                    value = ArrayUtil.mean(values);
                    break;
                case Median:
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
        BiPredicate<SegmentedObject, SegmentedObject> mergePredicate = mergePolicy.getSelectedEnum().mergePredicate;
        if (!objectsToRemove.isEmpty()) SegmentedObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor, true); // only delete
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{feature, preFilters, statCond, threshold, keepOverThreshold, mergePolicy};
    }

    
}
