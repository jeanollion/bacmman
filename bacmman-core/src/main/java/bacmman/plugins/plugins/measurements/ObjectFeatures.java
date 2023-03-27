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
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.PreFilter;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import bacmman.plugins.object_feature.ObjectFeatureWithCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 *
 * @author Jean Ollion
 */
public class ObjectFeatures implements Measurement, Hint {
    ObjectClassParameter structure = new ObjectClassParameter("Object class", -1, true, false)
            .setNoSelectionString("Viewfield")
            .setEmphasized(true).setHint("Segmented object class of to compute feature(s) on (defines the region-of-interest of the measurement)");
    PluginParameter<ObjectFeature> def = new PluginParameter<>("Feature", ObjectFeature.class, false)
            .setAdditionalParameters(new TextParameter("Name", "", false)).setNewInstanceConfiguration(oc->{
                if (oc instanceof IntensityMeasurement) ((IntensityMeasurement)oc).setIntensityStructure(structure.getSelectedClassIdx());
                // TODO find a way to set name as default name ...
            });;
    SimpleListParameter<PluginParameter<ObjectFeature>> features = new SimpleListParameter<>("Features", 0, def).setEmphasized(true);
    PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters").setHint("All intensity measurements features will be computed on the image filtered by the operation defined in this parameter.");
    enum MODE_3D {ALL_PLANES, SINGLE_PLANE}
    EnumChoiceParameter<MODE_3D> mode3D = new EnumChoiceParameter<MODE_3D>("3D measurement", MODE_3D.values(), MODE_3D.ALL_PLANES).setHint("For intensity measurement only: In case of 3D measurement: choose ALL_PLANES to perform regular 3D measurement, or SINGLE_PLANE to limit measurement to a single user-defined plane");
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane", 0, -1, 0, null).setHint("Choose plane to perform measurement on (zero-based index of plane)");
    ConditionalParameter<MODE_3D> mode3Dcond = new ConditionalParameter<>(mode3D).setActionParameters(MODE_3D.SINGLE_PLANE, plane);
    Parameter[] parameters = new Parameter[]{structure, preFilters, features, mode3Dcond};
    
    @Override
    public String getHintText() {
        return "Collection of features (scalar values) computed on single objects, such as intensity measurement (mean, min..) or geometrical features (length, size..).";
    }
    
    public ObjectFeatures() {
        features.addNewInstanceConfiguration(pp -> {
            pp.addListener( s -> {
                TextParameter tp = ((TextParameter)s.getAdditionalParameters().get(0));
                if (s.isOnePluginSet()) tp.setValue(s.instantiatePlugin().getDefaultName());
                else tp.setValue("");
            });
            ((TextParameter)pp.getAdditionalParameters().get(0)).addValidationFunction((t)-> t.getValue().length()>0);
        });
    }

    public ObjectFeatures(int structureIdx) {
        this();
        this.structure.setSelectedIndex(structureIdx);
    }
    public ObjectFeatures addFeatures(ObjectFeature... features) {
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
    public ObjectFeatures addFeature(ObjectFeature feature, String key) {
        PluginParameter<ObjectFeature> f = def.duplicate().setPlugin(feature);
        this.features.insert(f);
        ((TextParameter)f.getAdditionalParameters().get(0)).setValue(key);
        return this;
    }
    public ObjectFeatures addPreFilter(PreFilter... prefilters) {
        this.preFilters.add(prefilters);
        return this;
    }
    @Override
    public int getCallObjectClassIdx() {
        return structure.getSelectedClassIdx()==-1 ? -1 : structure.getParentObjectClassIdx();
    }
    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }
    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res=  new ArrayList<>(features.getChildCount());
        for (PluginParameter<ObjectFeature> ofp : features.getActivatedChildren()) res.add(new MeasurementKeyObject(((TextParameter)ofp.getAdditionalParameters().get(0)).getValue(), structure.getSelectedIndex()));
        return res;
    }
    @Override
    public void performMeasurement(SegmentedObject parent) {
        int structureIdx = structure.getSelectedIndex();
        Map<Image, IntensityMeasurementCore> cores = new ConcurrentHashMap<>();
        BiFunction<Image, ImageMask, Image> pf = (im, mask) -> preFilters.filter(im,mask);
        for (PluginParameter<ObjectFeature> ofp : features.getActivatedChildren()) {
            ObjectFeature f = ofp.instantiatePlugin();
            if (f!=null) {
                if (f instanceof IntensityMeasurement && mode3D.getSelectedEnum().equals(MODE_3D.SINGLE_PLANE)) ((IntensityMeasurement)f).limitToZ(plane.getIntValue());
                f.setUp(parent, structureIdx, parent.getChildRegionPopulation(structureIdx));
                if (f instanceof ObjectFeatureWithCore) ((ObjectFeatureWithCore)f).setUpOrAddCore(cores, pf);
                parent.getChildren(structureIdx).forEach(o-> {
                    double m = f.performMeasurement(o.getRegion());
                    o.getMeasurements().setValue(((TextParameter)ofp.getAdditionalParameters().get(0)).getValue(), m);
                });
            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    
}
