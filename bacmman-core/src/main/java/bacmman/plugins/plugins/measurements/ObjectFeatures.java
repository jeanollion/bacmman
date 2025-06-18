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
import java.util.stream.Collectors;

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
    SimpleListParameter<PluginParameter<ObjectFeature>> features = new SimpleListParameter<>("Features", def).setMinChildCount(1).setChildrenNumber(1).setEmphasized(true);
    PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters").setHint("All intensity measurements features will be computed on the image filtered by the operation defined in this parameter.");
    enum MODE_3D {ALL_PLANES, SINGLE_PLANE, CENTER_PLANE}
    EnumChoiceParameter<MODE_3D> mode3D = new EnumChoiceParameter<MODE_3D>("3D measurement", MODE_3D.values(), MODE_3D.ALL_PLANES).setHint("For intensity measurement only: In case of 3D measurement: choose <br/>ALL_PLANES to perform regular 3D measurement <br/>SINGLE_PLANE to limit measurement to a single user-defined plane. <br/>CENTER_PLANE: to perform measurement on the plane of the Z coordinate of the center (linear interpolation for non-integer coordinate)");
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
            ObjectFeature feature = ofp.instantiatePlugin();
            if (feature!=null) {
                int[] zMinMax = new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE};
                if (feature instanceof IntensityMeasurement && mode3D.getSelectedEnum().equals(MODE_3D.CENTER_PLANE)) {
                    parent.getChildren(structureIdx).mapToDouble(c -> c.getRegion().getCenterOrGeomCenter().getWithDimCheck(2))
                        .forEach(z -> {
                            if (((int)z) < zMinMax[0]) zMinMax[0] = (int)z;
                            if (((int)Math.ceil(z)) > zMinMax[1]) zMinMax[1] = (int)Math.ceil(z);
                        });
                }
                if (feature instanceof IntensityMeasurement && mode3D.getSelectedEnum().equals(MODE_3D.CENTER_PLANE) && zMinMax[0]!=zMinMax[1]) { // compute for each plane and measure intensity
                    //logger.debug("center intensity measurement: [{}; {}]", zMinMax[0], zMinMax[1]);
                    List<SegmentedObject> children = parent.getChildren(structureIdx).collect(Collectors.toList());
                    double[][] measurements = new double[zMinMax[1] - zMinMax[0] + 1][children.size()];
                    ObjectFeature fz = feature;
                    for (int z = zMinMax[0]; z<=zMinMax[1]; ++z) {
                        ((IntensityMeasurement) fz).limitToZ(z);
                        fz.setUp(parent, structureIdx, parent.getChildRegionPopulation(structureIdx));
                        ((ObjectFeatureWithCore) fz).setUpOrAddCore(cores, pf);
                        for (int i = 0; i<measurements[z].length; ++i) {
                            if (bacmman.image.BoundingBox.containsZ(children.get(i).getBounds(), z)) measurements[z][i] = fz.performMeasurement(children.get(i).getRegion());
                        }
                        if (z<zMinMax[1]) fz = ofp.instantiatePlugin();
                    }
                    for (int i = 0; i<measurements[0].length; ++i) {
                        double z = children.get(i).getRegion().getCenterOrGeomCenter().get(2);
                        int z1 = (int)z;
                        int z2 = (int)Math.ceil(z);
                        double m = measurements[z1][i] * (z2 - z) + measurements[z2][i] * (z - z1);
                        children.get(i).getMeasurements().setValue(((TextParameter) ofp.getAdditionalParameters().get(0)).getValue(), m);
                    }
                } else {
                    if (feature instanceof IntensityMeasurement && mode3D.getSelectedEnum().equals(MODE_3D.SINGLE_PLANE))
                        ((IntensityMeasurement) feature).limitToZ(plane.getIntValue());
                    else if (feature instanceof IntensityMeasurement && mode3D.getSelectedEnum().equals(MODE_3D.CENTER_PLANE)) ((IntensityMeasurement) feature).limitToZ(zMinMax[0]);
                    feature.setUp(parent, structureIdx, parent.getChildRegionPopulation(structureIdx));
                    if (feature instanceof ObjectFeatureWithCore) ((ObjectFeatureWithCore) feature).setUpOrAddCore(cores, pf);
                    parent.getChildren(structureIdx).forEach(o -> {
                        double m = feature.performMeasurement(o.getRegion());
                        o.getMeasurements().setValue(((TextParameter) ofp.getAdditionalParameters().get(0)).getValue(), m);
                    });
                }
            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    
}
