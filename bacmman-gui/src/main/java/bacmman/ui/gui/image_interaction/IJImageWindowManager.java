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
import bacmman.data_structure.region_container.roi.TrackRoi;
import bacmman.image.*;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.ui.GUI;
import bacmman.ui.ManualEdition;
import bacmman.utils.HashMapGetCreate;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.*;
import ij.process.ColorProcessor;
import ij.process.FloatPolygon;

import java.awt.Color;
import java.awt.Component;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.*;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import bacmman.plugins.ObjectSplitter;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
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
public class IJImageWindowManager extends ImageWindowManager<ImagePlus, Roi3D, TrackRoi> {
    
           
    public IJImageWindowManager(ImageObjectListener listener, ImageDisplayer<ImagePlus> displayer) {
        super(listener, displayer);
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
            ImagePlus imp = this.getDisplayer().getCurrentImage();
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
                InteractiveImage i = getImageObjectInterface(image);
                boolean hyperstack = i instanceof HyperStack;

                int completionStructureIdx=-1;
                if (strechObjects) { // select parents
                    completionStructureIdx = i.getChildStructureIdx();
                    if (i.getChildStructureIdx()!=i.parentStructureIdx) i = IJImageWindowManager.super.getImageObjectInterface(image, i.parentStructureIdx);
                    //logger.debug("Strech: children: {}, current IOI: {}", completionStructureIdx, i.getChildStructureIdx());
                }
                if (i==null) {
                    logger.trace("no image interface found");
                    return;
                }
                if (!addToSelection) {
                    if (listener!=null) {
                        //listener.fireObjectDeselected(Pair.unpair(getLabileObjects(image)));
                        //listener.fireTracksDeselected(getLabileTrackHeads(image));
                        listener.fireDeselectAllObjects(i.childStructureIdx);
                        listener.fireDeselectAllTracks(i.childStructureIdx);
                    }
                    hideAllRois(image, true, false);
                }
                List<Pair<SegmentedObject, BoundingBox>> selectedObjects = new ArrayList<>();
                Roi r = ip.getRoi();
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
                        //logger.debug("selection: {}", selection);
                        if (selection.sizeX()==0 && selection.sizeY()==0) selection=null;
                        i.addClickedObjects(selection, selectedObjects);
                        //logger.debug("selection: {} #objects before remove afterwards: {}, bounds: {}, offsets: {}", selection, selectedObjects.size(), selectedObjects.stream().map(o->o.key.getBounds()).collect(Collectors.toList()), selectedObjects.stream().map(o->o.value).collect(Collectors.toList()));
                        boolean is2D = i.is2D();
                        if (removeAfterwards || (selection.sizeX()<=2 && selection.sizeY()<=2)) {
                            FloatPolygon fPoly = r.getInterpolatedPolygon();
                            selectedObjects.removeIf(p -> !intersect(p.key, p.value, fPoly, is2D ? -1 : ip.getZ()-1));
                        }
                        if (!freeHandSplit || !strechObjects || !freeHandDraw || !freeHandDrawMerge || !freeHandErase) ip.deleteRoi();
                    }
                }
                // simple click : get clicked object // TODO not used anymore ?
                if (!fromSelection && !strechObjects) {
                    int offscreenX = canvas.offScreenX(e.getX());
                    int offscreenY = canvas.offScreenY(e.getY());
                    Pair<SegmentedObject, BoundingBox> o = i.getClickedObject(offscreenX, offscreenY, ip.getZ()-1);
                    //logger.debug("click {}, {}, object: {} (total: {}, parent: {}), ctlr:{}", x, y, o, i.getObjects().size(), ctrl);
                    if (o!=null) {
                        selectedObjects.add(o);
                        //logger.debug("selected object: "+o.key);
                    } else return;
                    if (r!=null && r.getType()==Roi.TRACED_ROI) {
                        //logger.debug("Will delete Roi: type: {}, class: {}", r.getTypeAsString(), r.getClass().getSimpleName());
                        if (!freeHandSplit) ip.deleteRoi();
                    }
                }
                if (!displayTrack && !strechObjects) {
                    //logger.debug("objects to display: {}", selectedObjects);
                    displayObjects(image, selectedObjects, ImageWindowManager.defaultRoiColor, true, true);
                    if (listener!=null) {
                        //List<Pair<StructureObject, BoundingBox>> labiles = getSelectedLabileObjects(image);
                        //fire deselected objects
                        listener.fireObjectSelected(Pair.unpairKeys(selectedObjects), true);
                    }
                    if (hyperstack) {
                        ((HyperStack)i).setChangeIdxCallback(null); // stop showing all objects/tracks
                        ((HyperStack)i).setDisplayAllObjects(false);
                    }
                } else if (!strechObjects) { // display tracks
                    if (!hyperstack) {
                        List<SegmentedObject> trackHeads = new ArrayList<>();
                        for (Pair<SegmentedObject, BoundingBox> p : selectedObjects)
                            trackHeads.add(p.key.getTrackHead());
                        Utils.removeDuplicates(trackHeads, false);
                        for (SegmentedObject th : trackHeads) {
                            List<SegmentedObject> track = SegmentedObjectUtils.getTrack(th);
                            List<Pair<SegmentedObject, BoundingBox>> disp = i.pairWithOffset(track);
                            Color c = hyperstack ? getColor(track.get(0)) : ImageWindowManager.getColor();
                            displayTrack(image, i, disp, c, true, true, false);
                            displayObjects(image, disp, c, true, false);
                        }
                        if (listener != null) listener.fireTracksSelected(trackHeads, true);
                    } else {
                        ((HyperStack)i).setChangeIdxCallback(null); // stop showing all objects/tracks
                        Set<List<SegmentedObject>> tracks = selectedObjects.stream().map(p -> p.key.getTrackHead()).distinct().map(SegmentedObjectUtils::getTrack).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
                        if (!addToSelection) hideAllRois(image, true, false);
                        displayTracks(image, i, tracks, true);
                    }
                }
                if (freeHandSplit && r!=null && !selectedObjects.isEmpty()) {
                    // if there are several objects per parent keep only to
                    List<SegmentedObject> objects = Pair.unpairKeys(selectedObjects);
                    //Map<SegmentedObject, List<SegmentedObject>> byParent = SegmentedObjectUtils.splitByParent(objects);
                    //objects.removeIf(o -> byParent.get(o.getParent()).size()>1);
                    // get line & split
                    FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                    ObjectSplitter splitter = new FreeLineSplitter(selectedObjects, ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints));
                    ManualEdition.splitObjects(GUI.getDBConnection(), objects, GUI.hasInstance()?GUI.getInstance().getManualEditionRelabel():true,false, splitter, true);
                } else if ((freeHandDraw||freeHandDrawMerge||freeHandErase) && r!=null) {
                    //if (selectedObjects.size()>1) return;
                    int parentStructure = i.getParent().getExperimentStructure().getParentObjectClassIdx(i.getChildStructureIdx());
                    List<Pair<SegmentedObject, BoundingBox>> selectedParentObjects = new ArrayList<>();
                    Rectangle rect = r.getBounds();
                    MutableBoundingBox selection = new MutableBoundingBox(rect.x, rect.x+rect.width, rect.y, rect.y+rect.height, ip.getZ()-1, ip.getZ());
                    InteractiveImage ioi = getImageObjectInterface(image, parentStructure);
                    ioi.addClickedObjects(selection, selectedParentObjects);
                    if (selectedParentObjects.size()>1) {
                        logger.debug("selection is over several parents: {}", selectedParentObjects.size());
                        return;
                    } else if (selectedParentObjects.isEmpty()) {
                        logger.debug("no parent touched");
                        return;
                    }
                    SegmentedObject parent = selectedParentObjects.get(0).key;
                    Offset parentOffset = selectedParentObjects.get(0).value;
                    Consumer<Collection<SegmentedObject>> store = l -> GUI.getDBConnection().getDao(parent.getPositionName()).store(l);
                    Collection<SegmentedObject> seg;
                    if (brush) {
                        Roi3D roi = new Roi3D(1);
                        RegionPopulation pop = parent.getChildRegionPopulation(i.getChildStructureIdx());
                        boolean is2D = pop.getRegions().isEmpty() ? parent.is2D() : pop.getRegions().get(0).is2D();
                        roi.setIs2D(is2D);
                        roi.put(ip.getZ()-1, r);
                        Region region = new Region(roi,1, roi.getBounds(), parent.getScaleXY(), parent.getScaleZ());
                        Offset revOff = new SimpleOffset(parentOffset).reverseOffset();
                        region.translate(revOff);
                        if (!freeHandErase) seg = FreeLineSegmenter.createSegmentedObject(region, parent, i.getChildStructureIdx(), GUI.getInstance().getManualEditionRelabel(), store);
                        else {
                            region.translate(parent.getBounds());
                            region.setIsAbsoluteLandmark(true);
                            seg = null;
                            List<SegmentedObject> modified = new ArrayList<>();
                            Collection<SegmentedObject> toErase = parent.getChildren(i.getChildStructureIdx())
                                    .filter(o -> o.getRegion().intersect(region))
                                    .peek(o -> o.getRegion().remove(region))
                                    .peek(modified::add)
                                    .filter(o -> o.getRegion().size()==0)
                                    .collect(Collectors.toList());
                            GUI.getDBConnection().getDao(parent.getPositionName()).delete(toErase, true, true, GUI.getInstance().getManualEditionRelabel());
                            modified.removeAll(toErase);
                            store.accept(modified);
                            reloadObjects_(i.getParent(), i.getChildStructureIdx(), true);
                            hideLabileObjects(image);
                            displayObjects(image, i.pairWithOffset(modified), Color.orange , true, false);
                        }
                    } else {
                        FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                        seg = FreeLineSegmenter.segment(parent, parentOffset, Pair.unpairKeys(selectedObjects), ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints), i.getChildStructureIdx(), GUI.getInstance().getManualEditionRelabel(), store);
                    }
                    if (!freeHandErase && !seg.isEmpty()) {
                        reloadObjects_(i.getParent(), i.getChildStructureIdx(), true);
                        hideLabileObjects(image);
                        if (freeHandDrawMerge && !selectedObjects.isEmpty()) {
                            seg.addAll(Pair.unpairKeys(selectedObjects));
                            ManualEdition.mergeObjects(GUI.getDBConnection(), seg, !GUI.hasInstance() || GUI.getInstance().getManualEditionRelabel(), true);
                        } else {
                            displayObjects(image, i.pairWithOffset(seg), Color.orange , true, false);
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
    @Override public void registerInteractiveHyperStackFrameCallback(Image image, HyperStack k, boolean interactive) {
        ImagePlus ip = displayer.getImage(image);
        if (ip!=null && ip.getImageStack() instanceof IJVirtualStack) {
            //logger.debug("registering frame callback on image: {} for kymograph : {}", image.hashCode(), k==null ? "null" : k.hashCode());
            if (k==null) {
                ((IJVirtualStack)ip.getImageStack()).resetSetFrameCallback();
            } else {
                ((IJVirtualStack) ip.getImageStack()).appendSetFrameCallback(k::setIdx);
                if (interactive) ((IJVirtualStack) ip.getImageStack()).appendSetFrameCallback(i -> GUI.updateRoiDisplayForSelections(image, k));
                ((IJVirtualStack) ip.getImageStack()).updateFrameCallback();
                ip.getWindow().addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent windowEvent) {
                        ((IJVirtualStack)ip.getImageStack()).resetSetFrameCallback();
                    }
                    @Override
                    public void windowGainedFocus(WindowEvent e) {
                        int currentFrame = ip.getT()-1;
                        if (k.getIdx()!=currentFrame) {
                            ((IJVirtualStack) ip.getImageStack()).updateFrameCallback();
                        }
                    }
                    @Override
                    public void windowActivated(WindowEvent e) {
                        int currentFrame = ip.getT()-1;
                        if (k.getIdx()!=currentFrame) {
                            ((IJVirtualStack) ip.getImageStack()).updateFrameCallback();
                        }
                    }
                });
            }
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
            if (!imageObjectInterfaceMap.keySet().contains(im)) ip.close();
        }
    }
    
    @Override public void setActive(Image image) {
        super.setActive(image);
        ImagePlus ip = this.displayer.getImage(image);
        if (displayer.isDisplayed(ip)) {
            IJ.selectWindow(ip.getTitle());
        } else { // not visible -> show image
            displayer.showImage(image);
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
    
    @Override
    public void displayObject(ImagePlus image, Roi3D roi) {
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
    public Roi3D generateObjectRoi(Pair<SegmentedObject, BoundingBox> object, Color color, int frameIdx) {
        if (object.key.getBounds().sizeZ()<=0 || object.key.getBounds().sizeX()<=0 || object.key.getBounds().sizeY()<=0) GUI.logger.error("wrong object dim: o:{} {}", object.key, object.key.getBounds());
        Roi3D r;
        SegmentedObjectAccessor accessor = getAccessor();
        // TODO why following condition is false when multiple selection ?
        //if (accessor.hasRegionContainer(object.key) && accessor.getRegionContainer(object.key) instanceof RegionContainerIjRoi && ((RegionContainerIjRoi)accessor.getRegionContainer(object.key)).getRoi()!=null) { // look for existing ROI
        if (object.key.getRegion().getRoi()!=null) {
            //logger.debug("object: {} has IJROICONTAINER", object.key);
            r = object.key.getRegion().getRoi().duplicate().smooth(ROI_SMOOTH_RADIUS)
            //r = ((RegionContainerIjRoi)accessor.getRegionContainer(object.key)).getRoi().duplicate()
                    .translate(new SimpleOffset(object.value).translate(new SimpleOffset(object.key.getBounds()).reverseOffset()));
        } else if (object.key.getRegion() instanceof Spot) {
            double x = object.key.getRegion().getCenter().getDoublePosition(0) + object.value.xMin() - object.key.getBounds().xMin(); // cannot call setLocation with offset -> would remove advantage of subpixel resolution
            double y = object.key.getRegion().getCenter().getDoublePosition(1) + object.value.yMin() - object.key.getBounds().yMin();
            double z = object.key.getRegion().getCenter().getWithDimCheck(2) + object.value.zMin() - object.key.getBounds().zMin();
            double rad = ((Spot)object.key.getRegion()).getRadius();
            if (object.key.is2D()) {
                Roi roi = new EllipseRoi(x + 0.5, y - rad + 0.5, x + 0.5, y + rad + 0.5, 1);
                roi.setPosition(0, 1, frameIdx+1);
                r = new Roi3D(1).setIs2D(true);
                r.put(0, roi);
            } else {
                //logger.debug("display 3D spot: center: [{};{};{}] slice Z: {} rad: {}", x, y, z, sliceZ, rad);
                r = new Roi3D((int)Math.ceil(rad * 2)+1).setIs2D(false);
                double scaleR = ((Spot)object.key.getRegion()).getzAspectRatio();
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
            r.setLocDelta(bds.xMin() - object.value.xMin(), bds.yMin() - object.value.yMin());
        } else if (object.key.getRegion() instanceof Ellipse2D) {
            double dx = object.value.xMin() - object.key.getBounds().xMin(); // cannot call setLocation with offset -> would remove advantage of subpixel resolution
            double dy = object.value.yMin() - object.key.getBounds().yMin();
            double z = object.key.getRegion().getCenter().getWithDimCheck(2) + object.value.zMin() - object.key.getBounds().zMin();
            int sliceZ = (int)(Math.ceil(z));
            Ellipse2D o = (Ellipse2D)object.key.getRegion();
            List<Point> foci = o.getMajorAxisEnds();
            foci.stream().forEach(p -> p.translate(new Vector((float)dx, (float)dy)));
            Roi roi = new EllipseRoi(foci.get(0).get(0)+ 0.5, foci.get(0).get(1)+ 0.5, foci.get(1).get(0)+ 0.5, foci.get(1).get(1)+ 0.5, o.getAspectRatio());
            if (o.is2D()) sliceZ=0; // necessary ?
            roi.setPosition(0, sliceZ + 1, frameIdx+1);
            r = new Roi3D(1).setIs2D(o.is2D());
            r.put(sliceZ, roi);
            BoundingBox bds = r.getBounds();
            r.setLocDelta(bds.xMin() - object.value.xMin(), bds.yMin() - object.value.yMin());
            //logger.debug("creating Ellipse2D for {} @ {}, foci: {}, bds: {}, is2D: {}, parent bds: {}, loc bds: {}", object.key, object.key.getRegion().getCenter(), foci, object.key.getBounds(), object.key.getRegion().is2D(), object.key.getParent().getBounds(), object.value);
        } else {
            //logger.debug("object: {} has container: {}, container type: {}, container ROI not null ? {}, has ROI: {}", object.key, accessor.hasRegionContainer(object.key), accessor.hasRegionContainer(object.key)? accessor.getRegionContainer(object.key).getClass() : "null", ((RegionContainerIjRoi)accessor.getRegionContainer(object.key)).getRoi()!=null, object.key.getRegion().getRoi()!=null);
            r =  RegionContainerIjRoi.createRoi(object.key.getMask(), object.value, !object.key.is2D()).smooth(ROI_SMOOTH_RADIUS);
        }

        if (displayCorrections && object.key.getAttribute(SegmentedObject.EDITED_SEGMENTATION, false)) { // also display when segmentation is edited
            Point p = new Point((float)object.key.getBounds().xMean(), (float)object.key.getBounds().yMean());
            object.key.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(1, 1));
            p.translate(object.value).translateRev(object.key.getBounds()); // go to kymograph offset
            Arrow arrow = new Arrow(p.get(0), p.get(1), p.get(0), p.get(1));
            arrow.enableSubPixelResolution();
            arrow.setStrokeColor(trackCorrectionColor);
            arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
            new HashSet<>(r.keySet()).stream().filter(z->z>=0).forEach((z) -> {
                Arrow arrowS = r.size()>1 ? (Arrow)arrow.clone() : arrow;
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
    public void displayTrack(ImagePlus image, TrackRoi roi, InteractiveImage i) {
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
                TrackRoi dup = roi.duplicateForZ(z);
                if (!image.isDisplayedHyperStack()) {
                    if (image.getNSlices() > 1) dup.setZToPosition();
                    else if (image.getNFrames() > 1) dup.setTToPosition();
                }
                for (Roi r : dup) o.add(r);
            }
        }
    }

    @Override
    public void hideTrack(ImagePlus image, TrackRoi roi, InteractiveImage i) {
        Overlay o = image.getOverlay();
        if (o!=null) {
            for (Roi r : roi) o.remove(r);
            if (!(i instanceof HyperStack) & image.getNSlices()>1) {
                for (TrackRoi tr : roi.getSliceDuplicates().values()) {
                    for (Roi r : tr) o.remove(r);
                }
            }
        }
    }

    @Override
    public TrackRoi generateTrackRoi(List<SegmentedObject> parents, List<Pair<SegmentedObject, BoundingBox>> track, Color color, InteractiveImage i, boolean forceDefaultDisplay) {
        if (!(i instanceof HyperStack)) {
            if (!forceDefaultDisplay && !parents.isEmpty() && parents.get(0).getExperimentStructure().getTrackDisplay(i.childStructureIdx).equals(Structure.TRACK_DISPLAY.CONTOUR)) return createContourTrackRoi(parents, track, color, i);
            else return createKymographTrackRoi(track, color, i, TRACK_ARROW_STROKE_WIDTH);
        }
        else return createContourTrackRoi(parents, track, color, i);
    }
    
    @Override
    protected void setTrackColor(TrackRoi roi, Color color) {
        for (Roi r : roi) if (r.getStrokeColor()!=ImageWindowManager.trackCorrectionColor && r.getStrokeColor()!=ImageWindowManager.trackErrorColor) {
            setRoiColor(r, color);
        }
        for (TrackRoi dup : roi.getSliceDuplicates().values()) setTrackColor(dup, color);
    }

    protected TrackRoi createContourTrackRoi(List<SegmentedObject> parentTrack, List<Pair<SegmentedObject, BoundingBox>> track, Color color, InteractiveImage i) {
        TrackRoi trackRoi= new TrackRoi();
        trackRoi.setIs2D(track.get(0).key.is2D());
        IntFunction<Integer> getFrame = f -> {
           if (i instanceof HyperStack) return ((HyperStack)i).frameMapIdx.get(f);
           else return 0; // kymograph mode
        };

        Function<Pair<SegmentedObject, BoundingBox>, Roi3D> getRoi = p -> {
            Integer frame =  getFrame.apply(p.key.getFrame());
            if (frame==null) return null;
            if (displayTrackEdges && ((p.key.getParent().getPrevious()!=null && p.key.getPrevious()==null) || (p.key.getParent().getNext()!=null && p.key.getNext()==null))) {
                Roi3D r = createRoiImage(p.key.getMask(), p.value, p.key.is2D(), color, trackRoiContourEdgeOpacity);
                setRoiAttributes(r, color, frame);
                return r;
            } else {
                Roi3D r = labileObjectRoiMap.get(p);
                if (r == null) r = objectRoiMap.get(p);
                if (r == null) {
                    r = generateObjectRoi(p, color, frame);
                    objectRoiMap.put(p, r);
                } else {
                    setRoiAttributes(r, color, frame);
                }
                return r;
            }

        };

        track.stream().map(getRoi).filter(Objects::nonNull).flatMap(r -> r.values().stream()).forEach(trackRoi::add);
        // add flag when track links have been edited
        if (displayCorrections) {
            Predicate<SegmentedObject> edited = o -> o.getAttribute(SegmentedObject.EDITED_LINK_PREV, false) || o.getAttribute(SegmentedObject.EDITED_LINK_NEXT, false);
            Consumer<Pair<SegmentedObject, BoundingBox>> addEditedArrow = object -> {
                if (edited.test(object.key)) { // also display when segmentation is edited
                    Integer frameIdx = getFrame.apply(object.key.getFrame());
                    Point p = new Point((float) object.key.getBounds().xMean(), (float) object.key.getBounds().yMean());
                    object.key.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(1, 1));
                    p.translate(object.value).translateRev(object.key.getBounds()); // go to kymograph offset
                    Arrow arrow = new Arrow(p.get(0), p.get(1), p.get(0), p.get(1));
                    arrow.enableSubPixelResolution();
                    arrow.setStrokeColor(trackCorrectionColor);
                    arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
                    arrow.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
                    int zMin = object.value.zMin();
                    int zMax = object.value.zMax();
                    if (zMin == zMax) {
                        arrow.setPosition(0, zMin + 1, frameIdx + 1);
                        trackRoi.add(arrow);
                    } else {
                        for (int z = zMin; z <= zMax; ++z) {
                            Arrow a = (Arrow) arrow.clone();
                            a.setPosition(0, z + 1, frameIdx + 1);
                            trackRoi.add(a);
                        }
                    }
                }
            };
            track.stream().forEach(addEditedArrow::accept);
        }
        // add arrow to indicate splitting
        Utils.TriConsumer<Pair<SegmentedObject, BoundingBox>, Pair<SegmentedObject, BoundingBox>, Color> addSplitArrow = (o1, o2, c) -> {
            Integer frame = getFrame.apply(o1.key.getFrame());
            Point p1 = o1.key.getRegion().getCenter() == null ? o1.key.getBounds().getCenter() : o1.key.getRegion().getCenter().duplicate();
            Point p2 = o2.key.getRegion().getCenter() == null ? o2.key.getBounds().getCenter() : o2.key.getRegion().getCenter().duplicate();
            p1.translate(o1.value).translateRev(o1.key.getBounds()); // go back to hyperstack offset
            p2.translate(o2.value).translateRev(o2.key.getBounds());
            Arrow arrow = new Arrow(p1.get(0), p1.get(1), p2.get(0), p2.get(1));
            arrow.enableSubPixelResolution();
            arrow.setDoubleHeaded(true);
            arrow.setStrokeColor(c);
            arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
            int zMin = Math.max(o1.value.zMin(), o2.value.zMin());
            int zMax = Math.min(o1.value.zMax(), o2.value.zMax());
            if (zMin==zMax) {
                arrow.setPosition(0, zMin+1, frame+1);
                trackRoi.add(arrow);
            } else {
                for (int z = zMin; z <= zMax; ++z) {
                    Arrow a = (Arrow) arrow.clone();
                    a.setPosition(0, z+1, frame+1);
                    trackRoi.add(a);
                }
            }
        };
        Utils.TriConsumer<Pair<SegmentedObject, BoundingBox>, Pair<SegmentedObject, BoundingBox>, Color> addArrow = (source, target, c) -> {
            Integer frame = getFrame.apply(source.key.getFrame());
            Point p1 = source.key.getRegion().getCenter() == null ? source.key.getBounds().getCenter() : source.key.getRegion().getCenter().duplicate();
            Point p2 = target.key.getRegion().getCenter() == null ? target.key.getBounds().getCenter() : target.key.getRegion().getCenter().duplicate();
            p1.translate(source.value).translateRev(source.key.getBounds()); // go back to hyperstack offset
            p2.translate(target.value).translateRev(target.key.getBounds());
            Arrow arrow1 = new Arrow(p1.get(0), p1.get(1), p2.get(0), p2.get(1));
            arrow1.enableSubPixelResolution();
            arrow1.setDoubleHeaded(false);
            arrow1.setStrokeColor(c);
            arrow1.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow1.setHeadSize(Math.max(TRACK_ARROW_STROKE_WIDTH*1.1, 1.1));
            int zMin = Math.max(source.value.zMin(), target.value.zMin());
            int zMax = Math.min(source.value.zMax(), target.value.zMax());
            if (zMin==zMax) {
                arrow1.setPosition(0, zMin+1, frame+1);
                trackRoi.add(arrow1);
            } else {
                for (int z = zMin; z <= zMax; ++z) {
                    Arrow a1 = (Arrow) arrow1.clone();
                    a1.setPosition(0, z+1, frame+1);
                    trackRoi.add(a1);
                }
            }
        };
        if (track.size()==1) { // when called from show all tracks : only sub-tracks of 1 frame are given as argument
            SegmentedObject o = track.get(0).key;
            if (o.getPreviousId()!=null) {
                if (o.getPrevious()==null) logger.debug("object: {} center: {}, previous null, previous id: {}", o,o.getRegion().getGeomCenter(false), o.getPreviousId());
            }
            if (o.getPreviousId()!=null && o.getPrevious()!=null && o.getPrevious().getTrackHead()!=o.getTrackHead()) {
                List<SegmentedObject> div = SegmentedObjectEditor.getNext(o.getPrevious()).collect(Collectors.toList());
                if (div.size()>1) { // only show
                    List<Pair<SegmentedObject, BoundingBox>> divP = i.pairWithOffset(div);
                    for (Pair<SegmentedObject, BoundingBox> other : divP) {
                        if (!other.key.equals(o) && other.key.getIdx()>o.getIdx()) addSplitArrow.accept(track.get(0), other, getColor(o.getPrevious().getTrackHead()));
                    }
                }
            }
            if (o.getNextId()!=null) {
                if (o.getNext()==null) logger.debug("object: {} center: {}, next null, next id: {}", o, o.getRegion().getGeomCenter(false), o.getNextId());
            }
            if (o.getNextId()!=null && o.getNext()!=null && !o.getNext().getTrackHead().equals(o.getTrackHead())) {
                List<SegmentedObject> merge = SegmentedObjectEditor.getPrevious(o.getNext()).collect(Collectors.toList());
                Pair<SegmentedObject, BoundingBox> target = new Pair<>(o.getNext(), i.getObjectOffset(o.getNext()));
                if (merge.size()>1) { // only show
                    List<Pair<SegmentedObject, BoundingBox>> mergeP = i.pairWithOffset(merge);
                    for (Pair<SegmentedObject, BoundingBox> other : mergeP) {
                        addArrow.accept(other, target, getColor(o.getNext().getTrackHead()));
                    }
                }
            }
        } else {
            if (track.get(track.size() - 1).key.getNextId() == null) {
                List<SegmentedObject> next = SegmentedObjectEditor.getNext(track.get(track.size() - 1).key).collect(Collectors.toList());
                if (next.size() > 1) { // show division by displaying arrows between objects
                    List<Pair<SegmentedObject, BoundingBox>> nextP = i.pairWithOffset(next);
                    for (int idx = 0; idx < next.size() - 1; ++idx)
                        addSplitArrow.accept(nextP.get(idx), nextP.get(idx + 1), color);
                }
            }
            if (track.get(0).key.getPreviousId() == null) {
                List<SegmentedObject> prev = SegmentedObjectEditor.getPrevious(track.get(0).key).collect(Collectors.toList());
                Pair<SegmentedObject, BoundingBox> target = track.get(0);
                if (prev.size() > 1) { // show merging by displaying arrows between objects
                    List<Pair<SegmentedObject, BoundingBox>> prevP = i.pairWithOffset(prev);
                    prevP.forEach(p -> addArrow.accept(p, target, color));
                }
            }
        }
        return trackRoi.setTrackType(Structure.TRACK_DISPLAY.CONTOUR);
    }

    protected static TrackRoi createKymographTrackRoi(List<Pair<SegmentedObject, BoundingBox>> track, Color color, InteractiveImage i, double arrowStrokeWidth) {
        Predicate<SegmentedObject> editedprev = o -> o.getAttribute(SegmentedObject.EDITED_LINK_PREV, false);
        Predicate<SegmentedObject> editedNext = o -> o.getAttribute(SegmentedObject.EDITED_LINK_NEXT, false);
        TrackRoi trackRoi= new TrackRoi();
        trackRoi.setIs2D(track.get(0).key.is2D());
        double arrowSize = track.size()==1 ? 1.5 : 0.65;
        int zMin = track.stream().mapToInt(o-> o.value.zMin()).min().orElse(0);
        int zMax = track.stream().mapToInt(o-> o.value.zMax()).max().orElse(0);
        BiConsumer<Pair<SegmentedObject, BoundingBox>, Pair<SegmentedObject, BoundingBox>> appendTrackArrow = (o1, o2) -> {
            Arrow arrow;
            if (track.size()==1 && o2==null) {
                double size = arrowStrokeWidth*arrowSize;
                Point p = new Point((float)o1.key.getBounds().xMean(), (float)o1.key.getBounds().yMean());
                o1.key.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(-1, -1));
                p.translate(o1.value).translateRev(o1.key.getBounds()); // go to kymograph offset
                arrow = new Arrow(p.get(0)-size, p.get(1)-size, p.get(0), p.get(1));
                arrow.enableSubPixelResolution();
            } else {
                Point p1 = new Point((float)o1.value.xMean(), (float)o1.value.yMean());
                Point p2 = new Point((float)o2.value.xMean(), (float)o2.value.yMean());
                double minDist = TRACK_LINK_MIN_SIZE;
                if (p1.dist(p2)>minDist) {  // get coordinates outside regions so that track links do not hide regions
                    Vector dir = Vector.vector2D(p1, p2);
                    double dirFactor = 1d;
                    dir.multiply(dirFactor/dir.norm()); // increment
                    p1.translateRev(o1.value).translate(o1.key.getBounds()); // go to each region offset for the out-of-region test
                    p2.translateRev(o2.value).translate(o2.key.getBounds());
                    o1.key.getRegion().translateToFirstPointOutsideRegionInDir(p1, dir);
                    o2.key.getRegion().translateToFirstPointOutsideRegionInDir(p2, dir.multiply(-1));
                    p1.translate(o1.value).translateRev(o1.key.getBounds()); // go back to kymograph offset
                    p2.translate(o2.value).translateRev(o2.key.getBounds());
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

            // 2D only errors -> TODO 3D also
            if (displayCorrections) {
                boolean error = (o2 != null && o2.key.hasTrackLinkError(true, false)) || (o1.key.hasTrackLinkError(false, true));
                boolean correction = editedNext.test(o1.key) || (o2 != null && editedprev.test(o2.key));
                arrow.setStrokeColor(color);
                if (error || correction) {
                    Color c = error ? ImageWindowManager.trackErrorColor : ImageWindowManager.trackCorrectionColor;
                    trackRoi.add(getErrorArrow(arrow.x1, arrow.y1, arrow.x2, arrow.y2, c, color, arrowStrokeWidth));
                }
            }
            if (!trackRoi.is2D()) { // in 3D -> display on all slices between slice min & slice max

                if (zMin==zMax) {
                    arrow.setPosition(0, zMin+1, 0);
                    trackRoi.add(arrow);
                    //logger.debug("add arrow: {}", arrow);
                } else {
                    for (int z = zMin; z <= zMax; ++z) {
                        Arrow dup = (Arrow)arrow.clone();
                        dup.setPosition(0, z+1, 0);
                        trackRoi.add(dup);
                        //logger.debug("add arrow (z): {}", arrow);
                    }
                }
            } else {
                trackRoi.add(arrow);
            }
        };
        if (track.size()==1) appendTrackArrow.accept(track.get(0), null);
        else {
            IntStream.range(1, track.size()).forEach(idx -> {
                Pair<SegmentedObject, BoundingBox> o1 = track.get(idx - 1);
                Pair<SegmentedObject, BoundingBox> o2 = track.get(idx);
                appendTrackArrow.accept(o1, o2);
            });
        }
        // append previous arrows
        for (Pair<SegmentedObject, BoundingBox> p : i.pairWithOffset(SegmentedObjectEditor.getPrevious(track.get(0).key).collect(Collectors.toList()))) {
            appendTrackArrow.accept(p, track.get(0));
        }
        return trackRoi;
    }
    private static Arrow getErrorArrow(double x1, double y1, double x2, double y2, Color c, Color fillColor, double arrowStrokeWidth) {
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
