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

import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.*;
import bacmman.measurement.MeasurementExtractor;
import bacmman.ui.GUI;
import bacmman.core.DefaultWorker;

import java.awt.Color;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import bacmman.plugins.TestableProcessingPlugin.TestDataStore;
import bacmman.utils.*;
import bacmman.utils.HashMapGetCreate.SetFactory;

import static bacmman.utils.Pair.unpairValues;

import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 * @param <I> image class
 * @param <U> object ROI class
 * @param <V> track ROI class
 */
public abstract class ImageWindowManager<I, U, V> {
    public static final Logger logger = LoggerFactory.getLogger(ImageWindowManager.class);
    public enum RegisteredImageType {KYMOGRAPH, RAW_INPUT, PRE_PROCESSED}
    public static boolean displayTrackMode;
    public final static Color[] palette = new Color[]{new Color(166, 206, 227, 150), new Color(31,120,180, 150), new Color(178,223,138, 150), new Color(51,160,44, 150), new Color(251,154,153, 150), new Color(253,191,111, 150), new Color(255,127,0, 150), new Color(255,255,153, 150), new Color(177,89,40, 150)};
    public final static Color defaultRoiColor = new Color(255, 0, 255, 150);
    public static Color getColor(int idx) {return palette[idx%palette.length];}
    protected final static Color trackErrorColor = new Color(255, 0, 0);
    protected final static Color trackCorrectionColor = new Color(0, 0, 255);
    public static Color getColor() {
        return Palette.getColor(150, trackErrorColor, trackCorrectionColor);
    }
    protected final Map<SegmentedObject, Color> trackColor = new HashMapGetCreate.HashMapGetCreateRedirected<>(t -> getColor());
    public Color getColor(SegmentedObject trackHead) {
        return trackColor.get(trackHead.getTrackHead());
        //return Palette.getColor(0, SegmentedObjectUtils.getIndexTree(trackHead.getTrackHead()));
    }
    final static double TRACK_ARROW_STROKE_WIDTH = 3;
    double ROI_STROKE_WIDTH = 0.5;
    public static double TRACK_LINK_MIN_SIZE = 23;
    protected final HashMap<InteractiveImageKey, InteractiveImage> imageObjectInterfaces;
    protected final HashMap<Image, InteractiveImageKey> imageObjectInterfaceMap;
    protected final HashMapGetCreate<SegmentedObject, List<List<SegmentedObject>>> trackHeadTrackMap;
    protected final LinkedHashMap<String, I> displayedRawInputFrames = new LinkedHashMap<>();
    protected final LinkedHashMap<String, I> displayedPrePocessedFrames = new LinkedHashMap<>();
    protected final LinkedList<Image> displayedInteractiveImages = new LinkedList<>();
    final ImageObjectListener listener;
    final ImageDisplayer<I> displayer;
    int interactiveStructureIdx;
    int displayedImageNumber = 20;
    ZoomPane localZoom;
    // displayed objects 
    protected final Map<Pair<SegmentedObject, BoundingBox>, U> objectRoiMap = new HashMap<>();
    protected final Map<Pair<SegmentedObject, SegmentedObject>, V> parentTrackHeadKymographTrackRoiMap=new HashMap<>();
    protected final Map<Pair<SegmentedObject, SegmentedObject>, V> parentTrackHeadTrackRoiMap=new HashMap<>();
    protected final Map<Pair<SegmentedObject, BoundingBox>, U> labileObjectRoiMap = new HashMap<>();
    protected final Map<Pair<SegmentedObject, SegmentedObject>, V> labileParentTrackHeadTrackRoiMap=new HashMap<>();
    protected final Map<Pair<SegmentedObject, SegmentedObject>, V> labileParentTrackHeadKymographTrackRoiMap=new HashMap<>();
    protected final HashMapGetCreate<Image, Set<U>> displayedLabileObjectRois = new HashMapGetCreate<>(new SetFactory<>());
    protected final HashMapGetCreate<Image, Set<V>> displayedLabileTrackRois = new HashMapGetCreate<>(new SetFactory<>());

    protected final Map<Image, List<DefaultWorker>> runningWorkers = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.ListFactory<>());
    protected static InteractiveImageKey.TYPE defaultInteractiveType = InteractiveImageKey.TYPE.HYPERSTACK;
    public ImageWindowManager(ImageObjectListener listener, ImageDisplayer<I> displayer) {
        this.listener=null;
        this.displayer=displayer;
        imageObjectInterfaceMap = new HashMap<>();
        imageObjectInterfaces = new HashMap<>();
        trackHeadTrackMap = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory());
    }
    public static void setDefaultInteractiveType(InteractiveImageKey.TYPE type) {defaultInteractiveType = type;}
    public static InteractiveImageKey.TYPE getDefaultInteractiveType() {return defaultInteractiveType;}
    public void setDisplayImageLimit(int limit) {
        this.displayedImageNumber=limit;
    }
    public void setRoiStrokeWidth(double width) {
        this.ROI_STROKE_WIDTH = width;
    }
    public int getDisplayImageLimit() {
        return displayedImageNumber;
    }
    public RegisteredImageType getRegisterType(Object image) {
        if (image instanceof Image) {
            if (displayedInteractiveImages.contains(image)) return RegisteredImageType.KYMOGRAPH;
            else return null;
        }
        if (this.displayedRawInputFrames.values().contains(image)) return RegisteredImageType.RAW_INPUT;
        if (this.displayedPrePocessedFrames.values().contains(image)) return RegisteredImageType.PRE_PROCESSED;
        try {
            I im = (I) image;
            if (displayedInteractiveImages.contains(getDisplayer().getImage(im))) return RegisteredImageType.KYMOGRAPH;
        } catch(Exception e) {}
        
        return null;
    }
    void addLocalZoom(Component parent) {
        MouseAdapter ma = new MouseAdapter() {
            private void update(MouseEvent e) {
                if (localZoom ==null) return;
                localZoom.setParent(parent);
                localZoom.updateLocation(e);
            }
            @Override
            public void mouseClicked(MouseEvent e) {
                update(e);
            }
            @Override
            public void mouseDragged(MouseEvent e){
                update(e);
            }
            @Override
            public void mouseMoved(MouseEvent e) {
                update(e);
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                update(e);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                if (localZoom ==null) return;
                else localZoom.detach();
            }
        };
        parent.addMouseListener(ma);
        parent.addMouseMotionListener(ma);
    }
    public abstract boolean isCurrentFocusOwnerAnImage();
    public void toggleActivateLocalZoom() {
        if (localZoom==null) {
            localZoom =  GUI.getInstance()==null? new ZoomPane() : new ZoomPane(GUI.getInstance().getLocalZoomLevel(), GUI.getInstance().getLocalZoomArea(), GUI.getInstance().getLocalZoomScale());
        } else {
            localZoom.detach();
            localZoom = null;
        }
    }
    public abstract void toggleSetObjectCreationTool();
    public Map<Image, List<DefaultWorker>> getRunningWorkers() {
        return runningWorkers;
    }
    public void stopAllRunningWorkers() {
        try {
            for (DefaultWorker w : runningWorkers.values().stream().flatMap(Collection::stream).collect(Collectors.toList())) {
                w.cancel(true);
            }
            runningWorkers.clear();
        } catch (CancellationException e) {}
    }
    public void flush() {
        if (!runningWorkers.isEmpty()) logger.debug("flush: will stop {} running workers", runningWorkers.size());
        stopAllRunningWorkers();
        if (!objectRoiMap.isEmpty()) logger.debug("flush: will remove {} rois", objectRoiMap.size());
        objectRoiMap.clear();
        parentTrackHeadTrackRoiMap.clear();
        parentTrackHeadKymographTrackRoiMap.clear();
        if (!labileObjectRoiMap.isEmpty()) logger.debug("flush: will remove {} rois", labileObjectRoiMap.size());
        labileObjectRoiMap.clear();
        labileParentTrackHeadTrackRoiMap.clear();
        labileParentTrackHeadKymographTrackRoiMap.clear();
        displayedLabileObjectRois.clear();
        displayedLabileTrackRois.clear();
        displayer.flush();
        imageObjectInterfaces.clear();
        if (!imageObjectInterfaceMap.isEmpty()) logger.debug("flush: will remove {} images", imageObjectInterfaceMap.size());
        imageObjectInterfaceMap.clear();
        trackHeadTrackMap.clear();
        displayedRawInputFrames.clear();
        displayedPrePocessedFrames.clear();
        displayedInteractiveImages.clear();
        testData.clear();
        trackColor.clear();
    }
    public void revertObjectClass(int objectClassIdx, MasterDAO db) {
        // TODO: bug with hyperstacks
        /*Consumer<InteractiveImageKey> flushIOI = k -> {
            imageObjectInterfaces.remove(k);
            Utils.getKeys(imageObjectInterfaceMap, k).forEach(displayer::close);
        };
        // reset hyperstack callback
        imageObjectInterfaceMap.entrySet().stream()
                .filter(e -> e.getValue().imageType.equals(InteractiveImageKey.TYPE.HYPERSTACK))
                .forEach(e -> registerInteractiveHyperStackFrameCallback(e.getKey(), null, true));
        // reset image object interface
        new ArrayList<>(imageObjectInterfaces.keySet()).forEach(k -> {
            InteractiveImage i = imageObjectInterfaces.get(k);
            ExperimentStructure xp = i.getParent().getExperimentStructure();
            if (db==null || !(i instanceof Kymograph) || objectClassIdx == i.getParent().getStructureIdx() || xp.isChildOf(objectClassIdx, i.getParent().getStructureIdx())) { // close image and remove IOI
                flushIOI.accept(k);
            } else {
                SegmentedObject trackHead = i.parents.get(0).getTrackHead();
                trackHead = db.getDao(trackHead.getPositionName()).getById(trackHead.getParentTrackHeadId(), trackHead.getStructureIdx(), trackHead.getFrame(), trackHead.getId()); // replace object
                List<SegmentedObject> track = db.getDao(trackHead.getPositionName()).getTrack(trackHead);
                if (track.size() == i.parents.size()) {
                    Kymograph newI = Kymograph.generateKymograph(track, i.childStructureIdx, i instanceof HyperStack);
                    i.setGUIMode(GUI.hasInstance());
                    if (newI instanceof HyperStack) {
                        Utils.getKeys(imageObjectInterfaceMap, k).forEach(im -> {
                            registerHyperStack(im, (HyperStack)newI);
                            GUI.updateRoiDisplayForSelections(im, newI);
                        });
                    } else imageObjectInterfaces.put(k, newI);
                } else flushIOI.accept(k);
            }
        });
        //imageObjectInterfaces.clear();
        imageObjectInterfaceMap.forEach((i,k) -> {
            if (k.interactiveObjectClass == objectClassIdx) hideAllRois(displayer.getImage(i));
        });
        trackHeadTrackMap.clear();
        objectRoiMap.clear();
        labileObjectRoiMap.clear();
        parentTrackHeadKymographTrackRoiMap.clear();
        parentTrackHeadTrackRoiMap.clear();
        labileParentTrackHeadTrackRoiMap.clear();
        labileParentTrackHeadKymographTrackRoiMap.clear();
        trackColor.clear();
        displayedLabileObjectRois.clear();
        displayedLabileTrackRois.clear();*/
        flush();
    }
    public void closeNonInteractiveWindows() {
        closeLastInputImages(0);
    }
    public ImageDisplayer<I> getDisplayer() {return displayer;}
    
    //protected abstract I getImage(Image image);
    
    public void setInteractiveStructure(int structureIdx) {
        this.interactiveStructureIdx=structureIdx;
    }
    
    public int getInteractiveStructure() {
        return interactiveStructureIdx;
    }
    
    public Image getImage(InteractiveImage i) {
        if (i==null) {
            logger.error("cannot get image if IOI null");
            return null;
        }
        List<Image> list = Utils.getKeys(imageObjectInterfaceMap, i.getKey());
        if (list.isEmpty()) return null;
        else return list.get(0);
    }
    public Image getImage(InteractiveImage i, int displayStructureIdx) {
        List<Image> list = Utils.getKeys(imageObjectInterfaceMap, i.getKey().getKey(displayStructureIdx));
        if (list.isEmpty()) return null;
        else return list.get(0);
    }
    
    public void setActive(Image image) {
        boolean b =  displayedInteractiveImages.remove(image);
        if (b) displayedInteractiveImages.add(image);
    }
    
    public void addImage(Image image, InteractiveImage i, int displayOCIdx, boolean displayImage) {
        if (image==null) return;
        GUI.logger.debug("adding image: {}, IOI {} exists: {} ({}), displayed OC: {}", image.getName(), i.getKey(), imageObjectInterfaces.containsKey(i.getKey()), imageObjectInterfaces.containsValue(i));
        /*if (!imageObjectInterfaces.containsValue(i)) {
            //throw new RuntimeException("image object interface should be created through the manager");
            imageObjectInterfaces.put(i.getKey(), i);
        }*/
        imageObjectInterfaces.put(i.getKey(), i);
        //T dispImage = getImage(image);
        imageObjectInterfaceMap.put(image, i.getKey().getKey(displayOCIdx));
        if (displayImage) {
            displayImage(image, i);
            if (i instanceof Kymograph && ((Kymograph)i).imageCallback.containsKey(image)) this.displayer.addMouseWheelListener(image, ((Kymograph)i).imageCallback.get(image));
        }
    }
    public abstract void registerInteractiveHyperStackFrameCallback(Image image, HyperStack k, boolean interactive);
    public void registerHyperStack(Image image, HyperStack i) {
        imageObjectInterfaces.put(i.getKey(), i);
        imageObjectInterfaceMap.put(image, i.getKey());
        if (i.loadObjectsWorker!=null && !i.loadObjectsWorker.isDone()) {
            runningWorkers.get(image).add(i.loadObjectsWorker);
            i.loadObjectsWorker.appendEndOfWork(()->runningWorkers.get(image).remove(i.loadObjectsWorker));
        }
        registerInteractiveHyperStackFrameCallback(image, i, true);
    }
    public void addHyperStack(Image image, I displayedImage, HyperStack i) {
        logger.debug("adding frame stack: {} (hash: {}), IOI exists: {} ({})", image.getName(), image.hashCode(), imageObjectInterfaces.containsKey(i.getKey()), imageObjectInterfaces.containsValue(i));
        //T dispImage = getImage(image);
        displayedInteractiveImages.add(image);
        displayer.putImage(image, displayedImage);
        registerHyperStack(image, i);
        addMouseListener(image);
        addWindowClosedListener(displayedImage, e-> {
            List<DefaultWorker> l = runningWorkers.get(image);
            if (!l.isEmpty()) {
                logger.debug("interrupting {} object lazy loading for image: {}", l.size(), image.getName());
                l.forEach(w -> w.cancel(true));
            }
            runningWorkers.remove(image);
            displayedInteractiveImages.remove(image);
            displayer.removeImage(image, displayedImage);
            imageObjectInterfaceMap.remove(image);
            displayedLabileObjectRois.remove(image);
            displayedLabileTrackRois.remove(image);
            return null;
        });
        i.setGUIMode(GUI.hasInstance());
        GUI.updateRoiDisplayForSelections(image, i);
        closeLastActiveImages(displayedImageNumber);
    }

    public void displayImage(Image image, InteractiveImage i) {
        long t0 = System.currentTimeMillis();
        displayer.showImage(image);
        long t1 = System.currentTimeMillis();
        displayedInteractiveImages.add(image);
        addMouseListener(image);
        addWindowClosedListener(image, e-> {
            List<DefaultWorker> l = runningWorkers.get(image);
            if (!l.isEmpty()) {
                logger.debug("interrupting generation of closed image: {}", image.getName());
                l.forEach(w -> w.cancel(true));
            }
            runningWorkers.remove(image);
            displayedInteractiveImages.remove(image);
            displayer.removeImage(image, null);
            return null;
        });

        long t2 = System.currentTimeMillis();
        GUI.updateRoiDisplayForSelections(image, i);
        long t3 = System.currentTimeMillis();
        closeLastActiveImages(displayedImageNumber);
        long t4 = System.currentTimeMillis();
        GUI.logger.debug("display image: show: {} ms, add list: {}, update ROI: {}, close last active image: {}", t1-t0, t2-t1, t3-t2, t4-t3);
    }
    public String getPositionOfInputImage(I image) {
        String pos = Utils.getOneKey(displayedRawInputFrames, image);
        if (pos!=null) return pos;
        return Utils.getOneKey(displayedPrePocessedFrames, image);
    }
    public void addInputImage(String position, I image, boolean raw) {
        if (image==null) return;
        addWindowClosedListener(image, e-> {
            if (raw) displayedRawInputFrames.remove(position);
            else displayedPrePocessedFrames.remove(position);
            displayer.removeImage(null, image);
            return null;
        });
        if (raw) displayedRawInputFrames.put(position, image);
        else displayedPrePocessedFrames.put(position,  image);
        closeLastInputImages(displayedImageNumber);
    }
    public void closeLastActiveImages(int numberOfKeptImages) {
        GUI.logger.debug("close active images: total open {} limit: {}", displayedInteractiveImages.size(), numberOfKeptImages);
        if (numberOfKeptImages<=0) return;
        if (displayedInteractiveImages.size()>numberOfKeptImages) {
            Iterator<Image> it = displayedInteractiveImages.iterator();
            while(displayedInteractiveImages.size()>numberOfKeptImages && it.hasNext()) {
                Image next = it.next();
                it.remove();
                displayer.close(next);
            }
        }
    }
    public void closeLastInputImages(int numberOfKeptImages) {
        //logger.debug("close input images: raw: {} pp: {} limit: {}", displayedRawInputFrames.size(), displayedPrePocessedFrames.size(), numberOfKeptImages);
        if (numberOfKeptImages<=0) return;
        if (displayedRawInputFrames.size()>numberOfKeptImages) {
            Iterator<String> it = displayedRawInputFrames.keySet().iterator();
            while(displayedRawInputFrames.size()>numberOfKeptImages && it.hasNext()) {
                String i = it.next();
                I im = displayedRawInputFrames.get(i);
                it.remove();
                displayer.close(im);
            }
        }
        if (displayedPrePocessedFrames.size()>numberOfKeptImages) {
            Iterator<String> it = displayedPrePocessedFrames.keySet().iterator();
            while(displayedPrePocessedFrames.size()>numberOfKeptImages && it.hasNext()) {
                String i = it.next();
                I im = displayedPrePocessedFrames.get(i);
                it.remove();
                displayer.close(im);
            }
        }
    }
    
    public void resetImageObjectInterface(SegmentedObject parent, int childStructureIdx) {
        for (InteractiveImageKey.TYPE it : InteractiveImageKey.TYPE.values()) imageObjectInterfaces.remove(new InteractiveImageKey(new ArrayList<SegmentedObject>(1){{add(parent);}}, it, childStructureIdx));
    }
    
    public InteractiveImage getImageObjectInterface(SegmentedObject parent, int childStructureIdx, boolean createIfNotExisting) {
        InteractiveImage i = imageObjectInterfaces.get(new InteractiveImageKey(new ArrayList<SegmentedObject>(1){{add(parent);}}, InteractiveImageKey.TYPE.SINGLE_FRAME, childStructureIdx));
        if (i==null && createIfNotExisting) {
            i= new SimpleInteractiveImage(parent, childStructureIdx);
            imageObjectInterfaces.put(i.getKey(), i);
        } 
        return i;
    }
    public InteractiveImage getImageTrackObjectInterface(List<SegmentedObject> parentTrack, int childStructureIdx, InteractiveImageKey.TYPE type) {
        
        if (parentTrack.isEmpty()) {
            logger.warn("cannot create kymograph with parent track of length == 0" );
            return null;
        }
        InteractiveImage i = imageObjectInterfaces.get(new InteractiveImageKey(parentTrack, type, childStructureIdx));
        logger.debug("getIOI: type: {}, hash: {} ({}), exists: {}, trackHeadTrackMap: {}", type, parentTrack.hashCode(), new InteractiveImageKey(parentTrack, type, childStructureIdx).hashCode(), i!=null, trackHeadTrackMap.containsKey(parentTrack.get(0)));
        if (i==null) {
            long t0 = System.currentTimeMillis();
            i = Kymograph.generateKymograph(parentTrack, childStructureIdx, type.equals(InteractiveImageKey.TYPE.HYPERSTACK));
            long t1 = System.currentTimeMillis();
            imageObjectInterfaces.put(i.getKey(), i);
            trackHeadTrackMap.getAndCreateIfNecessary(parentTrack.get(0)).add(parentTrack);
            i.setGUIMode(GUI.hasInstance());

            long t2 = System.currentTimeMillis();
            logger.debug("create IOI: {} key: {}, creation time: {} ms + {} ms", i.hashCode(), i.getKey().hashCode(), t1-t0, t2-t1);
        } 
        return i;
    }

    protected void reloadObjects__(InteractiveImageKey key) {
        InteractiveImage i = imageObjectInterfaces.get(key);
        if (i!=null) {
            GUI.logger.debug("reloading object for parentTrackHead: {} structure: {}", key.parent.get(0), key.interactiveObjectClass);
            i.reloadObjects();
        }
    }
    protected void reloadObjects_(SegmentedObject parent, int childStructureIdx, boolean track) {
        if (track) parent=parent.getTrackHead();
        if (!trackHeadTrackMap.containsKey(parent)) {
            final SegmentedObject p = parent;
            reloadObjects__(new InteractiveImageKey(new ArrayList<SegmentedObject>(1){{add(p);}}, InteractiveImageKey.TYPE.SINGLE_FRAME, childStructureIdx));
        } else {
            for (List<SegmentedObject> l : trackHeadTrackMap.get(parent)) {
                for (InteractiveImageKey.TYPE it : InteractiveImageKey.TYPE.values()) reloadObjects__(new InteractiveImageKey(l, it, childStructureIdx));
            }
        }
    }
    public void resetObjects(String position, int childStructureIdx) {
        imageObjectInterfaces.values().stream()
                .filter(i->i.getChildStructureIdx()==childStructureIdx)
                .filter(i->position==null || i.getParent().getPositionName().equals(position))
                .forEach(InteractiveImage::resetObjects);
        resetObjectsAndTracksRoi();
    }
    public void reloadObjects(SegmentedObject parent, int childStructureIdx, boolean wholeTrack) {
        reloadObjects_(parent, childStructureIdx, true); // reload track images
        if (wholeTrack) { // reload each image of the track
            List<SegmentedObject> track = SegmentedObjectUtils.getTrack(parent.getTrackHead(), false);
            for (SegmentedObject o : track) reloadObjects_(o, childStructureIdx, false);
        } else reloadObjects_(parent, childStructureIdx, false);
        if (parent.getParent()!=null) reloadObjects(parent.getParent(), childStructureIdx, wholeTrack);
        this.resetObjectsAndTracksRoi();
    } 
    
    public InteractiveImage getCurrentImageObjectInterface() {
        return getImageObjectInterface(null);
    }
    public InteractiveImageKey getImageObjectInterfaceKey(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) {
                return null;
            }
        }
        return imageObjectInterfaceMap.get(image);
    }
    public InteractiveImage getImageObjectInterface(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) {
                return null;
            }
        }
        InteractiveImageKey key = imageObjectInterfaceMap.get(image);
        if (key==null) return null;
        return getImageObjectInterface(image, interactiveStructureIdx, key.imageType);
    }

    public InteractiveImage getImageObjectInterface(Image image, InteractiveImageKey.TYPE type) {
        return getImageObjectInterface(image, interactiveStructureIdx, type); // use the interactive structure. Creates the ImageObjectInterface if necessary
    }

    public InteractiveImage getImageObjectInterface(Image image, int objectClassIdx) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) {
                return null;
            }
        }
        InteractiveImageKey key = imageObjectInterfaceMap.get(image);
        if (key==null) return null;
        return getImageObjectInterface(image, objectClassIdx, key.imageType);
    }

    public InteractiveImage getImageObjectInterface(Image image, int structureIdx, InteractiveImageKey.TYPE type) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) {
                return null;
            }
        }
        InteractiveImageKey key = imageObjectInterfaceMap.get(image);
        if (key==null) return null;
        InteractiveImage i = this.imageObjectInterfaces.get(key.getKey(structureIdx));
        
        if (i==null) {
            InteractiveImage ref = InteractiveImageKey.getOneElementIgnoreStructure(key, imageObjectInterfaces);
            if (ref==null) {
                logger.error("IOI not found: ref: {} ({}), all IOI: {}", key, key.getKey(-1), imageObjectInterfaces.keySet());
                return null;
            }
            // create imageObjectInterface
            if (ref instanceof Kymograph) i = this.getImageTrackObjectInterface((ref).parents, structureIdx, type);
            else i = this.getImageObjectInterface(ref.parents.get(0), structureIdx, true);
            if (ref.getName().length()>0) {
                imageObjectInterfaces.remove(i.getKey());
                i.setName(ref.getName());
            }
            logger.debug("created IOI: {} from ref: {}", i.getKey(), ref);
            if (i instanceof HyperStack) registerHyperStack(image, (HyperStack) i);
        } 
        return i;
    }
    
    public void removeImage(Image image) {
        imageObjectInterfaceMap.remove(image);
        //removeClickListener(image);
    }
    public void removeImageObjectInterface(InteractiveImageKey key) {
        // ignore structure
        Iterator<Entry<InteractiveImageKey, InteractiveImage>> it = imageObjectInterfaces.entrySet().iterator();
        while(it.hasNext()) if (it.next().getKey().equalsIgnoreStructure(key)) it.remove();
        Iterator<Entry<Image, InteractiveImageKey>> it2 = imageObjectInterfaceMap.entrySet().iterator();
        while(it2.hasNext()) if (it2.next().getValue().equalsIgnoreStructure(key)) it2.remove();
    }
    
    public abstract void addMouseListener(Image image);
    public abstract void addWindowListener(I image, WindowListener wl);
    public void addWindowClosedListener(Image image, Function<WindowEvent, Void> closeFunction) {
        I im = displayer.getImage(image);
        if (im!=null) addWindowClosedListener(im, closeFunction);
    }
    public void addWindowClosedListener(I image, Function<WindowEvent, Void> closeFunction) {
        addWindowListener(image, new WindowListener() {
            @Override
            public void windowOpened(WindowEvent e) { }
            @Override
            public void windowClosing(WindowEvent e) {}
            @Override
            public void windowClosed(WindowEvent e) {
                closeFunction.apply(e);
            }
            @Override
            public void windowIconified(WindowEvent e) { }
            @Override
            public void windowDeiconified(WindowEvent e) { }
            @Override
            public void windowActivated(WindowEvent e) { }
            @Override
            public void windowDeactivated(WindowEvent e) { }
        });
    }
    /**
     * 
     * @param image
     * @return list of coordinates (x, y, z starting from 0) within the image, in voxel unit
     */
    protected abstract List<Point> getSelectedPointsOnImage(I image);
    /**
     * 
     * @param image
     * @return mapping of containing objects (parents) to relative (to the parent) coordinated of selected point 
     */
    public Map<SegmentedObject, List<Point>> getParentSelectedPointsMap(Image image, int parentStructureIdx) {
        I dispImage;
        if (image==null) {
            dispImage = displayer.getCurrentImage();
            if (dispImage==null) return null;
            image = displayer.getImage(dispImage);
        } else dispImage = displayer.getImage(image);
        if (dispImage==null) return null;
        InteractiveImage i = this.getImageObjectInterface(image, parentStructureIdx);
        if (i==null) return null;
        
        List<Point> rawCoordinates = getSelectedPointsOnImage(dispImage);
        HashMapGetCreate<SegmentedObject, List<Point>> map = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory<>());
        for (Point c : rawCoordinates) {
            Pair<SegmentedObject, BoundingBox> parent = i.getClickedObject(c.getIntPosition(0), c.getIntPosition(1), c.getIntPosition(2));
            if (parent!=null) {
                c.translateRev(parent.value);
                List<Point> children = map.getAndCreateIfNecessary(parent.key);
                children.add(c);
                GUI.logger.debug("adding point: {} to parent: {} located: {}", c, parent.key, parent.value);
            }
        }
        return map;
        
    }
    //public abstract void removeClickListener(Image image);
    
    public Pair<SegmentedObject, BoundingBox> getClickedObject(Image image, int x, int y, int z) {
        InteractiveImage i = getImageObjectInterface(image);
        if (i!=null) {
            return i.getClickedObject(x, y, z);
        } else GUI.logger.warn("image: {} is not registered for click");
        return null;
    }

    public void displayAllObjects(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) {
                GUI.logger.debug("no active image");
                return;
            }
        }
        InteractiveImage i =  getImageObjectInterface(image, interactiveStructureIdx);
        if (i==null) {
            GUI.logger.info("no image object interface found for image: {} and structure: {}", image.getName(), interactiveStructureIdx);
            return;
        }
        if (i instanceof HyperStack) {
            HyperStack k = ((HyperStack)i);
            Image im = image;
            k.setChangeIdxCallback(idx -> {
                hideAllRois(im, true, false);
                displayObjects(im, k.getObjects(), defaultRoiColor, true, false);
            });
        }
        displayObjects(image, i.getObjects(), defaultRoiColor, true, false);
        if (listener!=null) listener.fireObjectSelected(Pair.unpairKeys(i.getObjects()), true);
    }
    
    public void displayAllTracks(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return;
        }
        InteractiveImage i =  getImageObjectInterface(image, interactiveStructureIdx);
        if (i==null) {
            GUI.logger.info("no image object interface found for image: {} and object: {}", image.getName(), interactiveStructureIdx);
            return;
        }
        if (i instanceof HyperStack) {
            HyperStack k = ((HyperStack)i);
            Image im = image;
            IntConsumer callback = idx -> {
                // a sub-track @ current frame is considered
                List<List<SegmentedObject>> selTracks = k.getObjects().stream().map(o -> new ArrayList<SegmentedObject>(1){{add(o.key);}}).collect(Collectors.toList());
                hideAllRois(im, true, false);
                // set palette
                Set<SegmentedObject> th = selTracks.stream().map(l -> l.get(0).getTrackHead()).collect(Collectors.toSet());
                th.removeAll(trackColor.keySet());
                List<SegmentedObject> thList = new ArrayList<>(th);
                double start = new Random().nextDouble()*Palette.increment;
                double inc = (1d - start)/thList.size();
                IntStream.range(0, th.size()).forEach(j -> trackColor.put(thList.get(j), Palette.getColorFromDouble(start + j * inc)));
                if (!selTracks.isEmpty()) displayTracks(im, k, selTracks, true);
            };
            k.setChangeIdxCallback(callback);
            callback.accept(k.getIdx());
        } else {
            Collection<SegmentedObject> objects = Pair.unpairKeys(i.getObjects());
            Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(objects, !(i instanceof HyperStack));
            //for (Entry<StructureObject, List<StructureObject>> e : allTracks.entrySet()) logger.debug("th:{}->{}", e.getKey(), e.getValue());
            displayTracks(image, i, allTracks.values(), true);
            //if (listener!=null)
        }
    }
    
    
    public abstract void displayObject(I image, U roi);
    public abstract void hideObject(I image, U roi);
    protected abstract U generateObjectRoi(Pair<SegmentedObject, BoundingBox> object, Color color, int frameIdx);
    protected abstract void setRoiAttributes(U roi, Color color, int frameIdx);
    public void setRoiModifier(RoiModifier<U> modifier) {this.roiModifier=modifier;}
    RoiModifier<U> roiModifier;
    public static interface RoiModifier<U> {
        public void modifyRoi(Pair<SegmentedObject, BoundingBox> currentObject, U currentRoi, Collection<Pair<SegmentedObject, BoundingBox>> objectsToDisplay);
    }
    public void displayObjects(Image image, Collection<Pair<SegmentedObject, BoundingBox>> objectsToDisplay, Color color, boolean labileObjects, boolean hideIfAlreadyDisplayed) {
        if (objectsToDisplay.isEmpty() || (objectsToDisplay.iterator().next()==null)) return;
        if (color==null) color = ImageWindowManager.defaultRoiColor;
        I dispImage;
        if (image==null) {
            dispImage = displayer.getCurrentImage();
            if (dispImage==null) return;
            image = displayer.getImage(dispImage);
        }
        else dispImage = displayer.getImage(image);
        if (dispImage==null || image==null) return;
        Set<U> labiles = labileObjects ? this.displayedLabileObjectRois.getAndCreateIfNecessary(image) : null;
        Map<Pair<SegmentedObject, BoundingBox>, U> map = labileObjects ? this.labileObjectRoiMap : objectRoiMap;
        long t0 = System.currentTimeMillis();
        ToIntFunction<SegmentedObject> getFrame = o -> 1;
        InteractiveImage i =  getImageObjectInterface(image, objectsToDisplay.iterator().next().key.getStructureIdx());
        if (i!=null) {
            if (i instanceof HyperStack) {
                Map<Integer, Integer> frameMapIdx = IntStream.range(0, i.getParents().size()).boxed().collect(Collectors.toMap(idx->i.getParents().get(idx).getFrame(), idx->idx));
                getFrame = o -> {
                    Integer frameIdx = frameMapIdx.get(o.getFrame());
                    if (frameIdx!=null) return frameIdx;
                    return -1;
                };
            }
        }
        for (Pair<SegmentedObject, BoundingBox> p : objectsToDisplay) {
            if (p==null || p.key==null) continue;
            int frame = getFrame.applyAsInt(p.key);
            if (frame<0) continue;
            //logger.debug("getting mask of object: {}", o);
            U roi=map.get(p);
            if (roi==null) {
                roi = generateObjectRoi(p, color, frame);
                map.put(p, roi);
                //if (!labileObjects) logger.debug("add non labile object: {}, found by keyonly? {}", p.key, map.containsKey(new Pair(p.key, null)));
            }
            if (roiModifier!=null) roiModifier.modifyRoi(p, roi, objectsToDisplay);
            if (labileObjects) {
                if (labiles.contains(roi)) {
                    if (hideIfAlreadyDisplayed) {
                        hideObject(dispImage, roi);
                        labiles.remove(roi);
                        //logger.debug("display -> inverse state: hide: {}", p.key);
                        /*Object attr = new HashMap<String, Object>(0);
                        try {
                            Field attributes = SegmentedObject.class.getDeclaredField("attributes");
                            attributes.setAccessible(true);
                            attr = attributes.get(p.key);
                        } catch (Exception e) {}
                        logger.debug("isTH: {}, values: {}, attributes: {}", p.key.isTrackHead(), p.key.getMeasurements().getKeys(), attr);
                        */
                    }
                } else {
                    setRoiAttributes(roi, color, frame);
                    displayObject(dispImage, roi);
                    labiles.add(roi);
                }
            } else {

                setRoiAttributes(roi, color, frame);
                displayObject(dispImage, roi);
            }
        }
        long t1 = System.currentTimeMillis();
        displayer.updateImageRoiDisplay(image);
        long t2 = System.currentTimeMillis();
        //logger.debug("display {} objects: create roi & add to overlay: {}, update display: {}", objectsToDisplay.size(), t1-t0, t2-t1);
    }
    
    public void hideObjects(Image image, Collection<Pair<SegmentedObject, BoundingBox>> objects, boolean labileObjects) {
        I dispImage;
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return;
        } 
        dispImage = getDisplayer().getImage(image);
        if (dispImage==null) return;
        Set<U> selectedObjects = labileObjects ? this.displayedLabileObjectRois.get(image) : null;
        Map<Pair<SegmentedObject, BoundingBox>, U> map = labileObjects ? labileObjectRoiMap : objectRoiMap;
        for (Pair<SegmentedObject, ?> p : objects) {
            //logger.debug("hiding: {}", p.key);
            U roi=map.get(p);
            if (roi!=null) {
                hideObject(dispImage, roi);
                if (selectedObjects!=null) selectedObjects.remove(roi);
            }
            //logger.debug("hide object: {} found? {}", p.key, roi!=null);
        }
        displayer.updateImageRoiDisplay(image);
    }

    public void displayLabileObjects(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return;
        }
        Set<U> rois = this.displayedLabileObjectRois.get(image);
        if (rois!=null) {
            I dispImage = displayer.getImage(image);
            if (dispImage==null) return;
            for (U roi: rois) displayObject(dispImage, roi);
            displayer.updateImageRoiDisplay(image);
        }
        //if (listener!=null) listener.fireObjectSelected(Pair.unpair(getLabileObjects(image)), true);
    }

    
    public void hideLabileObjects(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return;
        }
        Set<U> rois = this.displayedLabileObjectRois.remove(image);
        if (rois!=null) {
            I dispImage = displayer.getImage(image);
            if (dispImage==null) return;
            for (U roi: rois) hideObject(dispImage, roi);
            displayer.updateImageRoiDisplay(image);
            //if (listener!=null) listener.fireObjectDeselected(Pair.unpair(getLabileObjects(image)));
        }
    }
    
    public List<SegmentedObject> getSelectedLabileObjects(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return Collections.emptyList();
        }
        Set<U> rois = displayedLabileObjectRois.get(image);
        if (rois!=null) {
            List<Pair<SegmentedObject, BoundingBox>> pairs = Utils.getKeys(labileObjectRoiMap, rois);
            List<SegmentedObject> res = Pair.unpairKeys(pairs);
            Utils.removeDuplicates(res, false);
            return res;
        } else return Collections.emptyList();
    }

    public List<SegmentedObject> getSelectedLabileObjectsOrTracks(Image image) {
        if (displayTrackMode) {
            List<SegmentedObject> th = getSelectedLabileTrackHeads(image);
            logger.debug("number of labile th: {} -> {}", th.size(), th);
            return th.stream().flatMap(t -> SegmentedObjectUtils.getTrack(t).stream()).collect(Collectors.toList());
        } else return getSelectedLabileObjects(image);
    }
    /// track-related methods
    
    protected abstract void displayTrack(I image, V roi, InteractiveImage i);
    protected abstract void hideTrack(I image, V roi, InteractiveImage i);
    protected abstract V generateTrackRoi(List<SegmentedObject> parentTrack, List<Pair<SegmentedObject, BoundingBox>> track, Color color, InteractiveImage i);
    protected abstract void setTrackColor(V roi, Color color);

    public void displayTracks(Image image, InteractiveImage i, Collection<List<SegmentedObject>> tracks, boolean labile) {
        if (image==null) {
            image = displayer.getCurrentImage2();
            if (image==null) return;
        }
        if (i ==null) {
            i = this.getImageObjectInterface(image);
            
        }
        //logger.debug("display {} tracks on image: {}, OI: {}", tracks.size(), image.getName(), i.getClass().getSimpleName());
        boolean hyperStack = i instanceof HyperStack;
        for (List<SegmentedObject> track : tracks) {
            displayTrack(image, i, i.pairWithOffset(track), hyperStack? getColor(track.get(0)) : getColor() , labile, false);
        }
        if (hyperStack) {
            int minFrame = tracks.stream().filter(track -> !track.isEmpty()).mapToInt(track -> track.stream().filter(SegmentedObject::isTrackHead).findFirst().orElse(track.size()>1 ? track.get(1).getTrackHead() : track.get(0)).getFrame()).min().orElse(-1);
            int maxFrame = tracks.stream().filter(track -> !track.isEmpty()).mapToInt(track -> track.get(track.size() - 1).getFrame()).max().orElse(-1);
            if (minFrame > -1) {
                int curFrame = ((HyperStack)i).idxMapFrame.get(displayer.getFrame(image));
                if (curFrame < minFrame || curFrame > maxFrame) displayer.setFrame(minFrame, image);
            }
        }
        displayer.updateImageRoiDisplay(image);
        //GUI.updateRoiDisplayForSelections(image, i);
    }
    public void displayTrack(Image image, InteractiveImage i, List<Pair<SegmentedObject, BoundingBox>> track, Color color, boolean labile) {
        displayTrack(image, i, track, color, labile, true);
    }

    protected void displayTrack(Image image, InteractiveImage i, List<Pair<SegmentedObject, BoundingBox>> track, Color color, boolean labile, boolean updateDisplay) {
        //logger.debug("display selected track: image: {}, track length: {} color: {}", image, track==null?"null":track.size(), color);
        if (track==null || track.isEmpty()) return;
        I dispImage;
        if (image==null) {
            dispImage = getDisplayer().getCurrentImage();
            if (dispImage==null) return;
            image = getDisplayer().getImage(dispImage);
        } else dispImage = getDisplayer().getImage(image);
        if (dispImage==null || image==null) return;
        if (i==null) {
            i=this.getImageObjectInterface(image);
            //logger.debug("image: {}, OI: {}", image.getName(), i.getClass().getSimpleName());
            if (i==null) return;
        }
        InteractiveImageKey.TYPE type = i.getKey().imageType;
        boolean hyperStack = i instanceof HyperStack;
        SegmentedObject trackHead = track.get(track.size()>1 ? 1 : 0).key.getTrackHead(); // idx = 1 because track might begin with previous object
        boolean canDisplayTrack = i instanceof Kymograph;
        //canDisplayTrack = canDisplayTrack && ((TrackMask)i).parent.getTrackHead().equals(trackHead.getParent().getTrackHead()); // same track head
        //canDisplayTrack = canDisplayTrack && i.getParent().getStructureIdx()<=trackHead.getStructureIdx();
        if (canDisplayTrack) {
            Kymograph tm = (Kymograph)i;
            tm.trimTrack(track);
            canDisplayTrack = !track.isEmpty();
        }
        Map<Pair<SegmentedObject, SegmentedObject>, V> map = labile ? (hyperStack ? labileParentTrackHeadTrackRoiMap : labileParentTrackHeadKymographTrackRoiMap  ) : (hyperStack ? parentTrackHeadTrackRoiMap : parentTrackHeadKymographTrackRoiMap ) ;
        boolean doNotStore = hyperStack && track.size()==1; // partial tracks: do not store //&& (!trackHead.equals(track.get(0).key) || trackHead.getNextId()!=null)
        if (canDisplayTrack) {
            if (i.getKey().interactiveObjectClass != trackHead.getStructureIdx()) { // change current object class
                i = getImageTrackObjectInterface(i.parents, trackHead.getStructureIdx(), type);
                map.clear();
            }
            if (i.getParent()==null) logger.error("Track mask parent null!!!");
            else if (i.getParent().getTrackHead()==null) logger.error("Track mask parent trackHead null!!!");
            Pair<SegmentedObject, SegmentedObject> key = new Pair<>(i.getParent().getTrackHead(), trackHead);
            Set<V>  disp = null;
            if (labile) disp = displayedLabileTrackRois.getAndCreateIfNecessary(image);
            V roi = doNotStore ? null:map.get(key);
            if (roi==null) {
                roi = generateTrackRoi(i.parents,track, color, i);
                map.put(key, roi);
            } else setTrackColor(roi, color);
            if (disp==null || !disp.contains(roi)) displayTrack(dispImage, roi ,i);
            if (disp!=null) disp.add(roi);
            if (updateDisplay) {
                displayer.updateImageRoiDisplay(image);
                if (hyperStack) {
                    int minFrame = trackHead.getFrame();
                    int maxFrame = track.get(track.size() - 1).key.getFrame();
                    int curFrame = ((HyperStack)i).idxMapFrame.get(displayer.getFrame(image));
                    if (curFrame < minFrame || curFrame > maxFrame) displayer.setFrame(minFrame, image);
                }
            }

        } else GUI.logger.warn("image cannot display selected track: ImageObjectInterface null? {}, is Track? {}", i==null, i instanceof Kymograph);
    }
    
    public void hideTracks(Image image, InteractiveImage i, Collection<SegmentedObject> trackHeads, boolean labile) {
        I dispImage;
        if (image==null) {
            dispImage = getDisplayer().getCurrentImage();
            if (dispImage==null) return;
            image = getDisplayer().getImage(dispImage);
        } else dispImage = getDisplayer().getImage(image);
        if (dispImage==null || image==null) return;
        if (i==null) {
            i=this.getImageObjectInterface(image);
            if (i==null) return;
        }
        Set<V> disp = this.displayedLabileTrackRois.get(image);
        SegmentedObject parentTrackHead = i.getParent().getTrackHead();
        Map<Pair<SegmentedObject, SegmentedObject>, V> map = labile ? ((i instanceof HyperStack) ? labileParentTrackHeadTrackRoiMap : labileParentTrackHeadKymographTrackRoiMap ) : ((i instanceof HyperStack) ? parentTrackHeadTrackRoiMap : parentTrackHeadKymographTrackRoiMap ) ;
        for (SegmentedObject th : trackHeads) {
            V roi=map.get(new Pair(parentTrackHead, th));
            if (roi!=null) {
                hideTrack(dispImage, roi ,i);
                if (disp!=null) disp.remove(roi);
            }
        }
        //GUI.updateRoiDisplayForSelections(image, i);
        displayer.updateImageRoiDisplay(image);
        
    }
    
    protected abstract void hideAllRois(I image);
    public void hideAllRois(Image image, boolean labile, boolean nonLabile) {
        if (!labile && !nonLabile) return;
        I im = getDisplayer().getImage(image);
        if (im !=null) hideAllRois(im);
        if (!labile) {
            displayLabileObjects(image);
            displayLabileTracks(image);
        } else {
            displayedLabileTrackRois.remove(image);
            displayedLabileObjectRois.remove(image);
        }
        if (!nonLabile) {
            GUI.updateRoiDisplayForSelections(image, null);
        }
    }
    public void displayLabileTracks(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return;
        }
        Set<V> tracks = this.displayedLabileTrackRois.get(image);
        if (tracks!=null) {
            InteractiveImage i=this.getImageObjectInterface(image);
            I dispImage = displayer.getImage(image);
            if (dispImage==null) return;
            for (V roi: tracks) displayTrack(dispImage, roi, i);
            displayer.updateImageRoiDisplay(image);
        }
    }
    public void hideLabileTracks(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return;
        }
        Set<V> tracks = this.displayedLabileTrackRois.remove(image);
        if (tracks!=null) {
            I dispImage = displayer.getImage(image);
            if (dispImage==null) return;
            InteractiveImage i=this.getImageObjectInterface(image);
            for (V roi: tracks) hideTrack(dispImage, roi, i);
            displayer.updateImageRoiDisplay(image);
            //if (listener!=null) listener.fireTracksDeselected(getLabileTrackHeads(image));
        }
    }
    
    public void displayTrackAllImages(InteractiveImage i, boolean addToCurrentSelectedTracks, List<Pair<SegmentedObject, BoundingBox>> track, Color color, boolean labile) {
        if (i==null && track!=null && !track.isEmpty()) i = this.getImageObjectInterface(track.get(0).key.getTrackHead(), track.get(0).key.getStructureIdx(), false);
        if (i==null) return;
        ArrayList<Image> images= Utils.getKeys(this.imageObjectInterfaceMap, i.getKey().getKey(-1));
        //logger.debug("display track on {} images", images.size());
        for (Image image : images) {
            if (!addToCurrentSelectedTracks) hideAllRois(image, true, true); // TODO only tracks?
            displayTrack(image, i, track, color, labile);
        }
    }
    
    public List<SegmentedObject> getSelectedLabileTrackHeads(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return Collections.emptyList();
        }
        Set<V> rois = this.displayedLabileTrackRois.get(image);
        if (rois!=null) {
            InteractiveImage i = getImageObjectInterface(image);
            if (i instanceof HyperStack) { // tracks are not stored

            }
            List<Pair<SegmentedObject, SegmentedObject>> pairs = Utils.getKeys(i instanceof HyperStack ? labileParentTrackHeadTrackRoiMap : labileParentTrackHeadKymographTrackRoiMap, rois);
            List<SegmentedObject> res = unpairValues(pairs);
            Utils.removeDuplicates(res, false);
            return res;
        } else return Collections.emptyList();
    }
    
    public void removeObjects(Collection<SegmentedObject> objects, boolean removeTrack) {
        for (Image image : this.displayedLabileObjectRois.keySet()) {
            InteractiveImage i = this.getImageObjectInterface(image);
            if (i!=null) hideObjects(image, i.pairWithOffset(objects), true);
        }
        for (SegmentedObject object : objects) {
            Pair k = new Pair(object, null);
            Utils.removeFromMap(objectRoiMap, k);
            Utils.removeFromMap(labileObjectRoiMap, k);
        }
        if (removeTrack) removeTracks(SegmentedObjectUtils.getTrackHeads(objects));
    }
    
    public void removeTracks(Collection<SegmentedObject> trackHeads) {
        for (Image image : this.displayedLabileTrackRois.keySet()) hideTracks(image, null, trackHeads, true);
        for (SegmentedObject trackHead : trackHeads) {
            Pair k = new Pair(null, trackHead);
            Utils.removeFromMap(labileParentTrackHeadTrackRoiMap, k);
            Utils.removeFromMap(labileParentTrackHeadKymographTrackRoiMap, k);
            Utils.removeFromMap(parentTrackHeadTrackRoiMap, k);
            Utils.removeFromMap(parentTrackHeadKymographTrackRoiMap, k);
        }
    }
    
    public void resetObjectsAndTracksRoi() {
        for (Image image : imageObjectInterfaceMap.keySet()) hideAllRois(image, true, true);
        objectRoiMap.clear();
        labileObjectRoiMap.clear();
        labileParentTrackHeadTrackRoiMap.clear();
        labileParentTrackHeadKymographTrackRoiMap.clear();
        parentTrackHeadTrackRoiMap.clear();
        parentTrackHeadKymographTrackRoiMap.clear();
        trackColor.clear();
    }
    
    public void goToNextTrackError(Image trackImage, List<SegmentedObject> trackHeads, boolean next) {
        //ImageObjectInterface i = imageObjectInterfaces.get(new ImageObjectInterfaceKey(rois.get(0).getParent().getTrackHead(), rois.get(0).getStructureIdx(), true));
        if (trackImage==null) {
            I selectedImage = displayer.getCurrentImage();
            trackImage = displayer.getImage(selectedImage);
            if (trackImage==null) return;
        }
        InteractiveImage i = this.getImageObjectInterface(trackImage);
        if (i==null || i instanceof SimpleInteractiveImage) {
            GUI.logger.warn("selected image is not a track image");
            return;
        }
        Kymograph tm = (Kymograph)i;
        if (trackHeads==null || trackHeads.isEmpty()) trackHeads = this.getSelectedLabileTrackHeads(trackImage);
        if (trackHeads==null || trackHeads.isEmpty()) {
            List<SegmentedObject> allObjects = Pair.unpairKeys(i.getObjects());
            trackHeads = new ArrayList<>(SegmentedObjectUtils.getTrackHeads(allObjects));
        }
        if (trackHeads==null || trackHeads.isEmpty()) return;
        Collections.sort(trackHeads);
        BoundingBox currentDisplayRange = this.displayer.getDisplayRange(trackImage);
        int minTimePoint = tm.getClosestFrame(currentDisplayRange.xMin(), currentDisplayRange.yMin());
        int maxTimePoint = tm.getClosestFrame(currentDisplayRange.xMax(), currentDisplayRange.yMax());
        if (next) {
            if (maxTimePoint == i.getParents().get(i.getParents().size()-1).getFrame()) return;
            if (maxTimePoint>minTimePoint+2) maxTimePoint-=2;
            else maxTimePoint--;
        } else {
            if (minTimePoint == i.getParents().get(0).getFrame()) return;
            if (maxTimePoint>minTimePoint+2) minTimePoint+=2;
            else minTimePoint++;
        }
        //logger.debug("Current Display range: {}, maxTimePoint: {}, number of selected rois: {}", currentDisplayRange, maxTimePoint, rois.size());

        SegmentedObject nextError = next ? getNextError(maxTimePoint, trackHeads) : getPreviousError(minTimePoint, trackHeads);
        if (nextError==null) GUI.logger.info("No errors detected {} timepoint: {}", next? "after": "before", maxTimePoint);
        else {
            if (i instanceof HyperStack) {
                ((HyperStack)i).setFrame(nextError.getFrame());
            } else {
                BoundingBox off = tm.getObjectOffset(nextError);
                if (off == null) trackHeads = new ArrayList<>(trackHeads);
                while (off == null) {
                    trackHeads.remove(nextError);
                    nextError = getNextObject(nextError.getFrame(), trackHeads, next);
                    if (nextError == null) return;
                    off = tm.getObjectOffset(nextError);
                }
                int midX = (off.xMin() + off.xMax()) / 2;
                if (midX + currentDisplayRange.sizeX() / 2 >= trackImage.sizeX())
                    midX = trackImage.sizeX() - currentDisplayRange.sizeX() / 2;
                if (midX - currentDisplayRange.sizeX() / 2 < 0) midX = currentDisplayRange.sizeX() / 2;

                int midY = (off.yMin() + off.yMax()) / 2;
                if (midY + currentDisplayRange.sizeY() / 2 >= trackImage.sizeY())
                    midY = trackImage.sizeY() - currentDisplayRange.sizeY() / 2;
                if (midY - currentDisplayRange.sizeY() / 2 < 0) midY = currentDisplayRange.sizeY() / 2;

                SimpleBoundingBox nextDisplayRange = new SimpleBoundingBox(midX - currentDisplayRange.sizeX() / 2, midX + currentDisplayRange.sizeX() / 2, midY - currentDisplayRange.sizeY() / 2, midY + currentDisplayRange.sizeY() / 2, currentDisplayRange.zMin(), currentDisplayRange.zMax());
                GUI.logger.info("Error detected @ timepoint: {}, xMid: {}, update display range: {}", nextError.getFrame(), midX, nextDisplayRange);
                displayer.setDisplayRange(nextDisplayRange, trackImage);
            }
        }
    }
    /**
     * Center this image on the objects at next (or previous, if {@param next} is false) undiplayed frames
     * @param trackImage
     * @param objects
     * @param next 
     * @return true if display has changed
     */
    public boolean goToNextObject(Image trackImage, List<SegmentedObject> objects, boolean next) {
        if (trackImage==null) {
            I selectedImage = displayer.getCurrentImage();
            trackImage = displayer.getImage(selectedImage);
        }
        InteractiveImage i = this.getImageObjectInterface(trackImage);
        if (i instanceof SimpleInteractiveImage) {
            GUI.logger.warn("selected image is not a track image");
            return false;
        }
        Kymograph tm = (Kymograph)i;
        if (objects==null || objects.isEmpty()) objects = this.getSelectedLabileObjects(trackImage);
        if (objects==null || objects.isEmpty()) objects = Pair.unpairKeys(i.getObjects());
        if (objects==null || objects.isEmpty()) return false;
        int minTimePoint, maxTimePoint;
        BoundingBox currentDisplayRange;
        if (tm instanceof HyperStack) {
            minTimePoint = ((HyperStack)tm).getFrame();
            maxTimePoint = minTimePoint;
            currentDisplayRange = null;
        } else {
            currentDisplayRange = this.displayer.getDisplayRange(trackImage);
            minTimePoint = tm.getClosestFrame(currentDisplayRange.xMin(), currentDisplayRange.yMin());
            maxTimePoint = tm.getClosestFrame(currentDisplayRange.xMax(), currentDisplayRange.yMax());
            if (next) {
                if (maxTimePoint == i.getParents().get(i.getParents().size() - 1).getFrame()) return false;
                if (maxTimePoint > minTimePoint + 2) maxTimePoint -= 2;
                else maxTimePoint--;
            } else {
                if (minTimePoint == i.getParents().get(0).getFrame()) return false;
                if (maxTimePoint > minTimePoint + 2) minTimePoint += 2;
                else minTimePoint++;
            }
        }
        GUI.logger.debug("Current Display range: maxTimePoint: {}, minTimePoint: {}, number of objects: {}", maxTimePoint, minTimePoint, objects.size());
        Collections.sort(objects, SegmentedObjectUtils.frameComparator()); // sort by frame
        SegmentedObject nextObject = getNextObject(next? maxTimePoint+1: minTimePoint-1, objects, next);
        if (nextObject==null) {
            logger.info("No object detected {} timepoint: {}", next? "after" : "before", maxTimePoint);
            return false;
        } else {
            if (i instanceof HyperStack) {
                logger.debug("Hyperstack navigate to frame: {}", nextObject.getFrame());
                boolean ok = ((HyperStack)i).setFrame(nextObject.getFrame());
                if (ok) displayer.setFrame(((HyperStack)i).getIdx(), trackImage);
                return ok;
            } else {
                BoundingBox off = tm.getObjectOffset(nextObject);
                if (off == null) objects = new ArrayList<>(objects);
                while (off == null) {
                    objects = new ArrayList<>(objects);
                    objects.remove(nextObject);
                    nextObject = getNextObject(nextObject.getFrame(), objects, next);
                    if (nextObject == null) {
                        logger.info("No object detected {} timepoint: {}", next ? "after" : "before", maxTimePoint);
                        return false;
                    }
                    off = tm.getObjectOffset(nextObject);
                }
                int midX = (off.xMin() + off.xMax()) / 2;
                if (midX + currentDisplayRange.sizeX() / 2 >= trackImage.sizeX())
                    midX = trackImage.sizeX() - currentDisplayRange.sizeX() / 2 - 1;
                if (midX - currentDisplayRange.sizeX() / 2 < 0) midX = currentDisplayRange.sizeX() / 2;

                int midY = (off.yMin() + off.yMax()) / 2;
                if (midY + currentDisplayRange.sizeY() / 2 >= trackImage.sizeY())
                    midY = trackImage.sizeY() - currentDisplayRange.sizeY() / 2 - 1;
                if (midY - currentDisplayRange.sizeY() / 2 < 0) midY = currentDisplayRange.sizeY() / 2;

                MutableBoundingBox nextDisplayRange = new MutableBoundingBox(midX - currentDisplayRange.sizeX() / 2, midX + currentDisplayRange.sizeX() / 2, midY - currentDisplayRange.sizeY() / 2, midY + currentDisplayRange.sizeY() / 2, currentDisplayRange.zMin(), currentDisplayRange.zMax());
                if (!nextDisplayRange.equals(currentDisplayRange)) {
                    GUI.logger.info("Object detected @ timepoint: {}, xMid: {}, update display range: {} (current was: {}", nextObject.getFrame(), midX, nextDisplayRange, currentDisplayRange);
                    displayer.setDisplayRange(nextDisplayRange, trackImage);
                    return true;
                }
                return false;
            }
        }
    }
    
    private static SegmentedObject getNextObject(int timePointLimit, List<SegmentedObject> objects, boolean next) {
        if (objects.isEmpty()) return null;

        int idx = Collections.binarySearch(objects, getAccessor().createRoot(timePointLimit, null, null), SegmentedObjectUtils.frameComparator());
        if (idx>=0) return objects.get(idx);
        int insertionPoint = -idx-1;
        if (next) {
            if (insertionPoint<objects.size()) return objects.get(insertionPoint);
        } else {
            if (insertionPoint>0) return objects.get(insertionPoint-1);
        }
        return null;
    }
    
    private static SegmentedObject getNextError(int maxTimePoint, List<SegmentedObject> tracksHeads) {
        if (tracksHeads.isEmpty()) return null;
        SegmentedObject[] trackArray = tracksHeads.toArray(new SegmentedObject[tracksHeads.size()]);
        boolean change = true;
        boolean remainTrack = true;
        int currentTimePoint = maxTimePoint;
        while(remainTrack) {
            change = false;
            remainTrack= false;
            for (int trackIdx = 0; trackIdx<trackArray.length; ++trackIdx) {
                if (trackArray[trackIdx]!=null) {
                    remainTrack=true;
                    if (trackArray[trackIdx].getFrame()<currentTimePoint) {
                        trackArray[trackIdx]=trackArray[trackIdx].getNext(); 
                        change=true;
                    }
                    if (trackArray[trackIdx]!=null && trackArray[trackIdx].getFrame()==currentTimePoint && trackArray[trackIdx].hasTrackLinkError(true, true)) return trackArray[trackIdx];
                }
            }
            if (!change) ++currentTimePoint;
        }
        
        return null;
    }
    private static SegmentedObject getPreviousError(int minTimePoint, List<SegmentedObject> trackHeads) {
        if (trackHeads.isEmpty()) return null;
        SegmentedObject[] trackArray = trackHeads.toArray(new SegmentedObject[trackHeads.size()]);
        // get all rois to maximal value < errorTimePoint
        for (int trackIdx = 0; trackIdx<trackArray.length; ++trackIdx) {
            if (trackArray[trackIdx].getFrame()>=minTimePoint) trackArray[trackIdx] = null;
            else while (trackArray[trackIdx].getNext()!=null && trackArray[trackIdx].getFrame()<minTimePoint-1) trackArray[trackIdx] = trackArray[trackIdx].getNext();
        }
        
        boolean change = true;
        boolean remainTrack = true;
        int currentTimePoint = minTimePoint-1;
        while(remainTrack) {
            change = false;
            remainTrack= false;
            for (int trackIdx = 0; trackIdx<trackArray.length; ++trackIdx) {
                if (trackArray[trackIdx]!=null) {
                    remainTrack=true;
                    if (trackArray[trackIdx].getFrame()>currentTimePoint) {
                        trackArray[trackIdx]=trackArray[trackIdx].getPrevious();
                        change=true;
                    }
                    if (trackArray[trackIdx]!=null && trackArray[trackIdx].getFrame()==currentTimePoint && trackArray[trackIdx].hasTrackLinkError(true, true)) return trackArray[trackIdx];
                }
            }
            if (!change) --currentTimePoint;
        }
        
        return null;
    }
    // menu section
    
    protected Map<Image, Collection<TestDataStore>> testData = new HashMap<>();
    public void addTestData(Image image, Collection<TestDataStore> testData) {
        this.testData.put(image, testData);
    }
    protected JPopupMenu getMenu(Image image) {
        final List<SegmentedObject> sel = getSelectedLabileObjects(image);
        if (testData.containsKey(image)) { // test menu
            Collection<TestDataStore> stores = testData.get(image);
            SegmentedObject o = sel.isEmpty() ? null : sel.get(0); // only first selected object
            Predicate<TestDataStore> storeWithinSel = s-> o == null || s.getParent().equals(o.getParent(s.getParent().getStructureIdx()));
            Set<String> commands = stores.stream().filter(storeWithinSel).map(TestDataStore::getMiscCommands).flatMap(Set::stream).distinct().sorted().collect(Collectors.toSet());
            JPopupMenu menu = getMenu(o);
            if (!commands.isEmpty()) {
                menu.addSeparator();
                commands.forEach(s-> {
                    JMenuItem item = new JMenuItem(s);
                    menu.add(item);
                    item.setAction(new AbstractActionImpl(item.getActionCommand(), stores, storeWithinSel, new ArrayList(sel)));
                });
            }
            return menu;
        } else { // regular menu
            if (sel.isEmpty()) return null;
            else if (sel.size()==1) return getMenu(sel.get(0));
            else {
                Collections.sort(sel);
                return getMenu(sel.subList(0, Math.min(50, sel.size())));
            }
        }
    }

    private JPopupMenu getMenu(SegmentedObject o) {
        JPopupMenu menu = new JPopupMenu();
        if (o==null) return menu;
        menu.add(new JMenuItem("<html><b>Object Attributes</b></html>"));
        menu.add(new JMenuItem(o.toString()));
        menu.add(new JMenuItem("Prev:"+o.getPrevious()));
        menu.add(new JMenuItem("Next:"+o.getNext()));
        menu.add(new JMenuItem("TrackHead:"+o.getTrackHead()));
        menu.add(new JMenuItem("Time: "+toString(o.getMeasurements().getCalibratedTimePoint())));
        menu.add(new JMenuItem("IsTrackHead: "+o.isTrackHead()));
        menu.add(new JMenuItem("Is2D: "+o.getRegion().is2D()));
        //DecimalFormat df = new DecimalFormat("#.####");
        if (!o.getAttributeKeys().isEmpty()) {
            menu.addSeparator();
            menu.add(new JMenuItem("<html><b>Other Attributes</b></html>"));
            for (String k : new TreeSet<>(o.getAttributeKeys())) {
                JMenuItem item = new JMenuItem(truncate(k, TRUNC_LENGTH)+": "+truncate(toString(o.getAttribute(k)), TRUNC_LENGTH));
                menu.add(item);
                item.setAction(new AbstractAction(item.getActionCommand()) {
                    @Override
                        public void actionPerformed(ActionEvent ae) {
                            Object v = o.getAttribute(k);
                            java.awt.datatransfer.Transferable stringSelection = new StringSelection(v.getClass().isArray() ? ArrayUtil.toString(v) : v.toString());
                            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                            clipboard.setContents(stringSelection, null);
                        }
                });
            }
        }

        menu.addSeparator();
        menu.add(new JMenuItem("<html><b>Measurements</b></html>"));
        for (String key  : new TreeSet<>(o.getMeasurements().getKeys())) {
            JMenuItem item = new JMenuItem(truncate(key, TRUNC_LENGTH)+": "+truncate(toString(o.getMeasurements().getValue(key)), TRUNC_LENGTH));
            menu.add(item);
            item.setAction(new AbstractAction(item.getActionCommand()) {
                @Override
                    public void actionPerformed(ActionEvent ae) {
                        Object v = o.getMeasurements().getValue(key);
                        java.awt.datatransfer.Transferable stringSelection = new StringSelection(v.getClass().isArray() ? ArrayUtil.toString(v) : v.toString());
                        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                        clipboard.setContents(stringSelection, null);
                    }
            });
        }
        
        return menu;
    }
    private JPopupMenu getMenu(List<SegmentedObject> list) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(new JMenuItem(Utils.toStringList(list)));
        menu.add(new JMenuItem("Prev:"+Utils.toStringList(list, o->o.getPrevious()==null?"NA":o.getPrevious().toString())));
        menu.add(new JMenuItem("Next:"+Utils.toStringList(list, o->o.getNext()==null?"NA":o.getNext().toString())));
        List<String> thList = Utils.transform(list, o->o.getTrackHead()==null?"NA":o.getTrackHead().toString());
        replaceRepetedValues(thList);
        menu.add(new JMenuItem("TrackHead:"+Utils.toStringList(thList)));
        //DecimalFormat df = new DecimalFormat("#.####E0");
        // getAllAttributeKeys
        Collection<String> attributeKeys = new HashSet();
        Collection<String> mesKeys = new HashSet();
        for (SegmentedObject o : list) {
            if (!o.getAttributeKeys().isEmpty()) attributeKeys.addAll(o.getAttributeKeys());
            mesKeys.addAll(o.getMeasurements().getKeys());
        }
        attributeKeys=new ArrayList(attributeKeys);
        Collections.sort((List)attributeKeys);
        mesKeys=new ArrayList(mesKeys);
        Collections.sort((List)mesKeys);
        
        if (!attributeKeys.isEmpty()) {
            menu.addSeparator();
            for (String s : attributeKeys) {
                List<Object> values = new ArrayList(list.size());
                for (SegmentedObject o : list) values.add(o.getAttribute(s));
                replaceRepetedValues(values);
                menu.add(new JMenuItem(truncate(s, TRUNC_LENGTH)+": "+Utils.toStringList(values, v -> truncate(toString(v), TRUNC_LENGTH))));
            }
        }
        if (!mesKeys.isEmpty()) {
            menu.addSeparator();
            for (String s : mesKeys) {
                List<Object> values = new ArrayList(list.size());
                for (SegmentedObject o : list) values.add(o.getMeasurements().getValue(s));
                replaceRepetedValues(values);
                menu.add(new JMenuItem(truncate(s, TRUNC_LENGTH)+": "+Utils.toStringList(values, v -> truncate(toString(v), TRUNC_LENGTH) )));
            }
        }
        return menu;
    }
    
    private static void replaceRepetedValues(List list) {
        if (list.size()<=1) return;
        Object lastValue=list.get(0);
        for (int i = 1; i<list.size(); ++i) {
            if (lastValue==null) lastValue = list.get(i);
            else if (lastValue.equals(list.get(i))) {
                list.remove(i);
                list.add(i, "%");
            } else lastValue = list.get(i);
        }
    }
    private static String toString(Object o) {
        if (o instanceof Point) return o.toString();
        return Measurements.asString(o, MeasurementExtractor.numberFormater);
        //return o instanceof Number ? Utils.format((Number) o, 3) : o.toString();
    }
    private static int TRUNC_LENGTH = 30;
    private static String truncate(String s, int length) {
        if (s.length()>length) return s.substring(0, length-3)+"...";
        else return s;
    }

    private static class AbstractActionImpl extends AbstractAction {

        private final Collection<TestDataStore> stores;
        private final Predicate<TestDataStore> storeWithinSel;
        private final List<SegmentedObject> sel;

        public AbstractActionImpl(String name, Collection<TestDataStore> stores, Predicate<TestDataStore> storeWithinSel, List<SegmentedObject> sel) {
            super(name);
            this.stores = stores;
            this.storeWithinSel = storeWithinSel;
            this.sel = sel;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            stores.stream().filter(storeWithinSel).forEach((TestDataStore s) -> {
                s.displayMiscData(e.getActionCommand(), sel);
            });
        }
    }
    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}