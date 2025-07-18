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
import bacmman.data_structure.region_container.roi.IJRoi3D;
import bacmman.data_structure.region_container.roi.IJTrackRoi;
import bacmman.image.*;
import bacmman.processing.ImageLabeller;
import bacmman.ui.GUI;
import bacmman.ui.ManualEdition;
import bacmman.utils.*;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import bacmman.plugins.ObjectSplitter;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;

import java.awt.KeyboardFocusManager;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class IJImageWindowManager extends ImageWindowManager<ImagePlus, IJRoi3D, IJTrackRoi> {
    
           
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
        //logger.debug("current focus owner class: {}", c.getClass());
        return (c instanceof ImageCanvas || c instanceof ImageWindow);
    }
    @Override
    public void addMouseListener(final Image image) {
        final ImagePlus ip = displayer.getImage(image);
        final ImageCanvas canvas = ip.getCanvas();
        if (canvas==null) {
            logger.warn("image: {} could not be set interactive", image.getName());
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
                Roi r = ip.getRoi();
                //boolean ctrl = (IJ.isMacOSX() || IJ.isMacintosh()) ? e.isAltDown() : e.isControlDown(); // for mac: ctrl + click = right click -> alt instead of ctrl
                boolean freeHandSplit = ( IJ.getToolName().equals("freeline")) && ctrl && !shift && r!=null && (r instanceof PolygonRoi && ((PolygonRoi)r).getNCoordinates()>1); // ctrl + click = display connected tracks
                boolean freeHandTool = (IJ.getToolName().equals("freeline") || IJ.getToolName().equals("oval") || IJ.getToolName().equals("ellipse"));
                boolean brush = IJ.getToolName().equals("brush");
                boolean freeHandDraw = (freeHandTool||brush) && shift && ctrl;
                boolean freeHandDrawMerge = (freeHandTool||brush) && shift && alt;
                boolean freeHandErase = brush && ctrl && !alt && !shift;
                boolean objectEdition  = freeHandSplit || freeHandDraw || freeHandDrawMerge || freeHandErase;
                boolean addToSelection = shift && !objectEdition;
                //logger.debug("ctrl: {}, tool : {}, freeHandSplit: {}, freehand draw: {}, freehand draw+merge {}, freehand erase: {}, addToSelection: {}", ctrl, IJ.getToolName(), freeHandSplit, freeHandDraw, freeHandDrawMerge, freeHandErase, addToSelection);
                boolean displayTrack = displayTrackMode;
                //logger.debug("button ctrl: {}, shift: {}, alt: {}, meta: {}, altGraph: {}, alt: {}", e.isControlDown(), e.isShiftDown(), e.isAltDown(), e.isMetaDown(), e.isAltGraphDown(), displayTrackMode);
                InteractiveImage i = getInteractiveImage(image);
                if (i==null) {
                    logger.trace("no image interface found");
                    return;
                }
                int sliceIdx = ip.getT() - 1;

                boolean isKymoView = IJ.getToolName().equals("rectangle") && ctrl && shift && r!=null && r.getType()==Roi.RECTANGLE && i instanceof HyperStack;
                boolean isHyperView = IJ.getToolName().equals("rectangle") && ctrl && alt && r!=null && r.getType()==Roi.RECTANGLE && i instanceof HyperStack;
                if (isKymoView || isHyperView) {
                    Rectangle rect = r.getBounds();
                    if (rect.height==0 || rect.width==0) return;
                    BoundingBox view = new SimpleBoundingBox(rect.x, rect.x+rect.width, rect.y, rect.y+rect.height, 0, ip.getNSlices()-1);
                    TimeLapseInteractiveImage interactiveImageView = isKymoView ? Kymograph.generateKymograph(i.getParents(), view, i.channelNumber, i.getMaxSizeZ(), i.imageSupplier) :
                            HyperStack.generateHyperstack(i.getParents(), view, i.channelNumber, i.getMaxSizeZ(), i.imageSupplier);
                    int channelIdx = ip.getC()-1;
                    Image imageView = interactiveImageView.generateImage().setPosition(0, channelIdx);
                    addInteractiveImage(imageView, interactiveImageView, true);
                    syncView(image, interactiveImageView, imageView);
                    return;
                }

                boolean displayObjectClasses = displayMode.get(image).equals(DISPLAY_MODE.OBJECTS_CLASSES);
                boolean drawBrush = brush && (freeHandDraw||freeHandDrawMerge||freeHandErase);
                if (!displayObjectClasses || !drawBrush) {
                    if (!addToSelection || displayMode.get(image).equals(DISPLAY_MODE.TRACKS) || displayObjectClasses) hideAllRois(image, true, false);
                    displayMode.put(image, DISPLAY_MODE.NONE);
                    IJVirtualStack stack = getVirtualStack(image);
                    if (stack != null) stack.resetSetFrameCallback();
                }
                List<ObjectDisplay> selectedObjects = new ArrayList<>();

                boolean fromSelection = false;


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
                        if (!freeHandSplit || !freeHandDraw || !freeHandDrawMerge || !freeHandErase) ip.deleteRoi();
                    }
                }
                // simple click : get clicked object
                if (!fromSelection) {
                    int offscreenX = canvas.offScreenX(e.getX());
                    int offscreenY = canvas.offScreenY(e.getY());
                    ObjectDisplay o = i.getObjectAtPosition(offscreenX, offscreenY, ip.getZ()-1, getInteractiveObjectClass(), sliceIdx);
                    //logger.debug("click {}, {}, object: {}, ctlr:{}", offscreenX, offscreenY, o, ctrl);
                    if (o!=null) {
                        selectedObjects.add(o);
                        if (i instanceof Kymograph) ((Kymograph)i).addObjectsFromOverlappingSlices(selectedObjects);
                        //logger.debug("single click object: {} all: {}", o.object+"@"+o.sliceIdx, Utils.toStringList(selectedObjects, ob -> ob.object+"@"+ob.sliceIdx));
                    } else return;
                    if (r!=null && r.getType()==Roi.TRACED_ROI) {
                        //logger.debug("Will delete Roi: type: {}, class: {}", r.getTypeAsString(), r.getClass().getSimpleName());
                        if (!freeHandSplit) ip.deleteRoi();
                    }
                }
                if (!objectEdition) { // select
                    if (!displayTrack) { // display objects
                        displayObjects(image, selectedObjects, null, false, true, true);
                    } else { // display tracks
                        Set<List<SegmentedObject>> tracks = selectedObjects.stream()
                            .map(p -> p.object.getTrackHead())
                            .distinct().map(SegmentedObjectUtils::getTrack)
                            .filter(t -> !t.isEmpty())
                            .collect(Collectors.toSet());
                        if (ctrl) { // also display connected tracks
                            // TODO limit depth ?
                            List<List<SegmentedObject>> prevConnected = SegmentedObjectUtils.getConnectedTracks(tracks.stream(), true).collect(Collectors.toList());
                            List<List<SegmentedObject>> nextConnected = SegmentedObjectUtils.getConnectedTracks(tracks.stream(), false).collect(Collectors.toList());
                            while(!prevConnected.isEmpty()) {
                                tracks.addAll(prevConnected);
                                prevConnected = SegmentedObjectUtils.getConnectedTracks(prevConnected.stream(), true).collect(Collectors.toList());
                            }
                            while(!nextConnected.isEmpty()) {
                                tracks.addAll(nextConnected);
                                nextConnected = SegmentedObjectUtils.getConnectedTracks(nextConnected.stream(), false).collect(Collectors.toList());
                            }
                        }
                        displayTracks(image, i, tracks, null, true, !ctrl);

                    }
                } else { // edit
                    selectedObjects.removeIf(od -> od.sliceIdx != sliceIdx); // in case of a kymograph, selected objects can belong to several slices
                    if (freeHandSplit && r != null) { // SPLIT
                        if (selectedObjects.isEmpty()) {
                            Utils.displayTemporaryMessage("No object to split from interactive object class", 3000);
                        } else {
                            // get line & split
                            FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                            ObjectSplitter splitter = new FreeLineSplitter(selectedObjects, ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints));
                            ManualEdition.splitObjects(GUI.getDBConnection(), ObjectDisplay.getObjectList(selectedObjects), GUI.hasInstance() ? GUI.getInstance().getManualEditionRelabel() : true, false, splitter, true);
                        }
                    } else if ((freeHandDraw || freeHandDrawMerge || freeHandErase) && r != null) { // DRAW / ERASE
                        int parentObjectClass = i.getParent().getExperimentStructure().getParentObjectClassIdx(getInteractiveObjectClass());
                        List<ObjectDisplay> selectedParentObjects = new ArrayList<>();
                        Rectangle rect = r.getBounds();
                        MutableBoundingBox selection = new MutableBoundingBox(rect.x, rect.x + rect.width, rect.y, rect.y + rect.height, ip.getZ() - 1, ip.getZ());
                        i.addObjectsWithinBounds(selection, parentObjectClass, sliceIdx, selectedParentObjects);
                        if (selectedParentObjects.isEmpty()) {
                            Utils.displayTemporaryMessage("No parent touched", 3000);
                            return;
                        } else if (selectedParentObjects.size() > 1) {
                            FloatPolygon fPoly = r.getInterpolatedPolygon();
                            selectedParentObjects.removeIf(p -> !intersect(p.object, p.offset, fPoly, ip.getZ()-1));
                            if (!freeHandErase && selectedParentObjects.size()>1) {
                                Utils.displayTemporaryMessage("selection is over several parents: " + selectedParentObjects.size(), 3000);
                                return;
                            }
                        }

                        // convert drawn ROI to IJRoi
                        IJRoi3D roi = new IJRoi3D(1);
                        RegionPopulation pop = ObjectDisplay.getObjectList(selectedParentObjects).stream()
                                .filter(p -> !Utils.streamIsNullOrEmpty(p.getChildren(getInteractiveObjectClass())) )
                                .map(p -> p.getChildRegionPopulation(getInteractiveObjectClass())).findAny().orElse(null);
                        SegmentedObject po = selectedParentObjects.get(0).object;
                        boolean is2D = pop == null ? po.getExperimentStructure().is2D(getInteractiveObjectClass(), po.getPositionName()) : pop.getRegions().get(0).is2D();
                        roi.setIs2D(is2D);
                        int z = is2D ? 0 : ip.getZ() - 1;
                        roi.put(z, r);

                        List<SegmentedObject> toDisplay = new ArrayList<>();
                        boolean erasedObjects = false;
                        double eraseSizeThld = Toolbar.getBrushSize()==1 ? 0 : 1; // erase 1x1 objects when brush is not of radius 1
                        for (ObjectDisplay od : selectedParentObjects) {
                            SegmentedObject parent = od.object;
                            Offset parentOffset = new SimpleOffset(od.offset);
                            Consumer<Collection<SegmentedObject>> store = l -> parent.dao.store(l);
                            Consumer<Collection<SegmentedObject>> delete = l -> {
                                parent.dao.delete(l, true, true, GUI.getInstance().getManualEditionRelabel());
                                removeObjects(l, false); // delete from windows manager cache
                            };
                            SegmentedObject newObject;
                            if (brush) {
                                Region brushRegion = new Region(roi.duplicate(), 1, roi.getBounds().duplicate(), parent.getScaleXY(), parent.getScaleZ());
                                Offset revOff = new SimpleOffset(parentOffset).reverseOffset();
                                brushRegion.translate(revOff);
                                if (!freeHandErase) {
                                    if (!parent.getExperimentStructure().allowOverlap(getInteractiveObjectClass())) { // honnot overlap parameter
                                        brushRegion.translate(parent.getBounds());
                                        brushRegion.setIsAbsoluteLandmark(true);
                                        parent.getChildren(getInteractiveObjectClass())
                                            .filter(o -> o.getRegion().intersect(brushRegion))
                                            .forEach(o -> brushRegion.remove(o.getRegion()));
                                    }
                                    if (brushRegion.size()>0) {
                                        newObject = FreeLineSegmenter.createSegmentedObject(brushRegion, parent, getInteractiveObjectClass(), GUI.getInstance().getManualEditionRelabel(), store);
                                        toDisplay.add(newObject);
                                    } else {
                                        newObject = null;
                                        logger.error("brush has generated empty object");
                                    }
                                } else { // eraser
                                    //logger.debug("Eraser: children of {} are {}", parent, parent.getChildren(interactiveObjectClassIdx).map(o -> o+"="+o.getBounds()).collect(Collectors.toList()));
                                    brushRegion.translate(parent.getBounds());
                                    brushRegion.setIsAbsoluteLandmark(true);
                                    Set<SegmentedObject> modified = new HashSet<>();
                                    List<SegmentedObject> toDelete = new ArrayList<>();
                                    parent.getChildren(getInteractiveObjectClass())
                                        .filter(o -> o.getRegion().intersect(brushRegion))
                                        .forEach(o -> {
                                            double size = o.getRegion().size();
                                            o.getRegion().remove(brushRegion);
                                            double newSize = o.getRegion().size();
                                            //logger.debug("intersecting object: {} bounds={}->{}, size: {}->{}", o, bds, o.getBounds(), size, newSize);
                                            if (size == newSize) return;
                                            if (newSize>eraseSizeThld) modified.add(o);
                                            else toDelete.add(o);
                                        });
                                    // remaining modified objects: check if split into two objects
                                    SegmentedObjectFactory factory = getFactory(interactiveObjectClassIdx);
                                    if (!modified.isEmpty()) {
                                        new ArrayList<>(modified).forEach( o -> {
                                            List<Region> regions = ImageLabeller.labelImageList(o.getMask());
                                            if (regions.size() > 1) { // erasing has generated several objects
                                                RegionPopulation splitPop = new RegionPopulation(regions, new SimpleImageProperties(o.getMask()).resetOffset());
                                                splitPop.translate(o.getBounds(), true);
                                                List<SegmentedObject> toLink = new ArrayList<>();
                                                factory.split(o, splitPop, null).forEach(newO -> {
                                                    if (newO.getRegion().size() > eraseSizeThld) {
                                                        modified.add(newO);
                                                        toLink.add(newO);
                                                    } else factory.removeFromParent(newO);
                                                });
                                                if (o.getRegion().size() <= eraseSizeThld) {
                                                    modified.remove(o);
                                                    toDelete.add(o);
                                                } else toLink.add(o);
                                                if (!toLink.isEmpty()) {
                                                    SegmentedObjectEditor.getPrevious(o).forEach(toLink::add);
                                                    SegmentedObjectEditor.getNext(o).forEach(toLink::add);
                                                    ManualEdition.modifyObjectLinks(toLink, false, false, true, true, modified);
                                                }
                                            }
                                        });
                                        //logger.debug("After split: modified: {} and will delete: {}", modified, toDelete);
                                        modified.removeAll(toDelete);
                                        if (modified.stream().anyMatch(o -> o.getRegion().size()<=eraseSizeThld)) { // TODO check if bug remains
                                            logger.error("EMPTY OBJECT GENERATED: {}", modified.stream().filter(o -> o.getRegion().size()<=eraseSizeThld).map(o -> o+" Area:"+o.getBounds()).collect(Collectors.toList()));
                                            modified.removeIf(o -> o.getRegion().size()<=eraseSizeThld);
                                        }
                                        store.accept(modified);
                                        modified.removeIf(o -> o.getFrame() != parent.getFrame());
                                        toDisplay.addAll(modified);
                                    }
                                    if (!toDelete.isEmpty()) {
                                        delete.accept(toDelete);
                                        //logger.debug("after delete, children of {} are {}", parent, parent.getChildren(interactiveObjectClassIdx).map(o -> o+"="+o.getBounds()).collect(Collectors.toList()));
                                        erasedObjects = true;
                                    }
                                    if (parent.getChildren(interactiveObjectClassIdx).anyMatch(o->o.getRegion().size()==0)) {
                                        SegmentedObject[] nullObjects = parent.getChildren(interactiveObjectClassIdx).filter(o->o.getRegion().size()==0).toArray(SegmentedObject[]::new);
                                        logger.error("EMPTY CHILDREN REMAINING IN PARENT={}: {}", parent,  Arrays.stream(nullObjects).map(o -> o+" Area:"+o.getBounds()).collect(Collectors.toList()));
                                        factory.removeFromParent(nullObjects);
                                    }
                                    newObject = null;
                                }
                            } else {
                                FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                                newObject = FreeLineSegmenter.segment(parent, parentOffset, ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints), ip.getZ() - 1, getInteractiveObjectClass(), GUI.getInstance().getManualEditionRelabel(), store);
                                toDisplay.add(newObject);
                            }
                            if (newObject != null && !newObject.is2D() && freeHandDrawMerge) { // also add touching objects on adjacent slices
                                BoundingBox newObjectBds = newObject.getBounds();
                                parent.getChildren(newObject.getStructureIdx())
                                        .filter( c -> !c.equals(newObject) && BoundingBox.intersect2D(c.getBounds(), newObjectBds)
                                                && (BoundingBox.containsZ(c.getBounds(), newObjectBds.zMin() - 1) && newObject.getRegion().intersect(c.getRegion().intersectWithZPlane(newObjectBds.zMin()-1, true, false)) || BoundingBox.containsZ(c.getBounds(), newObjectBds.zMax() + 1) && newObject.getRegion().intersect(c.getRegion().intersectWithZPlane(newObjectBds.zMax()+1, true, false))) )
                                        .forEach(c -> {
                                            logger.debug("object touching in Z: {}", c);
                                            toDisplay.add(c);
                                            selectedObjects.add(i.toObjectDisplay(c, sliceIdx));
                                        });
                            }
                        }
                        if (freeHandErase) {
                            if (!displayObjectClasses && (!toDisplay.isEmpty() || erasedObjects)) {
                                resetObjects(i.getParent().getPositionName(), interactiveObjectClassIdx);
                                displayObjects(image, i.toObjectDisplay(toDisplay, sliceIdx), Color.orange, false, true, false);
                                GUI.updateRoiDisplayForSelections(image, i);
                            }
                        } else {
                            if (!toDisplay.isEmpty()) {
                                if (freeHandDrawMerge && !selectedObjects.isEmpty()) {
                                    toDisplay.addAll(ObjectDisplay.getObjectList(selectedObjects));
                                    ManualEdition.mergeObjects(GUI.getDBConnection(), toDisplay, !GUI.hasInstance() || GUI.getInstance().getManualEditionRelabel(), true); // !(drawBrush && displayObjectClasses)
                                    if (drawBrush && displayObjectClasses) removeObjects(toDisplay, true);
                                } else if (!(drawBrush && displayObjectClasses)) {
                                    removeObjects(toDisplay, true);
                                    resetObjects(i.getParent().getPositionName(), interactiveObjectClassIdx);
                                    hideLabileObjects(image, false);
                                    displayObjects(image, i.toObjectDisplay(toDisplay, sliceIdx), Color.orange, false, true, false);
                                    displayObjects(image, selectedObjects, null, false, true, false);
                                    GUI.updateRoiDisplayForSelections(image, i);
                                }
                            } else {
                                Utils.displayTemporaryMessage("No object could be segmented", 3000);
                            }
                        }
                    }
                    if (drawBrush && displayObjectClasses) {
                        resetObjects(i.getParent().getPositionName(), interactiveObjectClassIdx);
                        displayAllObjectClasses(image, sliceIdx);
                    }
                }
            }

            public void mouseEntered(MouseEvent e) {
                //logger.trace("mouseentered");
            }

            public void mouseExited(MouseEvent e) {
                //logger.trace("mousexited");
            }
        };
        canvas.disablePopupMenu(true); 
        //MouseListener[] mls = canvas.getMouseListeners();
        //for (MouseListener m : mls) canvas.removeMouseListener(m);
        canvas.addMouseListener(ml); // put in front
        //for (MouseListener m : mls) canvas.addMouseListener(m);
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
        if (displayer.isDisplayed(image)) {
            IJ.selectWindow(image.getName());
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
        hideAllRois(image, true, false);
        displayMode.put(image, DISPLAY_MODE.OBJECTS);
        displayAllObjects(image, displayer.getFrame(image));
        IJVirtualStack stack = getVirtualStack(image);
        if (stack!=null) {
            Image image_ = image;
            stack.resetSetFrameCallback(); // do not display contours & track at the same time
            stack.appendSetFrameCallback(slice -> displayAllObjects(image_, slice), true);
        }
    }

    protected void displayAllObjects(Image image, int slice) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) {
                logger.debug("no active image");
                return;
            }
        }
        InteractiveImage i = getInteractiveImage(image);
        if (i==null) {
            Utils.displayTemporaryMessage("Image is not interactive", 3000);
            return;
        }
        displayObjects(image, i.getObjectDisplay(interactiveObjectClassIdx, slice).collect(Collectors.toList()), null, false, true, false);
    }

    @Override
    public void displayAllObjectClasses(Image image) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) {
                logger.debug("no active image");
                return;
            }
        }
        InteractiveImage i = getInteractiveImage(image);
        if (i==null) {
            Utils.displayTemporaryMessage("Image is not interactive", 3000);
            return;
        }
        displayMode.put(image, DISPLAY_MODE.OBJECTS_CLASSES);
        displayAllObjectClasses(image, displayer.getFrame(image));
        IJVirtualStack stack = getVirtualStack(image);
        if (stack!=null) {
            Image image_ = image;
            stack.resetSetFrameCallback(); // do not display contours & track at the same time
            stack.appendSetFrameCallback(slice -> displayAllObjectClasses(image_, slice), true);
        }
    }

    protected void displayAllObjectClasses(Image image, int slice) {
        if (image==null) {
            image = getDisplayer().getCurrentImage();
            if (image==null) {
                logger.debug("no active image");
                return;
            }
        }
        InteractiveImage i = getInteractiveImage(image);
        if (i==null) {
            Utils.displayTemporaryMessage("Image is not interactive", 3000);
            return;
        }
        ExperimentStructure xp = i.getParent().getExperimentStructure();
        Color[] objectColors = xp.getObjectColors().toArray(Color[]::new);
        Set<Integer> excludeClasses= new HashSet<>();
        int oc = i.getParent().getStructureIdx();
        while(oc>=0) {
            excludeClasses.add(oc);
            oc = xp.getParentObjectClassIdx(oc);
        }
        for (int ocIdx = 0; ocIdx<objectColors.length; ++ocIdx) {
            if (excludeClasses.contains(ocIdx)) continue;
            if (objectColors[ocIdx]!=null) {
                displayObjects(image, i.getObjectDisplay(ocIdx, slice).collect(Collectors.toList()), getTransparentColor(objectColors[ocIdx], true), true, true, false);
            }
        }
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
            Utils.displayTemporaryMessage("Image is not interactive", 3000);
            return;
        }
        hideAllRois(image, true, false);
        displayMode.put(image, DISPLAY_MODE.TRACKS);
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
            Utils.displayTemporaryMessage("Image is not interactive", 3000);
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
    void updateOverlay(Image image) { // TODO explore option to call this at every frame change and add only ROIs related to current frame
        ImagePlus ip = displayer.getImage(image);
        if (ip == null) return;
        ip.setOverlay(new Overlay()); // reset overlay
        for (IJRoi3D roi : displayedObjectRois.get(image)) addObjectToOverlay(ip, roi);
        for (IJRoi3D roi : displayedLabileObjectRois.get(image)) addObjectToOverlay(ip, roi);
        for (IJTrackRoi roi : displayedTrackRois.get(image)) addTrackToOverlay(ip, roi);
        for (IJTrackRoi roi : displayedLabileTrackRois.get(image)) addTrackToOverlay(ip, roi);
    }

    @Override
    public void displayObject(ImagePlus image, IJRoi3D roi) {
        // do nothing: roi added to overlay at update display
    }

    void addObjectToOverlay(ImagePlus image, IJRoi3D roi) {
        Overlay o = image.getOverlay();
        if (o==null) {
            o=new Overlay();
            image.setOverlay(o);
        }
        if (image.getNSlices()>1 && roi.is2D()) {
            roi.duplicateROIUntilZ(image.getNSlices());
        }
        if (!image.isDisplayedHyperStack() && !(image instanceof sc.fiji.i5d.Image5D)) {
            if (image.getNSlices()>1) roi.setZToPosition();
            else if (image.getNFrames()>1) roi.setTToPosition();
        } else roi.setHyperstackPosition();
        for (Roi r : roi.values()) {
            r.setStrokeWidth(ROI_STROKE_WIDTH);
            o.add((Roi)r.clone());
        }
    }

    @Override
    public void hideObject(ImagePlus image, IJRoi3D roi) {
        Overlay o = image.getOverlay();
        if (o!=null) {
            for (Roi r : roi.values()) o.remove(r);
        }
    }

    @Override
    public IJRoi3D createObjectRoi(ObjectDisplay object, Color color, boolean fill) {
        if (object.object.getBounds().sizeZ()<=0 || object.object.getBounds().sizeX()<=0 || object.object.getBounds().sizeY()<=0) logger.error("wrong object dim: o:{} {}", object.object, object.object.getBounds());
        IJRoi3D r;
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
                roi.setPosition(0, 1, object.sliceIdx+1);
                r = new IJRoi3D(1).setIs2D(true);
                r.put(0, roi);
            } else {
                r = new IJRoi3D((int)Math.ceil(rad * 2)+1).setIs2D(false);
                double scaleR = ((Spot)object.object.getRegion()).getAspectRatioZ();
                double radZ = rad * scaleR;
                //logger.debug("display 3D spot: center: [{};{};{}] slice Z: {} rad: {} z:[{}; {}]", x, y, z, object.sliceIdx+1, rad, (int)Math.floor(Math.max(z-radZ, 0)), (int)Math.ceil(z+radZ));
                for (int zz = (int)Math.floor(Math.max(z-radZ, 0)); zz<=(int)Math.ceil(z+radZ); ++zz) {
                    double curRad = Math.sqrt(rad*rad - (scaleR==0?0:Math.pow((z-zz)/scaleR, 2))) ; // in order to take into anisotropy into account.
                    if (curRad<0.01 * rad) continue;
                    Roi roi = new EllipseRoi(x + 0.5, y - curRad + 0.5, x + 0.5, y + curRad + 0.5, 1);
                    roi.setPosition(0, zz + 1, object.sliceIdx+1);
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
            roi.setPosition(0, sliceZ + 1, object.sliceIdx+1);
            r = new IJRoi3D(1).setIs2D(o.is2D());
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
                arrowS.setPosition(0, z, object.sliceIdx+1);
                r.put(-z-1, arrowS);
            });
        }
        r.setColor(color, fill);
        r.setStrokeWidth(ROI_STROKE_WIDTH);
        r.setFrame(object.sliceIdx);
        return r;
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
        } else roi.setHyperstackPosition();
        Overlay finalO = o;
        roi.getRois().forEach(finalO::add);
        if (roi.is2D() && image.getNSlices()>1) {
            for (int z = 1; z<image.getNSlices(); ++z) {
                IJTrackRoi dup = roi.duplicateForZ(z);
                if (!image.isDisplayedHyperStack()) {
                    if (image.getNSlices() > 1) dup.setZToPosition();
                    else if (image.getNFrames() > 1) dup.setTToPosition();
                } else roi.setHyperstackPosition();
                dup.getRois().forEach(finalO::add);
            }
        }
    }

    @Override
    public void hideTrack(ImagePlus image, IJTrackRoi roi) {
        // do nothing -> track is removed at update display call
    }

    @Override
    public IJTrackRoi createTrackRoi(List<ObjectDisplay> track, InteractiveImage i, Color color, boolean forceDefaultDisplay) {
        if (i instanceof Kymograph) {
            if (!forceDefaultDisplay && !track.isEmpty() && track.get(0).object.getExperimentStructure().getTrackDisplay(track.get(0).object.getStructureIdx()).equals(Structure.TRACK_DISPLAY.CONTOUR)) return createContourTrackRoi(track, color, i, true);
            else {
                IJTrackRoi arrows = createKymographTrackRoi(track, color, i, TRACK_ARROW_STROKE_WIDTH);
                IJTrackRoi contours = createContourTrackRoi(track, color, i, false);
                return arrows.mergeWith(contours);
            }
        }
        else return createContourTrackRoi(track, color, i, true);
    }

    protected IJTrackRoi createContourTrackRoi(List<ObjectDisplay> track, Color color, InteractiveImage i, boolean flags) {
        IJTrackRoi trackRoi= new IJTrackRoi().setTrackType(Structure.TRACK_DISPLAY.CONTOUR).setIs2D(track.get(0).object.is2D());
        String position = track.get(0).object.getPositionName();
        Function<ObjectDisplay, IJRoi3D> getRoi = p -> {
            boolean edge = flags && displayTrackEdges && ((p.object.getParent().getPrevious()!=null && p.object.getPrevious()==null) || (p.object.getParent().getNext()!=null && p.object.getNext()==null));
            Map<ObjectDisplay, IJRoi3D> cache = objectRoiCache.get(position);
            IJRoi3D r = cache.get(p);
            if (r == null) {
                r = createObjectRoi(p, getTransparentColor(color, edge), edge);
                cache.put(p, r.duplicate());
            } else {
                r = r.duplicate().setHyperstackPosition();
                r.setColor(getTransparentColor(color, edge), edge);
            }
            if (r.values().stream().anyMatch(Objects::isNull)) {
                List<Integer> nullFrames = r.entrySet().stream().filter(e -> e.getValue()==null).map(Map.Entry::getKey).collect(Collectors.toList());
                logger.debug("track: {} object: {} loc {} size: {} has null ROI at frames={}", track.get(0).object, p.object, p.offset, p.object.getRegion().size(), nullFrames);
            }
            if (r.is2D() && r.get(0)==null) { // TODO why can this happen
                logger.debug("track: {} object: {} loc {} size: {}, slices: {} is2D but has no ROI at slice 0", track.get(0).object, p.object, p.offset, p.object.getRegion().size(), r.keySet());
            }
            return r;
        };

        track.stream().map(getRoi)
            .flatMap(r -> r.is2D() ? Stream.of(r.get(0)).filter(Objects::nonNull) // TODO why can this happen ?
                    : r.values().stream())
                .forEach(trackRoi::addObject);
        // add flag when track links have been edited
        if (flags && displayCorrections) {
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
                        arrow.setPosition(0, zMin + 1, object.sliceIdx + 1);
                        trackRoi.addFlag(arrow, ()->trackCorrectionColor);
                    } else {
                        for (int z = zMin; z <= zMax; ++z) {
                            Arrow a = (Arrow) arrow.clone();
                            a.setPosition(0, z + 1, object.sliceIdx + 1);
                            trackRoi.addFlag(a, ()->trackCorrectionColor);
                        }
                    }
                }
            };
            track.stream().forEach(addEditedArrow::accept);
        }

        if (flags) { // add arrows to indicate splitting / merging
            Utils.TriConsumer<ObjectDisplay, ObjectDisplay, Supplier<Color>> addArrow = (source, target, c) -> {
                Point p1 = source.object.getRegion().getCenter() == null ? source.object.getBounds().getCenter() : source.object.getRegion().getCenter().duplicate();
                Point p2 = target.object.getRegion().getCenter() == null ? target.object.getBounds().getCenter() : target.object.getRegion().getCenter().duplicate();
                p1.translate(source.offset).translateRev(source.object.getBounds()); // go back to hyperstack offset
                p2.translate(target.offset).translateRev(target.object.getBounds());
                Arrow arrow1 = new Arrow(p1.get(0), p1.get(1), p2.get(0), p2.get(1));
                arrow1.enableSubPixelResolution();
                arrow1.setDoubleHeaded(false);
                Color col = c.get();
                if (col == null) col = color;
                arrow1.setStrokeColor(col);
                arrow1.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
                arrow1.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
                int zMin = Math.max(source.offset.zMin(), target.offset.zMin());
                int zMax = Math.min(source.offset.zMax(), target.offset.zMax());
                if (zMin==zMax) {
                    arrow1.setPosition(0, zMin+1, source.sliceIdx + 1 );
                    trackRoi.addFlag(arrow1, c);
                } else {
                    for (int z = zMin; z <= zMax; ++z) {
                        Arrow a1 = (Arrow) arrow1.clone();
                        a1.setPosition(0, z+1, source.sliceIdx + 1 );
                        trackRoi.addFlag(a1, c);
                    }
                }
            };
            SegmentedObject head = track.get(0).object;
            if (head.getPreviousId() != null && head.getPrevious() != null && head.getPrevious().getTrackHead() != head.getTrackHead()) { // split
                i.toObjectDisplay(Stream.of(head.getPrevious())).forEach(od -> addArrow.accept(od, track.get(0), ()->getColor(od.object.getTrackHead())));
            }
            SegmentedObject tail = track.get(track.size()-1).object;
            if (tail.getNextId() != null && tail.getNext() != null && !tail.getNext().getTrackHead().equals(tail.getTrackHead())) { // merge
                i.toObjectDisplay(Stream.of(tail.getNext())).forEach(od -> addArrow.accept(track.get(track.size() - 1), od, () -> getColor(od.object.getTrackHead())));
            }
            if (track.size()==1 && !tail.isTrackHead()) { // special case: in display all mode, only track subset of length 1 are displayed. display split link from the previous track
                if (tail.getNextId()==null) {
                    i.toObjectDisplay(SegmentedObjectEditor.getNext(tail)).forEach(od -> addArrow.accept(track.get(0), od, () -> getColor(od.object.getTrackHead())));
                }
            }


        }
        return trackRoi;
    }

    protected static IJTrackRoi createKymographTrackRoi(List<ObjectDisplay> track, Color color, InteractiveImage i, double arrowStrokeWidth) {
        Predicate<SegmentedObject> editedprev = o -> o.getAttribute(SegmentedObject.EDITED_LINK_PREV, false);
        Predicate<SegmentedObject> editedNext = o -> o.getAttribute(SegmentedObject.EDITED_LINK_NEXT, false);
        IJTrackRoi trackRoi= new IJTrackRoi();
        trackRoi.setIs2D(track.get(0).object.is2D());
        double arrowSize = 0.65;
        Color arrowColor = Palette.setOpacity(color, (int)Math.round(arrowOpacity * 255));
        BiConsumer<ObjectDisplay, ObjectDisplay> appendTrackArrow = (o1, o2) -> {
            Arrow arrow;
            if (track.size()==1 && o2==null) { // arrow that shows the object
                double size = arrowStrokeWidth*1.5;
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
                arrow.setDoubleHeaded(false);
            }
            arrow.setStrokeWidth(arrowStrokeWidth);
            arrow.setHeadSize(arrowStrokeWidth*arrowSize);
            arrow.setStrokeColor(arrowColor);
            if (displayCorrections) {
                boolean error = (o2 != null && o2.object.hasTrackLinkError(true, false)) || (o1.object.hasTrackLinkError(false, true));
                boolean correction = editedNext.test(o1.object) || (o2 != null && editedprev.test(o2.object));
                if (error || correction) {
                    Color c = error ? ImageWindowManager.trackErrorColor : ImageWindowManager.trackCorrectionColor;
                    trackRoi.addFlag(getErrorArrow(arrow.x1, arrow.y1, arrow.x2, arrow.y2, c, arrowColor, arrowStrokeWidth, o1.sliceIdx+1), null);
                }
            }
            int zMin = Math.max(o1.offset.zMin(), o2==null ? -1 : o2.offset.zMin());
            int zMax = Math.min(o1.offset.zMax(), o2==null ? Integer.MAX_VALUE : o2.offset.zMax());
            if (zMin==zMax) {
                arrow.setPosition(0, zMin+1, o1.sliceIdx+1);
                trackRoi.addLink(arrow);
            } else {
                for (int z = zMin; z <= zMax; ++z) {
                    Arrow a1 = (Arrow) arrow.clone();
                    a1.setPosition(0, z+1, o1.sliceIdx+1);
                    trackRoi.addLink(a1);
                }
            }
        };
        if (track.size()==1) {
            //appendTrackArrow.accept(track.get(0), null); // no need to display arrow on single object when contours are drawn
        } else {
            IntStream.range(1, track.size()).forEach(idx -> {
                ObjectDisplay o1 = track.get(idx - 1);
                ObjectDisplay o2 = track.get(idx);
                if (o1.sliceIdx!=o2.sliceIdx) {
                    SegmentedObject o1Next = o1.object.getNext();
                    SegmentedObject o2Prev = o2.object.getPrevious();
                    //if (o1Next == null || o2Prev == null) logger.error("Error displaying over distinct slices: o1={} next={} o2={} prev={} s1={} s2={}", o1.object, o1.object.getNext(), o2.object, o2.object.getPrevious(), o1.sliceIdx, o2.sliceIdx);
                    if (o1Next!=null) {
                        BoundingBox off = i.getObjectOffset(o1Next, o1.sliceIdx);
                        if (off != null) {
                            ObjectDisplay o1N = new ObjectDisplay(o1Next, off, o1.sliceIdx);
                            appendTrackArrow.accept(o1, o1N);
                        } else {
                            logger.debug("error displaying object: {} next from : {} @ slice: {}", o1Next, o1, o1.sliceIdx);
                        }
                    }
                    if (o2Prev!=null) {
                        BoundingBox off = i.getObjectOffset(o2Prev, o2.sliceIdx);
                        if (off != null) {
                            ObjectDisplay o2P = new ObjectDisplay(o2Prev, off, o2.sliceIdx);
                            appendTrackArrow.accept(o2P, o2);
                        } else {
                            logger.debug("error displaying object: {} prev from : {} @ slice: {}", o2Prev, o2, o2.sliceIdx);
                        }
                    }
                } else appendTrackArrow.accept(o1, o2);
            });
        }
        // append previous arrows
        track.stream().filter(o -> o.object.isTrackHead()).forEach( head -> {
            for (ObjectDisplay p : i.toObjectDisplay(SegmentedObjectEditor.getPrevious(head.object).collect(Collectors.toList()), head.sliceIdx)) {
                appendTrackArrow.accept(p, head);
            }
        });
        // append next arrows only if not displayed at current slice
        ObjectDisplay last = track.get(track.size()-1);
        if (last.object.getNext() != null && last.object.getNext().getTrackHead().equals(last.object.getTrackHead())) {
            Offset off = i.getObjectOffset(last.object.getNext(), last.sliceIdx);
            if (off != null) {
                ObjectDisplay n = new ObjectDisplay(last.object.getNext(), off, last.sliceIdx);
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
    }

    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
