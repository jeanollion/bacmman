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

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.ImageDAO;
import bacmman.data_structure.input_image.InputImages;
import bacmman.data_structure.region_container.roi.ObjectRoi;
import bacmman.data_structure.region_container.roi.TrackRoi;
import bacmman.image.*;
import bacmman.measurement.MeasurementExtractor;
import bacmman.ui.GUI;

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
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.*;
import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import bacmman.plugins.TestableProcessingPlugin.TestDataStore;
import bacmman.utils.*;
import bacmman.utils.HashMapGetCreate.SetFactory;

import static bacmman.utils.Palette.setOpacity;

import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 * @param <I> image class
 * @param <O> object ROI class
 * @param <T> track ROI class
 */
public abstract class ImageWindowManager<I, O extends ObjectRoi<O>, T extends TrackRoi> {
    static final Logger logger = LoggerFactory.getLogger(ImageWindowManager.class);

    public enum RegisteredImageType {KYMOGRAPH, HYPERSTACK, RAW_INPUT, PRE_PROCESSED}
    public static boolean displayTrackMode, displayTrackEdges, displayCorrections;
    public final static Color[] palette = new Color[]{new Color(166, 206, 227, 150), new Color(31,120,180, 150), new Color(178,223,138, 150), new Color(51,160,44, 150), new Color(251,154,153, 150), new Color(253,191,111, 150), new Color(255,127,0, 150), new Color(255,255,153, 150), new Color(177,89,40, 150)};
    public final static Color defaultRoiColor = new Color(255, 0, 255, 150);
    public static Color getColor(int idx) {return palette[idx%palette.length];}
    protected final static Color trackErrorColor = new Color(255, 0, 0);
    protected final static Color trackCorrectionColor = new Color(0, 0, 255);
    public static double filledRoiOpacity = 0.4;
    public static double strokeRoiOpacity = 0.6;
    public static double arrowOpacity = 0.6;
    public static Color getColor() {
        return Palette.getColor(150, trackErrorColor, trackCorrectionColor, defaultRoiColor);
    }
    protected final Map<SegmentedObject, Color> trackColor = new HashMapGetCreate.HashMapGetCreateRedirected<>(t -> setOpacity(getColor(), (int)(255 * strokeRoiOpacity)));
    public Color getColor(SegmentedObject trackHead) {
        return trackColor.get(trackHead.getTrackHead());
        //return Palette.getColor(0, SegmentedObjectUtils.getIndexTree(trackHead.getTrackHead()));
    }
    int ROI_SMOOTH_RADIUS = 0;
    double TRACK_ARROW_STROKE_WIDTH = 3;
    double ROI_STROKE_WIDTH = 0.5;
    public static double TRACK_LINK_MIN_SIZE = 23;
    final ImageDisplayer<I> displayer;
    int interactiveObjectClassIdx;
    int displayedImageNumber = 20;
    ZoomPane localZoom;
    protected final Map<InteractiveImage, Set<Image>> interactiveImageMapImages = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.SetFactory<>());
    protected final Map<Image, InteractiveImage> imageMapInteractiveImage = new HashMap<>();
    protected final LinkedHashMap<String, Image> displayedRawInputImages = new LinkedHashMap<>();
    protected final LinkedHashMap<String, Image> displayedPrePocessedImages = new LinkedHashMap<>();
    protected final LinkedList<Image> displayedInteractiveImages = new LinkedList<>();
    protected final LinkedList<String> activePositions = new LinkedList<>();

    // displayed objects 
    protected final Map<String, Map<ObjectDisplay, O>> objectRoiCache = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.MapFactory<>());
    protected final Map<String, Map<ObjectDisplay, O>> persistentObjectRoiCache = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.MapFactory<>());
    protected final Map<String, Map<List<ObjectDisplay>, T>> kymographTrackRoiCache = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.MapFactory<>());
    protected final Map<String, Map<List<ObjectDisplay>, T>> hyperstackTrackRoiCache = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.MapFactory<>());
    protected final Map<Image, Set<O>> displayedLabileObjectRois = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new SetFactory<>());
    protected final Map<Image, Set<O>> displayedObjectRois = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new SetFactory<>());
    protected final Map<Image, Set<T>> displayedLabileTrackRois = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.SetFactory<>());
    protected final Map<Image, Set<T>> displayedTrackRois = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.SetFactory<>());

    enum DISPLAY_MODE {NONE, OBJECTS, TRACKS, OBJECTS_CLASSES}
    protected final Map<Image, DISPLAY_MODE> displayMode = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(im -> DISPLAY_MODE.NONE);

    public ImageWindowManager(ImageDisplayer<I> displayer) {
        this.displayer=displayer;
    }
    static Class<? extends InteractiveImage> defaultInteractiveType;
    public static void setDefaultInteractiveType(String type) {
        if (type == null) defaultInteractiveType = null;
        else if ("hyperstack".equals(type.toLowerCase())) defaultInteractiveType = HyperStack.class;
        else if ("kymograph".equals(type.toLowerCase())) defaultInteractiveType = Kymograph.class;
        else throw new IllegalArgumentException("Invalid interactive type: "+type);
    }
    public static Class<? extends InteractiveImage> getDefaultInteractiveType() {return defaultInteractiveType;}
    public void setDisplayImageLimit(int limit) {
        this.displayedImageNumber=limit;
    }
    public void setRoiStrokeWidth(double width) {
        this.ROI_STROKE_WIDTH = width;
    }
    public void setArrowStrokeWidth(double width) {
        this.TRACK_ARROW_STROKE_WIDTH = width;
    }
    public void setROISmoothRadius(int radius) {
        this.ROI_SMOOTH_RADIUS = radius;
    }
    public int getDisplayImageLimit() {
        return displayedImageNumber;
    }
    public RegisteredImageType getRegisterType(Image image) {
        InteractiveImage ii = imageMapInteractiveImage.get(image);
        if (ii != null) {
            if (ii instanceof Kymograph) return RegisteredImageType.KYMOGRAPH;
            else if (ii instanceof HyperStack) return RegisteredImageType.HYPERSTACK;
        }
        if (this.displayedRawInputImages.containsValue(image)) return RegisteredImageType.RAW_INPUT;
        if (this.displayedPrePocessedImages.containsValue(image)) return RegisteredImageType.PRE_PROCESSED;
        return null;
    }


    public void stopAllRunningWorkers() {
        for (InteractiveImage im : interactiveImageMapImages.keySet()) {
            im.stopAllRunningWorkers();
        }
    }

    public List<String> limitActivePositions(int limit) {
        if (activePositions.size() > limit) {
            List<String> positionToFlush = new ArrayList<>(activePositions.subList(0, activePositions.size()-limit));
            for (String p : positionToFlush) flush(p);
            return positionToFlush;
        } else return Collections.emptyList();
    }

    public void closeInteractiveImages(int numberOfKeptImages) {
        logger.debug("close active images: total open {} limit: {}", displayedInteractiveImages.size(), numberOfKeptImages);
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

    public void closeInputImages(int numberOfKeptImages) {
        //logger.debug("close input images: raw: {} pp: {} limit: {}", displayedRawInputFrames.size(), displayedPrePocessedFrames.size(), numberOfKeptImages);
        if (numberOfKeptImages<=0) return;
        if (displayedRawInputImages.size()>numberOfKeptImages) {
            Iterator<String> it = displayedRawInputImages.keySet().iterator();
            while(displayedRawInputImages.size()>numberOfKeptImages && it.hasNext()) {
                String i = it.next();
                Image im = displayedRawInputImages.get(i);
                it.remove();
                displayer.close(im);
            }
        }
        if (displayedPrePocessedImages.size()>numberOfKeptImages) {
            Iterator<String> it = displayedPrePocessedImages.keySet().iterator();
            while(displayedPrePocessedImages.size()>numberOfKeptImages && it.hasNext()) {
                String i = it.next();
                Image im = displayedPrePocessedImages.get(i);
                it.remove();
                displayer.close(im);
            }
        }
    }

    public void flush(String position) {
        List<InteractiveImage> iis = new ArrayList<>(interactiveImageMapImages.keySet());
        for (InteractiveImage ii : iis) {
            if (ii.getParent().getPositionName().equals(position)) {
                Set<Image> images = interactiveImageMapImages.get(ii);
                images.forEach(displayer::close);
                // no need to remove from interactiveImageMapImages : this is taken cared by window closed event, as well as running workers, rois...
            }
        }
        activePositions.remove(position);
        persistentObjectRoiCache.remove(position);
        hyperstackTrackRoiCache.remove(position);
        kymographTrackRoiCache.remove(position);
        objectRoiCache.remove(position);
        Image im = displayedPrePocessedImages.remove(position);
        if (im!=null) displayer.close(im);
        im = displayedRawInputImages.remove(position);
        if (im!=null) displayer.close(im);
        trackColor.entrySet().removeIf(e -> e.getKey().getPositionName().equals(position));
    }

    public void flush() {
        stopAllRunningWorkers();
        if (!objectRoiCache.isEmpty()) logger.debug("flush: will remove {} rois", objectRoiCache.size());
        objectRoiCache.clear();
        persistentObjectRoiCache.clear();
        hyperstackTrackRoiCache.clear();
        kymographTrackRoiCache.clear();
        displayedLabileObjectRois.clear();
        displayedObjectRois.clear();
        displayedLabileTrackRois.clear();
        displayedTrackRois.clear();
        displayer.flush();
        interactiveImageMapImages.clear();
        imageMapInteractiveImage.clear();
        displayMode.clear();
        displayedRawInputImages.clear();
        displayedPrePocessedImages.clear();
        displayedInteractiveImages.clear();
        testData.clear();
        trackColor.clear();
    }

    public void closeNonInteractiveWindows() {
        closeInputImages(0);
    }
    public ImageDisplayer<I> getDisplayer() {return displayer;}
    
    //protected abstract I getImage(Image image);
    
    public void setInteractiveStructure(int structureIdx) {
        this.interactiveObjectClassIdx =structureIdx;
    }
    
    public int getInteractiveObjectClass() {
        return interactiveObjectClassIdx;
    }
    
    public void setActive(Image image) { // overriden function will actually set to front the window
        InteractiveImage i = getInteractiveImage(image);
        boolean b = displayedInteractiveImages.remove(image);
        if (b && i!=null) {
            displayedInteractiveImages.add(image);
            activePositions.remove(i.getParent().getPositionName());
            activePositions.add(i.getParent().getPositionName());
        }
        if (!displayer.isDisplayed(image)) {
            if (i != null) displayInteractiveImage(image, i);
            else displayer.displayImage(image);
        }
    }

    public List<String> displayInputImage(Experiment xp, String position, boolean preProcessed) {
        activePositions.remove(position);
        activePositions.add(position);
        if (preProcessed) {
            if (displayedPrePocessedImages.containsKey(position)) {
                Image im = displayedPrePocessedImages.remove(position);
                displayedPrePocessedImages.put(position, im); // put last
                setActive(im);
                return Collections.emptyList();
            }
        } else {
            if (displayedRawInputImages.containsKey(position)) {
                Image im = displayedRawInputImages.remove(position);
                displayedRawInputImages.put(position, im); // put last
                setActive(im);
                return Collections.emptyList();
            }
        }
        Position f = xp.getPosition(position);
        int channels = xp.getChannelImageCount(preProcessed);
        int frames = f.getFrameNumber(false);
        String title = (preProcessed ? "PreProcessed Images of position: #" : "Input Images of position: #")+f.getIndex();
        ImageDAO imageDAO = preProcessed ? f.getImageDAO() : null;
        InputImages inputImages = !preProcessed ? f.getInputImages() : null;
        logger.debug("testing if image is present");
        if (preProcessed) {
            if (imageDAO.isEmpty()) {
                Utils.displayTemporaryMessage("Position " + position + " has not been pre-processed yet ", 5000);
                return Collections.emptyList();
            }
        } else {
            if (!inputImages.sourceImagesLinked()) {
                Utils.displayTemporaryMessage("Images of Position " + position + " not found", 5000);
                return Collections.emptyList();
            }
        }
        logger.debug("image is present");
        IntUnaryOperator getSizeZC = preProcessed ? c -> {
            try {
                return imageDAO.getPreProcessedImageProperties(c).sizeZ();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } : inputImages::getSourceSizeZ;
        int[] sizeZC = IntStream.range(0, channels).map(getSizeZC).toArray();
        int maxZIdx = ArrayUtil.max(sizeZC);
        int maxZ = sizeZC[maxZIdx];
        if (maxZ != 1) {
            for (int z = 0; z<sizeZC.length; ++z) {
                if (sizeZC[z]!=1 && sizeZC[z]!=maxZ) throw new RuntimeException("At least two channels have slice (z) number that differ and are not equal to 1");
            }
        }

        Function<int[], Image> imageOpenerFCZ  = preProcessed ? (fcz) -> {
            if (sizeZC[fcz[1]] == 1) fcz[2] = 0;
            try {
                return imageDAO.openPreProcessedImagePlane(fcz[2], fcz[1], fcz[0]);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } : (fcz) -> {
            if (sizeZC[fcz[1]] == 1) fcz[2] = 0;
            try {
                return inputImages.getRawPlane(fcz[2], fcz[1], fcz[0]);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        LazyImage5D source = new LazyImage5DPlane(title, LazyImage5DPlane.homogenizeType(channels, imageOpenerFCZ), new int[]{frames, channels, maxZ});
        source.setChannelNames(xp.getChannelImagesAsString(preProcessed));
        source.setChannelColors(xp.getChannelColorAsString(preProcessed).toArray(String[]::new));
        I image = getDisplayer().displayImage(source);
        addWindowClosedListener(image, ()-> {
            if (!preProcessed) displayedRawInputImages.remove(position);
            else displayedPrePocessedImages.remove(position);
            displayer.removeImage(source);
        });
        if (!preProcessed) displayedRawInputImages.put(position, source);
        else displayedPrePocessedImages.put(position,  source);
        activePositions.remove(position);
        activePositions.add(position);
        closeInputImages(displayedImageNumber);
        return limitActivePositions(displayedImageNumber);
    }

    public String getPositionOfInputImage(Image image) {
        String pos = Utils.getOneKey(displayedRawInputImages, image);
        if (pos!=null) return pos;
        return Utils.getOneKey(displayedPrePocessedImages, image);
    }

    public List<String> addInteractiveImage(Image image, InteractiveImage i, boolean displayImage) {
        if (image==null) return Collections.emptyList();
        logger.debug("adding image: {}, IOI {} exists: {} ({}), displayed OC: {}", image.getName(), i, imageMapInteractiveImage.containsKey(i));
        interactiveImageMapImages.get(i).add(image);
        imageMapInteractiveImage.put(image, i);
        i.setGUIMode(GUI.hasInstance());
        if (displayImage) {
            return displayInteractiveImage(image, i);
        } else {
            activePositions.remove(i.getParent().getPositionName());
            activePositions.add(i.getParent().getPositionName());
            return limitActivePositions(displayedImageNumber);
        }
    }

    protected List<String> displayInteractiveImage(Image image, InteractiveImage i) {
        long t0 = System.currentTimeMillis();
        displayer.displayImage(image);
        long t1 = System.currentTimeMillis();
        displayedInteractiveImages.add(image);
        activePositions.remove(i.getParent().getPositionName());
        activePositions.add(i.getParent().getPositionName());
        addMouseListener(image);
        addWindowClosedListener(image, ()-> {
            interactiveImageMapImages.get(i).remove(image);
            if (interactiveImageMapImages.get(i).isEmpty()) {
                i.stopAllRunningWorkers();
                interactiveImageMapImages.remove(i);
            }
            imageMapInteractiveImage.remove(image);
            displayedInteractiveImages.remove(image);
            displayMode.remove(image);
            testData.remove(image);
            displayer.removeImage(image);
            displayedLabileObjectRois.remove(image);
            displayedObjectRois.remove(image);
            displayedLabileTrackRois.remove(image);
            displayedTrackRois.remove(image);
        });
        long t2 = System.currentTimeMillis();
        GUI.updateRoiDisplayForSelections(image, i);
        long t3 = System.currentTimeMillis();
        closeInteractiveImages(displayedImageNumber);
        long t4 = System.currentTimeMillis();
        logger.debug("display image: show: {} ms, add list: {}, update ROI: {}, close last active image: {}", t1-t0, t2-t1, t3-t2, t4-t3);
        return limitActivePositions(displayedImageNumber);
    }

    public <II extends InteractiveImage> II getInteractiveImage(List<SegmentedObject> parentTrack, Class<II> interactiveImageClass, boolean createIfNotExisting) {
        if (parentTrack.isEmpty()) {
            logger.warn("cannot get interactive image with parent track of length == 0" );
            return null;
        }
        II res = interactiveImageMapImages.keySet().stream().filter(ii -> interactiveImageClass.isAssignableFrom(ii.getClass()) && ii.getParents().equals(parentTrack)).map(ii -> (II)ii).findAny().orElse(null);
        if (res == null && createIfNotExisting) {
            if (interactiveImageClass.equals(HyperStack.class)) res = (II) HyperStack.generateHyperstack(parentTrack, null, interactiveObjectClassIdx);
            else if (Kymograph.class.isAssignableFrom(interactiveImageClass)) res = (II) Kymograph.generateKymograph(parentTrack, null, interactiveObjectClassIdx);
        }
        return res;
    }

    public <I extends Image<I>> Stream<Image<I>> getImages(InteractiveImage i ) {
        if (i==null) return Stream.empty();
        return interactiveImageMapImages.get(i).stream().map(im -> (Image<I>)im);
    }

    public Image getOneImage(InteractiveImage i ) {
        if (i==null) return null;
        return interactiveImageMapImages.get(i).isEmpty() ? null : interactiveImageMapImages.get(i).iterator().next();
    }
    public Stream<InteractiveImage> getAllInteractiveImages() {
        return interactiveImageMapImages.keySet().stream();
    }
    public Stream<InteractiveImage> getAllInteractiveImages(List<SegmentedObject> parentTrack) {
        if (parentTrack.isEmpty()) {
            logger.warn("cannot get interactive image with parent track of length == 0" );
            return null;
        }
        return interactiveImageMapImages.keySet().stream().filter(ii -> ii.getParents().equals(parentTrack));
    }

    public void syncView(Image referenceImage, TimeLapseInteractiveImage targetInteractiveImage, Image... targetImages) {
        Image refImage  = referenceImage == null ? displayer.getCurrentImage() : referenceImage;
        if (refImage == null) return;
        InteractiveImage refInteractiveImage = getInteractiveImage(referenceImage);
        if (!(refInteractiveImage instanceof TimeLapseInteractiveImage)) return;
        if (targetInteractiveImage == null) {
            logger.debug("Sync view from : {}", refImage.getName());
            interactiveImageMapImages.keySet().stream().filter(ii -> ii.getParents().equals(refInteractiveImage.getParents())).forEach(ii -> syncView(refImage, (TimeLapseInteractiveImage) ii));
            return;
        }
        if (targetImages.length == 0) targetImages = interactiveImageMapImages.get(targetInteractiveImage).stream().filter(i -> !i.equals(refImage)).toArray(Image[]::new);
        else targetImages = Arrays.stream(targetImages).filter(i -> !i.equals(refImage)).toArray(Image[]::new);

        boolean setSlice;
        for (Image targetImage : targetImages) {
            logger.debug("sync view from {} to {}", refImage.getName(), targetImage.getName());
            switch (displayMode.get(refImage)) {
                case NONE:
                default:
                    List<SegmentedObject> sel = getSelectedLabileObjects(refImage);
                    sel.removeIf(o -> targetInteractiveImage.getObjectsAtFrame(o.getStructureIdx(), o.getFrame()).noneMatch(o::equals));
                    if (!sel.isEmpty()) {
                        displayObjects(targetImage, targetInteractiveImage.toObjectDisplay(sel.stream()).collect(Collectors.toList()), null, false, true, false);
                        boolean move = goToNextObject(targetImage, sel, true, false);
                        if (!move) goToNextObject(targetImage, sel, false, false);
                    }
                    List<SegmentedObject> selTracks = getSelectedLabileTrackHeads(refImage);
                    selTracks.removeIf(o -> targetInteractiveImage.getObjectsAtFrame(o.getStructureIdx(), o.getFrame()).noneMatch(o::equals));
                    if (!selTracks.isEmpty()) displayTracks(targetImage, targetInteractiveImage, SegmentedObjectUtils.getTracks(selTracks), null, true, false);
                    if (sel.isEmpty() && selTracks.isEmpty()) setSlice = true;
                    else setSlice = false;
                    //logger.debug("sel: {} tracks: {} sel slice: {}", sel.size(), selTracks.size(), setSlice);
                    break;
                case OBJECTS:
                    displayAllObjects(targetImage);
                    setSlice = true;
                    break;
                case TRACKS:
                    displayAllTracks(targetImage);
                    setSlice = true;
                    break;
                case OBJECTS_CLASSES:
                    displayAllObjectClasses(targetImage);
                    setSlice = true;
                    break;
            }
            if (setSlice) {
                int sliceIdx = displayer.getFrame(referenceImage);
                if (refInteractiveImage instanceof HyperStack) {
                    if (targetInteractiveImage instanceof HyperStack) displayer.setFrame(sliceIdx, targetImage);
                    else {
                        int frame = refInteractiveImage.getParents().get(sliceIdx).getFrame();
                        int targetSlice = ((Kymograph) targetInteractiveImage).getSlice(frame).mapToInt(s -> s).min().getAsInt();
                        displayer.setFrame(targetSlice, targetImage);
                    }
                } else {
                    if (targetInteractiveImage instanceof Kymograph) displayer.setFrame(sliceIdx, targetImage);
                    else {
                        int parentIdx = ((Kymograph)refInteractiveImage).getStartParentIdx(sliceIdx);
                        displayer.setFrame(parentIdx, targetImage);
                    }
                }
            }
        }
    }

    public void resetObjects(String position, int... childStructureIdx) {
        interactiveImageMapImages.keySet().stream()
            .filter(i->position==null || i.getParent().getPositionName().equals(position))
            .forEach(i -> i.resetObjects(InteractiveImage.getObjectClassesAndChildObjectClasses(i.parent.getExperimentStructure(), childStructureIdx)));
        resetObjectsAndTracksRoi(); // TODO more specific reset
    }

    public InteractiveImage getCurrentImageObjectInterface() {
        return getInteractiveImage(null);
    }

    public  InteractiveImage getInteractiveImage(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) {
                return null;
            }
        }
        return imageMapInteractiveImage.get(image);
    }

    public abstract void addMouseListener(Image image);
    public abstract void addWindowListener(I image, WindowListener wl);
    public void addWindowClosedListener(Image image, Runnable closeFunction) {
        I im = displayer.getImage(image);
        if (im!=null) addWindowClosedListener(im, closeFunction);
    }
    public void addWindowClosedListener(I image, Runnable closeFunction) {
        addWindowListener(image, new WindowListener() {
            @Override
            public void windowOpened(WindowEvent e) { }
            @Override
            public void windowClosing(WindowEvent e) {}
            @Override
            public void windowClosed(WindowEvent e) {
                closeFunction.run();
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


    /**
     * 
     * @param image
     * @return list of coordinates (x, y, z starting from 0) within the image, in voxel unit
     */
    protected abstract Map<Integer, List<Point>> getSelectedPointsOnImage(I image);
    /**
     * 
     * @param image
     * @return mapping of containing objects (parents) to relative (to the parent) coordinated of selected point 
     */
    public Map<SegmentedObject, List<Point>> getParentSelectedPointsMap(Image image, int parentObjectClassIdx) {
        I dispImage;
        if (image==null) {
            dispImage = displayer.getCurrentDisplayedImage();
            if (dispImage==null) return null;
            image = displayer.getImage(dispImage);
        } else dispImage = displayer.getImage(image);
        if (dispImage==null) return null;
        InteractiveImage i = this.getInteractiveImage(image);
        if (i==null) return null;

        Map<Integer, List<Point>> rawCoordinatesByFrame = getSelectedPointsOnImage(dispImage);
        HashMapGetCreate<SegmentedObject, List<Point>> map = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory<>());
        rawCoordinatesByFrame.forEach((f, rawCoordinates) ->  {
            rawCoordinates.forEach(c -> {
                ObjectDisplay parent = i.getObjectAtPosition(c.getIntPosition(0), c.getIntPosition(1), c.getIntPosition(2), parentObjectClassIdx, f);
                if (parent!=null) {
                    c.translateRev(parent.offset);
                    List<Point> children = map.getAndCreateIfNecessary(parent.object);
                    children.add(c);
                }
            });
        });
        return map;
        
    }
    public abstract void displayAllObjects(Image image);
    public abstract void displayAllObjectClasses(Image image);
    public abstract void displayAllTracks(Image image);
    public abstract void displayObject(I image, O roi);
    public abstract void hideObject(I image, O roi);
    protected abstract O createObjectRoi(ObjectDisplay object, Color color, boolean fill);
    public abstract void updateImageRoiDisplay(Image image);
    
    public void displayObjects(Image image, Collection<ObjectDisplay> objectsToDisplay, Color color, boolean fill, boolean labileObjects, boolean hideIfAlreadyDisplayed) {
        if (objectsToDisplay.isEmpty() || (objectsToDisplay.iterator().next()==null)) return;
        I dispImage;
        if (image==null) {
            dispImage = displayer.getCurrentDisplayedImage();
            if (dispImage==null) return;
            image = displayer.getImage(dispImage);
        }
        else dispImage = displayer.getImage(image);
        if (dispImage==null || image==null) return;
        InteractiveImage ii = getInteractiveImage(image);
        String position = ii.getParent().getPositionName();
        Set<O> displayed = labileObjects ? displayedLabileObjectRois.get(image) : displayedObjectRois.get(image);
        long t0 = System.currentTimeMillis();
        for (ObjectDisplay od : objectsToDisplay) {
            if (od==null) continue;
            if (color == null) {
                color = od.object.getStructureIdx()<0 ? null : od.object.getExperimentStructure().getObjectColors().toArray(Color[]::new)[od.object.getStructureIdx()];
                if (color == null) color = defaultRoiColor;
                color = getTransparentColor(color, fill);
            }
            //logger.debug("getting mask of object: {}", o);
            Map<ObjectDisplay, O> cache = labileObjects ? objectRoiCache.get(position) : persistentObjectRoiCache.get(position);
            O roi= cache.get(od);
            if (roi==null && !labileObjects) {
                roi = objectRoiCache.get(position).get(od);
                if (roi != null) {
                    roi = roi.duplicate();
                    cache.put(od, roi);
                }
            }
            if (roi==null) {
                roi = createObjectRoi(od, color, fill);
                cache.put(od, roi);
            } else {
                roi.setColor(color, fill);
                roi.setStrokeWidth(ROI_STROKE_WIDTH);
            }
            if (displayed.contains(roi)) {
                if (hideIfAlreadyDisplayed) {
                    hideObject(dispImage, roi);
                    displayed.remove(roi);
                }
            } else {
                displayObject(dispImage, roi);
                displayed.add(roi);
            }
        }
        long t1 = System.currentTimeMillis();
        updateImageRoiDisplay(image);
        long t2 = System.currentTimeMillis();
        //logger.debug("display {} objects: create roi & add to overlay: {}, update display: {}", objectsToDisplay.size(), t1-t0, t2-t1);
    }
    
    public void hideObjects(Image image, Collection<ObjectDisplay> objects, boolean labileObjects) {
        I dispImage;
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) return;
        } 
        dispImage = getDisplayer().getImage(image);
        if (dispImage==null) return;
        InteractiveImage ii = getInteractiveImage(image);
        String position = ii.getParent().getPositionName();
        Set<O> selectedObjects = labileObjects ? this.displayedLabileObjectRois.get(image) : displayedObjectRois.get(image);
        for (ObjectDisplay p : objects) {
            //logger.debug("hiding: {}", p.key);
            O roi= labileObjects ? objectRoiCache.get(position).get(p) : persistentObjectRoiCache.get(position).get(p);
            if (roi!=null) {
                hideObject(dispImage, roi);
                if (selectedObjects!=null) selectedObjects.remove(roi);
            }
            //logger.debug("hide object: {} found? {}", p.key, roi!=null);
        }
        updateImageRoiDisplay(image);
    }

    public void hideLabileObjects(Image image, boolean updateDisplay) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) return;
        }
        Set<O> rois = this.displayedLabileObjectRois.remove(image);
        if (rois!=null) {
            I dispImage = displayer.getImage(image);
            if (dispImage==null) return;
            for (O roi: rois) hideObject(dispImage, roi);
            if (updateDisplay) updateImageRoiDisplay(image);
            //if (listener!=null) listener.fireObjectDeselected(Pair.unpair(getLabileObjects(image)));
        }
    }
    
    public List<SegmentedObject> getSelectedLabileObjects(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) return Collections.emptyList();
        }
        InteractiveImage i = getInteractiveImage(image);
        if (i==null) return Collections.emptyList();
        String position = i.getParent().getPositionName();
        if (displayMode.get(image).equals(DISPLAY_MODE.OBJECTS)) {
            logger.debug("getSelected Labile object: display all objects mode");
            return i.getAllObjects(interactiveObjectClassIdx).collect(Collectors.toList());
        }
        Set<O> rois = displayedLabileObjectRois.get(image);
        if (rois!=null) {
            List<ObjectDisplay> ods = Utils.getKeys(objectRoiCache.get(position), rois);
            List<SegmentedObject> res = ods.stream().map(o->o.object).collect(Collectors.toList());
            Utils.removeDuplicates(res, false);
            return res;
        } else return Collections.emptyList();
    }

    public List<SegmentedObject> getSelectedLabileObjectsOrTracks(Image image) {
        if (displayTrackMode && !displayMode.get(image).equals(DISPLAY_MODE.OBJECTS) || displayMode.get(image).equals(DISPLAY_MODE.TRACKS)) {
            List<SegmentedObject> th = getSelectedLabileTrackHeads(image);
            return th.stream().flatMap(t -> SegmentedObjectUtils.getTrack(t).stream()).collect(Collectors.toList());
        } else return getSelectedLabileObjects(image);
    }
    /// track-related methods
    
    protected abstract void displayTrack(I image, T roi);
    protected abstract void hideTrack(I image, T roi);
    protected abstract T createTrackRoi(List<ObjectDisplay> track, InteractiveImage i, Color color, boolean forceDefaultDisplay);
    public void displayTracks(Image image, InteractiveImage i, Collection<List<SegmentedObject>> tracks, Color color, boolean labile, boolean hideIfAlreadyDisplayed) {
        if (image==null) {
            image = displayer.getCurrentImage();
            if (image==null) return;
        }
        if (i ==null) {
            i = this.getInteractiveImage(image);
        }
        //logger.debug("display {} tracks on image: {}, OI: {}", tracks.size(), image.getName(), i.getClass().getSimpleName());
        boolean hyperStack = i instanceof HyperStack;
        List<List<SegmentedObject>> displayedTracks = new ArrayList<>();
        for (List<SegmentedObject> track : tracks) {
            List<ObjectDisplay> trackOD = i.toObjectDisplay(track.stream()).sorted().collect(Collectors.toList());
            boolean disp = displayTrack(image, i, trackOD, color==null?getColor(track.get(0)):color, labile, false, hideIfAlreadyDisplayed, false);
            if (disp) displayedTracks.add(track);
        }

        int minFrame = displayedTracks.stream().flatMapToInt(track -> track.stream().mapToInt(SegmentedObject::getFrame)).min().orElse(-1);
        int maxFrame = displayedTracks.stream().flatMapToInt(track -> track.stream().mapToInt(SegmentedObject::getFrame)).max().orElse(-1);
        if (minFrame > -1) {
            if (hyperStack) {
                int curFrame = ((HyperStack)i).parentIdxMapFrame.get(displayer.getFrame(image));
                if (curFrame < minFrame) displayer.setFrame(minFrame, image);
                else if (curFrame > maxFrame) displayer.setFrame(maxFrame, image);
            } else {
                Kymograph k = (Kymograph) i;
                int minSlice = k.getSlice(minFrame).mapToInt(ii->ii).min().orElse(-1);
                if (minSlice<0) return;
                int maxSlice = k.getSlice(maxFrame).mapToInt(ii->ii).max().orElse(-1);
                if (maxSlice<0) return;
                int currentSlice = displayer.getFrame(image);
                if (currentSlice < minSlice) displayer.setFrame(minSlice, image);
                else if (currentSlice > maxSlice) displayer.setFrame(maxSlice, image);
            }
        }
        updateImageRoiDisplay(image);
        //GUI.updateRoiDisplayForSelections(image, i);
    }

    protected boolean displayTrack(Image image, InteractiveImage i, List<ObjectDisplay> track, Color color, boolean labile, boolean forceDefaultDisplay, boolean hideIfAlreadyDisplayed, boolean updateDisplay) {
        //logger.debug("display selected track: image: {}, track length: {} color: {}", image, track==null?"null":track.size(), color);
        if (track==null || track.isEmpty()) return true;
        I dispImage;
        if (image==null) {
            dispImage = getDisplayer().getCurrentDisplayedImage();
            if (dispImage==null) return false;
            image = getDisplayer().getImage(dispImage);
        } else dispImage = getDisplayer().getImage(image);
        if (dispImage==null || image==null) return false;
        if (i==null) {
            i=this.getInteractiveImage(image);
            //logger.debug("image: {}, OI: {}", image.getName(), i.getClass().getSimpleName());
            if (i==null) return false;
        }
        String position = i.getParent().getPositionName();
        boolean hyperStack = i instanceof HyperStack;
        SegmentedObject trackHead = track.get(0).object.getTrackHead();
        boolean canDisplayTrack = i instanceof TimeLapseInteractiveImage;
        if (canDisplayTrack) {
            TimeLapseInteractiveImage tm = (TimeLapseInteractiveImage)i;
            tm.trimTrack(track);
            canDisplayTrack = !track.isEmpty();
        }
        Map<List<ObjectDisplay>, T> map = hyperStack ? hyperstackTrackRoiCache.get(position) : kymographTrackRoiCache.get(position);
        if (canDisplayTrack) {
            Set<T>  disp = labile ? displayedLabileTrackRois.get(image) : displayedTrackRois.get(image);
            T roi = map.get(track);
            boolean forceKymo = i instanceof Kymograph && forceDefaultDisplay;
            Structure.TRACK_DISPLAY targetTrackDisplay = forceKymo ? Structure.TRACK_DISPLAY.DEFAULT : i.getParent().getExperimentStructure().getTrackDisplay(trackHead.getStructureIdx());
            if (roi==null || (i instanceof Kymograph && !roi.getDisplayType().equals(targetTrackDisplay))) {
                roi = createTrackRoi(track, i, color, forceKymo);
                /*try { // erase malformed objects
                    roi = createTrackRoi(track, i, color, forceKymo);
                } catch (NullPointerException e) {
                    track.stream().map(o -> o.object).forEach(o -> {
                        GUI.getDBConnection().getDao(o.getPositionName()).delete(o, true, true, true);
                    });
                }*/
                map.put(track, roi);
            } else roi.setColor(color, strokeRoiOpacity, filledRoiOpacity, arrowOpacity);
            boolean alreadyDisplayed = disp != null && disp.contains(roi); // TODO : fails for partial tracks
            if (!alreadyDisplayed) {
                displayTrack(dispImage, roi);
                if (disp!=null) disp.add(roi);
            } else if (hideIfAlreadyDisplayed) {
                disp.remove(roi);
                hideTrack(dispImage, roi);
            }
            if (updateDisplay && !alreadyDisplayed) {
                updateImageRoiDisplay(image);
                int minFrame = trackHead.getFrame();
                int maxFrame = track.get(track.size() - 1).object.getFrame();
                if (hyperStack) {
                    int curFrame = ((HyperStack)i).parentIdxMapFrame.get(displayer.getFrame(image));
                    if (curFrame < minFrame || curFrame > maxFrame) displayer.setFrame(minFrame, image);
                } else  {
                    Kymograph k = (Kymograph) i;
                    int minSlice = k.getSlice(minFrame).mapToInt(ii->ii).min().getAsInt();
                    int maxSlice = k.getSlice(maxFrame).mapToInt(ii->ii).max().getAsInt();
                    int currentSlice = displayer.getFrame(image);
                    if (currentSlice < minSlice || currentSlice > maxSlice) displayer.setFrame(minSlice, image);
                }
            }
            return !hideIfAlreadyDisplayed || !alreadyDisplayed;
        } else logger.warn("image cannot display selected track: ImageObjectInterface null? {}, is Track? {}", i==null, i instanceof TimeLapseInteractiveImage);
        return false;
    }
    
    public void hideTracks(Image image, InteractiveImage i, Collection<SegmentedObject> trackHeads, boolean labile) {
        I dispImage;
        if (image==null) {
            dispImage = getDisplayer().getCurrentDisplayedImage();
            if (dispImage==null) return;
            image = getDisplayer().getImage(dispImage);
        } else dispImage = getDisplayer().getImage(image);
        if (dispImage==null || image==null) return;
        if (i==null) {
            i=this.getInteractiveImage(image);
            if (i==null) return;
        }
        String position = i.getParent().getPositionName();
        Set<T> disp = labile ? this.displayedLabileTrackRois.get(image) : displayedTrackRois.get(image);
        Map<List<ObjectDisplay>, T> map = i instanceof Kymograph ? kymographTrackRoiCache.get(position) : hyperstackTrackRoiCache.get(position);
        if (disp != null && !trackHeads.isEmpty()) {
            for (SegmentedObject th : trackHeads) {
                for (T roi : getTrackRoi(map, th)) {
                    if (disp.remove(roi)) hideTrack(dispImage, roi);
                }
            }
        }
        updateImageRoiDisplay(image);
    }

    public void hideAllRois(Image image, boolean labile, boolean nonLabile) {
        if (!labile && !nonLabile) return;
        I im = getDisplayer().getImage(image);
        if (labile) {
            Set<O> objectRois = displayedLabileObjectRois.remove(image);
            if (objectRois!=null) for (O roi : objectRois) hideObject(im, roi);
            Set<T> trackRois = displayedLabileTrackRois.remove(image);
            if (trackRois!=null) for (T roi : trackRois) hideTrack(im, roi);
        } else {
            Set<O> objectRois = displayedObjectRois.remove(image);
            if (objectRois!=null) for (O roi : objectRois) hideObject(im, roi);
            Set<T> trackRois = displayedTrackRois.remove(image);
            if (trackRois!=null) for (T roi : trackRois) hideTrack(im, roi);
        }
        updateImageRoiDisplay(image);
    }

    public List<SegmentedObject> getSelectedLabileTrackHeads(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) return Collections.emptyList();
        }
        InteractiveImage i = getInteractiveImage(image);
        if (i==null) return Collections.emptyList();
        String position = i.getParent().getPositionName();
        if (displayMode.get(image).equals(DISPLAY_MODE.TRACKS)) {
            logger.debug("getSelected Labile tracks: display all mode");
            return i.getAllObjects(interactiveObjectClassIdx).filter(SegmentedObject::isTrackHead).collect(Collectors.toList());
        }
        Set<T> rois = this.displayedLabileTrackRois.get(image);
        if (rois!=null) {
            Map<List<ObjectDisplay>, T> map = i instanceof Kymograph ? kymographTrackRoiCache.get(position) : hyperstackTrackRoiCache.get(position);
            return Utils.getKeys(map, rois).stream().map(t -> t.get(0).object.getTrackHead()).collect(Collectors.toList());
        } else return Collections.emptyList();
    }
    
    public void removeObjects(Collection<SegmentedObject> objects, boolean removeTrack) {
        if (objects.isEmpty()) return;
        for (Image image : this.displayedLabileObjectRois.keySet()) {
            InteractiveImage i = this.getInteractiveImage(image);
            if (i!=null) hideObjects(image, i.toObjectDisplay(objects.stream()).collect(Collectors.toList()), true);
        }
        for (Image image : this.displayedObjectRois.keySet()) {
            InteractiveImage i = this.getInteractiveImage(image);
            if (i!=null) hideObjects(image, i.toObjectDisplay(objects.stream()).collect(Collectors.toList()), false);
        }
        for (SegmentedObject object : objects) {
            String pos = object.getPositionName();
            objectRoiCache.get(pos).keySet().removeIf(o -> o.object.equals(object));
            persistentObjectRoiCache.get(pos).keySet().removeIf(o -> o.object.equals(object));
        }
        if (removeTrack) removeTracks(SegmentedObjectUtils.getTrackHeads(objects));

        // also children
        ExperimentStructure xp = objects.iterator().next().getExperimentStructure();
        SegmentedObjectUtils.splitByStructureIdx(objects, true).forEach((ocIdx, list) -> {
            for (int cIdx : xp.getAllDirectChildStructures(ocIdx)) {
                for (SegmentedObject o : list) removeObjects(o.getChildren(cIdx).collect(Collectors.toList()), removeTrack);
            }
        });
    }
    
    public void removeTracks(Collection<SegmentedObject> trackHeads) {
        for (Image image : this.displayedLabileTrackRois.keySet()) hideTracks(image, null, trackHeads, true);
        for (Image image : this.displayedTrackRois.keySet()) hideTracks(image, null, trackHeads, false);
        Map<String, Set<SegmentedObject>> thByPos = trackHeads.stream().collect(Collectors.groupingBy(SegmentedObject::getPositionName, Utils.collectToSet(o->o)));
        thByPos.forEach((pos, th) -> {
            removeFromMap(hyperstackTrackRoiCache.get(pos),th);
            removeFromMap(kymographTrackRoiCache.get(pos), th);
        });

    }

    protected static void removeFromMap(Map<List<ObjectDisplay>, ?> map, Set<SegmentedObject> trackHeads) {
        map.keySet().removeIf(o -> trackHeads.contains(o.get(0).object.getTrackHead()));
    }
    protected List<T> getTrackRoi(Map<List<ObjectDisplay>, T> map, SegmentedObject trackHead) {
        return map.entrySet().stream().filter(e -> e.getKey().get(0).object.getTrackHead().equals(trackHead)).map(Map.Entry::getValue).collect(Collectors.toList());
    }
    protected List<T> removeFromMap(Map<List<ObjectDisplay>, T> map, SegmentedObject trackHead) {
        List<T> res = new ArrayList<>();
        Iterator<Map.Entry<List<ObjectDisplay>, T>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<List<ObjectDisplay>, T> e = it.next();
            if (e.getKey().get(0).object.getTrackHead().equals(trackHead)) {
                res.add(e.getValue());
                it.remove();
            }
        }
        return res;
    }
    
    public void resetObjectsAndTracksRoi() {
        for (Image image : imageMapInteractiveImage.keySet()) hideAllRois(image, true, true);
        objectRoiCache.clear();
        persistentObjectRoiCache.clear();
        hyperstackTrackRoiCache.clear();
        kymographTrackRoiCache.clear();
        displayedLabileTrackRois.clear();
        displayedLabileObjectRois.clear();
        displayedTrackRois.clear();
        displayedObjectRois.clear();
        trackColor.clear();
    }
    
    public boolean goToNextTrackError(Image trackImage, List<SegmentedObject> trackHeads, boolean next, boolean forceMove) {
        //ImageObjectInterface i = imageObjectInterfaces.get(new ImageObjectInterfaceKey(rois.get(0).getParent().getTrackHead(), rois.get(0).getStructureIdx(), true));
        if (trackImage==null) {
            I selectedImage = displayer.getCurrentDisplayedImage();
            trackImage = displayer.getImage(selectedImage);
            if (trackImage==null) return false;
        }
        InteractiveImage i = this.getInteractiveImage(trackImage);
        if (i==null || i instanceof SimpleInteractiveImage) {
            logger.warn("selected image is not a track image");
            return false;
        }
        int slice = displayer.getFrame(trackImage);
        List<SegmentedObject> errors;
        if (trackHeads!=null && !trackHeads.isEmpty()) {
            errors = trackHeads.stream().flatMap(th -> SegmentedObjectUtils.getTrack(th).stream()).filter(o -> o.hasTrackLinkError(true, true)).collect(Collectors.toList());
        } else {
            trackHeads = this.getSelectedLabileTrackHeads(trackImage);
            if (trackHeads==null || trackHeads.isEmpty()) {
                errors = i.getObjects(interactiveObjectClassIdx, slice).filter(o -> o.hasTrackLinkError(true, true)).collect(Collectors.toList());
            } else {
                errors = trackHeads.stream().flatMap(th -> SegmentedObjectUtils.getTrack(th).stream()).filter(o -> o.hasTrackLinkError(true, true)).collect(Collectors.toList());
            }
        }
        if (errors.isEmpty()) return false;
        Collections.sort(errors);
        return goToNextObject(trackImage, errors, next, forceMove);
    }
    /**
     * Center this image on the objects at next (or previous, if {@param next} is false) undiplayed frames
     * @param trackImage
     * @param objects
     * @param next 
     * @return true if display has changed
     */
    public boolean goToNextObject(Image trackImage, List<SegmentedObject> objects, boolean next, boolean forceMove) {
        if (trackImage==null) {
            I selectedImage = displayer.getCurrentDisplayedImage();
            trackImage = displayer.getImage(selectedImage);
        }
        InteractiveImage i = this.getInteractiveImage(trackImage);
        if (!(i instanceof TimeLapseInteractiveImage)) {
            logger.warn("selected image is not a track image");
            return false;
        }
        int slice = displayer.getFrame(trackImage);
        TimeLapseInteractiveImage tm = (TimeLapseInteractiveImage)i;
        if (objects==null || objects.isEmpty()) objects = this.getSelectedLabileObjects(trackImage);
        if (objects.isEmpty()) return false;
        int minTimePoint, maxTimePoint;
        BoundingBox currentDisplayRange;
        if (tm instanceof HyperStack) {
            minTimePoint = tm.getParents().get(slice).getFrame();
            maxTimePoint = minTimePoint;
            currentDisplayRange = null;
        } else {
            currentDisplayRange = this.displayer.getDisplayRange(trackImage);
            Kymograph k = (Kymograph)tm;
            minTimePoint = k.getClosestFrame(currentDisplayRange.xMin(), currentDisplayRange.yMin(), slice);
            maxTimePoint = k.getClosestFrame(currentDisplayRange.xMax(), currentDisplayRange.yMax(), slice);
            if (next) {
                if (maxTimePoint == i.getParents().get(i.getParents().size() - 1).getFrame()) return false;
                //if (maxTimePoint > minTimePoint + 2) maxTimePoint -= 2;
                //else maxTimePoint--;
            } else {
                if (minTimePoint == i.getParents().get(0).getFrame()) return false;
                //if (maxTimePoint > minTimePoint + 2) minTimePoint += 2;
                //else minTimePoint++;
            }
        }
        //logger.debug("Current Display range: [{}; {}], number of objects: {} frame range: [{}; {}]", minTimePoint, maxTimePoint, objects.size(), objects.stream().mapToInt(SegmentedObject::getFrame).min().orElse(-1), objects.stream().mapToInt(SegmentedObject::getFrame).max().orElse(-1));
        objects.sort(SegmentedObjectUtils.frameComparator()); // sort by frame
        if (!forceMove) { // check if objects are already displayed and do not move
            if (objects.stream().mapToInt(SegmentedObject::getFrame).anyMatch(f -> f>= minTimePoint && f<= maxTimePoint)) return true;
        }
        SegmentedObject nextObject = getNextObject(next? maxTimePoint+1: minTimePoint-1, objects, next); // next object outside display range
        if (nextObject==null) {
            //logger.info("No object detected {} timepoint: {}", next? "after" : "before", maxTimePoint);
            return false;
        } else {
            if (i instanceof HyperStack) {
                int nextSlice = ((HyperStack)i).getSlice(nextObject.getFrame());
                if (nextSlice<0) return false;
                displayer.setFrame(nextSlice, trackImage);
                return true;
            } else {
                boolean dirX = tm instanceof KymographX;
                int nextSlice = ((Kymograph)tm).getSlice(nextObject.getFrame()).min( (s1, s2) -> { // get the slice in which object is most central
                    BoundingBox off1 = tm.getObjectOffset(nextObject, s1);
                    BoundingBox off2 = tm.getObjectOffset(nextObject, s2);
                    if (dirX) {
                        double d1 = tm.getImageProperties().xMean() - off1.xMean();
                        double d2 = tm.getImageProperties().xMean() - off2.xMean();
                        return Double.compare(Math.abs(d1), Math.abs(d2));
                    } else {
                        double d1 = tm.getImageProperties().yMean() - off1.yMean();
                        double d2 = tm.getImageProperties().yMean() - off2.yMean();
                        return Double.compare(Math.abs(d1), Math.abs(d2));
                    }
                } ).orElse(-1);
                if (nextSlice == -1) {
                    Utils.displayTemporaryMessage("Next object not found in interactive image", 5000);
                    //logger.debug("object: {} not found in interactive image", nextObject);
                    return false;
                }
                BoundingBox off = tm.getObjectOffset(nextObject, nextSlice);
                //logger.debug("object: {} with off: {} found at slice: {} (among: {}, current: {})", nextObject, off, nextSlice, ((Kymograph)tm).getSlice(nextObject.getFrame()).mapToInt(s->s).toArray(), slice);
                /*if (off == null) objects = new ArrayList<>(objects);
                while (off == null) {
                    objects = new ArrayList<>(objects);
                    objects.remove(nextObject);
                    nextObject = getNextObject(nextObject.getFrame(), objects, next);
                    if (nextObject == null) {
                        logger.info("No object detected {} timepoint: {}", next ? "after" : "before", maxTimePoint);
                        return false;
                    }
                    off = tm.getObjectOffset(nextObject, slice);
                }*/
                int midX = (off.xMin() + off.xMax()) / 2;
                if (midX + currentDisplayRange.sizeX() / 2 >= trackImage.sizeX())
                    midX = trackImage.sizeX() - currentDisplayRange.sizeX() / 2 - 1;
                if (midX - currentDisplayRange.sizeX() / 2 < 0) midX = currentDisplayRange.sizeX() / 2;

                int midY = (off.yMin() + off.yMax()) / 2;
                if (midY + currentDisplayRange.sizeY() / 2 >= trackImage.sizeY())
                    midY = trackImage.sizeY() - currentDisplayRange.sizeY() / 2 - 1;
                if (midY - currentDisplayRange.sizeY() / 2 < 0) midY = currentDisplayRange.sizeY() / 2;

                MutableBoundingBox nextDisplayRange = new MutableBoundingBox(midX - currentDisplayRange.sizeX() / 2, midX + currentDisplayRange.sizeX() / 2 -1, midY - currentDisplayRange.sizeY() / 2, midY + currentDisplayRange.sizeY() / 2 -1, currentDisplayRange.zMin(), currentDisplayRange.zMax());
                boolean move = nextSlice != slice;
                if (nextSlice != slice) displayer.setFrame(nextSlice, trackImage);
                if (!nextDisplayRange.equals(currentDisplayRange)) {
                    //logger.info("Object detected @ timepoint: {}, xMid: {}, update display range: {} (current was: {}", nextObject.getFrame(), midX, nextDisplayRange, currentDisplayRange);
                    displayer.setDisplayRange(nextDisplayRange, trackImage);
                    move = true;
                }
                return move;
            }
        }
    }
    
    private static SegmentedObject getNextObject(int timePointLimit, List<SegmentedObject> objects, boolean next) {
        if (objects.isEmpty()) return null;
        int idx = Collections.binarySearch(objects, timePointLimit, SegmentedObjectUtils.frameComparator2());
        if (idx>=0) return objects.get(idx);
        int insertionPoint = -idx-1;
        if (next) {
            if (insertionPoint<objects.size()) return objects.get(insertionPoint);
        } else {
            if (insertionPoint>0) return objects.get(insertionPoint-1);
        }
        return null;
    }

    // menu section
    
    protected Map<Image, Collection<TestDataStore>> testData = new HashMap<>();
    public void addTestData(Image image, Collection<TestDataStore> testData) {
        this.testData.put(image, testData);
    }
    protected JPopupMenu getMenu(Image image) {
        List<SegmentedObject> sel = getSelectedLabileObjects(image);
        JPopupMenu menu;
        if (sel.size()==1) menu = getMenu(sel.get(0));
        else if (!sel.isEmpty()) {
            Collections.sort(sel);
            menu = getMenu(sel);
        } else menu = new JPopupMenu();
        if (testData.containsKey(image)) { // test menu
            Collection<TestDataStore> stores = testData.get(image);
            if (sel.isEmpty()) {
                InteractiveImage ii = getInteractiveImage(null);
                int slice = displayer.getFrame(image);
                if (ii!=null) sel = ii.getObjects(interactiveObjectClassIdx, slice).collect(Collectors.toList());
            }
            //SegmentedObject o = sel.isEmpty() ? null : sel.get(0); // only first selected object
            //Predicate<TestDataStore> storeWithinSel = s-> o == null || s.getParent().equals(o.getParent(s.getParent().getStructureIdx()));
            List<SegmentedObject> finalSel = sel;
            Predicate<TestDataStore> storeWithinSel = s-> finalSel.isEmpty() || finalSel.stream().map(o -> o.getParent(s.getParent().getStructureIdx())).anyMatch(p->p.equals(s.getParent()));
            List<String> commands = stores.stream().filter(storeWithinSel).map(TestDataStore::getMiscCommands).flatMap(Set::stream).distinct().sorted().collect(Collectors.toList());
            if (!commands.isEmpty()) {
                menu.addSeparator();
                menu.add(new JMenuItem("<html><b>Test Commands/Values</b></html>"));
                List<SegmentedObject> sel2 = new ArrayList<>(sel);
                commands.forEach(s-> {
                    JMenuItem item = new JMenuItem(s);
                    menu.add(item);
                    item.setAction(new AbstractActionImpl(item.getActionCommand(), stores, storeWithinSel, sel2));
                });
            }
        } else if (sel.isEmpty()) return null;
        return menu;
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
        List<SegmentedObject> sublist = list.subList(0, Math.min(20, list.size()));
        menu.add(new JMenuItem(truncate(Utils.toStringList(sublist), TRUNC_LENGTH_MENU)));
        menu.add(new JMenuItem(truncate("Prev:"+Utils.toStringList(sublist, o->o.getPrevious()==null?"NA":o.getPrevious().toString()), TRUNC_LENGTH_MENU)));
        menu.add(new JMenuItem(truncate("Next:"+Utils.toStringList(sublist, o->o.getNext()==null?"NA":o.getNext().toString()), TRUNC_LENGTH_MENU)));
        List<String> thList = Utils.transform(sublist, o->o.getTrackHead()==null?"NA":o.getTrackHead().toString());
        replaceRepeatedValues(thList);
        menu.add(new JMenuItem(truncate("TrackHead:"+Utils.toStringList(thList), TRUNC_LENGTH_MENU)));
        //DecimalFormat df = new DecimalFormat("#.####E0");
        // getAllAttributeKeys
        Collection<String> attributeKeys = new HashSet<>();
        Collection<String> mesKeys = new HashSet<>();
        for (SegmentedObject o : list) {
            if (!o.getAttributeKeys().isEmpty()) attributeKeys.addAll(o.getAttributeKeys());
            mesKeys.addAll(o.getMeasurements().getKeys());
        }
        attributeKeys=new ArrayList<>(attributeKeys);
        Collections.sort((List)attributeKeys);
        mesKeys=new ArrayList<>(mesKeys);
        Collections.sort((List)mesKeys);
        
        if (!attributeKeys.isEmpty()) {
            menu.addSeparator();
            for (String s : attributeKeys) {
                List<Object> values = new ArrayList<>(sublist.size());
                for (SegmentedObject o : sublist) values.add(o.getAttribute(s));
                boolean number = values.stream().filter(Objects::nonNull).anyMatch(o -> o instanceof Number);
                replaceRepeatedValues(values);
                JMenuItem jmi = new JMenuItem(truncate(truncate(s, TRUNC_LENGTH)+": "+Utils.toStringList(values, v -> truncate(toString(v), TRUNC_LENGTH)), TRUNC_LENGTH_MENU));
                menu.add(jmi);
                if (number) jmi.addActionListener(evt -> displayHistogram(s, list.stream().map(o -> o.getAttribute(s)).filter(Objects::nonNull).map(o -> (Number)o).collect(Collectors.toList())));
            }
        }
        if (!mesKeys.isEmpty()) {
            menu.addSeparator();
            for (String s : mesKeys) {
                List<Object> values = new ArrayList<>(list.size());
                for (SegmentedObject o : list) values.add(o.getMeasurements().getValue(s));
                boolean number = values.stream().filter(Objects::nonNull).anyMatch(o -> o instanceof Number);
                replaceRepeatedValues(values);
                JMenuItem jmi = new JMenuItem(truncate(truncate(s, TRUNC_LENGTH)+": "+Utils.toStringList(values, v -> truncate(toString(v), TRUNC_LENGTH) ), TRUNC_LENGTH_MENU));
                menu.add(jmi);
                if (number) jmi.addActionListener(evt -> displayHistogram(s, list.stream().map(o -> o.getMeasurements().getValue(s)).filter(Objects::nonNull).map(o -> (Number)o).collect(Collectors.toList())));
            }
        }
        return menu;
    }

    private static void displayHistogram(String name, List<Number> values) {
        Histogram h = HistogramFactory.getHistogram(()->values.stream().mapToDouble(Number::doubleValue), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        double binSize = h.getBinSize();
        double min = h.getMin() + binSize/2;
        Utils.plotHistogram("Histogram of "+name, IntStream.range(0, h.getData().length).mapToDouble(i -> min + i*binSize).toArray(), LongStream.of(h.getData()).mapToDouble(l->(double)l).toArray());
    }
    
    private static void replaceRepeatedValues(List list) {
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
    private static int TRUNC_LENGTH = 30, TRUNC_LENGTH_MENU=200;
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

    public static Color getTransparentColor(Color color, boolean fill) {
        return Palette.setOpacity(color, (int)Math.round((fill? filledRoiOpacity : strokeRoiOpacity) *255));
    }

    public void showDuplicateWithAllTracks(Image image) {
        if (image == null) image = displayer.getCurrentImage();
        if (image == null) return;
        InteractiveImage i = getInteractiveImage(image);
        if (i == null) return;
        Map<SegmentedObject, List<SegmentedObject>> tracks = i.getAllObjects(interactiveObjectClassIdx).collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
        Image dup;
        if (image instanceof LazyImage5D) dup = ((LazyImage5D)image).duplicateLazyImage();
        else dup = image.duplicate();
        I disp = displayer.displayImage(dup);
        displayTracks(dup, i, tracks.values(), null, true, false);
        displayer.removeImage(dup);
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