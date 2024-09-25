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
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.image.Image;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.*;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import bacmman.plugins.object_feature.ObjectFeatureWithCore;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.EdgeContact;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.LinearRegression;
import bacmman.utils.Utils;
import bacmman.configuration.parameters.ConditionalParameter;
import java.util.*;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.Size;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class GrowthRate implements Measurement, MultiThreaded, Hint {
    protected ObjectClassParameter structure = new ObjectClassParameter("Object Class", -1, false, false).setEmphasized(true).setHint("Select object class corresponding to bacteria");
    protected PluginParameter<GeometricalFeature> feature = new PluginParameter<>("Feature", GeometricalFeature.class, new Size(), false).setHint("Geometrical Feature of object used to estimate the size of a bacterium in order to compute the Growth Rate");
    protected BooleanParameter wholeCycle = new BooleanParameter("Whole Cycle", true).setHint("If true, growth rate is computed on the whole cycle, otherwise on a sliding window with the cycle");
    protected BoundedNumberParameter slidingWindow = new BoundedNumberParameter("Sliding Window", 0, 3, 1, null).setHint("Size of sliding window W: for each cycle in frames [F, F+N] growth rate will be computed at each frame f € [F+W, F+N-W] within the sliding window [f-W, f+W]");
    protected BooleanParameter fillEdges = new BooleanParameter("Fill Edges", true).setHint("If true, for each cycle, NA values at start and end of cycle are replaced by the nearest non-NA value");
    protected BoundedNumberParameter minCells = new BoundedNumberParameter("Minimum cell number", 0, 3, 2, null).setHint("Set here the minimum number of cell per generation to compute growth rate. NA is returned for generations with fewer cells than the value of this parameter");
    protected BooleanParameter saveSizeAtDiv = new BooleanParameter("Save Size at Birth", false).setHint("Whether the estimated size at birth should be saved or not");

    protected ConditionalParameter<Boolean> wholeCycleCond = new ConditionalParameter<>(wholeCycle)
            .setActionParameters(false, slidingWindow);

    protected TextParameter suffix = new TextParameter("Suffix", "", false).setHint("Suffix added to measurement name (column name in the extracted table)");
    protected BooleanParameter saveFeature = new BooleanParameter("Save Feature", false);
    protected TextParameter featureKey = new TextParameter("Feature Name", "", false).addValidationFunction((t)->t.getValue().length()>0).setHint("Name given to geometrical feature in measurements");
    protected ConditionalParameter<Boolean> saveFeatureCond = new ConditionalParameter<>(saveFeature).setActionParameters(true, featureKey).setHint("Whether value of geometrical feature (defined in the <em>Feature</em> parameter) should be saved to measurements");
    protected BooleanParameter saveResiduals = new BooleanParameter("Save Residuals", false);
    BooleanParameter filterCellsBool = new BooleanParameter("Filter Out Cells", false);
    FeatureFilter filter = new FeatureFilter(new EdgeContact().set(false, false, false, true, false, false), 2, false, false);
    ConditionalParameter<Boolean> filterCellsCond = new ConditionalParameter<>(filterCellsBool).setActionParameters(true, filter.getParameters());


    protected Parameter[] parameters = new Parameter[]{structure, feature, wholeCycleCond, minCells, saveSizeAtDiv, saveResiduals, saveFeatureCond, suffix, filterCellsCond};
    
    public GrowthRate() {
        feature.addListener( p -> {
            if (!suffix.getValue().isEmpty() && !featureKey.getValue().isEmpty()) return;
            String n = feature.instantiatePlugin().getDefaultName();
            if (suffix.getValue().isEmpty()) suffix.setValue(n);
            if (featureKey.getValue().isEmpty()) featureKey.setValue(n);
        });
    }
    
    public GrowthRate(int structureIdx) {
        this();
        structure.setSelectedIndex(structureIdx);
    }
    
    public GrowthRate setSuffix(String suffix) {
        this.suffix.setValue(suffix);
        return this;
    }
    
    public GrowthRate saveSizeAtBirth(boolean save) {
        saveSizeAtDiv.setSelected(save);
        return this;
    }

    public GrowthRate setFeature(GeometricalFeature f, boolean saveFeature) {
        this.feature.setPlugin(f);
        this.suffix.setValue(f.getDefaultName());
        this.featureKey.setValue(f.getDefaultName());
        this.saveFeature.setSelected(saveFeature);
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
    public void performMeasurement(SegmentedObject parentTrackHead) {
        int bIdx = structure.getSelectedIndex();
        String featKey = this.featureKey.getValue();
        boolean saveSizeDiv = saveSizeAtDiv.getSelected();
        boolean feat = saveFeature.getSelected();
        Map<Image, IntensityMeasurementCore> cores = new ConcurrentHashMap<>();
        HashMapGetCreate<SegmentedObject, ObjectFeature> ofMap = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(p -> {
            ObjectFeature of = feature.instantiatePlugin().setUp(p, bIdx, p.getChildRegionPopulation(bIdx));
            if (of instanceof ObjectFeatureWithCore) ((ObjectFeatureWithCore)of).setUpOrAddCore(cores, null);
            return of;
        });
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Utils.parallel(parentTrack.stream(), this.parallel).forEach(ofMap::getAndCreateIfNecessary);
        Map<SegmentedObject, List<SegmentedObject>> parentMapRemovedCells;
        if (filterCellsBool.getSelected()) {
            parentMapRemovedCells = Utils.parallel(parentTrack.stream(), this.parallel).collect(Collectors.toMap(p->p, p -> {
                RegionPopulation pop = p.getChildRegionPopulation(bIdx);
                pop = filter.runPostFilter(p, bIdx, pop);
                Set<Region> remaining = new HashSet<>(pop.getRegions());
                return p.getChildren(bIdx).filter(o -> !remaining.contains(o.getRegion())).collect(Collectors.toList());
            }));
        } else parentMapRemovedCells = null;
        long t1 = System.currentTimeMillis();
        logger.trace("Growth Rate: computing values... ({}) for : {}", featKey, parentTrackHead);
        Map<SegmentedObject, Double> logLengthMap = Utils.parallel(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), bIdx), true).collect(Collectors.toMap(b->b, b->Math.log(ofMap.get(b.getParent()).performMeasurement(b.getRegion()))));
        long t2 = System.currentTimeMillis();
        Map<SegmentedObject, List<SegmentedObject>> bacteriaTracks = SegmentedObjectUtils.getAllTracks(parentTrack, bIdx);
        long t3 = System.currentTimeMillis();
        boolean wholeCycle = this.wholeCycle.getSelected();
        int minCells = this.minCells.getValue().intValue();
        String key = key();
        String sizeAtBirthKey = sizeAtBirthKey();
        boolean saveResiduals = this.saveResiduals.getSelected();
        String residualKey = residualKey();
        int w = slidingWindow.getIntValue();
        Consumer<SegmentedObject> setNullMeasurements = b -> {
            b.getMeasurements().setValue(key, null );  // erase values
            if (saveSizeDiv) b.getMeasurements().setValue(sizeAtBirthKey, null );  // erase values
            if (saveResiduals) b.getMeasurements().setValue(residualKey, null );  // erase values
            if (feat) b.getMeasurements().setValue(featKey, Math.exp(logLengthMap.get(b)));
        };
        Utils.parallel(bacteriaTracks.values().stream(), this.parallel).forEach(l-> {
            List<SegmentedObject> removed;
            if (parentMapRemovedCells != null) {
                removed = new ArrayList<>(l.size());
                l.removeIf(b -> {
                    if (parentMapRemovedCells.get(b.getParent()).contains(b)) {
                        removed.add(b);
                        return true;
                    } else return false;
                });
            } else removed = null;
            if (l.size()>=minCells) {
                double[] frame = new double[l.size()];
                double[] length = new double[frame.length];
                int idx = 0;
                for (SegmentedObject b : l) {
                    frame[idx] = b.getCalibratedTimePoint() - frame[0]; // so that beta represents the estimation of the size at birth
                    length[idx++] = logLengthMap.get(b);
                }
                frame[0] = 0;
                if (wholeCycle || l.size()<=w*2+1) {
                    double[] beta = LinearRegression.run(frame, length);
                    double[] residuals = saveResiduals ? LinearRegression.getResiduals(frame, length, beta) : null;
                    for (int i = 0; i<l.size(); ++i) {
                        SegmentedObject b = l.get(i);
                        b.getMeasurements().setValue(key, beta[1]);
                        if (saveSizeDiv) b.getMeasurements().setValue(sizeAtBirthKey, Math.exp(beta[0]));
                        if (feat) b.getMeasurements().setValue(featKey, Math.exp(logLengthMap.get(b)));
                        if (saveResiduals) b.getMeasurements().setValue(residualKey, residuals[i]);
                    }
                } else {
                    Map<Integer, Double> sizeAtBirth = saveSizeDiv ? null : new HashMap<>(l.size());
                    for (int i = 0; i<l.size(); ++i) {
                        SegmentedObject b = l.get(i);
                        if (feat) b.getMeasurements().setValue(featKey, Math.exp(logLengthMap.get(b)));
                        if (i<w || i>=l.size()-w) {
                            if (!fillEdges.getSelected()) {
                                b.getMeasurements().setValue(key, null);
                                if (saveResiduals) b.getMeasurements().setValue(residualKey, null);
                            }
                        } else {
                            double[] frameSub = ArrayUtil.subset(frame, i-w, i+w+1);
                            double[] lengthSub = ArrayUtil.subset(length, i-w, i+w+1);
                            double[] beta = LinearRegression.run(frameSub, lengthSub);
                            b.getMeasurements().setValue(key, beta[1]);
                            if (saveSizeDiv) b.getMeasurements().setValue(sizeAtBirthKey, Math.exp(beta[0]));
                            else sizeAtBirth.put(i, beta[0]);
                            if (saveResiduals) b.getMeasurements().setValue(residualKey, LinearRegression.getResidual(frame[i], length[i], beta));
                        }
                    }
                    if (fillEdges.getSelected()) {
                        for (int i = 0; i<w; ++i) {
                            l.get(i).getMeasurements().setValue(key, (Number)l.get(w).getMeasurements().getValue(key));
                            if (saveSizeDiv) l.get(i).getMeasurements().setValue(sizeAtBirthKey, (Number)l.get(w).getMeasurements().getValue(sizeAtBirthKey));
                            if (saveResiduals) {
                                double b0 = saveSizeDiv ? Math.log(((Number) l.get(w).getMeasurements().getValue(sizeAtBirthKey)).doubleValue()) : sizeAtBirth.get(w);
                                double b1 = ((Number) l.get(w).getMeasurements().getValue(key)).doubleValue();
                                l.get(i).getMeasurements().setValue(residualKey, LinearRegression.getResidual(frame[i], length[i], new double[]{b0, b1}));
                            }
                        }
                        for (int i = l.size()-w; i<l.size(); ++i) {
                            l.get(i).getMeasurements().setValue(key, (Number)l.get(l.size()-w-1).getMeasurements().getValue(key));
                            if (saveSizeDiv) l.get(i).getMeasurements().setValue(sizeAtBirthKey, (Number)l.get(l.size()-w-1).getMeasurements().getValue(sizeAtBirthKey));
                            if (saveResiduals) {
                                double b0 = saveSizeDiv ? Math.log(((Number) l.get(w).getMeasurements().getValue(sizeAtBirthKey)).doubleValue()) : sizeAtBirth.get(w);
                                double b1 = ((Number) l.get(w).getMeasurements().getValue(key)).doubleValue();
                                l.get(i).getMeasurements().setValue(residualKey, LinearRegression.getResidual(frame[i], length[i], new double[]{b0, b1}));
                            }
                        }
                    }
                }
            } else {
                for (SegmentedObject b : l) setNullMeasurements.accept(b);
            }
            if (removed != null) for (SegmentedObject b : removed) setNullMeasurements.accept(b);
        });
        long t4 = System.currentTimeMillis();
        logger.debug("Growth Rate: compute values: {}ms, process: {}ms", t2-t1, t4-t3);
    }
    
    @Override 
    public ArrayList<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>(3);
        res.add(new MeasurementKeyObject(key(), structure.getSelectedIndex()));
        if (saveSizeAtDiv.getSelected()) res.add(new MeasurementKeyObject(sizeAtBirthKey(), structure.getSelectedIndex()));
        if (saveFeature.getSelected()) res.add(new MeasurementKeyObject(featureKey.getValue(), structure.getSelectedIndex()));
        if (saveResiduals.getSelected()) res.add(new MeasurementKeyObject(residualKey(), structure.getSelectedIndex()));
        return res;
    }

    protected String key() {
        return "GrowthRate"+suffix.getValue();
    }
    protected String sizeAtBirthKey() {
        return "SizeAtBirth"+suffix.getValue();
    }
    protected String residualKey() {
        return "GrowthRateResidual"+suffix.getValue();
    }


    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }
    public static String getTrackHeadName(int trackHeadIdx) {
        //return String.valueOf(trackHeadIdx);
        int r = trackHeadIdx%24; // 24 : skip T & H 
        int mod = trackHeadIdx/24;
        if (r>=18) { // skip T
            trackHeadIdx+=2;
            if (r>=24) r = trackHeadIdx%24;
            else r+=2;
        } else if (r>=7) { // skip H
            trackHeadIdx+=1;
            r+=1;
        }
        
        char c = (char)(r + 65); //ASCII UPPER CASE +65
        
        if (mod>0) return String.valueOf(c)+mod;
        else return String.valueOf(c);
    }
    boolean parallel = true;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel=parallel;
    }

    @Override
    public String getHintText() {
        return "Computes the growth rate of bacteria per generation by fitting an exponential function on the estimated size of bacteria (as defined in the parameter <em>Feature</em>). <br />St = S0 * exp(µt) where µ is the growth rate, S0 is the size at birth, t is the time elapsed since birth, St is the size at time t. <br />Note that this modules uses calibrated time and not frame index. If input images do not contain the calibrated time for each frame, frame duration should be set in the <em>Time Step</em> parameter of the <em>Pre-processing</em> parameter of each position.";
    }
}
