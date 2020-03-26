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
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.plugins.processing_pipeline.Duplicate;

import javax.swing.tree.MutableTreeNode;

import bacmman.utils.HashMapGetCreate;
import org.json.simple.JSONObject;
import bacmman.plugins.ManualSegmenter;
import bacmman.plugins.ObjectSplitter;
import bacmman.plugins.Segmenter;
import bacmman.plugins.ProcessingPipeline;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */

public class Structure extends ContainerParameterImpl<Structure> {
    static String parentTT = "Some object classes can be located within others on the images, for instance bacteria are inside microchannels and fluorescent spots are inside bacteria. Image processing takes this into account by segmenting and tracking a given object within an object of another class (called respectively <em>Segmentation Parent</em> and <em>Parent</em>). <br />Typically, the <em>Parent</em> class of Bacteria and Spots is Microchannels, the <em>Segmentation Parent</em> of Bacteria is Microchannels and the <em>Segmentation Parent</em> of Spots is Bacteria.";
    ParentObjectClassParameter parentStructure =  new ParentObjectClassParameter("Parent", -1, -1).setHint(parentTT);
    ParentObjectClassParameter segmentationParent =  new ParentObjectClassParameter("Segmentation Parent", -1, -1).setHint(parentTT);
    ChannelImageParameter channelImage = new ChannelImageParameter("Detection Channel", -1).setHint("Detection channel on which processing pipeline will be applied");
    PluginParameter<ObjectSplitter> objectSplitter = new PluginParameter<>("Object Splitter", ObjectSplitter.class, true).setEmphasized(false).setHint("Algorithm used to split segmented in manual edition. <br />If no algorithm is defined here and the segmenter is able to split objects, the segmenter will be used instead");
    PluginParameter<ManualSegmenter> manualSegmenter = new PluginParameter<>("Manual Segmenter", ManualSegmenter.class, true).setEmphasized(false).setHint("Algorithm used to segment object from user-defined points (<em>Create Objects</em> command) in manual edition<br />If no algorithm is defined here and the segmenter is able to segment objects from user-defined points, the segmenter will be used instead");
    PluginParameter<ProcessingPipeline> processingPipeline = new PluginParameter<>("Processing Pipeline", ProcessingPipeline.class, true).setEmphasized(false);
    BooleanParameter allowSplit = new BooleanParameter("Allow Split", "yes", "no", false).setHint("If <em>true</em> is set, a track can divide in several tracks");
    BooleanParameter allowMerge = new BooleanParameter("Allow Merge", "yes", "no", false).setHint("If <em>true</em> is set, several tracks can merge in one single track");
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Global Scaling", HistogramScaler.class, true).setHint("Define here a method to scale raw input images, using the histogram of all images of the parent structure in the same position");
    private Map<String, HistogramScaler> scalerP = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(p->scaler.instantiatePlugin());
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
        res.put("allowSplit", allowSplit.toJSONEntry());
        res.put("allowMerge", allowMerge.toJSONEntry());
        res.put("scaler", scaler.toJSONEntry());
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
        if (jsonO.containsKey("scaler")) scaler.initFromJSONEntry(jsonO.get("scaler"));
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
        initChildren(parentStructure, segmentationParent, channelImage, processingPipeline, scaler, objectSplitter, manualSegmenter, allowMerge, allowSplit); //brightObject
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

    public HistogramScaler getScalerForPosition(String position) {
        if (!scaler.isOnePluginSet()) return null;
        return scalerP.get(position);
    }
    public void ensureScalerConfiguration(String position) {
        if (scalerP.containsKey(position)) {
            Parameter[] scalerParams =  scalerP.get(position).getParameters();
            HistogramScaler hs = scaler.instantiatePlugin();
            if (hs==null) { // was removed during session
                scalerP.clear();
                return;
            }
            Parameter[] currentParams = hs.getParameters();
            if (!ParameterUtils.sameContent(scalerParams, currentParams)) {
                logger.debug("scaler configuration changed from: {} to {}", Arrays.deepToString(scalerParams), Arrays.deepToString(currentParams) );
                scalerP.remove(position); // configuration has changed -> reset scaler
            }
        }
    }

    public ProcessingPipeline getProcessingScheme() {
        return processingPipeline.instantiatePlugin();
    }
    
    public void setProcessingPipeline(ProcessingPipeline ps) {
        this.processingPipeline.setPlugin(ps);
    }
    
    public ObjectSplitter getObjectSplitter() {
        ObjectSplitter res = objectSplitter.instantiatePlugin();
        if (res == null) {
            ProcessingPipeline ps = this.processingPipeline.instantiatePlugin();
            if (ps!=null) {
                Segmenter s = ps.getSegmenter();
                if (s instanceof ObjectSplitter) return (ObjectSplitter)s;
            }
        }
        return res;
    }
    
    public ManualSegmenter getManualSegmenter() {
        ManualSegmenter res= manualSegmenter.instantiatePlugin();
        if (res == null) {
            ProcessingPipeline ps = this.processingPipeline.instantiatePlugin();
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
        if (processingPipeline.isOnePluginSet() && processingPipeline.instantiatePlugin() instanceof Duplicate) {
            processingPipeline.getParameters().stream().filter(p->p instanceof ParentObjectClassParameter).map(p->(ParentObjectClassParameter)p).forEach(p->p.setMaxStructureIdx(idx));
        }
    }
    public PluginParameter<ProcessingPipeline>  getProcessingPipelineParameter() {
        return processingPipeline;
    }
}
