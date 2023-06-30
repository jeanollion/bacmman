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
package bacmman.ui.gui.image_interaction;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.image.io.KymographFactory;
import bacmman.ui.GUI;
import bacmman.core.DefaultWorker;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageInteger;

import java.util.*;

import bacmman.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public abstract class Kymograph extends InteractiveImage {
    public static final Logger logger = LoggerFactory.getLogger(Kymograph.class);
    public static Kymograph generateKymograph(List<SegmentedObject> parentTrack, int childStructureIdx, boolean hyperStack) {
        if (hyperStack) return new HyperStack(KymographFactory.generateHyperstackData(parentTrack, true), childStructureIdx, true);
        KymographFactory.KymographData data = KymographFactory.generateKymographData(parentTrack, false, INTERVAL_PIX);
        switch (data.direction) {
            case X:
            default:
                return new KymographX(data, childStructureIdx);
            case Y:
                return new KymographY(data, childStructureIdx);
        }
    }
    BoundingBox[] trackOffset;
    SimpleInteractiveImage[] trackObjects;
    private static final int updateImageFrequency=50;
    public static int INTERVAL_PIX=0;
    Map<Image, Predicate<BoundingBox>> imageCallback = new HashMap<>();

    public Kymograph(KymographFactory.KymographData data, int childStructureIdx, boolean setAllChildren) {
        super(data.parentTrack, childStructureIdx);
        trackOffset = data.trackOffset;
        if (setAllChildren) SegmentedObjectUtils.setAllChildren(data.parentTrack, childStructureIdx);
        Consumer<SimpleInteractiveImage> peekFun = setAllChildren ? SimpleInteractiveImage::getObjects : (si)->{};
        trackObjects = IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImage(data.parentTrack.get(i), childStructureIdx, trackOffset[i])).peek(peekFun).toArray(SimpleInteractiveImage[]::new);
    }
    
    @Override public List<SegmentedObject> getParents() {
        return this.parents;
    }
    
    @Override public InteractiveImageKey getKey() {
        return new InteractiveImageKey(parents, InteractiveImageKey.TYPE.KYMOGRAPH, childStructureIdx, name);
    }
    
    @Override
    public void reloadObjects() {
        for (SimpleInteractiveImage m : trackObjects) m.reloadObjects();
    }

    @Override
    public void resetObjects() {
        for (SimpleInteractiveImage m : trackObjects) m.resetObjects();
    }

    public abstract int getClosestFrame(int x, int y);
    
    @Override
    public BoundingBox getObjectOffset(SegmentedObject object) {
        if (object==null) return null;
        
        //if (object.getFrame()<parent.getFrame()) logger.error("Object not in track : Object: {} parent: {}", object, parent);
        int idx = object.getFrame()-parents.get(0).getFrame();
        if (idx<trackObjects.length && idx>0 && parents.get(idx).getFrame()==object.getFrame()) return trackObjects[idx].getObjectOffset(object);
        else { // case of uncontinuous tracks -> search whole track
            idx = Collections.binarySearch(parents, object, Comparator.comparingInt(SegmentedObject::getFrame));
            if (idx<0) return null;
            BoundingBox res =  trackObjects[idx].getObjectOffset(object);
            if (res!=null) return res;
            int idx2 = idx-1;
            while (idx2>=0 && parents.get(idx2).getFrame()==object.getFrame()) {
                res =  trackObjects[idx2].getObjectOffset(object);
                if (res!=null) return res;
                --idx2;
            }
            idx2=idx+1;
            while (idx2<trackObjects.length && parents.get(idx2).getFrame()==object.getFrame()) {
                res=  trackObjects[idx2].getObjectOffset(object);
                if (res!=null) return res;
                ++idx2;
            }
        } 
        return null;
    }
    
    public void trimTrack(List<Pair<SegmentedObject, BoundingBox>> track) {
        int tpMin = parents.get(0).getFrame();
        int tpMax = parents.get(parents.size()-1).getFrame();
        track.removeIf(o -> o.key.getFrame()<tpMin || o.key.getFrame()>tpMax);
    }
    public abstract Image generateEmptyImage(String name, Image type);
    @Override public <T extends InteractiveImage> T setDisplayPreFilteredImages(boolean displayPreFilteredImages) {
        super.setDisplayPreFilteredImages(displayPreFilteredImages);
        for (SimpleInteractiveImage m : trackObjects) m.setDisplayPreFilteredImages(displayPreFilteredImages);
        return (T)this;
    }
    
    @Override public Image generateImage(final int structureIdx, boolean background) {
        background = false; // imageJ1 -> update display too slow -> better to paste whole image at once
        // use track image only if parent is first element of track image
        //if (trackObjects[0].parent.getOffsetInTrackImage()!=null && trackObjects[0].parent.getOffsetInTrackImage().xMin()==0 && trackObjects[0].parent.getTrackImage(structureIdx)!=null) return trackObjects[0].parent.getTrackImage(structureIdx);
        long t0 = System.currentTimeMillis();
        Image image0 = trackObjects[0].generateImage(structureIdx, false);
        //GUI.logger.debug("image bounds: {}, parent {} bounds: {}. is2D: {}", image0.getBoundingBox(), trackObjects[0].parent, trackObjects[0].parent.getBounds(), is2D());
        if (image0==null) return null;
        String structureName;
        if (getParent().getExperimentStructure()!=null) structureName = getParent().getExperimentStructure().getObjectClassName(structureIdx);
        else structureName= structureIdx+"";
        String pStructureName;
        if (getParent().getExperimentStructure()!=null) pStructureName = getParent().getStructureIdx()<0? "": " " + getParent().getExperimentStructure().getObjectClassName(getParent().getStructureIdx());
        else pStructureName= getParent().getStructureIdx()+"";
        final Image displayImage =  generateEmptyImage("Kymograph@"+pStructureName+"/P"+getParent().getPositionIdx()+"/Idx"+getParent().getIdx()+"/F["+getParent().getFrame()+";"+parents.get(parents.size()-1).getFrame()+"]: "+structureName, image0);
        Image.pasteImage(image0, displayImage, trackOffset[0]);
        long t1 = System.currentTimeMillis();
        GUI.logger.debug("generate image: {} for structure: {}, ex in background?{}, time: {}ms", parents.get(0), structureIdx, background, t1-t0);
        if (!background) {
            IntStream.range(0, trackOffset.length).parallel().forEach(i->{
                Image subImage = trackObjects[i].generateImage(structureIdx, false);
                Image.pasteImage(subImage, displayImage, trackOffset[i]);
            });
        } else {
            boolean[] pastedImage = new boolean[trackOffset.length];
            boolean[] pastedImageBck = new boolean[trackOffset.length];
            Integer[] lock = IntStream.range(0, trackOffset.length).mapToObj(i->i).toArray(Integer[]::new);
            pastedImage[0] = true;
            int frame0= parents.get(0).getFrame();
            Predicate<BoundingBox> callBack  = bounds -> {
                long t00 = System.currentTimeMillis();
                int idxMin = getClosestFrame(bounds.xMin(), bounds.yMin())-frame0;
                int idxMax = getClosestFrame(bounds.xMax(), bounds.yMax())-frame0;
                long t01 = System.currentTimeMillis();
                IntUnaryOperator pasteImage = i-> {
                    pastedImage[i]=true; // may habe pasted in background thus display not updated
                    if (!pastedImageBck[i]) {
                        synchronized(lock[i]) {
                            if (!pastedImageBck[i]) {
                                Image subImage = trackObjects[i].generateImage(structureIdx, false);
                                Image.pasteImage(subImage, displayImage, trackOffset[i]);
                            }
                        }
                    }
                    return 1;
                };
                boolean imageHasBeenPasted = IntStream.rangeClosed(idxMin, idxMax).filter(i->!pastedImage[i]).parallel().map(pasteImage).sum()>1;
                
                long t02 = System.currentTimeMillis();
                if (imageHasBeenPasted) GUI.logger.debug("call back paste image: [{};{}] time: {} & {}", idxMin, idxMax, t01-t00, t02-t01);
                return imageHasBeenPasted;
            };
            // ALSO launch a thread to paste image in background without image display
            DefaultWorker bckPaste = new DefaultWorker(i-> {
                if (pastedImage[i] || pastedImageBck[i]) return "";
                synchronized(lock[i]) {
                    if (pastedImage[i] || pastedImageBck[i]) return "";
                    Image subImage = trackObjects[i].generateImage(structureIdx, false);
                    Image.pasteImage(subImage, displayImage, trackOffset[i]);
                    pastedImageBck[i] = true;
                    //logger.debug("past image: {}", i);
                    return "";
                }
            }, trackOffset.length, GUI.getInstance());
            bckPaste.execute(); // TODO : add listener for close image to stop
            //bckPaste.cancel(true);
            this.imageCallback.put(displayImage, callBack);
        }
        
        return displayImage;
    }

    
    @Override public void drawObjects(final ImageInteger image) {
        trackObjects[0].drawObjects(image);
        double[] mm = image.getMinAndMax(null, trackObjects[0].parent.getBounds());
        // draw image in another thread..
        Thread t = new Thread(() -> {
            int count = 0;
            for (int i = 1; i<trackObjects.length; ++i) {
                trackObjects[i].drawObjects(image);
                //double[] mm2 = image.getMinAndMax(null, trackObjects[0].parent.getBounds());
                //if (mm[0]>mm2[0]) mm[0] = mm2[0];
                //if (mm[1]<mm2[1]) mm[1] = mm2[1];
                if (count>=updateImageFrequency) {

                    ImageWindowManagerFactory.getImageManager().getDisplayer().updateImageDisplay(image, mm[0], mm[1]); // do not compute min and max. Keep track of min and max?
                    count=0;
                } else count++;
            }
            ImageWindowManagerFactory.getImageManager().getDisplayer().updateImageDisplay(image);
        });
        t.start();
        if (!guiMode) try {
            t.join();
        } catch (InterruptedException ex) {
            GUI.logger.error("draw error", ex);
        }
    }
    
    public boolean containsTrack(SegmentedObject trackHead) {
        if (childStructureIdx==parentStructureIdx) return trackHead.getStructureIdx()==this.childStructureIdx && trackHead.getTrackHeadId().equals(this.parents.get(0).getId());
        else return trackHead.getStructureIdx()==this.childStructureIdx && trackHead.getParentTrackHeadId().equals(this.parents.get(0).getId());
    }

    @Override
    public List<Pair<SegmentedObject, BoundingBox>> getObjects() {
        ArrayList<Pair<SegmentedObject, BoundingBox>> res = new ArrayList<>();
        for (SimpleInteractiveImage m : trackObjects) res.addAll(m.getObjects());
        return res;
    }
    @Override
    public Stream<Pair<SegmentedObject, BoundingBox>> getAllObjects() {
        return Arrays.stream(trackObjects).flatMap(SimpleInteractiveImage::getAllObjects);
    }
}
