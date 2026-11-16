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
package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.SpotQuality;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Computes the quality of spots with the same rules as {@link bacmman.plugins.plugins.trackers.NestedSpotTracker} (see {@link SpotQuality}), optionally stores it, and removes spots of low quality
 */
public class SpotQualityFilter implements PostFilter, Hint {
    final SpotQuality spotQuality = new SpotQuality();
    NumberParameter minQuality = new NumberParameter<>("Min. Quality", 3, 0).setEmphasized(true)
            .setHint("Spots with a quality below this value are removed. Lower it to keep fainter spots, raise it to remove more false detections.<br />The quality depends on the <em>Quality Formula</em> and on the <em>Intensity normalization</em>");
    BooleanParameter storeQuality = new BooleanParameter("Store Quality", true)
            .setHint("If set, the computed quality replaces the quality of the spots (e.g. given by the segmenter): it is used by the <em>Quality</em> measurement and by trackers that use the quality (e.g. <em>NestedSpotTracker</em>), and it is also stored as the attribute <em>Quality</em> of the objects. Otherwise the quality of the spots is not modified and the computed quality is only used to remove spots");
    ParentObjectClassParameter normalizationClass = new ParentObjectClassParameter("Normalization Object Class", -1, -1, true, false)
            .setHint("Objects (e.g. bacteria) within which the intensity is normalized: the background MEAN and SD (see <em>Intensity normalization</em>) are measured within the object of this class that contains the spot (largest overlap).<br />If no class is selected, the segmentation parent of the spots is used (e.g. bacteria), as in <em>SpotSegmenter</em>. If no object of this class overlaps the spot, the intensity is normalized within the parent object given to the post-filter (e.g. microchannel)");

    @Override
    public String getHintText() {
        return "Computes the quality of spots and removes spots of low quality (see <em>Min. Quality</em>). The quality is computed with the same rules as the spot quality computed by <em>NestedSpotTracker</em> (and as in <em>SpotSegmenter</em>): value of the gaussian and / or laplacian transform of the intensity at the center of the spot (or median over the spot), after normalization of the intensity within the cell that contains the spot. This allows using the same quality whatever the segmenter (e.g. segmenters that do not compute a quality, or whose quality is not suited), and optionally storing it (see <em>Store Quality</em>)." +
                "<br />Technical: the gaussian and laplacian transforms are computed on the pre-filtered image (raw image if not available) of the parent of the spots (e.g. microchannel), on the area of the parent object given to the post-filter with a border, so that the values do not depend on the border of the parent object. When used as a track post-filter (e.g. after tracking) pre-filtered images are usually not available: the raw image is used, so the quality can differ from the one computed during segmentation if there are pre-filters";
    }

    public SpotQualityFilter setMinQuality(double minQuality) {
        this.minQuality.setValue(minQuality);
        return this;
    }

    public SpotQualityFilter setStoreQuality(boolean store) {
        this.storeQuality.setSelected(store);
        return this;
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        List<Region> regions = childPopulation.getRegions();
        if (regions.isEmpty()) return childPopulation;
        ExperimentStructure xs = parent.getExperimentStructure();
        // image: pre-filtered image of the parent of the spots (e.g. microchannel), to avoid border effects when the post-filter is called on the segmentation parent (e.g. bacteria)
        int parentOC = xs.getParentObjectClassIdx(childStructureIdx);
        SegmentedObject imageParent = parent.getStructureIdx() != parentOC && parentOC>=0 && xs.isChildOf(parentOC, parent.getStructureIdx()) ? parent.getParent(parentOC) : parent;
        Image source = imageParent.getPreFilteredImage(childStructureIdx);
        // regions in absolute landmark
        List<Region> absRegions = regions.stream().map(r -> r.isAbsoluteLandMark() ? r : r.duplicate().translate(parent.getBounds()).setIsAbsoluteLandmark(true)).collect(Collectors.toList());
        // area on which transforms are computed: parent and spots with a border, within the image
        MutableBoundingBox bounds = new MutableBoundingBox(parent.getBounds());
        absRegions.forEach(r -> bounds.union(r.getBounds()));
        bounds.addBorder(spotQuality.getTransformBorder(), false).setzMin(source.zMin()).setzMax(source.zMax());
        bounds.contract(source.getBoundingBox());
        // normalization object
        Function<Region, SegmentedObject> normalizationObject = getNormalizationObject(parent, childStructureIdx, xs);
        double[] quality = spotQuality.computeQuality(source, bounds, absRegions, normalizationObject, parent);
        Map<Region, Double> regionQuality = new IdentityHashMap<>(regions.size());
        for (int i = 0; i<regions.size(); ++i) regionQuality.put(regions.get(i), quality[i]);
        if (storeQuality.getSelected()) {
            // objects already exist when used as a track post-filter: also set the attribute (otherwise, it is set when the objects are created from the regions)
            Map<Region, SegmentedObject> regionObject = new IdentityHashMap<>();
            if (xs.isDirectChildOf(parent.getStructureIdx(), childStructureIdx)) {
                Stream<SegmentedObject> children = parent.getChildren(childStructureIdx);
                if (children != null) children.forEach(o -> regionObject.put(o.getRegion(), o));
            }
            for (int i = 0; i<regions.size(); ++i) {
                Region r = regions.get(i);
                r.setQuality(quality[i]);
                SegmentedObject o = regionObject.get(r);
                if (o != null) o.setAttribute("Quality", quality[i]);
            }
        }
        double minQuality = this.minQuality.getDoubleValue();
        return childPopulation.filter(r -> !(regionQuality.get(r) < minQuality)); // NaN quality: kept
    }

    /**
     * @return function that returns the object within which the intensity of a spot (absolute landmark) is normalized, or null if none
     */
    private Function<Region, SegmentedObject> getNormalizationObject(SegmentedObject parent, int childStructureIdx, ExperimentStructure xs) {
        int normOC = normalizationClass.getSelectedClassIdx();
        if (normOC < 0) normOC = xs.getSegmentationParentObjectClassIdx(childStructureIdx); // as in SpotSegmenter. Note: post-filters are called on the parent, not the segmentation parent
        if (normOC < 0 || normOC == parent.getStructureIdx()) return r -> parent;
        if (xs.isChildOf(normOC, parent.getStructureIdx())) { // parent is contained in an object of the normalization class
            SegmentedObject container = parent.getParent(normOC);
            return r -> container;
        }
        Stream<SegmentedObject> candidatesS = parent.getChildren(normOC);
        List<SegmentedObject> candidates = candidatesS == null ? Collections.emptyList() : candidatesS.collect(Collectors.toList());
        return r -> {
            SegmentedObject best = null;
            double bestOverlap = 0;
            for (SegmentedObject c : candidates) {
                double o = r.getOverlapArea(c.getRegion());
                if (o > bestOverlap) {
                    bestOverlap = o;
                    best = c;
                }
            }
            return best;
        };
    }

    final Parameter[] parameters = Stream.concat(Stream.concat(Stream.of(minQuality, storeQuality), Arrays.stream(spotQuality.getQualityParameters())), Stream.of(normalizationClass)).toArray(Parameter[]::new);

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
