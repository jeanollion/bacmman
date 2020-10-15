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
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.*;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import bacmman.plugins.object_feature.ObjectFeatureWithCore;
import bacmman.utils.ThreadRunner;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class ObjectFeaturesTPF implements Measurement, Hint, MultiThreaded {
    ObjectClassParameter structure = new ObjectClassParameter("Object class", -1, false, false).setEmphasized(true).setHint("Segmented object class of to compute feature(s) on (defines the region-of-interest of the measurement)");
    PluginParameter<ObjectFeature> def = new PluginParameter<>("Feature", ObjectFeature.class, false)
            .setAdditionalParameters(new TextParameter("Name", "", false)).setNewInstanceConfiguration(oc->{
                if (oc instanceof IntensityMeasurement) ((IntensityMeasurement)oc).setIntensityStructure(structure.getSelectedClassIdx());
                // TODO find a way to set name as default name ...
            });
    SimpleListParameter<PluginParameter<ObjectFeature>> features = new SimpleListParameter<>("Features", 0, def).setEmphasized(true);
    TrackPreFilterSequence preFilters = new TrackPreFilterSequence("Track Pre-Filters").setHint("All intensity measurements features will be computed on the image filtered by the operation defined in this parameter.");
    Parameter[] parameters = new Parameter[]{structure, preFilters, features};

    @Override
    public String getHintText() {
        return "Collection of features (scalar values) computed on single objects, such as intensity measurement (mean, min..) or geometrical features (length, size..). <br />This measurement is similar to ObjectFeatures except that TrackPreFilters can be set";
    }

    public ObjectFeaturesTPF() {
        def.addListener( s -> {
            TextParameter tp = ((TextParameter)s.getAdditionalParameters().get(0));
            if (s.isOnePluginSet()) tp.setValue(s.instantiatePlugin().getDefaultName());
            else tp.setValue("");
        });
        ((TextParameter)def.getAdditionalParameters().get(0)).addValidationFunction((t)->((TextParameter)t).getValue().length()>0);
    }

    public ObjectFeaturesTPF(int structureIdx) {
        this();
        this.structure.setSelectedIndex(structureIdx);
    }
    public ObjectFeaturesTPF addFeatures(ObjectFeature... features) {
        if (features==null) return this;
        for (ObjectFeature f : features) {
            if (f instanceof IntensityMeasurement) { // autoconfiguration of intensity measurements
                IntensityMeasurement im = ((IntensityMeasurement)f);
                if (im.getIntensityStructure()<0) im.setIntensityStructure(structure.getSelectedClassIdx());
            }
            PluginParameter<ObjectFeature> dup = def.duplicate().setPlugin(f);
            ((TextParameter)dup.getAdditionalParameters().get(0)).setValue(f.getDefaultName());
            this.features.insert(dup);
        }
        return this;
    }
    public ObjectFeaturesTPF addFeature(ObjectFeature feature, String key) {
        PluginParameter<ObjectFeature> f = def.duplicate().setPlugin(feature);
        this.features.insert(f);
        ((TextParameter)f.getAdditionalParameters().get(0)).setValue(key);
        return this;
    }
    public ObjectFeaturesTPF addTrackPreFilter(TrackPreFilter... prefilters) {
        this.preFilters.add(prefilters);
        return this;
    }
    @Override
    public int getCallObjectClassIdx() {
        return structure.getParentObjectClassIdx();
    }
    @Override
    public boolean callOnlyOnTrackHeads() {
        return true;
    }
    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res=  new ArrayList<>(features.getChildCount());
        for (PluginParameter<ObjectFeature> ofp : features.getActivatedChildren()) res.add(new MeasurementKeyObject(((TextParameter)ofp.getAdditionalParameters().get(0)).getValue(), structure.getSelectedIndex()));
        return res;
    }
    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        int structureIdx = structure.getSelectedIndex();
        // to save time, do not compute when no children object in track
        ProcessingPipeline.PARENT_TRACK_MODE mode =  preFilters.get().stream().map(TrackPreFilter::parentTrackMode).min(ProcessingPipeline.PARENT_TRACK_MODE.COMPARATOR).orElse(ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS);
        switch (mode) {
            case MULTIPLE_INTERVALS: {
                parentTrack.removeIf(p -> !p.getChildren(structureIdx).findAny().isPresent());
                break;
            }
            case SINGLE_INTERVAL: {
                // get frame interval of parent with objects
                IntSummaryStatistics stats= parentTrack.stream().filter(p->p.getChildren(structureIdx).findAny().isPresent()).mapToInt(SegmentedObject::getFrame).summaryStatistics();
                parentTrack.removeIf(p->p.getFrame()<stats.getMin() || p.getFrame()>stats.getMax());
                break;
            }
        }
        if (parentTrack.isEmpty()) return;
        Map<Image, IntensityMeasurementCore> cores = new ConcurrentHashMap<>();
        Set<Integer> allChildOC = features.getActivatedChildren().stream().map(PluginParameter::instantiatePlugin).filter(o -> o instanceof IntensityMeasurement).map(o -> ((IntensityMeasurement)o).getIntensityStructure()).collect(Collectors.toSet());;
        Map<Integer, BiFunction<Image, ImageMask, Image>> preFilterSequenceMapByOC = new HashMap<>();
        for (int oc : allChildOC) {
            Map<SegmentedObject, Image> preFiltered = preFilters.filterImages(oc, parentTrack);
            Map<Image, Image> rawToPF = preFiltered.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().getRawImage(oc), Map.Entry::getValue));
            preFilterSequenceMapByOC.put(oc, (im, mask)->rawToPF.get(im));
        }
        ThreadRunner.executeAndThrowErrors(parentTrack.parallelStream(), parent -> {
            for (PluginParameter<ObjectFeature> ofp : features.getActivatedChildren()) {
                ObjectFeature f = ofp.instantiatePlugin();
                if (f!=null) {
                    f.setUp(parent, structureIdx, parent.getChildRegionPopulation(structureIdx));
                    if (f instanceof ObjectFeatureWithCore) ((ObjectFeatureWithCore)f).setUpOrAddCore(cores, preFilterSequenceMapByOC.get(((ObjectFeatureWithCore)f).getIntensityStructure()));
                    parent.getChildren(structureIdx).forEach(o-> {
                        double m = f.performMeasurement(o.getRegion());
                        o.getMeasurements().setValue(((TextParameter)ofp.getAdditionalParameters().get(0)).getValue(), m);
                    });
                }
            }
        });
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    boolean parallel=true;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel=parallel;
    }
}
