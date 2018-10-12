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
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.data_structure.dao.ImageDAO;
import bacmman.data_structure.input_image.InputImage;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.SegmentedObject;

import static bacmman.data_structure.SegmentedObjectUtils.setTrackLinks;
import bacmman.image.BlankMask;

import java.util.ArrayList;
import java.util.List;

import org.json.simple.JSONObject;

import java.util.Map;
import java.util.function.Predicate;

/**
 *
 * @author Jean Ollion
 */
public class Position extends ContainerParameterImpl<Position> implements ListElementErasable {
    
    private MultipleImageContainer sourceImages;
    PreProcessingChain preProcessingChain=new PreProcessingChain("Pre-Processing");
    BoundedNumberParameter defaultTimePoint = new BoundedNumberParameter("Default TimePoint", 0, defaultTP, 0, null).setHint("Frame used by default by transformations that requires a single frame");
    InputImagesImpl inputImages;
    public static final int defaultTP = 50;

    @Override
    public Object toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("name", name);
        if (sourceImages!=null) res.put("images", sourceImages.toJSONEntry());
        this.getEndTrimFrame();
        res.put("preProcessingChain", preProcessingChain.toJSONEntry());
        res.put("defaultFrame", defaultTimePoint.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        name = (String)jsonO.get("name");
        if (jsonO.containsKey("images")) {
            sourceImages = MultipleImageContainer.createImageContainerFromJSON((JSONObject)jsonO.get("images"));
            initFrameParameters();
        }
        preProcessingChain.initFromJSONEntry(jsonO.get("preProcessingChain"));
        defaultTimePoint.initFromJSONEntry(jsonO.get("defaultFrame"));
    }
    
    public Position(String name) {
        super(name);
        initChildList();
    }
    @Override 
    public boolean isEmphasized() {
        return false;
    }
    public int getIndex() {
        return getParent().getIndex(this);
    }
    
    @Override
    protected void initChildList() {
        //logger.debug("MF: {}, init list..", name);
        //if (defaultTimePoint==null) defaultTimePoint = new TimePointParameter("Default Frame", defaultTP, false);
        initChildren(preProcessingChain, defaultTimePoint);
    }
    
    public void setPreProcessingChains(PreProcessingChain ppc) {
        preProcessingChain.setContentFrom(ppc);
    }
    
    public PreProcessingChain getPreProcessingChain() {
        return preProcessingChain;
    }
    private int getEndTrimFrame() {
        if (preProcessingChain.trimFrames.getValuesAsInt()[1]==0 && sourceImages!=null) return sourceImages.getFrameNumber()-1;
        return preProcessingChain.trimFrames.getValuesAsInt()[1];
    }
    public int getStartTrimFrame() {
        return preProcessingChain.trimFrames.getValuesAsInt()[0];
    }
    public boolean singleFrame(int structureIdx) {
        if (sourceImages==null) return false;
        int channelIdx = getExperiment().getChannelImageIdx(structureIdx);
        return singleFrameChannel(channelIdx);
    }
    public boolean singleFrameChannel(int channelIdx) {
        if (sourceImages==null) return false;
        return sourceImages.singleFrame(channelIdx);
    }
    public InputImagesImpl getInputImages() {
        if (inputImages !=null && inputImages.getFrameNumber()!=getFrameNumber(false)) {
            logger.warn("current inputImages has: {} frames while there are {} input images", inputImages.getFrameNumber(), getFrameNumber(false));
        }
        if (inputImages ==null) { // || inputImages.getFrameNumber()!=getTimePointNumber(false) // should be flushed when modified from gui
            synchronized(this) {
                if (inputImages ==null) { //inputImages.getFrameNumber()!=getTimePointNumber(false)
                    logger.debug("generate input images with {} frames (old: {}) ", getFrameNumber(false), inputImages !=null? inputImages.getFrameNumber() : "null");
                    ImageDAO dao = getExperiment().getImageDAO();
                    if (dao==null || sourceImages==null) return null;
                    int tpOff = getStartTrimFrame();
                    int tpNp = getEndTrimFrame() - tpOff+1;
                    InputImage[][] res = new InputImage[sourceImages.getChannelNumber()][];
                    for (int c = 0; c<sourceImages.getChannelNumber(); ++c) {
                        res[c] = sourceImages.singleFrame(c) ? new InputImage[1] : new InputImage[tpNp];
                        for (int t = 0; t<res[c].length; ++t) {
                            res[c][t] = new InputImage(c, t+tpOff, t, name, sourceImages, dao);
                        } 
                    }
                    int defTp = defaultTimePoint.getValue().intValue()-tpOff;
                    if (defTp<0) defTp=0;
                    if (defTp>=tpNp) defTp=tpNp-1;   
                    inputImages = new InputImagesImpl(res, defTp, getExperiment().getFocusChannelAndAlgorithm());
                    logger.debug("creation input images: def tp: {}, frames: {} ([{}; {}]), channels: {}",defTp, inputImages.getFrameNumber(), getStartTrimFrame(),getEndTrimFrame() , inputImages.getChannelNumber());
                }
            }
        } else {
            synchronized(this) {
                int defTp = defaultTimePoint.getValue().intValue() - getStartTrimFrame();
                if (defTp < 0) defTp = 0;
                if (defTp >= inputImages.getFrameNumber()) defTp = inputImages.getFrameNumber() - 1;
                inputImages.setDefaultTimePoint(defTp);
            }
        }
        return inputImages;
    }
    
    public void flushImages(boolean raw, boolean preProcessed) {
        if (preProcessed && inputImages !=null) {
            inputImages.flush();
            inputImages = null;
        }
        if (raw && sourceImages!=null) sourceImages.flush();
    }
    
    public BlankMask getMask() {
        BlankMask mask = getExperiment().getImageDAO().getPreProcessedImageProperties(name);
        if (mask==null) return null;
        // TODO: recreate image if configuration data has been already computed
        mask.setCalibration(sourceImages.getScaleXY(), sourceImages.getScaleZ());
        return mask;
    }
    
    public ArrayList<SegmentedObject> createRootObjects(ObjectDAO dao) {
        ArrayList<SegmentedObject> res = new ArrayList<>(getFrameNumber(false));
        if (getMask()==null) {
            logger.warn("Could not initiate root objects, perform preProcessing first");
            return null;
        }
        for (int t = 0; t<getFrameNumber(false); ++t) res.add(dao.getMasterDAO().getAccess().createRoot(t, getMask(), dao));
        setOpenedImageToRootTrack(res, dao.getMasterDAO().getAccess());
        setTrackLinks(res);
        return res;
    }
    public void setOpenedImageToRootTrack(List<SegmentedObject> rootTrack, SegmentedObjectAccessor accessor) {
        if (inputImages ==null) return;
        Map<Integer, List<Integer>> c2s = getExperiment().getChannelToStructureCorrespondance();
        for (int channelIdx = 0; channelIdx<getExperiment().getChannelImageCount(); ++channelIdx) {
            List<Integer> structureIndices =c2s.get(channelIdx);
            final int cIdx = channelIdx;
            if (structureIndices==null) continue; // no structure associated to channel
            rootTrack.parallelStream().filter(root -> inputImages.imageOpened(cIdx, root.getFrame())).forEach(root-> {
                structureIndices.forEach((s) -> accessor.setRawImage(root, s, inputImages.getImage(cIdx, root.getFrame())));
            });
        }
    }
    
    public Experiment getExperiment() {
        return (Experiment) parent.getParent();
    }
    
    public float getScaleXY(){
        if (!preProcessingChain.useCustomScale()) {
            if (sourceImages!=null && sourceImages.getScaleXY()!=0) return sourceImages.getScaleXY();
            else return 1;
        } else return (float)preProcessingChain.getScaleXY();
        
    }
    public float getScaleZ(){
        if (!preProcessingChain.useCustomScale()) {
            if (sourceImages!=null && sourceImages.getScaleZ()!=0) return sourceImages.getScaleZ();
            else return 1;
        } else return (float)preProcessingChain.getScaleZ();
    }
    public double getFrameDuration() {
        return preProcessingChain.getFrameDuration();
    }
    
    public int getFrameNumber(boolean raw) {
        if (sourceImages!=null) {
            if (raw) return sourceImages.getFrameNumber();
            else return getEndTrimFrame() - getStartTrimFrame()+1;
        }
        else return 0;
    }

    public int getDefaultTimePoint() {
        return defaultTimePoint.getValue().intValue();
    }
    public Position setDefaultFrame(int frame) {
        this.defaultTimePoint.setValue(frame);
        return this;
    }
    
    public int getSizeZ(int channelIdx) {
        if (sourceImages!=null) return sourceImages.getSizeZ(channelIdx);
        else return -1;
    }
    
    public void setImages(MultipleImageContainer images) {
        this.sourceImages=images;
        this.inputImages =null;
        initFrameParameters();
    }
    private void initFrameParameters() {
        if (sourceImages!=null) {
            int frameNb = sourceImages.getFrameNumber();
            preProcessingChain.trimFrames.setUpperBound(frameNb-1);
            if (preProcessingChain.trimFrames.getValuesAsInt()[1]<=0 || preProcessingChain.trimFrames.getValuesAsInt()[1]>=frameNb) preProcessingChain.trimFrames.setValue(frameNb-1, 1);
            defaultTimePoint.setUpperBound(frameNb-1);
            if (defaultTimePoint.getValue().intValue()>frameNb-1) defaultTimePoint.setValue(frameNb/2);
        }
    }
    
    @Override public Position duplicate() {
        Position mf = super.duplicate();
        if (sourceImages!=null) mf.setImages(sourceImages.duplicate());
        mf.setListeners(listeners);
        return mf;
    }
    
    @Override
    public String toString() {
        if (sourceImages!=null) return name+ "(#"+getIndex()+")";// + " number of time points: "+images.getTimePointNumber();
        return name + " no selected images";
    }
    
    @Override
    public void removeFromParent() { // when removed from GUI
        super.removeFromParent();
        
    }
    
    @Override 
    public void setContentFrom(Parameter other) {
        super.setContentFrom(other);
        if (other instanceof Position) {
            Position otherP = (Position) other;
            if (otherP.sourceImages!=null) sourceImages = otherP.sourceImages.duplicate();
        }
    }
    @Override 
    public boolean sameContent(Parameter other) {
        if (!super.sameContent(other)) return false;
        if (other instanceof Position) {
            Position otherP = (Position) other;
            if (otherP.sourceImages!=null && sourceImages!=null) {
                if (!sourceImages.sameContent(otherP.sourceImages)) {
                    logger.debug("Position: {}!={} content differs at images");
                    return true; // just warn, do not concerns configuration
                } else return true;
            } else if (otherP.sourceImages==null && sourceImages==null) return true;
            else return false;
        }
        return false;
    }

    // listElementErasable
    @Override 
    public boolean eraseData() {
        logger.debug("calling erase data method for position: {}", getName());
        if (deletePositionCallBack!=null) {
            return deletePositionCallBack.test(this);
        }
        return false;
    }
    Predicate<Position> deletePositionCallBack;
    public void setDeletePositionCallBack(Predicate<Position> deletePositionCallBack) {
        this.deletePositionCallBack=deletePositionCallBack;
    }
    

    
}