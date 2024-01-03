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

import bacmman.data_structure.Region;
import bacmman.data_structure.region_container.roi.Roi3D;
import bacmman.image.*;
import bacmman.image.Image;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import ij.*;
import bacmman.image.wrappers.IJImageWrapper;
import ij.gui.*;
import ij.plugin.frame.SyncWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.IntFunction;
import java.util.function.ToIntBiFunction;

/**
 *
 * @author Jean Ollion
 */
public class IJImageDisplayer implements ImageDisplayer<ImagePlus> , OverlayDisplayer{
    static Logger logger = LoggerFactory.getLogger(IJImageDisplayer.class);
    protected HashMap<Image, ImagePlus> displayedImages=new HashMap<>();
    protected HashMap<ImagePlus, Image> displayedImagesInv=new HashMap<>();

    @Override
    public void updateTitle(Image image) {
        ImagePlus ip = displayedImages.get(image);
        if (ip!=null) ip.setTitle(image.getName());
    }
    @Override
    public void putImage(Image image, ImagePlus displayedImage) {
        displayedImages.put(image, displayedImage);
        displayedImagesInv.put(displayedImage, image);
    }
    @Override
    public void removeImage(Image image, ImagePlus displayedImage) {
        if (image==null && displayedImage!=null) image = getImage(displayedImage);
        else if (displayedImage==null && image!=null) displayedImage = getImage(image);
        if (image!=null) displayedImages.remove(image);
        if (displayedImage!=null) displayedImagesInv.remove(displayedImage);
    }
    @Override public ImagePlus displayImage(Image image, double... displayRange) {
        if (imageExistsButHasBeenClosed(image)) {
            displayedImagesInv.remove(displayedImages.get(image));
            displayedImages.remove(image);
        }
        ImagePlus ip = getImage(image);
        if (!(image instanceof LazyImage5D && displayRange.length==0)) {
            if (displayRange.length == 0) displayRange = ImageDisplayer.getDisplayRange(image, null);
            else if (displayRange.length == 1) {
                double[] dispRange = ImageDisplayer.getDisplayRange(image, null);
                dispRange[0] = displayRange[0];
                displayRange = dispRange;
            } else if (displayRange.length >= 2) {
                if (displayRange[1] <= displayRange[0]) {
                    double[] dispRange = ImageDisplayer.getDisplayRange(image, null);
                    displayRange[1] = dispRange[1];
                }
            }
            ip.setDisplayRange(displayRange[0], displayRange[1]);
        }
        //logger.debug("show image:w={}, h={}, disp: {}", ip.getWidth(), ip.getHeight(), displayRange);
        if (!ip.isVisible()) {
            ip.show();
            InteractiveImage im = ImageWindowManagerFactory.getImageManager().getInteractiveImage(image);
            TimeLapseInteractiveImageFactory.DIRECTION dir;
            ToIntBiFunction<Integer, Boolean> nextPos;
            if (im instanceof KymographX) {
                KymographX k = (KymographX)im;
                dir = TimeLapseInteractiveImageFactory.DIRECTION.X;
                nextPos = (nextSlice, next) -> {
                    if (next) {
                        if (nextSlice == k.data.nSlices - 1) {
                            int lastParentIdx = k.getStartParentIdx(nextSlice - 1) + k.data.nFramePerSlice - 1;
                            int frame = k.getParents().get(lastParentIdx).getFrame();
                            return k.getOffsetForFrame(frame, nextSlice).xMin();
                        } else return 0;
                    } else {
                        int lastParentIdx = k.getStartParentIdx(nextSlice + 1);
                        int frame = k.getParents().get(lastParentIdx).getFrame();
                        return k.getOffsetForFrame(frame, nextSlice).xMin() + k.getParents().get(lastParentIdx).getBounds().sizeX();
                    }
                };
            }
            else if (im instanceof KymographY) {
                KymographY k = (KymographY)im;
                dir = TimeLapseInteractiveImageFactory.DIRECTION.Y;
                nextPos = (nextSlice, next) -> {
                    if (next) {
                        if (nextSlice == k.data.nSlices - 1) {
                            int lastParentIdx = k.getStartParentIdx(nextSlice - 1) + k.data.nFramePerSlice - 1;
                            int frame = k.getParents().get(lastParentIdx).getFrame();
                            return k.getOffsetForFrame(frame, nextSlice).yMin();
                        } else return 0;
                    } else {
                        int lastParentIdx = k.getStartParentIdx(nextSlice + 1);
                        int frame = k.getParents().get(lastParentIdx).getFrame();
                        return k.getOffsetForFrame(frame, nextSlice).yMin() + k.getParents().get(lastParentIdx).getBounds().sizeY();
                    }
                };
            }
            else if (im instanceof HyperStack) {
                dir = TimeLapseInteractiveImageFactory.DIRECTION.T;
                nextPos = null;
            }
            else {
                dir = null;
                nextPos = null;
            }
            addMouseWheelListener(ip, dir, nextPos);
            ImageWindowManagerFactory.getImageManager().addLocalZoom(ip.getCanvas());
        }
        return ip;
    }
    
    @Override
    public boolean isDisplayed(ImagePlus ip) {
        return ip!=null && ip.isVisible();
    }
    
    public void flush() {
        for (ImagePlus ip : new ArrayList<>(displayedImages.values())) if (ip.isVisible()) ip.close();
        displayedImages.clear();
        displayedImagesInv.clear();
    }
    @Override public void close(Image image) {
        ImagePlus imp = this.getImage(image);
        this.displayedImages.remove(image);
        if (imp!=null) {
            imp.close();
            this.displayedImagesInv.remove(imp);
        }
    }
    @Override public void close(ImagePlus image) {
        if (image==null) return;
        Image im = this.displayedImagesInv.remove(image);
        if (im!=null) this.displayedImages.remove(im);
        image.close();
    }

    /*@Override public boolean isVisible(Image image) {
        return displayedImages.containsKey(image) && displayedImages.get(image).isVisible();
    }*/
    private boolean imageExistsButHasBeenClosed(Image image) {
        return displayedImages.get(image)!=null && displayedImages.get(image).getCanvas()==null;
    }
    
    protected void addMouseWheelListener(final ImagePlus imp, TimeLapseInteractiveImageFactory.DIRECTION direction, ToIntBiFunction<Integer, Boolean> getKymographPositionAtNeighborSlice) {
        if (imp==null) return;
        final ImageWindow iw = imp.getWindow();
        final ImageCanvas ic = imp.getCanvas();
        if (iw==null || ic ==null) return;
        logger.debug("adding mouse wheel listener to : {}", imp.getTitle());
        final boolean[] zoomHasBeenFixed = new boolean[1];
        MouseWheelListener mwl = e ->  { // code modified from IJ source to better suit needs for LARGE track mask images + call back to display images
            synchronized (iw) {
                if (!zoomHasBeenFixed[0] && ic.getMagnification()<0.4) { // case zoom is very low -> set to 100%
                    ic.zoom100Percent();
                    zoomHasBeenFixed[0] = true;
                }
                if (e==null) return;
                int rotation = e.getWheelRotation();
                int amount = e.getScrollAmount();
                boolean ctrl = e.isControlDown();
                boolean alt = e.isAltDown();
                boolean shift = e.isShiftDown();
                boolean space = IJ.spaceBarDown();
                boolean acceleratedScrolling = shift && alt; // accelerated scrolling
                if (amount<1) amount=1;
                if (rotation==0) return;
                int width = imp.getWidth();
                int height = imp.getHeight();
                Rectangle srcRect = ic.getSrcRect();
                int xstart = srcRect.x;
                int ystart = srcRect.y;

                boolean needScrollZ = imp.getNSlices()>1;
                boolean needScrollTime = imp.getNFrames()>1;
                boolean needScrollChannel = imp.getNChannels()>1;
                boolean needScrollImage = srcRect.height<height || srcRect.width<width;
                boolean scrollZ = needScrollZ && ((!needScrollImage && !alt && !shift) || space);
                boolean scrollTime = needScrollTime && !scrollZ && ((!needScrollImage && !space && !shift) || (alt && !shift));
                boolean scrollChannels = needScrollChannel && !scrollZ && !scrollTime && ((!needScrollImage && !space && !alt) || (shift && !alt));
                //logger.debug("scroll : type {}, amount: {}, rotation: {}, scrollZ: {}, scrollTime: {}, scrollChannels: {}, need scroll image: {}", e.getScrollType(), amount, rotation, scrollZ, scrollTime, scrollChannels, needScrollImage);
                if (ctrl && ic!=null) { // zoom
                    java.awt.Point loc = ic.getCursorLoc();
                    int x = ic.screenX(loc.x);
                    int y = ic.screenY(loc.y);
                    if (rotation<0) ic.zoomIn(x, y);
                    else ic.zoomOut(x, y);
                    return;
                }
                if (scrollZ) {
                    StackWindow sw = (StackWindow)iw;
                    int slice = imp.getSlice() + rotation;
                    if (slice<1) slice = 1;
                    else if (slice>imp.getNSlices()) slice = imp.getNSlices();
                    imp.setZ(slice);
                    imp.updateStatusbarValue();
                    SyncWindows.setZ(sw, slice);
                } else if (scrollTime) {
                    StackWindow sw = (StackWindow)iw;
                    int slice = imp.getFrame() + rotation;
                    if (slice<1) slice = 1;
                    else if (slice>imp.getNFrames()) slice = imp.getNFrames();
                    imp.setT(slice);
                    imp.updateStatusbarValue();
                    SyncWindows.setT(sw, slice);
                } else if (scrollChannels) {
                    StackWindow sw = (StackWindow)iw;
                    int slice = imp.getChannel() + rotation;
                    if (slice<1) slice = 1;
                    else if (slice>imp.getNChannels()) slice = imp.getNChannels();
                    imp.setC(slice);
                    imp.updateStatusbarValue();
                    SyncWindows.setC(sw, slice);
                } else { // move within image
                    int scrollX = rotation*amount* (acceleratedScrolling ? Math.max(width/60, srcRect.width/12) : srcRect.width/12);;
                    int scrollY = rotation*amount*(acceleratedScrolling ?  Math.max(height/60, srcRect.height/12) : srcRect.height/12);
                    if (TimeLapseInteractiveImageFactory.DIRECTION.X.equals(direction)) { // move or change slice if end of image
                        if (scrollX>0 && srcRect.x + srcRect.width == width) {
                            StackWindow sw = (StackWindow)iw;
                            int slice = imp.getFrame() + 1;
                            if (slice<=imp.getNFrames()) {
                                srcRect.x = getKymographPositionAtNeighborSlice.applyAsInt(slice-1, true);
                                imp.setT(slice);
                                imp.updateStatusbarValue();
                                SyncWindows.setT(sw, slice);
                            }
                        } else if (scrollX<0 && srcRect.x == 0) {
                            StackWindow sw = (StackWindow)iw;
                            int slice = imp.getFrame() - 1;
                            if (slice>0) {
                                srcRect.x = Math.max(0, getKymographPositionAtNeighborSlice.applyAsInt(slice-1, false) - srcRect.width); //width - srcRect.width;
                                imp.setT(slice);
                                imp.updateStatusbarValue();
                                SyncWindows.setT(sw, slice);
                            }
                        } else {
                            srcRect.x = Math.min(width - srcRect.width, Math.max(0, srcRect.x + scrollX));
                            imp.updateAndRepaintWindow();
                        }
                    } else if (TimeLapseInteractiveImageFactory.DIRECTION.Y.equals(direction)) { // move or change slice if end of image
                        if (scrollY>0 && srcRect.y + srcRect.height == height) {
                            StackWindow sw = (StackWindow)iw;
                            int slice = imp.getFrame() + 1;
                            if (slice<=imp.getNFrames()) {
                                srcRect.y = Math.max(0, getKymographPositionAtNeighborSlice.applyAsInt(slice-1, true));
                                imp.setT(slice);
                                imp.updateStatusbarValue();
                                SyncWindows.setT(sw, slice);
                            }
                        } else if (scrollY<0 && srcRect.y == 0) {
                            StackWindow sw = (StackWindow)iw;
                            int slice = imp.getFrame() - 1;
                            if (slice>0) {
                                srcRect.y = getKymographPositionAtNeighborSlice.applyAsInt(slice-1, false) - srcRect.height;
                                imp.setT(slice);
                                imp.updateStatusbarValue();
                                SyncWindows.setT(sw, slice);
                            }
                        } else {
                            srcRect.y = Math.min(height - srcRect.height, Math.max(0, srcRect.y + scrollY));
                            imp.updateAndRepaintWindow();
                        }
                    } else {
                        if ((double) srcRect.height / height > (double) srcRect.width / width || (srcRect.height / height < srcRect.width / width && space)) { // scroll in the most needed direction
                            srcRect.x += scrollX;
                            if (srcRect.x < 0) srcRect.x = 0;
                            if (srcRect.x + srcRect.width > width) srcRect.x = width - srcRect.width;
                        } else { // most needed direction is Y
                            srcRect.y += scrollY;
                            if (srcRect.y < 0) srcRect.y = 0;
                            if (srcRect.y + srcRect.height > height) srcRect.y = height - srcRect.height;
                        }
                    }
                }
                if (srcRect.x!=xstart || srcRect.y!=ystart) {
                    boolean update = false;
                    if (update) imp.updateAndRepaintWindow();//ic.repaint();
                    else  ic.repaint();
                }
            }    
	    };
        // replace mouse wheel listener
        for (MouseWheelListener mwl2: iw.getMouseWheelListeners()) iw.removeMouseWheelListener(mwl2);
        iw.addMouseWheelListener(mwl);
    }
    @Override
    public int getChannel(Image im) {
        ImagePlus image = getImage(im);
        if (image==null) return -1;
        if (image.isDisplayedHyperStack()) return image.getChannel()-1;
        else if (image.getNChannels() == image.getStackSize()) return image.getCurrentSlice()-1;
        return 0;
    }
    @Override
    public void setChannel(int channel, Image im) {
        ImagePlus image = getImage(im);
        if (image!=null) image.setC(channel+1);
    }

    @Override
    public int getFrame(Image im) {
        ImagePlus image = getImage(im);
        if (image==null) return -1;
        if (image.isDisplayedHyperStack()) return image.getFrame()-1;
        else if (image.getNFrames() == image.getStackSize()) return image.getCurrentSlice()-1;
        return 0;
    }

    @Override
    public void setFrame(int frame, Image im) {
        ImagePlus image = getImage(im);
        if (image!=null) image.setT(frame+1);
    }

    @Override public ImagePlus getImage(Image image) {
        if (image==null) return null;
        ImagePlus ip = displayedImages.get(image);
        if (ip==null) {
            if (image instanceof LazyImage5D) {
                IJVirtualStack s = new IJVirtualStack(image);
                if (IJVirtualStack.OpenAsImage5D) s.generateImage5D();
                else s.generateImagePlus();
                ip = s.imp;
            } else {
                ip = IJImageWrapper.getImagePlus(image);
            }
            displayedImages.put(image, ip);
            displayedImagesInv.put(ip, image);
        }
        return ip;
    }
    
    @Override public Image getImage(ImagePlus image) {
        if (image==null) return null;
        return displayedImagesInv.get(image);
        /*if (im==null) {
            im= IJImageWrapper.wrap(image);
            displayedImagesInv.put(image, im);
            displayedImages.put(im, image);
        }
        return im;*/
    }
    @Override public void updateImageDisplay(Image image, double... displayRange) {
        if (this.displayedImages.containsKey(image)) {
            if (displayRange.length == 0) {
                displayRange = ImageDisplayer.getDisplayRange(image, null);
            } else if (displayRange.length == 1) {
                double[] minAndMax = ImageDisplayer.getDisplayRange(image, null);
                minAndMax[0] = displayRange[0];
                displayRange = minAndMax;
            }
            ImagePlus ip = displayedImages.get(image);
            synchronized(ip) {
                if (displayRange[0]<displayRange[1]) ip.setDisplayRange(displayRange[0], displayRange[1]);
                ip.updateAndRepaintWindow();
                //ip.draw();
            }
            
        }
    }
    @Override public void updateImageRoiDisplay(Image image) {
        if (this.displayedImages.containsKey(image)) {
            ImagePlus ip = displayedImages.get(image);
            synchronized(ip) {
                ip.draw(); // draw is sufficient, no need to call upadate that does a snapshot...
            }
        }
    }

    /*public BoundingBox getImageDisplay(Image image) {
        ImagePlus im = image!=null ? this.getImage(image) : WindowManager.getCurrentImage();
        if (im==null) {
            logger.warn("no open image");
            return null;
        }
        im.getCanvas().get
    }*/
    
    @Override
    public BoundingBox getDisplayRange(Image image) {
        ImagePlus ip = this.getImage(image);
        if (ip!=null) {
            Rectangle r = ip.getCanvas().getSrcRect();
            int z = ip.getCurrentSlice()-1;
            return new SimpleBoundingBox(r.x, r.x+r.width-1, r.y, r.y+r.height-1, z, z);
        } else return null;
    }
    
    @Override
    public void setDisplayRange(BoundingBox bounds, Image image) {
        ImagePlus ip = this.getImage(image);
        if (ip!=null) {
            Rectangle r = ip.getCanvas().getSrcRect();
            r.x=bounds.xMin();
            r.y=bounds.yMin();
            r.width=bounds.sizeX();
            r.height=bounds.sizeY();
            ip.setSlice(bounds.zMin()+1);
            ip.draw();
            //ip.updateAndDraw();
            //ip.updateAndRepaintWindow();
            for (MouseWheelListener mwl : ip.getWindow().getMouseWheelListeners()) mwl.mouseWheelMoved(null); // case of track masks : will update image content
        } 
    }
    @Override
    public ImagePlus getCurrentDisplayedImage() {
        //logger.trace("get current image: {}", WindowManager.getCurrentImage());
        return WindowManager.getCurrentImage();
    }
    @Override
    public Image getCurrentImage() {
       ImagePlus curr = getCurrentDisplayedImage();
       return this.getImage(curr);
    }

    private Overlay getCurrentImageOverlay() {
        ImagePlus im = getCurrentDisplayedImage();
        if (im == null) return null;
        Overlay o = im.getOverlay();
        if (o==null) {
            o = new Overlay();
            im.setOverlay(o);
        }
        return o;
    }
    private void setFrameAndZ(Roi3D roi, int frame, ImagePlus image) {
        roi.setFrame(frame);
        if (image.getNSlices()>1 && roi.is2D()) {
            roi.duplicateROIUntilZ(image.getNSlices());
        }
        if (!image.isDisplayedHyperStack()) {
            if (image.getNSlices()>1) roi.setZToPosition();
            else if (image.getNFrames()>1) roi.setTToPosition();
        }
    }
    private InteractiveImage getCurrentInteractiveImage() {
        ImageWindowManager im = ImageWindowManagerFactory.getImageManager();
        if (im == null) return null;
        return im.getCurrentImageObjectInterface();
    }
    @Override
    public void displayContours(Region region, int frame, double strokeWidth, int smoothRadius, Color color, boolean dashed) {
        Image im = getCurrentImage();
        if (im == null) return;
        int slice = getFrame(im);
        Overlay o = getCurrentImageOverlay();
        if (o==null) return;
        InteractiveImage ii = getCurrentInteractiveImage();
        if (ii ==null) return;
        Offset additionalOffset = TimeLapseInteractiveImage.isKymograph(ii) ? ((TimeLapseInteractiveImage)ii).getOffsetForFrame(frame, slice) : null;
        if (strokeWidth<=0) strokeWidth = ImageWindowManagerFactory.getImageManager().ROI_STROKE_WIDTH;
        if (smoothRadius<=0) smoothRadius = ImageWindowManagerFactory.getImageManager().ROI_SMOOTH_RADIUS;
        Roi3D roi = region.getRoi();
        if (roi == null) {
            region.createRoi();
            roi = region.getRoi();
        }
        roi = roi.duplicate().smooth(smoothRadius);
        if (additionalOffset != null) roi.translate(additionalOffset);
        setFrameAndZ(roi, TimeLapseInteractiveImage.isKymograph(ii) ? 0 : frame, getCurrentDisplayedImage());
        for (Roi r : roi.values()) {
            r.setStrokeWidth(strokeWidth); // also sets scaleStrokeWidth = True
            if (dashed) {
                BasicStroke stroke = new BasicStroke((float)strokeWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 10, new float[]{9}, 0);
                r.setStroke(stroke);
            }
            if (color == null) color = ImageWindowManager.getColor();
            r.setStrokeColor(color);
            o.add(r);
        }
    }

    @Override
    public void displayRegion(Region region, int frame, Color color) {
        Image im = getCurrentImage();
        if (im == null) return;
        int slice = getFrame(im);
        Overlay o = getCurrentImageOverlay();
        if (o==null) return;
        InteractiveImage ii = getCurrentInteractiveImage();
        if (ii ==null) return;
        Offset additionalOffset = TimeLapseInteractiveImage.isKymograph(ii) ? ((TimeLapseInteractiveImage)ii).getOffsetForFrame(frame, slice) : null;
        Roi3D roi = region.getRoi();
        if (roi == null) {
            region.createRoi();
            roi = region.getRoi();
        }
        roi = roi.duplicate();
        if (additionalOffset != null) roi.translate(additionalOffset);
        setFrameAndZ(roi, TimeLapseInteractiveImage.isKymograph(ii) ? 0 : frame, getCurrentDisplayedImage());
        for (Roi r : roi.values()) {
            r.setFillColor(color);
            o.add(r);
        }
    }

    @Override
    public void displayArrow(Point start, Vector direction, int frame, boolean arrowStart, boolean arrowEnd, double strokeWidth, Color color) {
        Image im = getCurrentImage();
        if (im == null) return;
        int slice = getFrame(im);
        Overlay o = getCurrentImageOverlay();
        if (o==null) return;
        InteractiveImage ii = getCurrentInteractiveImage();
        if (ii ==null) return;
        Offset additionalOffset = TimeLapseInteractiveImage.isKymograph(ii) ? ((TimeLapseInteractiveImage)ii).getOffsetForFrame(frame, slice) : null;
        if (strokeWidth<=0) strokeWidth = ImageWindowManagerFactory.getImageManager().TRACK_ARROW_STROKE_WIDTH;
        if (additionalOffset != null) start = start.duplicate().translate(additionalOffset);
        Point end = start.duplicate().translate(direction);
        if (arrowStart && !arrowEnd) {
            Point temp = start;
            start = end;
            end = temp;
        }
        Arrow arrow = new Arrow(start.getDoublePosition(0), start.getDoublePosition(1), end.getDoublePosition(0), end.getDoublePosition(1));
        arrow.enableSubPixelResolution();
        arrow.setStrokeWidth(strokeWidth);
        arrow.setHeadSize(Math.max(strokeWidth * 1.1, 1.1));
        arrow.setStrokeColor(color);
        if (arrowStart && arrowEnd) arrow.setDoubleHeaded(true);
        Roi3D roi = new Roi3D(1);
        if (start.numDimensions()>2) {
            for (int i = (int)start.get(2); i<=(int)Math.ceil(end.get(2)); ++i) {
                roi.put(i, (Roi)arrow.clone());
            }
            roi.setIs2D(false);
        } else {
            roi.put(0, arrow);
            roi.setIs2D(true);
        }
        setFrameAndZ(roi, TimeLapseInteractiveImage.isKymograph(ii) ? 0 : frame, getCurrentDisplayedImage());
        for (Roi r : roi.values()) o.add(r);
    }
    @Override
    public void updateDisplay() {
        ImagePlus ip = getCurrentDisplayedImage();
        if (ip!=null) {
            ip.draw();
            logger.debug("updating display for image: {}", ip.getTitle());
        }
    }

    @Override
    public void hideLabileObjects() {
        Image im = getCurrentImage();
        if (im!=null) {
            ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
            iwm.hideLabileObjects(im, true);
        }

    }
}
