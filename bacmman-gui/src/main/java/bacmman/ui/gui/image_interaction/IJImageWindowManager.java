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

import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.*;
import bacmman.data_structure.region_container.RegionContainerIjRoi;
import bacmman.data_structure.region_container.roi.Roi3D;
import bacmman.data_structure.region_container.roi.IJTrackRoi;
import bacmman.image.*;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.ui.GUI;
import bacmman.ui.ManualEdition;
import bacmman.utils.HashMapGetCreate;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.*;
import ij.process.FloatPolygon;

import java.awt.Color;
import java.awt.Component;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.*;
import java.awt.image.IndexColorModel;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import bacmman.plugins.ObjectSplitter;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import ij.process.ImageProcessor;

import java.awt.KeyboardFocusManager;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class IJImageWindowManager extends ImageWindowManager<ImagePlus, Roi3D, IJTrackRoi> {
    
           
    public IJImageWindowManager(ImageDisplayer<ImagePlus> displayer) {
        super(displayer);
        //new ImageJ();
    }
    /*@Override
    protected ImagePlus getImage(Image image) {
        return IJImageWrapper.getImagePlus(image);
    }*/
    @Override 
    public void addWindowListener(ImagePlus image, WindowListener wl) {
        image.getWindow().addWindowListener(wl);
    }

    String lastTool = "freeline";
    @Override
    public void toggleSetObjectCreationTool() {
        if (IJ.getToolName()=="point"||IJ.getToolName()=="multipoint") {
            ImagePlus imp = this.getDisplayer().getCurrentDisplayedImage();
            if (imp !=null) imp.deleteRoi();
            IJ.setTool(lastTool);
        }
        else {
            lastTool = IJ.getToolName();
            IJ.setTool("multipoint");
        }
    }
    @Override 
    public boolean isCurrentFocusOwnerAnImage() {
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (c==null) return false;
        //GUI.logger.debug("current focus owner class: {}", c.getClass());
        return (c instanceof ImageCanvas || c instanceof ImageWindow);
    }
    @Override
    public void addMouseListener(final Image image) {
        final ImagePlus ip = displayer.getImage(image);
        final ImageCanvas canvas = ip.getCanvas();
        if (canvas==null) {
            GUI.logger.warn("image: {} could not be set interactive", image.getName());
            return;
        }
        MouseListener ml =  new MouseListener() {
            public void mouseClicked(MouseEvent e) {
                //logger.trace("mouseclicked");
            }
            public void mousePressed(MouseEvent e) {
                //logger.debug("mousepressed");
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu menu = getMenu(image);
                    if (menu!=null) {
                        menu.show(canvas, e.getX(), e.getY());
                        e.consume();
                    }
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                //logger.debug("tool : {}", IJ.getToolName());
                if (IJ.getToolName().equals("zoom") || IJ.getToolName().equals("hand") || IJ.getToolName().equals("multipoint") || IJ.getToolName().equals("point")) return;            
                boolean ctrl = e.isControlDown();
                boolean shift = e.isShiftDown();
                boolean alt = e.isAltDown();
                //boolean ctrl = (IJ.isMacOSX() || IJ.isMacintosh()) ? e.isAltDown() : e.isControlDown(); // for mac: ctrl + click = right click -> alt instead of ctrl
                boolean freeHandSplit = ( IJ.getToolName().equals("freeline")) && ctrl && !shift; //IJ.getToolName().equals("polyline")
                boolean freeHandTool = (IJ.getToolName().equals("freeline") || IJ.getToolName().equals("oval") || IJ.getToolName().equals("ellipse"));
                boolean brush = IJ.getToolName().equals("brush");
                boolean freeHandDraw = (freeHandTool||brush) && shift && ctrl;
                boolean freeHandDrawMerge = (freeHandTool||brush) && shift && alt;
                boolean freeHandErase = brush && ctrl && !alt && !shift;
                boolean strechObjects = (IJ.getToolName().equals("line")) && ctrl;
                boolean addToSelection = shift && (!freeHandSplit && !strechObjects && !freeHandDraw && !freeHandDrawMerge && !freeHandErase);
                //logger.debug("ctrl: {}, tool : {}, freeHandSplit: {}, freehand draw: {}, freehand draw+merge {}, freehand erase: {}, addToSelection: {}", ctrl, IJ.getToolName(), freeHandSplit, freeHandDraw, freeHandDrawMerge, freeHandErase, addToSelection);
                boolean displayTrack = displayTrackMode;
                //logger.debug("button ctrl: {}, shift: {}, alt: {}, meta: {}, altGraph: {}, alt: {}", e.isControlDown(), e.isShiftDown(), e.isAltDown(), e.isMetaDown(), e.isAltGraphDown(), displayTrackMode);
                InteractiveImage i = getInteractiveImage(image);
                if (i==null) {
                    logger.trace("no image interface found");
                    return;
                }
                displayAllObjects.put(image, false);
                displayAllTracks.put(image, false);
                IJVirtualStack stack = getVirtualStack(image);
                if (stack != null) stack.resetSetFrameCallback();
                if (!addToSelection) {
                    hideAllRois(image, true, false);
                }
                List<ObjectDisplay> selectedObjects = new ArrayList<>();
                Roi r = ip.getRoi();
                boolean fromSelection = false;
                int sliceIdx = ip.getT() - 1;

                // get all objects with intersection with ROI
                if (r!=null) {
                    boolean removeAfterwards = r.getType()==Roi.FREELINE || r.getType()==Roi.FREEROI || r.getType()==Roi.LINE || r.getType()==Roi.OVAL || (r.getType()==Roi.POLYGON && r.getState()==Roi.NORMAL || r.getType()==Roi.COMPOSITE);
                    //logger.debug("Roi: {}/{}, rem: {}", r.getTypeAsString(), r.getClass(), removeAfterwards);
                    if (r.getType()==Roi.RECTANGLE ||  removeAfterwards) {
                        // starts by getting all objects within bounding box of ROI
                        fromSelection=true;
                        Rectangle rect = removeAfterwards && (r.getType()!=Roi.OVAL) ? r.getPolygon().getBounds() : r.getBounds();
                        if (rect.height==0 || rect.width==0) removeAfterwards=false;
                        MutableBoundingBox selection = new MutableBoundingBox(rect.x, rect.x+rect.width, rect.y, rect.y+rect.height, ip.getZ()-1, ip.getZ()-1);
                        //logger.debug("slice idx: {} selection: {}", sliceIdx, selection);
                        i.addObjectsWithinBounds(selection, getInteractiveObjectClass(), sliceIdx, selectedObjects);
                        //logger.debug("selection: {} #objects before remove afterwards: {}, bounds: {}, offsets: {}", selection, selectedObjects.size(), selectedObjects.stream().map(o->o.object.getBounds()).collect(Collectors.toList()), selectedObjects.stream().map(o->o.offset).collect(Collectors.toList()));
                        boolean is2D = i.is2D();
                        if (removeAfterwards || (selection.sizeX()<=2 && selection.sizeY()<=2)) {
                            FloatPolygon fPoly = r.getInterpolatedPolygon();
                            selectedObjects.removeIf(p -> !intersect(p.object, p.offset, fPoly, is2D ? -1 : ip.getZ()-1));
                        }
                        if (i instanceof Kymograph) ((Kymograph)i).addObjectsFromOverlappingSlices(selectedObjects);
                        if (!freeHandSplit || !strechObjects || !freeHandDraw || !freeHandDrawMerge || !freeHandErase) ip.deleteRoi();
                    }
                }
                // simple click : get clicked object
                if (!fromSelection && !strechObjects) {
                    int offscreenX = canvas.offScreenX(e.getX());
                    int offscreenY = canvas.offScreenY(e.getY());
                    ObjectDisplay o = i.getObjectAtPosition(offscreenX, offscreenY, ip.getZ()-1, getInteractiveObjectClass(), sliceIdx);
                    //logger.debug("click {}, {}, object: {}, ctlr:{}", offscreenX, offscreenY, o, ctrl);
                    if (o!=null) {
                        selectedObjects.add(o);
                        if (i instanceof Kymograph) ((Kymograph)i).addObjectsFromOverlappingSlices(selectedObjects);
                        //logger.debug("single click object: {} all: {}", o.object+"@"+o.getSliceIdx(), Utils.toStringList(selectedObjects, ob -> ob.object+"@"+ob.getSliceIdx()));
                    } else return;
                    if (r!=null && r.getType()==Roi.TRACED_ROI) {
                        //logger.debug("Will delete Roi: type: {}, class: {}", r.getTypeAsString(), r.getClass().getSimpleName());
                        if (!freeHandSplit) ip.deleteRoi();
                    }
                }
                if (!displayTrack && !strechObjects) { // display objects
                    displayObjects(image, selectedObjects, ImageWindowManager.defaultRoiColor, true, true);
                } else if (!strechObjects) { // display tracks
                    Set<List<SegmentedObject>> tracks = selectedObjects.stream()
                            .map(p -> p.object.getTrackHead())
                            .distinct().map(SegmentedObjectUtils::getTrack)
                            .filter(t -> !t.isEmpty())
                            .collect(Collectors.toSet());
                    displayTracks(image, i, tracks, null, true, true);
                }
                if (freeHandSplit && r!=null && !selectedObjects.isEmpty()) { // SPLIT
                    // if there are several objects per parent keep only to
                    List<SegmentedObject> objects = ObjectDisplay.getObjectList(selectedObjects);
                    //Map<SegmentedObject, List<SegmentedObject>> byParent = SegmentedObjectUtils.splitByParent(objects);
                    //objects.removeIf(o -> byParent.get(o.getParent()).size()>1);
                    // get line & split
                    FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                    ObjectSplitter splitter = new FreeLineSplitter(selectedObjects, ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints));
                    ManualEdition.splitObjects(GUI.getDBConnection(), objects, GUI.hasInstance()?GUI.getInstance().getManualEditionRelabel():true,false, splitter, true);
                } else if ((freeHandDraw||freeHandDrawMerge||freeHandErase) && r!=null) { // DRAW / ERASE
                    //if (selectedObjects.size()>1) return;
                    int parentObjectClass = i.getParent().getExperimentStructure().getParentObjectClassIdx(getInteractiveObjectClass());
                    List<ObjectDisplay> selectedParentObjects = new ArrayList<>();
                    Rectangle rect = r.getBounds();
                    MutableBoundingBox selection = new MutableBoundingBox(rect.x, rect.x+rect.width, rect.y, rect.y+rect.height, ip.getZ()-1, ip.getZ());
                    InteractiveImage ioi = getInteractiveImage(image);
                    ioi.addObjectsWithinBounds(selection, parentObjectClass, sliceIdx, selectedParentObjects);
                    if (selectedParentObjects.size()>1) {
                        logger.debug("selection is over several parents: {}", selectedParentObjects.size());
                        return;
                    } else if (selectedParentObjects.isEmpty()) {
                        logger.debug("no parent touched");
                        return;
                    }
                    SegmentedObject parent = selectedParentObjects.get(0).object;
                    Offset parentOffset = selectedParentObjects.get(0).offset;
                    Consumer<Collection<SegmentedObject>> store = l -> GUI.getDBConnection().getDao(parent.getPositionName()).store(l);
                    Collection<SegmentedObject> seg;
                    if (brush) {
                        Roi3D roi = new Roi3D(1);
                        RegionPopulation pop = parent.getChildRegionPopulation(getInteractiveObjectClass());
                        boolean is2D = pop.getRegions().isEmpty() ? parent.is2D() : pop.getRegions().get(0).is2D();
                        roi.setIs2D(is2D);
                        roi.put(ip.getZ()-1, r);
                        Region region = new Region(roi,1, roi.getBounds(), parent.getScaleXY(), parent.getScaleZ());
                        Offset revOff = new SimpleOffset(parentOffset).reverseOffset();
                        region.translate(revOff);
                        if (!freeHandErase) seg = FreeLineSegmenter.createSegmentedObject(region, parent, getInteractiveObjectClass(), GUI.getInstance().getManualEditionRelabel(), store);
                        else {
                            region.translate(parent.getBounds());
                            region.setIsAbsoluteLandmark(true);
                            seg = null;
                            List<SegmentedObject> modified = new ArrayList<>();
                            Collection<SegmentedObject> toErase = parent.getChildren(getInteractiveObjectClass())
                                    .filter(o -> o.getRegion().intersect(region))
                                    .peek(o -> o.getRegion().remove(region))
                                    .peek(modified::add)
                                    .filter(o -> o.getRegion().size()==0)
                                    .collect(Collectors.toList());
                            GUI.getDBConnection().getDao(parent.getPositionName()).delete(toErase, true, true, GUI.getInstance().getManualEditionRelabel());
                            modified.removeAll(toErase);
                            store.accept(modified);
                            resetObjects(i.getParent().getPositionName(), getInteractiveObjectClass());
                            hideLabileObjects(image, false);
                            displayObjects(image, i.toObjectDisplay(modified, sliceIdx), Color.orange , true, false);
                        }
                    } else {
                        FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                        seg = FreeLineSegmenter.segment(parent, parentOffset, ObjectDisplay.getObjectList(selectedObjects), ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints), getInteractiveObjectClass(), GUI.getInstance().getManualEditionRelabel(), store);
                    }
                    if (!freeHandErase && !seg.isEmpty()) {
                        resetObjects(i.getParent().getPositionName(), getInteractiveObjectClass());
                        hideLabileObjects(image, false);
                        if (freeHandDrawMerge && !selectedObjects.isEmpty()) {
                            seg.addAll(ObjectDisplay.getObjectList(selectedObjects));
                            ManualEdition.mergeObjects(GUI.getDBConnection(), seg, !GUI.hasInstance() || GUI.getInstance().getManualEditionRelabel(), true);
                        } else {
                            displayObjects(image, i.toObjectDisplay(seg, sliceIdx), Color.orange , true, false);
                            displayObjects(image, selectedObjects, ImageWindowManager.defaultRoiColor , true, false);
                        }
                    } else if (!freeHandErase) logger.debug("no object could be segmented");
                }
                /*if (strechObjects && r!=null && !selectedObjects.isEmpty()) {
                    Structure s = selectedObjects.get(0).key.getExperiment().getStructure(completionStructureIdx);
                    FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                    ManualObjectStrecher.strechObjects(selectedObjects, completionStructureIdx, ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints), s.getManualObjectStrechThreshold(), s.isBrightObject());
                }*/
            }

            public void mouseEntered(MouseEvent e) {
                //logger.trace("mouseentered");
            }

            public void mouseExited(MouseEvent e) {
                //logger.trace("mousexited");
            }
        };
        canvas.disablePopupMenu(true); 
        MouseListener[] mls = canvas.getMouseListeners();
        for (MouseListener m : mls) canvas.removeMouseListener(m);
        canvas.addMouseListener(ml);
        for (MouseListener m : mls) canvas.addMouseListener(m);
    }
    private static boolean intersect(SegmentedObject seg, Offset offset, FloatPolygon selection, int sliceZ) {
        //logger.debug("intersect method: {}, bounds:{}, off: {}, sel length: {}, slice: {}", seg.getRegion().getClass(), seg.getBounds(), offset, selection.npoints, sliceZ);
        if (seg.getRegion() instanceof Analytical) {
            return IntStream.range(0, selection.npoints).anyMatch(i -> {
                double x= selection.xpoints[i] - offset.xMin()+seg.getBounds().xMin() - 0.5; // -0.5 center of pixel
                double y = selection.ypoints[i] - offset.yMin()+seg.getBounds().yMin() - 0.5;
                double z = sliceZ==-1 ? 0 : sliceZ - offset.zMin()+seg.getBounds().zMin();
                Analytical s = (Analytical)seg.getRegion();
                //logger.debug("test: x={} y={} z={}, equation: {}, contains: {}, bds: {}, center: {}", x, y, z,  s.equation(x, y, z), s.contains(new Point(x, y, z)), seg.getBounds(), s.getCenter());
                return s.contains(new Point(x, y, z));
        });
        } else {
            ImageMask mask = seg.getMask();
            return IntStream.range(0, selection.npoints).anyMatch(i -> {
                int x= Math.round(selection.xpoints[i] - offset.xMin() - 0.5f );
                int y = Math.round(selection.ypoints[i] - offset.yMin() - 0.5f );
                int z = seg.is2D() ? mask.zMin() : (sliceZ==-1 ? mask.zMin() : sliceZ - offset.zMin());
                return mask.contains(x, y, z) && mask.insideMask(x, y, z);
            });
        }
    }

    @Override public void closeNonInteractiveWindows() {
        super.closeNonInteractiveWindows();
        String[] names = WindowManager.getImageTitles();
        if (names==null) return;
        for (String s : names) {
            ImagePlus ip = WindowManager.getImage(s);
            Image im = this.displayer.getImage(ip);
            if (im ==null) ip.close();
            if (interactiveImageMapImages.values().stream().flatMap(Collection::stream).noneMatch(i->i.equals(im))) ip.close();
        }
    }
    
    @Override public void setActive(Image image) {
        super.setActive(image);
        ImagePlus ip = this.displayer.getImage(image);
        if (displayer.isDisplayed(ip)) {
            IJ.selectWindow(ip.getTitle());
        } else { // not visible -> show image
            displayer.displayImage(image);
            addMouseListener(image);
            displayer.updateImageRoiDisplay(image);
        }
    }
    
    @Override
    protected Map<Integer, List<Point>> getSelectedPointsOnImage(ImagePlus image) {
        Roi r = image.getRoi();
        if (r instanceof PointRoi) {
            PointRoi pRoi = (PointRoi)r;
            Polygon p = r.getPolygon();
            Map<Integer, List<Point>> res = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.ListFactory<>());
            for (int i = 0; i<p.npoints; ++i) {
                int n = pRoi.getPointPosition(i);
                int z = n>=1 ? image.convertIndexToPosition(n)[1]-1 : 0;
                int f = n>=1 ? image.convertIndexToPosition(n)[2]-1 : 0;
                res.get(f).add(new Point(p.xpoints[i], p.ypoints[i], Math.max(0, z)));
            }
            return res;
        } else return Collections.emptyMap();
    }

    protected IJVirtualStack getVirtualStack(Image image) {
        ImagePlus ip = displayer.getImage(image);
        if (ip == null) return null;
        if (ip.getImageStack() instanceof IJVirtualStack) return (IJVirtualStack) ip.getImageStack();
        else return null;
    }

    @Override
    public void displayAllObjects(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) {
                logger.debug("no active image");
                return;
            }
        }
        InteractiveImage i = getInteractiveImage(image);
        if (i==null) {
            logger.info("no image object interface found for image: {} and structure: {}", image.getName(), interactiveObjectClassIdx);
            return;
        }
        displayAllObjects(image, displayer.getFrame(image));
        IJVirtualStack stack = getVirtualStack(image);
        if (stack!=null) {
            Image image_ = image;
            stack.resetSetFrameCallback(); // do not display contours & track at the same time
            stack.appendSetFrameCallback(slice -> displayAllObjects(image_, slice), true);
        }
    }

    public void displayAllObjects(Image image, int slice) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) {
                logger.debug("no active image");
                return;
            }
        }
        InteractiveImage i = getInteractiveImage(image);
        if (i==null) {
            logger.info("no image object interface found for image: {} and structure: {}", image.getName(), interactiveObjectClassIdx);
            return;
        }
        displayObjects(image, i.getObjectDisplay(interactiveObjectClassIdx, slice).collect(Collectors.toList()), defaultRoiColor, true, false);
    }

    @Override
    public void displayAllTracks(Image image) {
        trackColor.clear(); //randomize track colors:
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) return;
        }
        InteractiveImage i =  getInteractiveImage(image);
        if (i==null) {
            logger.info("no image object interface found for image: {} and object: {}", image.getName(), interactiveObjectClassIdx);
            return;
        }
        displayAllTracks(image, displayer.getFrame(image));
        IJVirtualStack stack = getVirtualStack(image);
        if (stack!=null) {
            Image image_ = image;
            stack.resetSetFrameCallback(); // do not display contours & track at the same time
            stack.appendSetFrameCallback(slice -> displayAllTracks(image_, slice), true);
        }
    }
    protected void displayAllTracks(Image image, int slice) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) return;
        }
        InteractiveImage i =  getInteractiveImage(image);
        if (i==null) {
            logger.info("no image object interface found for image: {} and object: {}", image.getName(), interactiveObjectClassIdx);
            return;
        }
        if (i instanceof HyperStack) {
            HyperStack k = ((HyperStack)i);
            List<List<SegmentedObject>> selTracks = k.getObjectDisplay(interactiveObjectClassIdx, slice).map(o -> new ArrayList<SegmentedObject>(1){{add(o.object);}}).collect(Collectors.toList());
            hideAllRois(image, true, false);
            Collections.shuffle(selTracks); // shuffle to randomize colors
            if (!selTracks.isEmpty()) displayTracks(image, k, selTracks, null, true, false);
        } else {
            Collection<List<SegmentedObject>> allTracks = i.getObjectDisplay(interactiveObjectClassIdx, slice).map(o->o.object).collect(Collectors.groupingBy(SegmentedObject::getTrackHead)).values();
            hideAllRois(image, true, false);
            displayTracks(image, i, allTracks, null, true, false);
        }
    }
    @Override
    public void updateImageRoiDisplay(Image image) {
        updateOverlay(image);
        displayer.updateImageRoiDisplay(image);
    }
    void updateOverlay(Image image) {
        ImagePlus ip = displayer.getImage(image);
        if (ip == null) return;
        ip.setOverlay(new Overlay());
        for (Roi3D roi : displayedObjectRois.get(image)) addObjectToOverlay(ip, roi);
        for (Roi3D roi : displayedLabileObjectRois.get(image)) addObjectToOverlay(ip, roi);
        for (IJTrackRoi roi : displayedTrackRois.get(image)) addTrackToOverlay(ip, roi);
        for (IJTrackRoi roi : displayedLabileTrackRois.get(image)) addTrackToOverlay(ip, roi);
    }

    @Override
    public void displayObject(ImagePlus image, Roi3D roi) {
        // do nothing: added at update display
    }

    void addObjectToOverlay(ImagePlus image, Roi3D roi) {
        if (image.getNFrames()>1 && image.getFrame()-1!=roi.getFrame()) return;
        Overlay o = image.getOverlay();
        if (o==null) {
            o=new Overlay();
            image.setOverlay(o);
        }
        if (image.getNSlices()>1 && roi.is2D()) {
            roi.duplicateROIUntilZ(image.getNSlices());
        }
        if (!image.isDisplayedHyperStack()) {
            if (image.getNSlices()>1) roi.setZToPosition();
            else if (image.getNFrames()>1) roi.setTToPosition();
        }
        for (Roi r : roi.values()) {
            r.setStrokeWidth(ROI_STROKE_WIDTH);
            o.add(r);
        }
    }

    @Override
    public void hideObject(ImagePlus image, Roi3D roi) {
        Overlay o = image.getOverlay();
        if (o!=null) {
            for (Roi r : roi.values()) o.remove(r);
        }
    }

    // not to be called directly!!
    @Override
    protected void hideAllRois(ImagePlus image) {
        image.setOverlay(new Overlay());
    }

    @Override
    public Roi3D generateObjectRoi(ObjectDisplay object, Color color, int frameIdx) {
        if (object.object.getBounds().sizeZ()<=0 || object.object.getBounds().sizeX()<=0 || object.object.getBounds().sizeY()<=0) GUI.logger.error("wrong object dim: o:{} {}", object.object, object.object.getBounds());
        Roi3D r;
        SegmentedObjectAccessor accessor = getAccessor();
        if (object.object.getRegion().getRoi()!=null) {
            r = object.object.getRegion().getRoi().duplicate().smooth(ROI_SMOOTH_RADIUS)
                    .translate(new SimpleOffset(object.offset).translate(new SimpleOffset(object.object.getBounds()).reverseOffset()));
        } else if (object.object.getRegion() instanceof Spot) {
            double x = object.object.getRegion().getCenter().getDoublePosition(0) + object.offset.xMin() - object.object.getBounds().xMin(); // cannot call setLocation with offset -> would remove advantage of subpixel resolution
            double y = object.object.getRegion().getCenter().getDoublePosition(1) + object.offset.yMin() - object.object.getBounds().yMin();
            double z = object.object.getRegion().getCenter().getWithDimCheck(2) + object.offset.zMin() - object.object.getBounds().zMin();
            double rad = ((Spot)object.object.getRegion()).getRadius();
            if (object.object.is2D()) {
                Roi roi = new EllipseRoi(x + 0.5, y - rad + 0.5, x + 0.5, y + rad + 0.5, 1);
                roi.setPosition(0, 1, frameIdx+1);
                r = new Roi3D(1).setIs2D(true);
                r.put(0, roi);
            } else {
                //logger.debug("display 3D spot: center: [{};{};{}] slice Z: {} rad: {}", x, y, z, sliceZ, rad);
                r = new Roi3D((int)Math.ceil(rad * 2)+1).setIs2D(false);
                double scaleR = ((Spot)object.object.getRegion()).getzAspectRatio();
                double radZ = rad * scaleR;
                for (int zz = (int)Math.max(Math.ceil(z-radZ), 0); zz<=(int)Math.ceil(z+radZ); ++zz) {
                    double curRad = Math.sqrt(rad*rad - Math.pow((z-zz)/scaleR, 2)) ; // in order to take into anisotropy into account.
                    if (curRad<0.01 * rad) continue;
                    Roi roi = new EllipseRoi(x + 0.5, y - curRad + 0.5, x + 0.5, y + curRad + 0.5, 1);
                    roi.setPosition(0, zz + 1, frameIdx+1);
                    r.put(zz, roi);
                }
            }
            BoundingBox bds = r.getBounds();
            r.setLocDelta(bds.xMin() - object.offset.xMin(), bds.yMin() - object.offset.yMin());
        } else if (object.object.getRegion() instanceof Ellipse2D) {
            double dx = object.offset.xMin() - object.object.getBounds().xMin(); // cannot call setLocation with offset -> would remove advantage of subpixel resolution
            double dy = object.offset.yMin() - object.object.getBounds().yMin();
            double z = object.object.getRegion().getCenter().getWithDimCheck(2) + object.offset.zMin() - object.object.getBounds().zMin();
            int sliceZ = (int)(Math.ceil(z));
            Ellipse2D o = (Ellipse2D)object.object.getRegion();
            List<Point> foci = o.getMajorAxisEnds();
            foci.stream().forEach(p -> p.translate(new Vector((float)dx, (float)dy)));
            Roi roi = new EllipseRoi(foci.get(0).get(0)+ 0.5, foci.get(0).get(1)+ 0.5, foci.get(1).get(0)+ 0.5, foci.get(1).get(1)+ 0.5, o.getAspectRatio());
            if (o.is2D()) sliceZ=0; // necessary ?
            roi.setPosition(0, sliceZ + 1, frameIdx+1);
            r = new Roi3D(1).setIs2D(o.is2D());
            r.put(sliceZ, roi);
            BoundingBox bds = r.getBounds();
            r.setLocDelta(bds.xMin() - object.offset.xMin(), bds.yMin() - object.offset.yMin());
            //logger.debug("creating Ellipse2D for {} @ {}, foci: {}, bds: {}, is2D: {}, parent bds: {}, loc bds: {}", object.object, object.object.getRegion().getCenter(), foci, object.object.getBounds(), object.object.getRegion().is2D(), object.object.getParent().getBounds(), object.offset);
        } else {
            //logger.debug("object: {} has container: {}, container type: {}, container ROI not null ? {}, has ROI: {}", object.object, accessor.hasRegionContainer(object.object), accessor.hasRegionContainer(object.object)? accessor.getRegionContainer(object.object).getClass() : "null", ((RegionContainerIjRoi)accessor.getRegionContainer(object.object)).getRoi()!=null, object.object.getRegion().getRoi()!=null);
            r =  RegionContainerIjRoi.createRoi(object.object.getMask(), object.offset, !object.object.is2D()).smooth(ROI_SMOOTH_RADIUS);
        }

        if (displayCorrections && object.object.getAttribute(SegmentedObject.EDITED_SEGMENTATION, false)) { // also display when segmentation is edited
            Point p = new Point((float)object.object.getBounds().xMean(), (float)object.object.getBounds().yMean());
            object.object.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(1, 1));
            p.translate(object.offset).translateRev(object.object.getBounds()); // go to kymograph offset
            Arrow arrow = new Arrow(p.get(0), p.get(1), p.get(0), p.get(1));
            arrow.enableSubPixelResolution();
            arrow.setStrokeColor(trackCorrectionColor);
            arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
            new HashSet<>(r.keySet()).stream().filter(z->z>=0).forEach((z) -> {
                Arrow arrowS = r.sizeZ()>1 ? (Arrow)arrow.clone() : arrow;
                arrowS.setPosition(0, z, frameIdx+1);
                r.put(-z-1, arrowS);
            });
        }
        setRoiAttributes(r, color, frameIdx);
        return r;
    }
    
    @Override
    protected void setRoiAttributes(Roi3D roi, Color color, int frameIdx) {
        roi.entrySet().stream().filter(e->e.getKey()>=0).forEach(e -> {
            setRoiColor(e.getValue(), color);
            e.getValue().setStrokeWidth(ROI_STROKE_WIDTH);
        });
        if (frameIdx>=0) roi.setFrame(frameIdx);
        //if (location!=null) roi.setLocation(location);
    }

    protected void setRoiColor(Roi roi, Color color) {
        if (roi instanceof ImageRoi) {
            ImageRoi r = (ImageRoi)roi;
            ImageProcessor ip = r.getProcessor();
            int value = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)Math.round(ImageWindowManager.trackRoiContourEdgeOpacity*255)).getRGB();
            int[] pixels = (int[])ip.getPixels();
            for (int i = 0; i<pixels.length; ++i) {
                if (pixels[i]!=0) pixels[i] = value;
            }
            roi.setStrokeColor(color);
        } else {
            roi.setStrokeColor(color);
        }
    }
    // track-related methods
    @Override
    public void displayTrack(ImagePlus image, IJTrackRoi roi) {
        // do nothing -> track is added at update display call
    }
    void addTrackToOverlay(ImagePlus image, IJTrackRoi roi) {
        Overlay o = image.getOverlay();
        if (o==null) {
            o=new Overlay();
            image.setOverlay(o);
        }
        if (!image.isDisplayedHyperStack()) {
            if (image.getNSlices()>1) roi.setZToPosition();
            else if (image.getNFrames()>1) roi.setTToPosition();
        }
        for (Roi r : roi) o.add(r);
        if (roi.is2D() && image.getNSlices()>1) {
            for (int z = 1; z<image.getNSlices(); ++z) {
                IJTrackRoi dup = roi.duplicateForZ(z);
                if (!image.isDisplayedHyperStack()) {
                    if (image.getNSlices() > 1) dup.setZToPosition();
                    else if (image.getNFrames() > 1) dup.setTToPosition();
                }
                for (Roi r : dup) o.add(r);
            }
        }
    }

    @Override
    public void hideTrack(ImagePlus image, IJTrackRoi roi) {
        // do nothing -> track is removed at update display call
    }

    @Override
    public IJTrackRoi generateTrackRoi(List<ObjectDisplay> track, InteractiveImage i, Color color, boolean forceDefaultDisplay) {
        if (i instanceof Kymograph) {
            if (!forceDefaultDisplay && !track.isEmpty() && track.get(0).object.getExperimentStructure().getTrackDisplay(track.get(0).object.getStructureIdx()).equals(Structure.TRACK_DISPLAY.CONTOUR)) return createContourTrackRoi(track, color, i);
            else {
                IJTrackRoi arrows = createKymographTrackRoi(track, color, i, TRACK_ARROW_STROKE_WIDTH);
                IJTrackRoi contours = createContourTrackRoi(track, color, i);
                return arrows.mergeWith(contours);
            }
        }
        else return createContourTrackRoi(track, color, i);
    }
    
    @Override
    protected void setTrackColor(IJTrackRoi roi, Color color) {
        for (Roi r : roi) if (r.getStrokeColor()!=ImageWindowManager.trackCorrectionColor && r.getStrokeColor()!=ImageWindowManager.trackErrorColor) {
            setRoiColor(r, color);
        }
        for (IJTrackRoi dup : roi.getSliceDuplicates().values()) setTrackColor(dup, color);
    }

    protected IJTrackRoi createContourTrackRoi(List<ObjectDisplay> track, Color color, InteractiveImage i) {
        IJTrackRoi trackRoi= new IJTrackRoi();
        trackRoi.setIs2D(track.get(0).object.is2D());
        Function<ObjectDisplay, Roi3D> getRoi = p -> {
            if (displayTrackEdges && ((p.object.getParent().getPrevious()!=null && p.object.getPrevious()==null) || (p.object.getParent().getNext()!=null && p.object.getNext()==null))) {
                Roi3D r = createRoiImage(p.object.getMask(), p.offset, p.object.is2D(), color, trackRoiContourEdgeOpacity);
                setRoiAttributes(r, color, p.getSliceIdx());
                return r;
            } else {
                Roi3D r = labileObjectRoiMap.get(p);
                if (r == null) r = objectRoiMap.get(p);
                if (r == null) {
                    r = generateObjectRoi(p, color, p.getSliceIdx());
                    objectRoiMap.put(p, r.duplicate());
                } else {
                    r = r.duplicate();
                    setRoiAttributes(r, color, p.getSliceIdx());
                }
                return r;
            }
        };

        track.stream().map(getRoi).filter(Objects::nonNull).flatMap(r -> r.values().stream()).forEach(trackRoi::add);
        // add flag when track links have been edited
        if (displayCorrections) {
            Predicate<SegmentedObject> edited = o -> o.getAttribute(SegmentedObject.EDITED_LINK_PREV, false) || o.getAttribute(SegmentedObject.EDITED_LINK_NEXT, false);
            Consumer<ObjectDisplay> addEditedArrow = object -> {
                if (edited.test(object.object)) { // also display when segmentation is edited
                    Point p = new Point((float) object.object.getBounds().xMean(), (float) object.object.getBounds().yMean());
                    object.object.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(1, 1));
                    p.translate(object.offset).translateRev(object.object.getBounds()); // go to kymograph offset
                    Arrow arrow = new Arrow(p.get(0), p.get(1), p.get(0), p.get(1));
                    arrow.enableSubPixelResolution();
                    arrow.setStrokeColor(trackCorrectionColor);
                    arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
                    arrow.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
                    int zMin = object.offset.zMin();
                    int zMax = object.offset.zMax();
                    if (zMin == zMax) {
                        arrow.setPosition(0, zMin + 1, object.getSliceIdx() + 1);
                        trackRoi.add(arrow);
                    } else {
                        for (int z = zMin; z <= zMax; ++z) {
                            Arrow a = (Arrow) arrow.clone();
                            a.setPosition(0, z + 1, object.getSliceIdx() + 1);
                            trackRoi.add(a);
                        }
                    }
                }
            };
            track.stream().forEach(addEditedArrow::accept);
        }
        // add arrow to indicate splitting
        Utils.TriConsumer<ObjectDisplay, ObjectDisplay, Color> addSplitArrow = (o1, o2, c) -> {
            Point p1 = o1.object.getRegion().getCenter() == null ? o1.object.getBounds().getCenter() : o1.object.getRegion().getCenter().duplicate();
            Point p2 = o2.object.getRegion().getCenter() == null ? o2.object.getBounds().getCenter() : o2.object.getRegion().getCenter().duplicate();
            p1.translate(o1.offset).translateRev(o1.object.getBounds()); // go back to hyperstack offset
            p2.translate(o2.offset).translateRev(o2.object.getBounds());
            Arrow arrow = new Arrow(p1.get(0), p1.get(1), p2.get(0), p2.get(1));
            arrow.enableSubPixelResolution();
            arrow.setDoubleHeaded(true);
            arrow.setStrokeColor(c);
            arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
            int zMin = Math.max(o1.offset.zMin(), o2.offset.zMin());
            int zMax = Math.min(o1.offset.zMax(), o2.offset.zMax());
            if (zMin==zMax) {
                arrow.setPosition(0, zMin+1, o1.getSliceIdx() + 1 );
                trackRoi.add(arrow);
            } else {
                for (int z = zMin; z <= zMax; ++z) {
                    Arrow a = (Arrow) arrow.clone();
                    a.setPosition(0, z+1, o1.getSliceIdx() + 1 );
                    trackRoi.add(a);
                }
            }
        };
        Utils.TriConsumer<ObjectDisplay, ObjectDisplay, Color> addArrow = (source, target, c) -> {
            Point p1 = source.object.getRegion().getCenter() == null ? source.object.getBounds().getCenter() : source.object.getRegion().getCenter().duplicate();
            Point p2 = target.object.getRegion().getCenter() == null ? target.object.getBounds().getCenter() : target.object.getRegion().getCenter().duplicate();
            p1.translate(source.offset).translateRev(source.object.getBounds()); // go back to hyperstack offset
            p2.translate(target.offset).translateRev(target.object.getBounds());
            Arrow arrow1 = new Arrow(p1.get(0), p1.get(1), p2.get(0), p2.get(1));
            arrow1.enableSubPixelResolution();
            arrow1.setDoubleHeaded(false);
            arrow1.setStrokeColor(c);
            arrow1.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow1.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
            int zMin = Math.max(source.offset.zMin(), target.offset.zMin());
            int zMax = Math.min(source.offset.zMax(), target.offset.zMax());
            if (zMin==zMax) {
                arrow1.setPosition(0, zMin+1, source.getSliceIdx() + 1 );
                trackRoi.add(arrow1);
            } else {
                for (int z = zMin; z <= zMax; ++z) {
                    Arrow a1 = (Arrow) arrow1.clone();
                    a1.setPosition(0, z+1, source.getSliceIdx() + 1 );
                    trackRoi.add(a1);
                }
            }
        };
        if (track.size()==1) { // when called from show all tracks : only sub-tracks of 1 frame are given as argument
            SegmentedObject o = track.get(0).object;
            int slice = track.get(0).getSliceIdx();
            if (o.getPreviousId()!=null) {
                if (o.getPrevious()==null) logger.debug("object: {} center: {}, previous null, previous id: {}", o,o.getRegion().getGeomCenter(false), o.getPreviousId());
            }
            if (o.getPreviousId()!=null && o.getPrevious()!=null && o.getPrevious().getTrackHead()!=o.getTrackHead()) {
                List<SegmentedObject> div = SegmentedObjectEditor.getNext(o.getPrevious()).collect(Collectors.toList());
                if (div.size()>1) { // only show
                    List<ObjectDisplay> divP = i.toObjectDisplay(div, slice);
                    for (ObjectDisplay other : divP) {
                        if (!other.object.equals(o) && other.object.getIdx()>o.getIdx()) addSplitArrow.accept(track.get(0), other, getColor(o.getPrevious().getTrackHead()));
                    }
                }
            }
            if (o.getNextId()!=null) {
                if (o.getNext()==null) logger.debug("object: {} center: {}, next null, next id: {}", o, o.getRegion().getGeomCenter(false), o.getNextId());
            }
            if (o.getNextId()!=null && o.getNext()!=null && !o.getNext().getTrackHead().equals(o.getTrackHead())) {
                List<SegmentedObject> merge = SegmentedObjectEditor.getPrevious(o.getNext()).collect(Collectors.toList());
                ObjectDisplay target = new ObjectDisplay(o.getNext(), i.getObjectOffset(o.getNext(), slice), slice);
                if (merge.size()>1) { // only show
                    List<ObjectDisplay> mergeP = i.toObjectDisplay(merge, slice);
                    for (ObjectDisplay other : mergeP) {
                        addArrow.accept(other, target, getColor(o.getNext().getTrackHead()));
                    }
                }
            }
        } else {
            if (track.get(track.size() - 1).object.getNextId() == null) {
                List<SegmentedObject> next = SegmentedObjectEditor.getNext(track.get(track.size() - 1).object).collect(Collectors.toList());
                int slice = track.get(track.size() - 1).getSliceIdx();
                if (next.size() > 1) { // show division by displaying arrows between objects
                    List<ObjectDisplay> nextP = i.toObjectDisplay(next, slice);
                    for (int idx = 0; idx < next.size() - 1; ++idx)
                        addSplitArrow.accept(nextP.get(idx), nextP.get(idx + 1), color);
                }
            }
            if (track.get(0).object.getPreviousId() == null) {
                List<SegmentedObject> prev = SegmentedObjectEditor.getPrevious(track.get(0).object).collect(Collectors.toList());
                ObjectDisplay target = track.get(0);
                if (prev.size() > 1) { // show merging by displaying arrows between objects
                    List<ObjectDisplay> prevP = i.toObjectDisplay(prev, target.getSliceIdx());
                    prevP.forEach(p -> addArrow.accept(p, target, color));
                }
            }
        }
        return trackRoi.setTrackType(Structure.TRACK_DISPLAY.CONTOUR);
    }

    protected static IJTrackRoi createKymographTrackRoi(List<ObjectDisplay> track, Color color, InteractiveImage i, double arrowStrokeWidth) {
        Predicate<SegmentedObject> editedprev = o -> o.getAttribute(SegmentedObject.EDITED_LINK_PREV, false);
        Predicate<SegmentedObject> editedNext = o -> o.getAttribute(SegmentedObject.EDITED_LINK_NEXT, false);
        IJTrackRoi trackRoi= new IJTrackRoi();
        trackRoi.setIs2D(track.get(0).object.is2D());
        double arrowSize = track.size()==1 ? 1.5 : 0.65;
        BiConsumer<ObjectDisplay, ObjectDisplay> appendTrackArrow = (o1, o2) -> {
            Arrow arrow;
            if (track.size()==1 && o2==null) {
                double size = arrowStrokeWidth*arrowSize;
                Point p = new Point((float)o1.object.getBounds().xMean(), (float)o1.object.getBounds().yMean());
                o1.object.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(-1, -1));
                p.translate(o1.offset).translateRev(o1.object.getBounds()); // go to kymograph offset
                arrow = new Arrow(p.get(0)-size, p.get(1)-size, p.get(0), p.get(1));
                arrow.enableSubPixelResolution();
            } else {
                Point p1 = new Point((float)o1.offset.xMean(), (float)o1.offset.yMean());
                Point p2 = new Point((float)o2.offset.xMean(), (float)o2.offset.yMean());
                double minDist = TRACK_LINK_MIN_SIZE;
                if (p1.dist(p2)>minDist) {  // get coordinates outside regions so that track links do not hide regions
                    Vector dir = Vector.vector2D(p1, p2);
                    double dirFactor = 1d;
                    dir.multiply(dirFactor/dir.norm()); // increment
                    p1.translateRev(o1.offset).translate(o1.object.getBounds()); // go to each region offset for the out-of-region test
                    p2.translateRev(o2.offset).translate(o2.object.getBounds());
                    o1.object.getRegion().translateToFirstPointOutsideRegionInDir(p1, dir);
                    o2.object.getRegion().translateToFirstPointOutsideRegionInDir(p2, dir.multiply(-1));
                    p1.translate(o1.offset).translateRev(o1.object.getBounds()); // go back to kymograph offset
                    p2.translate(o2.offset).translateRev(o2.object.getBounds());
                    // ensure there is a minimal distance
                    double d = p1.dist(p2);
                    if (d<minDist) {
                        dir.multiply((minDist-d)/(2*dirFactor));
                        p1.translate(dir);
                        p2.translateRev(dir);
                    }
                }
                arrow = new Arrow(p1.get(0), p1.get(1), p2.get(0), p2.get(1));
                arrow.enableSubPixelResolution();
                arrow.setDoubleHeaded(true);
            }
            arrow.setStrokeWidth(arrowStrokeWidth);
            arrow.setHeadSize(arrowStrokeWidth*arrowSize);
            arrow.setStrokeColor(color);
            if (displayCorrections) {
                boolean error = (o2 != null && o2.object.hasTrackLinkError(true, false)) || (o1.object.hasTrackLinkError(false, true));
                boolean correction = editedNext.test(o1.object) || (o2 != null && editedprev.test(o2.object));
                if (error || correction) {
                    Color c = error ? ImageWindowManager.trackErrorColor : ImageWindowManager.trackCorrectionColor;
                    trackRoi.add(getErrorArrow(arrow.x1, arrow.y1, arrow.x2, arrow.y2, c, color, arrowStrokeWidth, o1.getSliceIdx()+1));
                    if (o2!=null && o2.getSliceIdx() != o1.getSliceIdx()) {
                        trackRoi.add(getErrorArrow(arrow.x1, arrow.y1, arrow.x2, arrow.y2, c, color, arrowStrokeWidth, o2.getSliceIdx()+1));
                    }
                }
            }
            int zMin = Math.max(o1.offset.zMin(), o2==null ? -1 : o2.offset.zMin());
            int zMax = Math.min(o1.offset.zMax(), o2==null ? Integer.MAX_VALUE : o2.offset.zMax());
            if (zMin==zMax) {
                arrow.setPosition(0, zMin+1, o1.sliceIdx+1);
                trackRoi.add(arrow);
            } else {
                for (int z = zMin; z <= zMax; ++z) {
                    Arrow a1 = (Arrow) arrow.clone();
                    a1.setPosition(0, z+1, o1.sliceIdx+1);
                    trackRoi.add(a1);
                }
            }
            if (o2!=null && o2.sliceIdx != o1.sliceIdx) {
                if (zMin==zMax) {
                    arrow = (Arrow)arrow.clone();
                    arrow.setPosition(0, zMin+1, o2.sliceIdx+1);
                    trackRoi.add(arrow);
                } else {
                    for (int z = zMin; z <= zMax; ++z) {
                        Arrow a1 = (Arrow) arrow.clone();
                        a1.setPosition(0, z+1, o2.sliceIdx+1);
                        trackRoi.add(a1);
                    }
                }
            }
        };
        if (track.size()==1) appendTrackArrow.accept(track.get(0), null);
        else {
            IntStream.range(1, track.size()).forEach(idx -> {
                ObjectDisplay o1 = track.get(idx - 1);
                ObjectDisplay o2 = track.get(idx);
                if (o1.getSliceIdx()!=o2.getSliceIdx()) {
                    ObjectDisplay o21 = new ObjectDisplay(o2.object, i.getObjectOffset(o2.object, o1.getSliceIdx()), o1.getSliceIdx());
                    ObjectDisplay o12 = new ObjectDisplay(o1.object, i.getObjectOffset(o1.object, o2.getSliceIdx()), o2.getSliceIdx());
                    appendTrackArrow.accept(o1, o21);
                    appendTrackArrow.accept(o12, o2);
                } else appendTrackArrow.accept(o1, o2);
            });
        }
        // append previous arrows
        for (ObjectDisplay p : i.toObjectDisplay(SegmentedObjectEditor.getPrevious(track.get(0).object).collect(Collectors.toList()), track.get(0).getSliceIdx())) {
            appendTrackArrow.accept(p, track.get(0));
        }
        // append next arrows only if not displayed at current slice
        ObjectDisplay last = track.get(track.size()-1);
        if (last.object.getNext() != null && last.object.getNext().getTrackHead().equals(last.object.getTrackHead())) {
            for (ObjectDisplay n : i.toObjectDisplay(Collections.singletonList(last.object.getNext()), last.getSliceIdx())) {
                appendTrackArrow.accept(last, n);
            }
        }

        return trackRoi;
    }
    private static Arrow getErrorArrow(double x1, double y1, double x2, double y2, Color c, Color fillColor, double arrowStrokeWidth, int slice) {
        double arrowSize = arrowStrokeWidth*2;
        double norm = Math.sqrt(Math.pow(x1-x2, 2)+Math.pow(y1-y2, 2));
        double[] vNorm = new double[]{(x2-x1)/norm, (y2-y1)/norm};
        double startLength = norm-2*arrowSize;
        double endLength = norm-arrowSize;
        double[] start = startLength>0 ? new double[]{x1+vNorm[0]*startLength, y1+vNorm[1]*startLength} : new double[]{x1, y1};
        double[] end = startLength>0 ? new double[]{x1+vNorm[0]*endLength, y1+vNorm[1]*endLength} : new double[]{x2, y2};
        Arrow res =  new Arrow(start[0], start[1], end[0], end[1]);
        res.enableSubPixelResolution();
        res.setStrokeColor(c);
        res.setFillColor(fillColor);
        res.setStrokeWidth(arrowStrokeWidth);
        res.setHeadSize(Math.max(arrowStrokeWidth*1.5, 1.1));
        res.setPosition(0, 0, slice+1);
        return res;
        
        // OTHER ARROW
        /*Arrow res =  new Arrow(x1, y1, x2, y2);
        res.setStrokeColor(c);
        double size = trackArrowStrokeWidth+1.5;
        res.setStrokeWidth(size);
        res.setHeadSize(trackArrowStrokeWidth*1.5);
        return res;*/
    }

    static IndexColorModel getCM(Color color) {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        for (int i = 1; i < 256; ++i) {
            r[i] = (byte) color.getRed();
            g[i] = (byte) color.getGreen();
            b[i] = (byte) color.getBlue();
        }
        return new IndexColorModel(8, 256, r, g, b);
    }
    public static IndexColorModel getCM(Color color1, Color color2) {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        float[] hsb1 = Color.RGBtoHSB(color1.getRed(), color1.getGreen(), color1.getBlue(), null);
        float[] hsb2 = Color.RGBtoHSB(color2.getRed(), color2.getGreen(), color2.getBlue(), null);
        float b1 = hsb1[2];
        float b2 = hsb2[2];
        for (int i = 0; i < 128; ++i) {
            hsb1[2] = (float) ( (128. - i) / 128.) * b1; // towards middle = black
            hsb2[2] = (float) ( (128. - i) / 128.) * b2; // towards middle = black
            color1 = Color.getHSBColor(hsb1[0], hsb1[1], hsb1[2]);
            r[i] = (byte) color1.getRed();
            g[i] = (byte) color1.getGreen();
            b[i] = (byte) color1.getBlue();
            color2 = Color.getHSBColor(hsb2[0], hsb2[1], hsb2[2]);
            r[255-i] = (byte) color2.getRed();
            g[255-i] = (byte) color2.getGreen();
            b[255-i] = (byte) color2.getBlue();
        }
        return new IndexColorModel(8, 256, r, g, b);
    }

    public static Roi3D createRoiImage(ImageMask mask, Offset offset, boolean is3D, Color color, double opacity) {
        if (offset == null) {
            logger.error("ROI creation : offset null for mask: {}", mask.getName());
            return null;
        }
        Roi3D res = new Roi3D(mask.sizeZ()).setIs2D(!is3D);
        ImageInteger maskIm = TypeConverter.maskToImageInteger(mask, null); // copy only if necessary
        ImagePlus maskPlus = IJImageWrapper.getImagePlus(maskIm);
        for (int z = 0; z < mask.sizeZ(); ++z) {
            ImageProcessor ip = maskPlus.getStack().getProcessor(z + 1);
            ip.setColorModel(getCM(color));
            ImageRoi roi = new ImageRoi(offset.xMin(), offset.yMin(), ip);
            roi.setZeroTransparent(true);
            roi.setOpacity(opacity);
            if (roi != null) {
                Rectangle bds = roi.getBounds();
                if (bds == null) {
                    continue;
                }
                roi.setPosition(z + 1 + offset.zMin());
                res.put(z + offset.zMin(), roi);
            }

        }
        return res;
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
