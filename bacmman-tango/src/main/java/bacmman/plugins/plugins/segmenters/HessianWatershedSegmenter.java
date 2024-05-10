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
package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.Hint;
import bacmman.plugins.Segmenter;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.Thresholder;
import bacmman.plugins.plugins.thresholders.Percentile;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.processing.watershed.WatershedTransform;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class HessianWatershedSegmenter implements Segmenter, TestableProcessingPlugin, Hint {
    ScaleXYZParameter hessianScale = new ScaleXYZParameter("Hessian Scale", 2, 2, false).setEmphasized(true);
    ScaleXYZParameter localExtremaRadius = new ScaleXYZParameter("Local Extrema Radius", 2, 2, false).setEmphasized(true);

    @Override
    public String getHintText() {
        return "Algorithm for segmentation of large spot-like objects (2D/3D), with decreasing intensity from center to borders / with variable shapes. <br /> This methods performs a seeded watershed transform on the maximal eigenvalue of the hessian transform. Seeds are selected with a user defined threshold. Propagation is stopped util a user-defined threshold or a threshold computed automatically (see hint of <em>Propagation Stop Method</em> parameter";
    }

    enum FOREGROUND_SELECTION_METHOD {GLOBAL, LOCAL, THRESHOLD}
    EnumChoiceParameter<FOREGROUND_SELECTION_METHOD> foregroundSelMethod = new EnumChoiceParameter<>("Propagation Stop Method", FOREGROUND_SELECTION_METHOD.values(), FOREGROUND_SELECTION_METHOD.GLOBAL).setEmphasized(true)
            .setHint("Method to discriminate foreground from background:<ul><li>GLOBAL: a global propagation threshold is computed as the average intensity value in the areas where the max hessian eigenvalue sign changes around spots</li><li>LOCAL: idem as GLOBAL but the threshold is computed individually for each spot. This method is more sensitive to noise</li><li>THRESHOL: propagation is stopped at a user-defined threshold</li></ul>");
    PluginParameter<Thresholder> seedThreshlod = new PluginParameter<>("Seed Threshold", Thresholder.class, new Percentile().setPercentile(0.75),false).setEmphasized(true).setHint("Threshold computed on the watershed map to select seeds");
    PluginParameter<Thresholder> propagationThreshlod = new PluginParameter<>("Propagation Threshold", bacmman.plugins.Thresholder.class, new Percentile().setPercentile(99),false).setEmphasized(true).setHint("Threshold computed on the input image / or on the watershed map (depending on the parameter <em>Propagation End Map</em>) to stop propagation");
    BooleanParameter propagationThresholdOnInputImage = new BooleanParameter("Propagation End Map", "Input Image", "Watershed Map", true).setEmphasized(true);
    BooleanParameter monotonicPropagation = new BooleanParameter("Monotonic Propagation", false).setHint("If true, watershed propagation will be strictly increasing (on hessian values). This option can limit propagation, but is not suitable to objects where intensity do not decrease towards edges");
    ConditionalParameter<FOREGROUND_SELECTION_METHOD> foregroundSelMethodCond = new ConditionalParameter<>(foregroundSelMethod)
            .setActionParameters(FOREGROUND_SELECTION_METHOD.GLOBAL, seedThreshlod)
            .setActionParameters(FOREGROUND_SELECTION_METHOD.LOCAL, seedThreshlod)
            .setActionParameters(FOREGROUND_SELECTION_METHOD.THRESHOLD, seedThreshlod, propagationThreshlod, propagationThresholdOnInputImage);
    BooleanParameter mergeConnectedRegions = new BooleanParameter("Merge Neighboring Regions", false).setEmphasized(true).setHint("If true, regions in contact with each other will be merged depending on a criterion on the mean hessian value at the contact area between the two regions");
    NumberParameter mergeThreshold = new BoundedNumberParameter("Merge Threshold", 5, -1, 0, null).setEmphasized(true).setHint("Lower this value to reduce merging. Set this value according to the test image called <em>edge values</em>");
    BooleanParameter normalizeEdgeValues = new BooleanParameter("Normalize Edge Values", true).setEmphasized(true).setHint("If true, the mean hessian values is divided by the mean intensity value (influences the <em>Merge Threshold</em> parameter)");
    ConditionalParameter<Boolean> mergeCond = new ConditionalParameter<>(mergeConnectedRegions).setActionParameters(true, mergeThreshold, normalizeEdgeValues);
    boolean parallel; //TODO
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        // perform watershed on all local extrema
        Image watershedMap = ImageFeatures.getHessian(input, hessianScale.getScaleXY(), hessianScale.getScaleZ(input.getScaleXY(), input.getScaleZ()), false)[0];
        double radXY = localExtremaRadius.getScaleXY();
        double radZ = localExtremaRadius.getScaleZ(watershedMap.getScaleXY(), watershedMap.getScaleZ());
        ImageByte localExtrema = Filters.localExtrema(watershedMap, null, false, parent.getMask(), Filters.getNeighborhood(radXY, radZ, watershedMap), parallel);
        FOREGROUND_SELECTION_METHOD method = foregroundSelMethod.getSelectedEnum();
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(false);
        // erase seed depending on threshold
        double seedThld = seedThreshlod.instantiatePlugin().runThresholder(watershedMap, parent);
        BoundingBox.LoopPredicate lp = (x, y, z) -> watershedMap.getPixel(x, y, z)>seedThld;
        ImageMask.loop(localExtrema, (x, y, z)->localExtrema.setPixel(x, y, z, 0), lp);
        if (stores!=null) Core.userLog("WatershedSegmenter: seed threshold: "+seedThld);
        WatershedTransform.PropagationCriterion monoCrit = new WatershedTransform.MonotonalPropagation();
        WatershedTransform.PropagationCriterion propagationStop = null;
        List<Region> seeds = null;
        if (method.equals(FOREGROUND_SELECTION_METHOD.GLOBAL) || method.equals(FOREGROUND_SELECTION_METHOD.LOCAL)) {
            // first propagation with threshold on hessian map
            config.propagationCriterion(new WatershedTransform.ThresholdPropagation(watershedMap, 0, false));
            RegionPopulation popTemp = WatershedTransform.watershed(watershedMap, parent.getMask(), localExtrema, config);
            seeds = popTemp.getRegions();
            if (!popTemp.getRegions().isEmpty()) {
                Neighborhood n = Filters.getNeighborhood(1.5, 1, parent.getMask());
                ImageInteger labelMap = (ImageInteger) Filters.applyFilter(popTemp.getLabelMap(), null, new Filters.BinaryMaxLabelWise(), n, false);
                popTemp = new RegionPopulation(labelMap, true);
                // global threshold
                if (method.equals(FOREGROUND_SELECTION_METHOD.GLOBAL)) {
                    double propThld = popTemp.getRegions().stream().flatMap(r -> r.getContour().stream()).mapToDouble(v -> input.getPixel(v.x, v.y, v.z)).average().orElse(Double.NaN);
                    propagationStop = new WatershedTransform.ThresholdPropagation(input, propThld, true);
                    if (stores != null) Core.userLog("WatershedSegmenter: propagation Thld: " + propThld);
                } else {
                    // local threshold
                    Map<Integer, Double> thresholdMap = popTemp.getRegions().stream().collect(Collectors.toMap(Region::getLabel, r -> r.getContour().stream().mapToDouble(v -> input.getPixel(v.x, v.y, v.z)).average().orElse(Double.NaN)));
                    propagationStop = new WatershedTransform.LocalThresholdPropagation(input, thresholdMap, true);
                    if (stores != null) Core.userLog("WatershedSegmenter: propagation Thld: " + thresholdMap);
                }
            } else return new RegionPopulation(null, parent.getMaskProperties());
        } else if (method.equals(FOREGROUND_SELECTION_METHOD.THRESHOLD)) {
            // set propagation criterion
            Image propEndMap = propagationThresholdOnInputImage.getSelected() ? input : watershedMap;
            double propThld = propagationThreshlod.instantiatePlugin().runThresholder(propEndMap, parent);
            propagationStop = new WatershedTransform.ThresholdPropagation(propEndMap, propThld, propagationThresholdOnInputImage.getSelected());
            if (stores!=null) Core.userLog("WatershedSegmenter: propagation Thld: "+propThld);
        }
        assert propagationStop!=null : "propagation criterion is not defined";
        if (monotonicPropagation.getSelected()) config.propagationCriterion(monoCrit, propagationStop);
        else config.propagationCriterion(propagationStop);
        RegionPopulation pop = seeds ==null ? WatershedTransform.watershed(watershedMap, parent.getMask(), localExtrema, config) : WatershedTransform.watershed(watershedMap, parent.getMask(), seeds, config);

        if (mergeConnectedRegions.getSelected()) {
            SplitAndMergeEdge sm = new SplitAndMergeEdge(watershedMap, input, mergeThreshold.getValue().doubleValue(), normalizeEdgeValues.getSelected());
            if (stores!=null) stores.get(parent).addIntermediateImage("edge values", sm.drawInterfaceValues(pop));
            pop = sm.merge(pop, null);
        }

        if (stores!=null) {
            if (stores.get(parent).isExpertMode()) stores.get(parent).addIntermediateImage("local extrema", localExtrema);
            stores.get(parent).addIntermediateImage("watershed map", watershedMap);
        }
        return pop;
    }
    
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{hessianScale, localExtremaRadius, foregroundSelMethodCond, monotonicPropagation, mergeCond};
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }
}
