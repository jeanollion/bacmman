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
import bacmman.data_structure.region_container.RegionContainerIjRoi;
import bacmman.data_structure.region_container.roi.Roi3D;
import bacmman.data_structure.region_container.roi.TrackRoi;
import bacmman.image.*;
import bacmman.ui.GUI;
import bacmman.ui.ManualEdition;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.*;
import ij.process.FloatPolygon;

import java.awt.Color;
import java.awt.Component;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowListener;
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
    public void addWindowListener(Object image, WindowListener wl) {
        if (image instanceof Image) image = displayer.getImage((Image)image);
        if (image instanceof ImagePlus) ((ImagePlus)image).getWindow().addWindowListener(wl);
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
                //boolean ctrl = (IJ.isMacOSX() || IJ.isMacintosh()) ? e.isAltDown() : e.isControlDown(); // for mac: ctrl + click = right click -> alt instead of ctrl
                boolean freeHandSplit = ( IJ.getToolName().equals("freeline")) && ctrl && !shift; //IJ.getToolName().equals("polyline")
                boolean freeHandDraw = (IJ.getToolName().equals("freeline") || IJ.getToolName().equals("oval") || IJ.getToolName().equals("ellipse")) && shift;
                boolean freeHandDrawMerge = freeHandDraw && !ctrl;
                boolean strechObjects = (IJ.getToolName().equals("line")) && ctrl;
                boolean addToSelection = shift && (!freeHandSplit || !strechObjects || !freeHandDraw);
                //logger.debug("ctrl: {}, tool : {}, freeHandSplit: {}, freehand draw: {}, addToSelection: {}", ctrl, IJ.getToolName(), freeHandSplit, freeHandDraw, addToSelection);
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
                    boolean removeAfterwards = r.getType()==Roi.FREELINE || r.getType()==Roi.FREEROI || r.getType()==Roi.LINE || r.getType()==Roi.OVAL || (r.getType()==Roi.POLYGON && r.getState()==Roi.NORMAL);
                    //logger.debug("Roi: {}/{}, rem: {}", r.getTypeAsString(), r.getClass(), removeAfterwards);
                    if (r.getType()==Roi.RECTANGLE ||  removeAfterwards) {
                        // starts by getting all objects within bounding box of ROI
                        fromSelection=true;
                        Rectangle rect = removeAfterwards && r.getType()!=Roi.OVAL ? r.getPolygon().getBounds() : r.getBounds();
                        if (rect.height==0 || rect.width==0) removeAfterwards=false;
                        MutableBoundingBox selection = new MutableBoundingBox(rect.x, rect.x+rect.width, rect.y, rect.y+rect.height, ip.getSlice()-1, ip.getSlice()-1);
                        //logger.debug("selection: {}", selection);
                        if (selection.sizeX()==0 && selection.sizeY()==0) selection=null;
                        i.addClickedObjects(selection, selectedObjects);
                        //logger.debug("selection: {} #objects before remove afterwards: {}, bounds: {}, offsets: {}", selection, selectedObjects.size(), selectedObjects.stream().map(o->o.key.getBounds()).collect(Collectors.toList()), selectedObjects.stream().map(o->o.value).collect(Collectors.toList()));
                        boolean is2D = i.is2D();
                        if (removeAfterwards || (selection.sizeX()<=2 && selection.sizeY()<=2)) {
                            FloatPolygon fPoly = r.getInterpolatedPolygon();
                            selectedObjects.removeIf(p -> !intersect(p.key, p.value, fPoly, is2D ? -1 : ip.getSlice()-1));
                        }
                        if (!freeHandSplit || !strechObjects || !freeHandDraw) ip.deleteRoi();
                    }
                }
                // simple click : get clicked object // TODO not used anymore ?
                if (!fromSelection && !strechObjects) {
                    int offscreenX = canvas.offScreenX(e.getX());
                    int offscreenY = canvas.offScreenY(e.getY());
                    Pair<SegmentedObject, BoundingBox> o = i.getClickedObject(offscreenX, offscreenY, ip.getSlice()-1);
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
                    if (hyperstack) ((HyperStack)i).setChangeIdxCallback(null);
                } else if (!strechObjects) {
                    if (!hyperstack) {
                        List<SegmentedObject> trackHeads = new ArrayList<>();
                        for (Pair<SegmentedObject, BoundingBox> p : selectedObjects)
                            trackHeads.add(p.key.getTrackHead());
                        Utils.removeDuplicates(trackHeads, false);
                        for (SegmentedObject th : trackHeads) {
                            List<SegmentedObject> track = SegmentedObjectUtils.getTrack(th, !hyperstack);
                            List<Pair<SegmentedObject, BoundingBox>> disp = i.pairWithOffset(track);
                            Color c = hyperstack ? getColor(track.get(0)) : ImageWindowManager.getColor();
                            displayTrack(image, i, disp, c, true);
                            displayObjects(image, disp, c, true, false);
                        }
                        if (listener != null) listener.fireTracksSelected(trackHeads, true);
                    } else {
                        // for hyper stack: create callback that displays only tracks at current frame
                        HyperStack k = ((HyperStack)i);
                        Set<SegmentedObject> trackHeads = selectedObjects.stream().map(p -> p.key.getTrackHead()).collect(Collectors.toSet());
                        IntConsumer callback = idx -> {
                            List<List<SegmentedObject>> selTracks = k.getObjects().stream().filter(p -> trackHeads.contains(p.key.getTrackHead()))
                                    .map(o -> new ArrayList<SegmentedObject>(1){{add(o.key);}}).collect(Collectors.toList());
                            hideAllRois(image, true, false);
                            if (!selTracks.isEmpty()) displayTracks(image, k, selTracks, true);
                        };
                        k.setChangeIdxCallback(callback);
                        callback.accept(k.getIdx());
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
                    ManualEdition.splitObjects(GUI.getDBConnection(), objects, true, false, splitter);
                } else if (freeHandDraw && r!=null) {
                    //logger.debug("freehand draw object class: {}, ", i.getChildStructureIdx());
                    //if (selectedObjects.size()>1) return;
                    int parentStructure = i.getParent().getStructureIdx();
                    List<Pair<SegmentedObject, BoundingBox>> selectedParentObjects = new ArrayList<>();
                    Rectangle rect = r.getBounds();
                    MutableBoundingBox selection = new MutableBoundingBox(rect.x, rect.x+rect.width, rect.y, rect.y+rect.height, ip.getSlice()-1, ip.getSlice());
                    getImageObjectInterface(image, parentStructure).addClickedObjects(selection, selectedParentObjects);
                    if (selectedParentObjects.size()>1) {
                        logger.debug("selection is over several parents: {}", selectedParentObjects.size());
                        return;
                    } else if (selectedParentObjects.isEmpty()) {
                        logger.debug("no parent touched");
                        return;
                    }
                    SegmentedObject parent = selectedParentObjects.get(0).key;
                    FloatPolygon p = r.getInterpolatedPolygon(-1, true);
                    Consumer<Collection<SegmentedObject>> store = l -> GUI.getDBConnection().getDao(parent.getPositionName()).store(l);
                    Collection<SegmentedObject> seg = FreeLineSegmenter.segment(parent, selectedParentObjects.get(0).value, Pair.unpairKeys(selectedObjects), ArrayUtil.toInt(p.xpoints), ArrayUtil.toInt(p.ypoints), i.getChildStructureIdx() , store);
                    if (!seg.isEmpty()) {
                        reloadObjects_(parent, i.getChildStructureIdx(), true);
                        hideLabileObjects(image);
                        if (freeHandDrawMerge && !selectedObjects.isEmpty()) {
                            seg.addAll(Pair.unpairKeys(selectedObjects));
                            ManualEdition.mergeObjects(GUI.getDBConnection(), seg, true);
                        } else {
                            displayObjects(image, i.pairWithOffset(seg), Color.orange , true, false);
                            displayObjects(image, selectedObjects, ImageWindowManager.defaultRoiColor , true, false);
                        }
                    } else logger.debug("no object could be segmented");

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
    @Override public void registerInteractiveHyperStackFrameCallback(Image image, HyperStack k) {
        ImagePlus ip = displayer.getImage(image);
        if (ip!=null && ip.getImageStack() instanceof IJVirtualStack) {
            //logger.debug("registering frame callback on image: {} for kymograph : {}", image.getName(), k.getKey());
            ((IJVirtualStack)ip.getImageStack()).appendSetFrameCallback(k::setIdx);
            ((IJVirtualStack)ip.getImageStack()).appendSetFrameCallback(i -> GUI.updateRoiDisplayForSelections(image, k));
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
            IJ.selectWindow(image.getName());
        } else { // not visible -> show image
            displayer.showImage(image);
            addMouseListener(image);
            displayer.updateImageRoiDisplay(image);
        }
    }
    
    @Override
    protected List<Point> getSelectedPointsOnImage(ImagePlus image) {
        Roi r = image.getRoi();
        if (r instanceof PointRoi) {
            PointRoi pRoi = (PointRoi)r;
            Polygon p = r.getPolygon();
            
            List<Point> res = new ArrayList<>(p.npoints);
            for (int i = 0; i<p.npoints; ++i) {
                res.add(new Point(p.xpoints[i], p.ypoints[i], Math.max(0, pRoi.getPointPosition(i)-1)));
            }
            return res;
        } else return Collections.emptyList();
    }
    
    @Override
    public void displayObject(ImagePlus image, Roi3D roi) {
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
            else if (image.getNFrames()>1) {
                //Roi r = roi.values().iterator().next();
                //logger.debug(" before hyperstack roi : p={}, z={}, T={}", r.getPosition(), r.getZPosition(), r.getTPosition());
                roi.setTToPosition();
                //logger.debug("not hyperstack nT: {}: roi position {}", image.getNFrames(), roi.values().iterator().next().getPosition());
            }
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

    @Override
    public Roi3D generateObjectRoi(Pair<SegmentedObject, BoundingBox> object, Color color, int frameIdx) {
        if (object.key.getBounds().sizeZ()<=0 || object.key.getBounds().sizeX()<=0 || object.key.getBounds().sizeY()<=0) GUI.logger.error("wrong object dim: o:{} {}", object.key, object.key.getBounds());
        Roi3D r;
        SegmentedObjectAccessor accessor = getAccessor();
        // TODO why following condition is false when multiple selection ?
        //if (accessor.hasRegionContainer(object.key) && accessor.getRegionContainer(object.key) instanceof RegionContainerIjRoi && ((RegionContainerIjRoi)accessor.getRegionContainer(object.key)).getRoi()!=null) { // look for existing ROI
        if (object.key.getRegion().getRoi()!=null) {
            //logger.debug("object: {} has IJROICONTAINER", object.key);
            r = object.key.getRegion().getRoi().duplicate()
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
            r =  RegionContainerIjRoi.createRoi(object.key.getMask(), object.value, !object.key.is2D());
        }

        if (object.key.getAttribute(SegmentedObject.EDITED_SEGMENTATION, false)) { // also display when segmentation is edited
            double size = TRACK_ARROW_STROKE_WIDTH*1.5;
            Point p = new Point((float)object.key.getBounds().xMean(), (float)object.key.getBounds().yMean());
            object.key.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(1, 1));
            p.translate(object.value).translateRev(object.key.getBounds()); // go to kymograph offset
            Arrow arrow = new Arrow(p.get(0)+size, p.get(1)+size, p.get(0), p.get(1));
            arrow.enableSubPixelResolution();
            arrow.setStrokeColor(trackCorrectionColor);
            arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow.setHeadSize(size);
            new HashSet<>(r.keySet()).stream().filter(z->z>=0).forEach((z) -> {
                Arrow arrowS = r.size()>1 ? (Arrow)arrow.clone() : arrow;
                arrowS.setPosition(0, z, frameIdx+1);
                r.put(-z-1, arrowS);
            });
        }
        setRoiAttributes(r, color, frameIdx, null);
        return r;
    }
    
    @Override
    protected void setRoiAttributes(Roi3D roi, Color color, int frameIdx, Offset location) {
        roi.entrySet().stream().filter(e->e.getKey()>=0).forEach(e -> {
            e.getValue().setStrokeColor(color);
            e.getValue().setStrokeWidth(ROI_STROKE_WIDTH);
        });
        if (frameIdx>=0) roi.setFrame(frameIdx);
        if (location!=null) roi.setLocation(location);
    }

    
    // track-related methods
    @Override
    public void displayTrack(ImagePlus image, TrackRoi roi, InteractiveImage i) {
        Overlay o = image.getOverlay();
        if (o==null) {
            o=new Overlay();
            image.setOverlay(o);
        }
        if (!(i instanceof HyperStack)) {
            for (Roi r : roi) o.add(r);
            if (roi.is2D() && image.getZ()>1) {
                for (int z = 1; z<image.getNSlices(); ++z) {
                    for (Roi r : roi.duplicateForZ(z)) o.add(r);
                }
            }
        } else {
            if (!image.isDisplayedHyperStack()) {
                if (image.getNSlices()>1) roi.setZToPosition();
                else if (image.getNFrames()>1) roi.setTToPosition();
            }
            for (Roi r : roi) {
                o.add(r);
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
    public TrackRoi generateTrackRoi(List<SegmentedObject> parents, List<Pair<SegmentedObject, BoundingBox>> track, Color color, InteractiveImage i) {
        if (!(i instanceof HyperStack)) return createKymographTrackRoi(track, color);
        else return createHyperStackTrackRoi(parents, track, color, (HyperStack)i);
    }
    
    @Override
    protected void setTrackColor(TrackRoi roi, Color color) {
        for (Roi r : roi) if (r.getStrokeColor()!=ImageWindowManager.trackCorrectionColor && r.getStrokeColor()!=ImageWindowManager.trackErrorColor) r.setStrokeColor(color);
    }
    protected TrackRoi createHyperStackTrackRoi(List<SegmentedObject> parentTrack, List<Pair<SegmentedObject, BoundingBox>> track, Color color, HyperStack i) {
        TrackRoi trackRoi= new TrackRoi();
        trackRoi.setIs2D(track.get(0).key.is2D());
        Function<Pair<SegmentedObject, BoundingBox>, Roi3D> getRoi = p -> {
            Integer frame = i.frameMapIdx.get(p.key.getFrame());
            if (frame==null) return null;
            Roi3D r = labileObjectRoiMap.get(p);
            if (r==null) r = objectRoiMap.get(p);
            if (r == null) {
                r = generateObjectRoi(p, color, frame);
                objectRoiMap.put(p, r);
            } else {
                setRoiAttributes(r, color, frame, p.value);
            }
            return r;
        };

        track.stream().map(getRoi::apply).filter(r -> r!=null).flatMap(r -> r.values().stream()).forEach(trackRoi::add);
        // add flag when track links have been edited
        Predicate<SegmentedObject> edited = o -> o.getAttribute(SegmentedObject.EDITED_LINK_PREV, false) || o.getAttribute(SegmentedObject.EDITED_LINK_NEXT, false);
        Consumer<Pair<SegmentedObject, BoundingBox>> addEditedArrow = object -> {
            if (edited.test(object.key)) { // also display when segmentation is edited
                Integer frameIdx = i.frameMapIdx.get(object.key.getFrame());
                double size = TRACK_ARROW_STROKE_WIDTH*1.5;
                Point p = new Point((float)object.key.getBounds().xMean(), (float)object.key.getBounds().yMean());
                object.key.getRegion().translateToFirstPointOutsideRegionInDir(p, new Vector(1, 1));
                p.translate(object.value).translateRev(object.key.getBounds()); // go to kymograph offset
                Arrow arrow = new Arrow(p.get(0)+size, p.get(1)+size, p.get(0), p.get(1));
                arrow.enableSubPixelResolution();
                arrow.setStrokeColor(trackCorrectionColor);
                arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
                arrow.setHeadSize(size);
                int zMin = object.value.zMin();
                int zMax = object.value.zMax();
                if (zMin==zMax) {
                    arrow.setPosition(0, zMin+1, frameIdx+1);
                    trackRoi.add(arrow);
                } else {
                    for (int z = zMin; z <= zMax; ++z) {
                        Arrow a = (Arrow) arrow.clone();
                        a.setPosition(0, z+1, frameIdx+1);
                        trackRoi.add(a);
                    }
                }
            }
        };
        track.stream().forEach(addEditedArrow::accept);
        // add arrow to indicate splitting
        double arrowSize = track.size()==1 ? 1.5 : 0.65;
        BiConsumer<Pair<SegmentedObject, BoundingBox>, Pair<SegmentedObject, BoundingBox>> addSplitMergeArrow = (o1, o2) -> {
            Integer frame = i.frameMapIdx.get(o1.key.getFrame());
            Point p1 = o1.key.getRegion().getCenter() == null ? o1.key.getBounds().getCenter() : o1.key.getRegion().getCenter();
            Point p2 = o2.key.getRegion().getCenter() == null ? o2.key.getBounds().getCenter() : o2.key.getRegion().getCenter();
            p1.translate(o1.value).translateRev(o1.key.getBounds()); // go back to kymograph offset
            p2.translate(o2.value).translateRev(o2.key.getBounds());
            Arrow arrow = new Arrow(p1.get(0), p1.get(1), p2.get(0), p2.get(1));
            arrow.enableSubPixelResolution();
            arrow.setDoubleHeaded(true);
            arrow.setStrokeColor(color);
            arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow.setHeadSize(TRACK_ARROW_STROKE_WIDTH*arrowSize);
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
        if (track.get(track.size()-1).key.getNextId()==null) {
            List<SegmentedObject> next = SegmentedObjectEditor.getNext(track.get(track.size() - 1).key);
            if (next.size() > 1) { // show division by displaying arrows between objects
                List<Pair<SegmentedObject, BoundingBox>> nextP = i.pairWithOffset(next);
                for (int idx = 0; idx < next.size() - 1; ++idx)
                    addSplitMergeArrow.accept(nextP.get(idx), nextP.get(idx + 1));
            }
        }
        if (track.get(0).key.getPreviousId()==null) {
            List<SegmentedObject> prev = SegmentedObjectEditor.getPrevious(track.get(0).key);
            if (prev.size() > 1) { // show merging by displaying arrows between objects
                List<Pair<SegmentedObject, BoundingBox>> prevP = i.pairWithOffset(prev);
                for (int idx = 0; idx < prev.size() - 1; ++idx)
                    addSplitMergeArrow.accept(prevP.get(idx), prevP.get(idx + 1));
            }
        }
        return trackRoi;
    }

    protected static TrackRoi createKymographTrackRoi(List<Pair<SegmentedObject, BoundingBox>> track, Color color) {
        Predicate<SegmentedObject> editedprev = o -> o.getAttribute(SegmentedObject.EDITED_LINK_PREV, false);
        Predicate<SegmentedObject> editedNext = o -> o.getAttribute(SegmentedObject.EDITED_LINK_NEXT, false);
        TrackRoi trackRoi= new TrackRoi();
        trackRoi.setIs2D(track.get(0).key.is2D());
        double arrowSize = track.size()==1 ? 1.5 : 0.65;
        IntStream.range(track.size()==1 ? 0 : 1, track.size()).forEach( idx -> {
            Pair<SegmentedObject, BoundingBox> o1 = idx>0 ? track.get(idx-1) : track.get(0);
            Pair<SegmentedObject, BoundingBox> o2 = track.get(idx);
            if (o1==null || o2==null) return;
            Arrow arrow;
            if (track.size()==1) {
                double size = TRACK_ARROW_STROKE_WIDTH*arrowSize;
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
            
            boolean error = o2.key.hasTrackLinkError(true, false) || (o1.key.hasTrackLinkError(false, true));
            boolean correction = editedNext.test(o1.key)||editedprev.test(o2.key);
            //arrow.setStrokeColor( (o2.key.hasTrackLinkError() || (o1.key.hasTrackLinkError()&&o1.key.isTrackHead()) )?ImageWindowManager.trackErrorColor: (o2.key.hasTrackLinkCorrection()||(o1.key.hasTrackLinkCorrection()&&o1.key.isTrackHead())) ?ImageWindowManager.trackCorrectionColor : color);
            arrow.setStrokeColor(color);
            arrow.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
            arrow.setHeadSize(TRACK_ARROW_STROKE_WIDTH*arrowSize);
            
            //if (o1.getNext()==o2) arrow.setDoubleHeaded(true);
            
            // 2D only errors -> TODO 3D also
            if (error || correction) {
                Color c = error ? ImageWindowManager.trackErrorColor : ImageWindowManager.trackCorrectionColor;
                trackRoi.add(getErrorArrow(arrow.x1, arrow.y1, arrow.x2, arrow.y2, c, color));
            } 
            
            if (!trackRoi.is2D()) { // in 3D -> display on all slices between slice min & slice max
                int zMin = Math.max(o1.value.zMin(), o2.value.zMin());
                int zMax = Math.min(o1.value.zMax(), o2.value.zMax());
                if (zMin==zMax) {
                    arrow.setPosition(0, zMin+1, 0);
                    trackRoi.add(arrow);
                    //logger.debug("add arrow: {}", arrow);
                } else {
                    // TODO debug
                    //logger.error("Display Track error. objects: {} & {} bounds: {} & {}, image bounds: {} & {}", o1, o2, o1.getBounds(), o2.getBounds(), b1, b2);
                    //if (true) return;
                    if (zMin>zMax) {

                        GUI.logger.error("DisplayTrack error: Zmin>Zmax: o1: {}, o2: {}", o1.key, o2.key);
                    }
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
        });
        return trackRoi;
    }
    private static Arrow getErrorArrow(double x1, double y1, double x2, double y2, Color c, Color fillColor) {
        double arrowSize = TRACK_ARROW_STROKE_WIDTH*2;
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
        res.setStrokeWidth(TRACK_ARROW_STROKE_WIDTH);
        res.setHeadSize(TRACK_ARROW_STROKE_WIDTH*1.5);
        return res;
        
        // OTHER ARROW
        /*Arrow res =  new Arrow(x1, y1, x2, y2);
        res.setStrokeColor(c);
        double size = trackArrowStrokeWidth+1.5;
        res.setStrokeWidth(size);
        res.setHeadSize(trackArrowStrokeWidth*1.5);
        return res;*/
    }
    
    // not to be called directly!!
    protected void hideAllRois(ImagePlus image) {
        image.setOverlay(new Overlay());
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
