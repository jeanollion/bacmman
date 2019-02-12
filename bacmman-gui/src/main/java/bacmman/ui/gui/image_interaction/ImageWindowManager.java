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

import bacmman.data_structure.Measurements;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementExtractor;
import bacmman.ui.GUI;
import bacmman.core.DefaultWorker;
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;

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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import bacmman.plugins.TestableProcessingPlugin.TestDataStore;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.HashMapGetCreate.SetFactory;
import bacmman.utils.Pair;

import static bacmman.utils.Pair.unpairValues;
import bacmman.utils.Palette;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 * @param <I> image class
 * @param <U> object ROI class
 * @param <V> track ROI class
 */
public abstract class ImageWindowManager<I, U, V> {
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
    final static double TRACK_ARROW_STROKE_WIDTH = 3;
    final static double ROI_STROKE_WIDTH = 1;
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
    protected final Map<Pair<SegmentedObject, SegmentedObject>, V> parentTrackHeadTrackRoiMap=new HashMap<>();
    protected final Map<Pair<SegmentedObject, BoundingBox>, U> labileObjectRoiMap = new HashMap<>();
    protected final Map<Pair<SegmentedObject, SegmentedObject>, V> labileParentTrackHeadTrackRoiMap=new HashMap<>();
    protected final HashMapGetCreate<Image, Set<U>> displayedLabileObjectRois = new HashMapGetCreate<>(new SetFactory<>());
    protected final HashMapGetCreate<Image, Set<V>> displayedLabileTrackRois = new HashMapGetCreate<>(new SetFactory<>());
    
    protected final Map<Image, DefaultWorker> runningWorkers = new HashMap<>();
    
    public ImageWindowManager(ImageObjectListener listener, ImageDisplayer<I> displayer) {
        this.listener=null;
        this.displayer=displayer;
        imageObjectInterfaceMap = new HashMap<>();
        imageObjectInterfaces = new HashMap<>();
        trackHeadTrackMap = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory());
    }
    public void setDisplayImageLimit(int limit) {
        this.displayedImageNumber=limit;
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
            localZoom =  GUI.getInstance()==null? new ZoomPane() : new ZoomPane(GUI.getInstance().getLocalZoomLevel(), GUI.getInstance().getLocalZoomArea());
        } else {
            localZoom.detach();
            localZoom = null;
        }
    }
    public abstract void toggleSetObjectCreationTool();
    public Map<Image, DefaultWorker> getRunningWorkers() {
        return runningWorkers;
    }
    public void stopAllRunningWorkers() {
        for (DefaultWorker w : runningWorkers.values()) w.cancel(true);
        runningWorkers.clear();
    }
    public void flush() {
        if (!runningWorkers.isEmpty()) GUI.logger.debug("flush: will stop {} running workers", runningWorkers.size());
        stopAllRunningWorkers();
        if (!objectRoiMap.isEmpty()) GUI.logger.debug("flush: will remove {} rois", objectRoiMap.size());
        objectRoiMap.clear();
        parentTrackHeadTrackRoiMap.clear();
        if (!labileObjectRoiMap.isEmpty()) GUI.logger.debug("flush: will remove {} rois", labileObjectRoiMap.size());
        labileObjectRoiMap.clear();
        labileParentTrackHeadTrackRoiMap.clear();
        displayedLabileObjectRois.clear();
        displayedLabileTrackRois.clear();
        displayer.flush();
        imageObjectInterfaces.clear();
        if (!imageObjectInterfaceMap.isEmpty()) GUI.logger.debug("flush: will remove {} images", imageObjectInterfaceMap.size());
        imageObjectInterfaceMap.clear();
        trackHeadTrackMap.clear();
        displayedRawInputFrames.clear();
        displayedPrePocessedFrames.clear();
        displayedInteractiveImages.clear();
        testData.clear();
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
            GUI.logger.error("cannot get image if IOI null");
            return null;
        }
        List<Image> list = Utils.getKeys(imageObjectInterfaceMap, new InteractiveImageKey(i.parents, i.childStructureIdx, i instanceof Kymograph ? InteractiveImageKey.IMAGE_TYPE.KYMOGRAPH : InteractiveImageKey.IMAGE_TYPE.SINGLE_FRAME));
        if (list.isEmpty()) return null;
        else return list.get(0);
    }
    public Image getImage(InteractiveImage i, int displayStructureIdx) {
        List<Image> list = Utils.getKeys(imageObjectInterfaceMap, new InteractiveImageKey(i.parents, displayStructureIdx, i instanceof Kymograph ? InteractiveImageKey.IMAGE_TYPE.KYMOGRAPH : InteractiveImageKey.IMAGE_TYPE.SINGLE_FRAME));
        if (list.isEmpty()) return null;
        else return list.get(0);
    }
    
    public void setActive(Image image) {
        boolean b =  displayedInteractiveImages.remove(image);
        if (b) displayedInteractiveImages.add(image);
    }
    
    public void addImage(Image image, InteractiveImage i, int displayedStructureIdx, boolean displayImage) {
        if (image==null) return;
        //ImageObjectInterface i = getImageObjectInterface(parent, childStructureIdx, timeImage);
        GUI.logger.debug("adding image: {} (hash: {}), IOI exists: {} ({})", image.getName(), image.hashCode(), imageObjectInterfaces.containsKey(i.getKey()), imageObjectInterfaces.containsValue(i));
        if (!imageObjectInterfaces.containsValue(i)) {
            //throw new RuntimeException("image object interface should be created through the manager");
            imageObjectInterfaces.put(i.getKey(), i);
        }
        //T dispImage = getImage(image);
        imageObjectInterfaceMap.put(image, new InteractiveImageKey(i.parents, displayedStructureIdx, i instanceof Kymograph ? InteractiveImageKey.IMAGE_TYPE.KYMOGRAPH : InteractiveImageKey.IMAGE_TYPE.SINGLE_FRAME));
        if (displayImage) {
            displayImage(image, i);
            if (i instanceof Kymograph && ((Kymograph)i).imageCallback.containsKey(image)) this.displayer.addMouseWheelListener(image, ((Kymograph)i).imageCallback.get(image));
        }
        
    }
    
    public void displayImage(Image image, InteractiveImage i) {
        long t0 = System.currentTimeMillis();
        displayer.showImage(image);
        long t1 = System.currentTimeMillis();
        displayedInteractiveImages.add(image);
        addMouseListener(image);
        addWindowClosedListener(image, e-> {
            DefaultWorker w = runningWorkers.get(image);
            if (w!=null) {
                GUI.logger.debug("interruptin generation of closed image: {}", image.getName());
                w.cancel(true);
            }
            displayedInteractiveImages.remove(image);
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
        for (InteractiveImageKey.IMAGE_TYPE it : InteractiveImageKey.IMAGE_TYPE.values()) imageObjectInterfaces.remove(new InteractiveImageKey(new ArrayList<SegmentedObject>(1){{add(parent);}}, childStructureIdx, it));
    }
    
    public InteractiveImage getImageObjectInterface(SegmentedObject parent, int childStructureIdx, boolean createIfNotExisting) {
        InteractiveImage i = imageObjectInterfaces.get(new InteractiveImageKey(new ArrayList<SegmentedObject>(1){{add(parent);}}, childStructureIdx, InteractiveImageKey.IMAGE_TYPE.SINGLE_FRAME));
        if (i==null && createIfNotExisting) {
            i= new SimpleInteractiveImage(parent, childStructureIdx);
            imageObjectInterfaces.put(i.getKey(), i);
        } 
        return i;
    }
    public InteractiveImage getImageTrackObjectInterface(List<SegmentedObject> parentTrack, int childStructureIdx) {
        
        if (parentTrack.isEmpty()) {
            GUI.logger.warn("cannot create kymograph with parent track of length == 0" );
            return null;
        }
        InteractiveImage i = imageObjectInterfaces.get(new InteractiveImageKey(parentTrack, childStructureIdx, InteractiveImageKey.IMAGE_TYPE.KYMOGRAPH));
        GUI.logger.debug("getIOI: hash: {} ({}), exists: {}, trackHeadTrackMap: {}", parentTrack.hashCode(), new InteractiveImageKey(parentTrack, childStructureIdx, InteractiveImageKey.IMAGE_TYPE.KYMOGRAPH).hashCode(), i!=null, trackHeadTrackMap.containsKey(parentTrack.get(0)));
        if (i==null) {
            long t0 = System.currentTimeMillis();
            i = Kymograph.generateKymograph(parentTrack, childStructureIdx);
            long t1 = System.currentTimeMillis();
            imageObjectInterfaces.put(i.getKey(), i);
            trackHeadTrackMap.getAndCreateIfNecessary(parentTrack.get(0)).add(parentTrack);
            i.setGUIMode(GUI.hasInstance());
            long t2 = System.currentTimeMillis();
            GUI.logger.debug("create IOI: {} key: {}, creation time: {} ms + {} ms", i.hashCode(), i.getKey().hashCode(), t1-t0, t2-t1);
        } 
        return i;
    }

    protected void reloadObjects__(InteractiveImageKey key) {
        
        InteractiveImage i = imageObjectInterfaces.get(key);
        if (i!=null) {
            GUI.logger.debug("reloading object for parentTrackHead: {} structure: {}", key.parent.get(0), key.displayedStructureIdx);
            i.reloadObjects();
        }
    }
    protected void reloadObjects_(SegmentedObject parent, int childStructureIdx, boolean track) {
        if (track) parent=parent.getTrackHead();
        if (!trackHeadTrackMap.containsKey(parent)) {
            final SegmentedObject p = parent;
            reloadObjects__(new InteractiveImageKey(new ArrayList<SegmentedObject>(1){{add(p);}}, childStructureIdx, InteractiveImageKey.IMAGE_TYPE.SINGLE_FRAME));
        } else {
            for (List<SegmentedObject> l : trackHeadTrackMap.get(parent)) {
                for (InteractiveImageKey.IMAGE_TYPE it : InteractiveImageKey.IMAGE_TYPE.values()) reloadObjects__ (new InteractiveImageKey(l, childStructureIdx, it));
            }
        }
        
        
        
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
        I current = getDisplayer().getCurrentImage();
        if (current!=null) {
            RegisteredImageType type = this.getRegisterType(current);
            Image im = getDisplayer().getImage(current);
            if (im!=null) {
                return getImageObjectInterface(im);
            }
        }
        return null;
    }
    public InteractiveImageKey getImageObjectInterfaceKey(Image image) {
        return imageObjectInterfaceMap.get(image);
    }
    public InteractiveImage getImageObjectInterface(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) return null;
        }
        InteractiveImageKey key = imageObjectInterfaceMap.get(image);
        if (key==null) return null;
        return getImageObjectInterface(image, interactiveStructureIdx); // use the interactive structure. Creates the ImageObjectInterface if necessary 
    }
    public InteractiveImage getImageObjectInterface(Image image, int structureIdx) {
        if (image==null) {
            image = getDisplayer().getCurrentImage2();
            if (image==null) {
                return null;
            }
        }
        InteractiveImageKey key = imageObjectInterfaceMap.get(image);
        if (key==null) return null;
        //if (key.parent.get(0).getStructureIdx()>structureIdx) return null;
        InteractiveImage i = this.imageObjectInterfaces.get(key.getKey(structureIdx));
        
        if (i==null) {
            InteractiveImage ref = InteractiveImageKey.getOneElementIgnoreStructure(key, imageObjectInterfaces);
            if (ref==null) GUI.logger.error("IOI not found: ref: {} ({}), all IOI: {}", key, key.getKey(-1), imageObjectInterfaces.keySet());
            else GUI.logger.debug("creating IOI: ref: {}", ref);
            // create imageObjectInterface
            if (ref instanceof Kymograph) i = this.getImageTrackObjectInterface(((Kymograph)ref).parents, structureIdx);
            else i = this.getImageObjectInterface(ref.parents.get(0), structureIdx, true);
            this.imageObjectInterfaces.put(i.getKey(), i);
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
    public abstract void addWindowListener(Object image, WindowListener wl);
    public void addWindowClosedListener(Object image, Function<WindowEvent, Void> closeFunction) {
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
    protected abstract List<int[]> getSelectedPointsOnImage(I image);
    /**
     * 
     * @param image
     * @return mapping of containing objects (parents) to relative (to the parent) coordinated of selected point 
     */
    public Map<SegmentedObject, List<int[]>> getParentSelectedPointsMap(Image image, int parentStructureIdx) {
        I dispImage;
        if (image==null) {
            dispImage = displayer.getCurrentImage();
            if (dispImage==null) return null;
            image = displayer.getImage(dispImage);
        } else dispImage = displayer.getImage(image);
        if (dispImage==null) return null;
        InteractiveImage i = this.getImageObjectInterface(image, parentStructureIdx);
        if (i==null) return null;
        
        List<int[]> rawCoordinates = getSelectedPointsOnImage(dispImage);
        HashMapGetCreate<SegmentedObject, List<int[]>> map = new HashMapGetCreate<SegmentedObject, List<int[]>>(new HashMapGetCreate.ListFactory<SegmentedObject, int[]>());
        for (int[] c : rawCoordinates) {
            Pair<SegmentedObject, BoundingBox> parent = i.getClickedObject(c[0], c[1], c[2]);
            if (parent!=null) {
                c[0]-=parent.value.xMin();
                c[1]-=parent.value.yMin();
                c[2]-=parent.value.zMin();
                List<int[]> children = map.getAndCreateIfNecessary(parent.key);
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
            GUI.logger.error("no image object interface found for image: {} and structure: {}", image.getName(), interactiveStructureIdx);
            return;
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
            GUI.logger.error("no image object interface found for image: {} and structure: {}", image.getName(), interactiveStructureIdx);
            return;
        }
        Collection<SegmentedObject> objects = Pair.unpairKeys(i.getObjects());
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(objects, true);
        //for (Entry<StructureObject, List<StructureObject>> e : allTracks.entrySet()) logger.debug("th:{}->{}", e.getKey(), e.getValue());
        displayTracks(image, i, allTracks.values(), true);
        //if (listener!=null) 
    }
    
    
    public abstract void displayObject(I image, U roi);
    public abstract void hideObject(I image, U roi);
    protected abstract U generateObjectRoi(Pair<SegmentedObject, BoundingBox> object, Color color);
    protected abstract void setObjectColor(U roi, Color color);
    
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
        for (Pair<SegmentedObject, BoundingBox> p : objectsToDisplay) {
            if (p==null || p.key==null) continue;
            //logger.debug("getting mask of object: {}", o);
            U roi=map.get(p);
            if (roi==null) {
                roi = generateObjectRoi(p, color);
                map.put(p, roi);
                //if (!labileObjects) logger.debug("add non labile object: {}, found by keyonly? {}", p.key, map.containsKey(new Pair(p.key, null)));
            } else {
                setObjectColor(roi, color);
            }
            if (roiModifier!=null) roiModifier.modifyRoi(p, roi, objectsToDisplay);
            if (labileObjects) {
                if (labiles.contains(roi)) {
                    if (hideIfAlreadyDisplayed) {
                        hideObject(dispImage, roi);
                        labiles.remove(roi);
                        GUI.logger.debug("display -> inverse state: hide: {}", p.key);
                        Object attr = new HashMap<String, Object>(0);
                        try {
                            Field attributes = SegmentedObject.class.getDeclaredField("attributes"); attributes.setAccessible(true);
                            attr = attributes.get(p.key);
                        } catch (Exception e) {}
                        GUI.logger.debug("isTH: {}, values: {}, attributes: {}", p.key.isTrackHead(), p.key.getMeasurements().getValues(), attr);
                    }
                } else {
                    displayObject(dispImage, roi);
                    labiles.add(roi);
                }
            } else displayObject(dispImage, roi);
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
    
    /// track-related methods
    
    protected abstract void displayTrack(I image, V roi);
    protected abstract void hideTrack(I image, V roi);
    protected abstract V generateTrackRoi(List<Pair<SegmentedObject, BoundingBox>> track, Color color);
    protected abstract void setTrackColor(V roi, Color color);
    public void displayTracks(Image image, InteractiveImage i, Collection<List<SegmentedObject>> tracks, boolean labile) {
        if (image==null) {
            image = displayer.getCurrentImage2();
            if (image==null) return;
        }
        if (i ==null) {
            i = this.getImageObjectInterface(image);
            
        }
        GUI.logger.debug("image: {}, OI: {}", image.getName(), i.getClass().getSimpleName());
        for (List<SegmentedObject> track : tracks) {
            displayTrack(image, i, i.pairWithOffset(track), getColor() , labile);
        }
        //GUI.updateRoiDisplayForSelections(image, i);
    }
    public void displayTrack(Image image, InteractiveImage i, List<Pair<SegmentedObject, BoundingBox>> track, Color color, boolean labile) {
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
        SegmentedObject trackHead = track.get(track.size()>1 ? 1 : 0).key.getTrackHead(); // idx = 1 because track might begin with previous object
        boolean canDisplayTrack = i instanceof Kymograph;
        //canDisplayTrack = canDisplayTrack && ((TrackMask)i).parent.getTrackHead().equals(trackHead.getParent().getTrackHead()); // same track head
        //canDisplayTrack = canDisplayTrack && i.getParent().getStructureIdx()<=trackHead.getStructureIdx();
        if (canDisplayTrack) {
            Kymograph tm = (Kymograph)i;
            tm.trimTrack(track);
            canDisplayTrack = !track.isEmpty();
        }
        Map<Pair<SegmentedObject, SegmentedObject>, V> map = labile ? labileParentTrackHeadTrackRoiMap : parentTrackHeadTrackRoiMap;
        if (canDisplayTrack) { 
            if (i.getKey().displayedStructureIdx!=trackHead.getStructureIdx()) {
                i = getImageTrackObjectInterface(((Kymograph)i).parents, trackHead.getStructureIdx());
            }
            if (((Kymograph)i).getParent()==null) GUI.logger.error("Track mask parent null!!!");
            else if (((Kymograph)i).getParent().getTrackHead()==null) GUI.logger.error("Track mask parent trackHead null!!!");
            Pair<SegmentedObject, SegmentedObject> key = new Pair(((Kymograph)i).getParent().getTrackHead(), trackHead);
            Set<V>  disp = null;
            if (labile) disp = displayedLabileTrackRois.getAndCreateIfNecessary(image);
            V roi = map.get(key);
            if (roi==null) {
                roi = generateTrackRoi(track, color);
                map.put(key, roi);
            } else setTrackColor(roi, color);
            if (disp==null || !disp.contains(roi)) displayTrack(dispImage, roi);
            if (disp!=null) disp.add(roi);
            displayer.updateImageRoiDisplay(image);
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
        Map<Pair<SegmentedObject, SegmentedObject>, V> map = labile ? labileParentTrackHeadTrackRoiMap : parentTrackHeadTrackRoiMap;
        for (SegmentedObject th : trackHeads) {
            V roi=map.get(new Pair(parentTrackHead, th));
            if (roi!=null) {
                hideTrack(dispImage, roi);
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
            I dispImage = displayer.getImage(image);
            if (dispImage==null) return;
            for (V roi: tracks) displayTrack(dispImage, roi);
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
            for (V roi: tracks) hideTrack(dispImage, roi);
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
            List<Pair<SegmentedObject, SegmentedObject>> pairs = Utils.getKeys(labileParentTrackHeadTrackRoiMap, rois);
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
            Utils.removeFromMap(parentTrackHeadTrackRoiMap, k);
        }
    }
    
    public void resetObjectsAndTracksRoi() {
        for (Image image : imageObjectInterfaceMap.keySet()) hideAllRois(image, true, true);
        objectRoiMap.clear();
        labileObjectRoiMap.clear();
        labileParentTrackHeadTrackRoiMap.clear();
        parentTrackHeadTrackRoiMap.clear();
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
            BoundingBox off = tm.getObjectOffset(nextError);
            if (off==null) trackHeads = new ArrayList<> (trackHeads);
            while(off==null) {
                trackHeads.remove(nextError);
                nextError = getNextObject(nextError.getFrame(), trackHeads, next);
                if (nextError==null) return;
                off = tm.getObjectOffset(nextError);
            }
            int midX = (off.xMin()+off.xMax())/2;
            if (midX+currentDisplayRange.sizeX()/2>=trackImage.sizeX()) midX = trackImage.sizeX()-currentDisplayRange.sizeX()/2;
            if (midX-currentDisplayRange.sizeX()/2<0) midX = currentDisplayRange.sizeX()/2;
            
            int midY = (off.yMin()+off.yMax())/2;
            if (midY+currentDisplayRange.sizeY()/2>=trackImage.sizeY()) midY = trackImage.sizeY()-currentDisplayRange.sizeY()/2;
            if (midY-currentDisplayRange.sizeY()/2<0) midY = currentDisplayRange.sizeY()/2;
            
            SimpleBoundingBox nextDisplayRange = new SimpleBoundingBox(midX-currentDisplayRange.sizeX()/2, midX+currentDisplayRange.sizeX()/2, midY-currentDisplayRange.sizeY()/2, midY+currentDisplayRange.sizeY()/2, currentDisplayRange.zMin(), currentDisplayRange.zMax());
            GUI.logger.info("Error detected @ timepoint: {}, xMid: {}, update display range: {}", nextError.getFrame(), midX,  nextDisplayRange);
            displayer.setDisplayRange(nextDisplayRange, trackImage);
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
        //ImageObjectInterface i = imageObjectInterfaces.get(new ImageObjectInterfaceKey(rois.get(0).getParent().getTrackHead(), rois.get(0).getStructureIdx(), true));
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
        BoundingBox currentDisplayRange = this.displayer.getDisplayRange(trackImage);
        int minTimePoint = tm.getClosestFrame(currentDisplayRange.xMin(), currentDisplayRange.yMin());
        int maxTimePoint = tm.getClosestFrame(currentDisplayRange.xMax(), currentDisplayRange.yMax());
        if (next) {
            if (maxTimePoint == i.getParents().get(i.getParents().size()-1).getFrame()) return false;
            if (maxTimePoint>minTimePoint+2) maxTimePoint-=2;
            else maxTimePoint--;
        } else {
            if (minTimePoint == i.getParents().get(0).getFrame()) return false;
            if (maxTimePoint>minTimePoint+2) minTimePoint+=2;
            else minTimePoint++;
        }
        GUI.logger.debug("Current Display range: {}, maxTimePoint: {}, minTimePoint: {}, number of objects: {}", currentDisplayRange, maxTimePoint, minTimePoint, objects.size());
        Collections.sort(objects, SegmentedObjectUtils.frameComparator()); // sort by frame
        SegmentedObject nextObject = getNextObject(next? maxTimePoint: minTimePoint, objects, next);
        if (nextObject==null) {
            GUI.logger.info("No object detected {} timepoint: {}", next? "after" : "before", maxTimePoint);
            return false;
        }
        else {
            BoundingBox off = tm.getObjectOffset(nextObject);
            if (off==null) objects = new ArrayList<>(objects);
            while(off==null) {
                objects = new ArrayList<>(objects);
                objects.remove(nextObject);
                nextObject = getNextObject(nextObject.getFrame(), objects, next);
                if (nextObject==null) {
                    GUI.logger.info("No object detected {} timepoint: {}", next? "after" : "before", maxTimePoint);
                    return false;
                }
                off = tm.getObjectOffset(nextObject);
            }
            int midX = (off.xMin()+off.xMax())/2;
            if (midX+currentDisplayRange.sizeX()/2>=trackImage.sizeX()) midX = trackImage.sizeX()-currentDisplayRange.sizeX()/2-1;
            if (midX-currentDisplayRange.sizeX()/2<0) midX = currentDisplayRange.sizeX()/2;
            
            int midY = (off.yMin()+off.yMax())/2;
            if (midY+currentDisplayRange.sizeY()/2>=trackImage.sizeY()) midY = trackImage.sizeY()-currentDisplayRange.sizeY()/2-1;
            if (midY-currentDisplayRange.sizeY()/2<0) midY = currentDisplayRange.sizeY()/2;
            
            MutableBoundingBox nextDisplayRange = new MutableBoundingBox(midX-currentDisplayRange.sizeX()/2, midX+currentDisplayRange.sizeX()/2, midY-currentDisplayRange.sizeY()/2, midY+currentDisplayRange.sizeY()/2, currentDisplayRange.zMin(), currentDisplayRange.zMax());
            if (!nextDisplayRange.equals(currentDisplayRange)) {
                GUI.logger.info("Object detected @ timepoint: {}, xMid: {}, update display range: {} (current was: {}", nextObject.getFrame(), midX,  nextDisplayRange, currentDisplayRange);
                displayer.setDisplayRange(nextDisplayRange, trackImage);
                return true;
            } return false;
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
        if (o.getAttributes()!=null && !o.getAttributes().isEmpty()) {
            menu.addSeparator();
            menu.add(new JMenuItem("<html><b>Other Attributes</b></html>"));
            for (Entry<String, Object> en : new TreeMap<>(o.getAttributes()).entrySet()) {
                JMenuItem item = new JMenuItem(truncate(en.getKey(), TRUNC_LENGTH)+": "+truncate(toString(en.getValue()), TRUNC_LENGTH));
                menu.add(item);
                item.setAction(new AbstractAction(item.getActionCommand()) {
                    @Override
                        public void actionPerformed(ActionEvent ae) {
                            java.awt.datatransfer.Transferable stringSelection = new StringSelection(en.getValue().toString());
                            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                            clipboard.setContents(stringSelection, null);
                        }
                });
            }
        }

        menu.addSeparator();
        menu.add(new JMenuItem("<html><b>Measurements</b></html>"));
        for (Entry<String, Object> en : new TreeMap<>(o.getMeasurements().getValues()).entrySet()) {
            JMenuItem item = new JMenuItem(truncate(en.getKey(), TRUNC_LENGTH)+": "+truncate(toString(en.getValue()), TRUNC_LENGTH));
            menu.add(item);
            item.setAction(new AbstractAction(item.getActionCommand()) {
                @Override
                    public void actionPerformed(ActionEvent ae) {
                        java.awt.datatransfer.Transferable stringSelection = new StringSelection(en.getValue().toString());
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
            if (o.getAttributes()!=null && !o.getAttributes().isEmpty()) attributeKeys.addAll(o.getAttributes().keySet());
            mesKeys.addAll(o.getMeasurements().getValues().keySet());
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