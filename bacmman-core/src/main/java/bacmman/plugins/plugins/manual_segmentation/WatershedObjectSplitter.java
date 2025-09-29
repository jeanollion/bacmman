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
package bacmman.plugins.plugins.manual_segmentation;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.plugins.MultiThreaded;
import bacmman.processing.ImageLabeller;
import bacmman.image.ImageMask;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import bacmman.plugins.ObjectSplitter;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.processing.split_merge.SplitAndMergeEDM;
import bacmman.processing.watershed.WatershedTransform;

/**
 *
 * @author Jean Ollion
 */
public class WatershedObjectSplitter implements ObjectSplitter, MultiThreaded {
    NumberParameter smoothScale = new BoundedNumberParameter("Smooth Scale (0=no smooth)", 1, 2, 0, null);
    BooleanParameter decreasingPropagation = new BooleanParameter("Decreasing propagation", true).setHint("True if foreground has higher values than background").setEmphasized(true);
    Parameter[] parameters = new Parameter[]{smoothScale, decreasingPropagation};
    boolean splitVerbose;

    public WatershedObjectSplitter() {}
    public WatershedObjectSplitter(double smoothScale, boolean decreasingPropagation) {
        this.smoothScale.setValue(smoothScale);
        this.decreasingPropagation.setSelected(decreasingPropagation);
    }
    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }

    public void setSplitVerboseMode(boolean verbose) {
        this.splitVerbose=verbose;
    }
    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
        double sScale = smoothScale.getValue().doubleValue();
        if (sScale>0) input = ImageFeatures.gaussianSmooth(input, sScale, false);
        input = object.isAbsoluteLandMark() ? input.cropWithOffset(object.getBounds()) : input.crop(object.getBounds());
        RegionPopulation res= splitInTwoInterface(input, object.getMask(), decreasingPropagation.getSelected(), splitVerbose);
        if (res!=null) res.translate(object.getBounds(), object.isAbsoluteLandMark());
        return res;
    }
    // splits regions at the interface with the lowest median value in case decreasingPropagation is true (highest otherwise)
    public static RegionPopulation splitInTwoInterface(Image watershedMap, ImageMask mask, boolean decreasingPropagation, boolean verbose) {
        SplitAndMergeEDM sm = new SplitAndMergeEDM(watershedMap, watershedMap, 0, SplitAndMerge.INTERFACE_VALUE.MEDIAN, false, 1, 1, false).setSmallEdgesFirst(decreasingPropagation);
        sm.setMapsProperties(!decreasingPropagation, !decreasingPropagation);
        RegionPopulation split = sm.split(mask, 10);
        if (verbose) Core.showImage(sm.drawInterfaceValues(split));
        return sm.merge(split, sm.objectNumberLimitCondition(2));
    }

    // selects two highest seeds and perform watershed
    public static RegionPopulation splitInTwoSeedSelect(Image watershedMap, ImageMask mask, final boolean decreasingPropagation, boolean keepOnlyTwoSeeds, boolean verbose, boolean parallel) {
        ImageByte localMax = Filters.localExtrema(watershedMap, null, decreasingPropagation, mask, Filters.getNeighborhood(1, 1, watershedMap), parallel).setName("Split seeds");
        List<Region> seeds = Arrays.asList(ImageLabeller.labelImage(localMax));
        if (seeds.size()<2) {
            //logger.warn("Object splitter : less than 2 seeds found");
            //new IJImageDisplayer().showImage(smoothed.setName("smoothed"));
            //new IJImageDisplayer().showImage(localMax.setName("localMax"));
            return null;
        } else {
            if ((keepOnlyTwoSeeds && seeds.size()>2) || seeds.size()>4) { // keep half of the seeds... TODO find other algorithm to maximize distance? // si contrainte de taille, supprimer les seeds qui génère des objets trop petits
                for (Region o : seeds) {
                    for (Voxel v : o.getVoxels()) v.value = watershedMap.getPixel(v.x, v.y, v.z);
                }
                Comparator<Region> c = Comparator.comparingDouble(WatershedObjectSplitter::getMeanVoxelValue);
                Collections.sort(seeds, c);

                if (keepOnlyTwoSeeds) {
                    if (decreasingPropagation) seeds = seeds.subList(seeds.size()-2, seeds.size());
                    else seeds = seeds.subList(0, 2);
                } else {
                    if (decreasingPropagation) seeds = seeds.subList(seeds.size()/2, seeds.size());
                    else seeds = seeds.subList(0, seeds.size()/2);
                }
            }
            WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(decreasingPropagation).fusionCriterion(new WatershedTransform.NumberFusionCriterion(2));
            RegionPopulation pop =  WatershedTransform.watershed(watershedMap, mask, seeds, config);
            if (verbose) {
                Core.showImage(localMax);
                Core.showImage(watershedMap.setName("watershedMap"));
                Core.showImage(pop.getLabelMap());
            }
            return pop;
        }
    }
    public static double getMeanVoxelValue(Region r) {
        if (r.getVoxels().isEmpty()) return Double.NaN;
        else if (r.getVoxels().size()==1) return r.getVoxels().iterator().next().value;
        else {
            double sum = 0;
            for (Voxel v : r.getVoxels()) sum+=v.value;
            return sum/(double)r.getVoxels().size();
        }
    }
    
    
    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }
    
}
