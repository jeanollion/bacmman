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
import bacmman.plugins.*;

import javax.swing.tree.MutableTreeNode;

import bacmman.plugins.plugins.processing_pipeline.ObjectClassOperation;
import bacmman.utils.HashMapGetCreate;
import org.json.simple.JSONObject;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */

public class Structure extends ContainerParameterImpl<Structure> implements ParameterChangeCallback<Structure> {
    static String parentTT = "Some object classes can be located within others on the images, for instance bacteria are inside microchannels and fluorescent spots are inside bacteria. Image processing takes this into account by segmenting and tracking a given object within an object of another class (called respectively <em>Segmentation Parent</em> and <em>Parent</em>). <br />Typically, the <em>Parent</em> class of Bacteria and Spots is Microchannels, the <em>Segmentation Parent</em> of Bacteria is Microchannels and the <em>Segmentation Parent</em> of Spots is Bacteria.";
    ParentObjectClassParameter parentStructure =  new ParentObjectClassParameter("Parent", -1, -1).setHint(parentTT);
    ParentObjectClassParameter segmentationParent =  new ParentObjectClassParameter("Segmentation Parent", -1, -1).setHint(parentTT);
    ChannelImageParameter channelImage = new ChannelImageParameter("Detection Channel", -1).setHint("Detection channel on which processing pipeline will be applied");
    PluginParameter<ObjectSplitter> objectSplitter = new PluginParameter<>("Object Splitter", ObjectSplitter.class, true).setEmphasized(false).setHint("Algorithm used to split segmented in manual edition. <br />If no algorithm is defined here and the segmenter is able to split objects, the segmenter will be used instead");
    PluginParameter<ManualSegmenter> manualSegmenter = new PluginParameter<>("Manual Segmenter", ManualSegmenter.class, true).setEmphasized(false).setHint("Algorithm used to segment object from user-defined points (<em>Create Objects</em> command) in manual edition<br />If no algorithm is defined here and the segmenter is able to segment objects from user-defined points, the segmenter will be used instead");
    PluginParameter<ProcessingPipeline> processingPipeline = new PluginParameter<>("Processing Pipeline", ProcessingPipeline.class, true).setEmphasized(false);
    PostFilterSequence manualPostFilters = new PostFilterSequence("Manual Post-Filters").setHint("Post-filter that can be applied on selected object by pressing ctrl + F");
    BooleanParameter allowSplit = new BooleanParameter("Allow Split", "yes", "no", false).setHint("If <em>true</em> is set, a track can divide in several tracks");
    BooleanParameter allowMerge = new BooleanParameter("Allow Merge", "yes", "no", false).setHint("If <em>true</em> is set, several tracks can merge in one single track");
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Global Scaling", HistogramScaler.class, true).setHint("Define here a method to scale raw input images, using the histogram of all images of the parent structure in the same position");
    private Map<String, HistogramScaler> scalerP = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(p->scaler.instantiatePlugin());
    public enum TRACK_DISPLAY {DEFAULT, CONTOUR}
    EnumChoiceParameter<TRACK_DISPLAY> trackDisplay = new EnumChoiceParameter<>("Track Display", TRACK_DISPLAY.values(), TRACK_DISPLAY.DEFAULT).setHint("In Kymograph mode, track are displayed as arrows by default. Choose contour to display them as coloured contours");
    public enum OBJECT_COLOR {
        MAGENTA(Color.MAGENTA), CYAN(Color.CYAN), ORANGE(Color.ORANGE), RED(Color.RED), GREEN(Color.GREEN), BLUE(Color.BLUE), YELLOW(Color.YELLOW), GREY(Color.GRAY), NONE(null);
        final Color c;
        OBJECT_COLOR(Color c) {
            this.c =c;
        }
    }
    EnumChoiceParameter<Structure.OBJECT_COLOR> color = new EnumChoiceParameter<>("Color", Structure.OBJECT_COLOR.values(), OBJECT_COLOR.MAGENTA).setAllowNoSelection(false).setHint("Display color");

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
        res.put("manualPostFilters", manualPostFilters.toJSONEntry());
        res.put("allowSplit", allowSplit.toJSONEntry());
        res.put("allowMerge", allowMerge.toJSONEntry());
        res.put("scaler", scaler.toJSONEntry());
        res.put("trackDisplay", trackDisplay.toJSONEntry());
        res.put("displayColor", color.toJSONEntry());
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
        if (jsonO.containsKey("manualPostFilters")) manualPostFilters.initFromJSONEntry(jsonO.get("manualPostFilters"));
        allowSplit.initFromJSONEntry(jsonO.get("allowSplit"));
        allowMerge.initFromJSONEntry(jsonO.get("allowMerge"));
        if (jsonO.containsKey("scaler")) scaler.initFromJSONEntry(jsonO.get("scaler"));
        if (jsonO.containsKey("trackDisplay")) trackDisplay.initFromJSONEntry(jsonO.get("trackDisplay"));
        if (jsonO.containsKey("displayColor")) color.initFromJSONEntry(jsonO.get("displayColor"));
        setParentStructure(parentStructure.getSelectedClassIdx()); // to initialize related parameters
    }
    
    public Structure(String name, int parentStructure, int channelImage) {
        this(name, parentStructure, parentStructure, channelImage);
    }
    public Structure(String name, int parentStructure, int segmentationParentStructure, int channelImage) {
        super(name);
        setParentStructure(parentStructure);
        this.segmentationParent.setSelectedIndex(segmentationParentStructure);
        this.channelImage.setSelectedIndex(channelImage);
        this.parentStructure.addListener((ParentObjectClassParameter source) -> {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, source, false);
            s.setMaxStructureIdx();
            int parentIdx = s.parentStructure.getSelectedIndex();
            s.setParentStructure(parentIdx);
            if (parameterChangeCallBack!=null) parameterChangeCallBack.forEach(cb -> cb.accept(s.segmentationParent));
        });
        segmentationParent.addListener((ParentObjectClassParameter source) -> {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, source, false);
            s.setMaxStructureIdx();
            s.setSegmentationParentStructure(s.segmentationParent.getSelectedIndex());
            if (parameterChangeCallBack!=null) parameterChangeCallBack.forEach(cb -> cb.accept(s.segmentationParent));
        });
        processingPipeline.addListener((PluginParameter<ProcessingPipeline> source) -> {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, source, false);
            s.setMaxStructureIdx();
        });
        initChildList();
    }
    // to update display
    List<Consumer<Parameter>> parameterChangeCallBack;
    @Override
    public Structure addParameterChangeCallback(Consumer<Parameter> parameterChangeCallBack) {
        if (this.parameterChangeCallBack == null) this.parameterChangeCallBack = new ArrayList<>();
        this.parameterChangeCallBack.add(parameterChangeCallBack);
        return this;
    }

    @Override
    public boolean removeParameterChangeCallback(Consumer<Parameter> parameterChangeCallBack) {
        if (this.parameterChangeCallBack ==null) return false;
        return this.parameterChangeCallBack.remove(parameterChangeCallBack);
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
        initChildren(parentStructure, segmentationParent, channelImage, processingPipeline, scaler, objectSplitter, manualSegmenter, manualPostFilters, allowMerge, allowSplit, trackDisplay, color); //brightObject
    }
    public boolean allowSplit() {
        return allowSplit.getSelected();
    }
    
    public boolean allowMerge() {
        return allowMerge.getSelected();
    }

    public TRACK_DISPLAY getTrackDisplay() {
        return trackDisplay.getSelectedEnum();
    }

    public Color getColor() {return color.getSelectedEnum().c;}

    public Structure setAutomaticColor() {
        if (getIndex() == -1 && getParent()==null) return this;
        int idx = (getIndex() == -1 ? getParent().getChildCount() : getIndex()) % (OBJECT_COLOR.values().length-1);
        if (idx>=0) color.setSelectedEnum(OBJECT_COLOR.values()[idx]);
        return this;
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
    public PostFilterSequence getManualPostFilters() {
        return manualPostFilters;
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
            if (ps!=null) return ps.getObjectSplitter();
        }
        return res;
    }
    
    public ManualSegmenter getManualSegmenter() {
        ManualSegmenter res= manualSegmenter.instantiatePlugin();
        if (res == null) {
            ProcessingPipeline ps = this.processingPipeline.instantiatePlugin();
            if (ps!=null) return ps.getManualSegmenter();
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
        if (parentStructure.getSelectedIndex()!=parentIdx) parentStructure.setSelectedIndex(parentIdx); // test to avoid loop with listeners
        int segParent = segmentationParent.getSelectedIndex();
        if (segParent<parentIdx) segmentationParent.setSelectedIndex(parentIdx);
        if (getProcessingPipelineParameter().isOnePluginSet() && getProcessingPipelineParameter().instantiatePlugin() instanceof ObjectClassOperation) {
            processingPipeline.getParameters().stream().filter(p->p instanceof SiblingObjectClassParameter).map(p->(SiblingObjectClassParameter)p).forEach(p->{
                p.setParentObjectClassIdx(parentIdx);
                if (!p.includeCurrent()) p.setMaxStructureIdx(getIndex());
            });
        }
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
        if (processingPipeline.isOnePluginSet() && processingPipeline.instantiatePlugin().objectClassOperations()) {
            processingPipeline.getParameters().stream().filter(p->p instanceof ParentObjectClassParameter).map(p->(ParentObjectClassParameter)p).forEach(p->p.setMaxStructureIdx(idx));
            processingPipeline.getParameters().stream().filter(p->p instanceof SiblingObjectClassParameter).map(p->(SiblingObjectClassParameter)p).filter(p->!p.includeCurrent()).forEach(p->p.setMaxStructureIdx(idx));
        }
    }
    public PluginParameter<ProcessingPipeline>  getProcessingPipelineParameter() {
        return processingPipeline;
    }
}
