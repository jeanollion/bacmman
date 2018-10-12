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
package bacmman.plugins.plugins.processing_pipeline;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import bacmman.plugins.Segmenter;
import bacmman.plugins.Hint;
import bacmman.utils.HashMapGetCreate;
import java.util.stream.Collectors;

import bacmman.plugins.TrackConfigurable;
import bacmman.plugins.TrackConfigurable.TrackConfigurer;
import bacmman.utils.MultipleException;

/**
 *
 * @author Jean Ollion
 */
public class SegmentOnly extends SegmentationProcessingPipeline<SegmentOnly> implements Hint {
    protected PluginParameter<Segmenter> segmenter = new PluginParameter<>("Segmentation algorithm", Segmenter.class, false);
    Parameter[] parameters = new Parameter[]{preFilters, trackPreFilters, segmenter, postFilters};
    
    public SegmentOnly() {}
    
    public SegmentOnly(Segmenter segmenter) {
        this.segmenter.setPlugin(segmenter);
    }
    protected SegmentOnly(PluginParameter<Segmenter> segmenter) {
        this.segmenter=segmenter;
    }
    @Override
    public String getHintText() {
        return "Performs only the segmentation (no tracking)";
    }
    
    @Override public void segmentAndTrack(final int structureIdx, final List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        getTrackPreFilters(true).filter(structureIdx, parentTrack); // set preFiltered images to structureObjects
        TrackConfigurer apply=TrackConfigurable.getTrackConfigurer(structureIdx, parentTrack, segmenter.instanciatePlugin());
        segmentAndTrack(structureIdx, parentTrack, apply, factory);
    }
    public void segmentAndTrack(final int structureIdx, final List<SegmentedObject> parentTrack, TrackConfigurer applyToSegmenter, SegmentedObjectFactory factory) {
        MultipleException me = new MultipleException();
        if (!segmenter.isOnePluginSet()) {
            logger.info("No segmenter set for structure: {}", structureIdx);
            return;
        }
        if (parentTrack.isEmpty()) return;
        int parentStructureIdx = parentTrack.get(0).getStructureIdx();
        int segParentStructureIdx = parentTrack.get(0).getExperimentStructure().getSegmentationParentObjectClassIdx(structureIdx);
        boolean subSegmentation = segParentStructureIdx>parentStructureIdx;
        boolean singleFrame = parentTrack.get(0).getExperimentStructure().singleFrame(parentTrack.get(0).getPositionName(), structureIdx); // will segment only on first frame
        
        // segment in direct parents
        List<SegmentedObject> allParents = singleFrame ? SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream().limit(1), segParentStructureIdx).collect(Collectors.toList()) : SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), segParentStructureIdx).collect(Collectors.toList());
        Collections.shuffle(allParents); // reduce thread blocking // TODO TEST NOW WITH STREAM
        final boolean ref2D= !allParents.isEmpty() && allParents.get(0).getRegion().is2D() && parentTrack.get(0).getRawImage(structureIdx).sizeZ()>1;
        long t0 = System.currentTimeMillis();
        long t1 = System.currentTimeMillis();
        long t2 = System.currentTimeMillis();
        List<RegionPopulation> pops = allParents.stream().parallel().map(subParent -> {
            SegmentedObject globalParent = subParent.getParent(parentStructureIdx);
            Segmenter seg = segmenter.instanciatePlugin();
            if (applyToSegmenter!=null) applyToSegmenter.apply(globalParent, seg);
            Image input = globalParent.getPreFilteredImage(structureIdx);
            if (subSegmentation) input = input.cropWithOffset(ref2D?new MutableBoundingBox(subParent.getBounds()).copyZ(input):subParent.getBounds());
            RegionPopulation pop = seg.runSegmenter(input, structureIdx, subParent);
            pop = postFilters.filter(pop, structureIdx, subParent);
            if (subSegmentation && pop!=null) pop.translate(subParent.getBounds(), true);
            return pop;
        }).collect(Collectors.toList()); 
        
        long t3 = System.currentTimeMillis();
        if (subSegmentation) { // collect if necessary and set to parent
            HashMapGetCreate<SegmentedObject, List<Region>> parentObjectMap = new HashMapGetCreate<>(parentTrack.size(), new HashMapGetCreate.ListFactory());
            //HashMap<RegionPopulation, StructureObject> popParentMap = new HashMap<>(pops.size());
            //for (int i = 0; i<pops.length; ++i) popParentMap.put(pops[i], allParents.get(i));
            //Arrays.sort(pops, (p1, p2)->popParentMap.get(p1).compareTo(popParentMap.get(p2)));
            for (int i = 0; i<pops.size(); ++i) {
                //StructureObject subParent = popParentMap.get(pops[i]);
                SegmentedObject subParent = allParents.get(i);
                SegmentedObject parent = subParent.getParent(parentStructureIdx);
                if (pops.get(i)!=null) {
                    List<Region> objects =  parentObjectMap.getAndCreateIfNecessary(parent);
                    int label = objects.size();
                    if (label>0) for (Region o : pops.get(i).getRegions()) o.setLabel(label++);
                    objects.addAll(pops.get(i).getRegions());
                }
                else logger.debug("pop null for subParent: {}", allParents.get(i));
            }
            RegionPopulation pop=null;
            for (Entry<SegmentedObject, List<Region>> e : parentObjectMap.entrySet()) {
                pop = new RegionPopulation(e.getValue(), e.getKey().getRawImage(structureIdx));
                factory.setChildObjects(e.getKey(), pop);
            }
            if (singleFrame) {
                if (parentObjectMap.size()>1) logger.error("Segmentation of structure: {} from track: {}, single frame but several populations", structureIdx, parentTrack.get(0));
                else {
                    for (SegmentedObject parent : parentTrack.subList(1, parentTrack.size())) factory.setChildObjects(parent, pop!=null ? pop.duplicate() : null);
                }
            } else { // also set no children to remove children already present and avoid access to dao
                Collection<SegmentedObject> parentsWithNoChildren = new HashSet<>(parentTrack);
                parentsWithNoChildren.removeAll(parentObjectMap.keySet());
                for (SegmentedObject p : parentsWithNoChildren)  factory.setChildObjects(p, null);
            }
        } else {
           for (int i = 0; i<pops.size(); ++i) factory.setChildObjects(allParents.get(i), pops.get(i));
           if (singleFrame) {
               if (pops.size()>1) logger.error("Segmentation of structure: {} from track: {}, single frame but several populations", structureIdx, parentTrack.get(0));
               else for (SegmentedObject parent : parentTrack.subList(1, parentTrack.size())) factory.setChildObjects(parent, pops.get(0)!=null ? pops.get(0).duplicate(): null);
           }
        }
        long t4 = System.currentTimeMillis();
        logger.debug("SegmentOnly: {}(trackLength: {}) total time: {}, load images: {}ms, compute maps: {}ms, process: {}ms, set to parents: {}", parentTrack.get(0), parentTrack.size(), t4-t0, t1-t0, t2-t1, t3-t2, t4-t3);
        if (!me.isEmpty()) throw me;
    }
    
    @Override public void trackOnly(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {return;}

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    @Override public Segmenter getSegmenter() {return segmenter.instanciatePlugin();}
}
