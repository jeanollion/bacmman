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
package bacmman.configuration.experiment;

import bacmman.configuration.parameters.*;
import bacmman.plugins.plugins.processing_pipeline.Duplicate;

import javax.swing.tree.MutableTreeNode;
import org.json.simple.JSONObject;
import bacmman.plugins.ManualSegmenter;
import bacmman.plugins.ObjectSplitter;
import bacmman.plugins.Segmenter;
import bacmman.plugins.ProcessingPipeline;

import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */

public class Structure extends ContainerParameterImpl<Structure> {
    ParentObjectClassParameter parentStructure =  new ParentObjectClassParameter("Parent Object Class", -1, -1).setHint("In the processing step: pre-filters, segmentation, tracking and post-filters will be run from each tracks of this object class");
    ParentObjectClassParameter segmentationParent =  new ParentObjectClassParameter("Segmentation Parent", -1, -1).setHint("If different from parent structure, allows to perform segmentation from objects of another structure contained in the object of the parent structure. Pre-filters, tracking and post-filters will still be run from the track of the parent structure.");
    ChannelImageParameter channelImage = new ChannelImageParameter("Channel Image", -1).setHint("Channel on which processing pipeline will be applied");
    PluginParameter<ObjectSplitter> objectSplitter = new PluginParameter<>("Object Splitter", ObjectSplitter.class, true).setEmphasized(false).setHint("Split algorithm used in split command in manual edition. <br />If no algorith is defined here and segmenter is able to split objects, segmenter will be used instead");
    PluginParameter<ManualSegmenter> manualSegmenter = new PluginParameter<>("Manual Segmenter", ManualSegmenter.class, true).setEmphasized(false).setHint("Segmentation algorithm used in create object command in manual edition<br />If no algorith is defined here and segmenter is able to segment objects from user-defined points, segmenter will be used instead");
    PluginParameter<ProcessingPipeline> processingPipeline = new PluginParameter<>("Processing Pipeline", ProcessingPipeline.class, true).setEmphasized(false);
    BooleanParameter allowSplit = new BooleanParameter("Allow Split", "yes", "no", false).setHint("Whether tracks can divide in several tracks");
    BooleanParameter allowMerge = new BooleanParameter("Allow Merge", "yes", "no", false).setHint("Whether several tracks can merge in one single track");
    
    @Override
    public JSONObject toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("name", name);
        res.put("parentStructure", parentStructure.toJSONEntry());
        res.put("segmentationParent", segmentationParent.toJSONEntry());
        res.put("channelImage", channelImage.toJSONEntry());
        res.put("objectSplitter", objectSplitter.toJSONEntry());
        res.put("manualSegmenter", manualSegmenter.toJSONEntry());
        res.put("processingScheme", processingPipeline.toJSONEntry());
        //res.put("brightObject", brightObject.toJSONEntry());
        res.put("allowSplit", allowSplit.toJSONEntry());
        res.put("allowMerge", allowMerge.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        name = (String)jsonO.get("name");
        parentStructure.initFromJSONEntry(jsonO.get("parentStructure"));
        segmentationParent.initFromJSONEntry(jsonO.get("segmentationParent"));
        channelImage.initFromJSONEntry(jsonO.get("channelImage"));
        objectSplitter.initFromJSONEntry(jsonO.get("objectSplitter"));
        manualSegmenter.initFromJSONEntry(jsonO.get("manualSegmenter"));
        processingPipeline.initFromJSONEntry(jsonO.get("processingScheme"));
        //brightObject.initFromJSONEntry(jsonO.get("brightObject"));
        allowSplit.initFromJSONEntry(jsonO.get("allowSplit"));
        allowMerge.initFromJSONEntry(jsonO.get("allowMerge"));
    }
    
    public Structure(String name, int parentStructure, int channelImage) {
        this(name, parentStructure, parentStructure, channelImage);
    }
    public Structure(String name, int parentStructure, int segmentationParentStructure, int channelImage) {
        super(name);
        this.parentStructure.setSelectedIndex(parentStructure);
        this.segmentationParent.setSelectedIndex(segmentationParentStructure);
        this.channelImage.setSelectedIndex(channelImage);
        this.parentStructure.addListener((ParentObjectClassParameter source) -> {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, source, false);
            logger.debug("parent modified for structure {}", s.getName());
            s.setMaxStructureIdx();
            int parentIdx = s.parentStructure.getSelectedIndex();
            s.setParentStructure(parentIdx);
            if (parameterChangeCallBack!=null) parameterChangeCallBack.accept(s.segmentationParent);
        });
        segmentationParent.addListener((ParentObjectClassParameter source) -> {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, source, false);
            logger.debug("segmentation parent modified for structure {}", s.getName());
            s.setMaxStructureIdx();
            s.setSegmentationParentStructure(s.segmentationParent.getSelectedIndex());
            if (parameterChangeCallBack!=null) parameterChangeCallBack.accept(s.segmentationParent);
        });
        processingPipeline.addListener((PluginParameter<ProcessingPipeline> source) -> {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, source, false);
            s.setMaxStructureIdx();
        });
        initChildList();
    }
    // to update display
    Consumer<Parameter> parameterChangeCallBack;
    public void setParameterChangeCallBack(Consumer<Parameter> parameterChangeCallBack) {
        this.parameterChangeCallBack=parameterChangeCallBack;
    }
    public Structure() {
        this("");
    }
    public Structure(String name) {
        this(name, -1, -1);
    }
    @Override 
    public boolean isEmphasized() {
        return false;
    }
    @Override
    protected void initChildList() {
        initChildren(parentStructure, segmentationParent, channelImage, processingPipeline, objectSplitter, manualSegmenter, allowMerge, allowSplit); //brightObject 
    }
    public boolean allowSplit() {
        return allowSplit.getSelected();
    }
    
    public boolean allowMerge() {
        return allowMerge.getSelected();
    }
    
    public Structure setAllowSplit(boolean allowSplit) {
        this.allowSplit.setSelected(allowSplit);
        return this;
    }
    
    public Structure setAllowMerge(boolean allowMerge) {
        this.allowMerge.setSelected(allowMerge);
        return this;
    }
    
    public ProcessingPipeline getProcessingScheme() {
        return processingPipeline.instanciatePlugin();
    }
    
    public void setProcessingPipeline(ProcessingPipeline ps) {
        this.processingPipeline.setPlugin(ps);
    }
    
    public ObjectSplitter getObjectSplitter() {
        ObjectSplitter res = objectSplitter.instanciatePlugin();
        if (res == null) {
            ProcessingPipeline ps = this.processingPipeline.instanciatePlugin();
            if (ps!=null) {
                Segmenter s = ps.getSegmenter();
                if (s instanceof ObjectSplitter) return (ObjectSplitter)s;
            }
        }
        return res;
    }
    
    public ManualSegmenter getManualSegmenter() {
        ManualSegmenter res= manualSegmenter.instanciatePlugin();
        if (res == null) {
            ProcessingPipeline ps = this.processingPipeline.instanciatePlugin();
            if (ps!=null) {
                Segmenter s = ps.getSegmenter();
                if (s instanceof ManualSegmenter) return (ManualSegmenter)s;
            }
        }
        return res;
    }
    
    public void setManualSegmenter(ManualSegmenter manualSegmenter) {
        this.manualSegmenter.setPlugin(manualSegmenter);
    }
    
    public void setObjectSplitter(ObjectSplitter objectSplitter) {
        this.objectSplitter.setPlugin(objectSplitter);
    }
    
    public int getParentStructure() {
        return parentStructure.getSelectedIndex();
    }
    
    public int getSegmentationParentStructure() {
        return segmentationParent.getSelectedIndex()<parentStructure.getSelectedIndex() ? parentStructure.getSelectedIndex() : segmentationParent.getSelectedIndex();
    }
    
    public void setParentStructure(int parentIdx) {
        if (parentStructure.getSelectedIndex()!=parentIdx) parentStructure.setSelectedIndex(parentIdx); // avoid loop with listeners
        segmentationParent.setMaxStructureIdx(parentIdx+1);
        int segParent = segmentationParent.getSelectedIndex();
        if (segParent<parentIdx) segmentationParent.setSelectedIndex(parentIdx);
    }
    public void setSegmentationParentStructure(int segmentationParentStructureIdx) {
        if (segmentationParentStructureIdx<parentStructure.getSelectedClassIdx()) segmentationParentStructureIdx = parentStructure.getSelectedClassIdx();
        if (segmentationParentStructureIdx != segmentationParent.getSelectedClassIdx()) segmentationParent.setSelectedIndex(segmentationParentStructureIdx);
    }
    
    
    public int getChannelImage() {
        return channelImage.getSelectedIndex();
    }
    
    public int getIndex() {
        return getParent().getIndex(this);
    }
    
    @Override 
    public void setParent(MutableTreeNode newParent) {
        super.setParent(newParent);
        setMaxStructureIdx();
    }
    
    protected void setMaxStructureIdx() {
        if (parent ==null) return;
        int idx = getIndex();
        parentStructure.setMaxStructureIdx(idx);
        segmentationParent.setMaxStructureIdx(idx);
        if (processingPipeline.isOnePluginSet() && processingPipeline.instanciatePlugin() instanceof Duplicate) {
            processingPipeline.getParameters().stream().filter(p->p instanceof ParentObjectClassParameter).map(p->(ParentObjectClassParameter)p).forEach(p->p.setMaxStructureIdx(idx));
        }
    }

}
