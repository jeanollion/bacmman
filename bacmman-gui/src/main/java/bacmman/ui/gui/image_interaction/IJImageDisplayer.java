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
import bacmman.plugins.Plugin;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import ij.*;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.image.Image;
import ij.gui.*;
import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;
import ij.plugin.frame.SyncWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 *
 * @author Jean Ollion
 */
public class IJImageDisplayer implements ImageDisplayer<ImagePlus> , OverlayDisplayer{
    static Logger logger = LoggerFactory.getLogger(IJImageDisplayer.class);
    protected HashMap<Image, ImagePlus> displayedImages=new HashMap<>();
    protected HashMap<ImagePlus, Image> displayedImagesInv=new HashMap<>();
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
    @Override public ImagePlus showImage(Image image, double... displayRange) {
        /*if (IJ.getInstance()==null) {
            ij.ImageJ.main(new String[0]);
            //new ImageJ();
        }*/
        if (imageExistsButHasBeenClosed(image)) {
            displayedImagesInv.remove(displayedImages.get(image));
            displayedImages.remove(image);
        }
        ImagePlus ip = getImage(image);
        if (displayRange.length==0) displayRange = ImageDisplayer.getDisplayRange(image, null);
        else if (displayRange.length==1) {
            double[] dispRange = ImageDisplayer.getDisplayRange(image, null);
            dispRange[0]=displayRange[0];
            displayRange=dispRange;
        } else if (displayRange.length>=2) {
            if (displayRange[1]<=displayRange[0]) {
                double[] dispRange = ImageDisplayer.getDisplayRange(image, null);
                displayRange[1] = dispRange[1];
            }
        }
        ip.setDisplayRange(displayRange[0], displayRange[1]);
        //logger.debug("show image:w={}, h={}, disp: {}", ip.getWidth(), ip.getHeight(), displayRange);
        if (!ip.isVisible()) ip.show();
        addMouseWheelListener(image, null);
        ImageWindowManagerFactory.getImageManager().addLocalZoom(ip.getCanvas());
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
    private static void waitUntill(long max, Supplier<Boolean> test) {
        long start = System.currentTimeMillis();
        while (!test.get()) {
            try {Thread.sleep(50);} 
            catch(InterruptedException e) {}
            if (System.currentTimeMillis()-start > max) break;
        }
        bacmman.ui.GUI.logger.debug("wait: {} test: {}", System.currentTimeMillis()-start, test.get());
    }
    
    @Override public void addMouseWheelListener(final Image image, Predicate<BoundingBox> callBack) {
        final ImagePlus imp = getImage(image);
        if (imp==null) return;
        final ImageWindow iw = imp.getWindow();
        final ImageCanvas ic = imp.getCanvas();
        final boolean[] zoomHasBeenFixed = new boolean[1];
        if (iw==null || ic ==null) return;
        MouseWheelListener mwl = e ->  { // code modified from IJ source to better suit needs for LARGE track mask images + call back to display images
            synchronized (iw) {
                if (e==null) { 
                    if (callBack!=null) { // can be called when scroll moved manually
                        Rectangle max = GUI.getMaxWindowBounds();
                        boolean update = callBack.test(new SimpleBoundingBox(ic.getSrcRect().x, ic.getSrcRect().x+Math.min(max.width-1, ic.getSrcRect().width-1), ic.getSrcRect().y, ic.getSrcRect().y+Math.min(max.height-1, ic.getSrcRect().height-1), 0, 0));
                        if (update ) imp.updateAndRepaintWindow();
                    }
                    return;
                }
                
                if (!zoomHasBeenFixed[0] && ic.getMagnification()<0.4) { // case zoom is very low -> set to 100%
                    ic.zoom100Percent();
                    zoomHasBeenFixed[0] = true;
                }
                int rotation = e.getWheelRotation();
                int amount = e.getScrollAmount();
                boolean ctrl = e.isControlDown();
                boolean alt = e.isAltDown();
                boolean altGr = e.isAltGraphDown();
                boolean space = IJ.spaceBarDown();
                boolean acceleratedScrolling = e.isShiftDown(); // accelerated scrolling
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
                boolean scrollZ = needScrollZ && (!needScrollImage || space);
                boolean scrollTime = needScrollTime && !scrollZ && (!needScrollImage || alt);
                boolean scrollChannels = needScrollChannel && !scrollZ && !scrollTime && (!needScrollImage || altGr);
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
                } else { // move image
                    if ((double)srcRect.height/height>(double)srcRect.width/width || (srcRect.height/height<srcRect.width/width && space)) { // scroll in the most needed direction
                            srcRect.x += rotation*amount* (acceleratedScrolling ? Math.max(width/60, srcRect.width/12) : srcRect.width/12);
                            if (srcRect.x<0) srcRect.x = 0;
                            if (srcRect.x+srcRect.width>width) srcRect.x = width-srcRect.width;
                    } else { // most needed direction is Y
                            srcRect.y += rotation*amount*(acceleratedScrolling ?  Math.max(height/60, srcRect.height/12) : srcRect.height/12);
                            if (srcRect.y<0) srcRect.y = 0;
                            if (srcRect.y+srcRect.height>height) srcRect.y = height-srcRect.height;
                    }
                }
                if (srcRect.x!=xstart || srcRect.y!=ystart) {
                    boolean update = false;
                    if (callBack !=null )  {
                        Rectangle max = GUI.getMaxWindowBounds();
                        update = callBack.test(new SimpleBoundingBox(ic.getSrcRect().x, ic.getSrcRect().x+Math.min(max.width-1, ic.getSrcRect().width-1), ic.getSrcRect().y, ic.getSrcRect().y+Math.min(max.height-1, ic.getSrcRect().height-1), 0, 0));
                    }
                    if (update) imp.updateAndRepaintWindow();//ic.repaint();
                    else  ic.repaint();
                }
            }    
	    };
        
        if (callBack!=null) { // initial call back
            boolean update = callBack.test(new SimpleBoundingBox(ic.getSrcRect().x, ic.getSrcRect().x+ic.getSrcRect().width-1, ic.getSrcRect().y, ic.getSrcRect().y+ic.getSrcRect().height-1, 0, 0));
            if (update ) imp.updateAndRepaintWindow();
        }
        
        for (MouseWheelListener mwl2: iw.getMouseWheelListeners()) iw.removeMouseWheelListener(mwl2);
        iw.addMouseWheelListener(mwl);
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
            ip= IJImageWrapper.getImagePlus(image);
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
    
    
    @Override public ImagePlus showImage5D(String title, Image[][] imageTC) {
        if (IJ.getInstance()==null) new ImageJ();
        /*Image5D res = new Image5D(title, getImagePlus(imageTC), imageTC[0].length, imageTC[0][0].getSizeZ(), imageTC.length);
        for (int i = 0; i < imageTC[0].length; i++) {
            float[] dispRange = imageTC[0][i].getMinAndMax(null);
            res.setChannelMinMax(i + 1, dispRange[0], dispRange[1]);
            res.setDefaultChannelNames();
        }*/
        /*for (int i = 0; i < images.length; i++) { // set colors of channels
            Color c = tango.gui.util.Colors.colors.get(tango.gui.util.Colors.colorNames[i + 1]);
            ColorModel cm = ChannelDisplayProperties.createModelFromColor(c);
            res.setChannelColorModel(i + 1, cm);
        }*/
        //res.setDisplayMode(ChannelControl.OVERLAY);
        //res.show();
        ImagePlus ip = IJImageWrapper.getImagePlus(imageTC, -1);
        ip.setTitle(title);
        double[] displayRange = ImageDisplayer.getDisplayRange(imageTC[0][0], null);
        ip.setDisplayRange(displayRange[0], displayRange[1]);
        ip.show();
        ImageWindowManagerFactory.getImageManager().addLocalZoom(ip.getCanvas());
        bacmman.ui.GUI.logger.debug("image: {}, isDisplayedAsHyperStack: {}, is HP: {}, dim: {}", title, ip.isDisplayedHyperStack(), ip.isHyperStack(), ip.getDimensions());
        displayedImages.put(imageTC[0][0], ip);
        displayedImagesInv.put(ip, imageTC[0][0]);
        return ip;
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
                ip.draw(); // draw is sufficient, no need to call upadate that doest a snapshot...
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
    public ImagePlus getCurrentImage() {
        //logger.trace("get current image: {}", WindowManager.getCurrentImage());
        return WindowManager.getCurrentImage();
    }
    @Override
    public Image getCurrentImage2() {
       ImagePlus curr = getCurrentImage();
       return this.getImage(curr);
    }
    
    public int[] getFCZCount(ImagePlus image) {
        return new int[]{image.getNFrames(), image.getNChannels(), image.getNSlices()};
    }
    
    @Override
    public Image[][] getCurrentImageCT() {
        ImagePlus ip = this.getCurrentImage();
        if (ip==null) return null;
        int[] FCZCount = getFCZCount(ip);
        return ImageDisplayer.reslice(IJImageWrapper.wrap(ip), FCZCount, IJImageWrapper.getStackIndexFunction(FCZCount));
    }
    private Overlay getCurrentImageOverlay() {
        ImagePlus im = getCurrentImage();
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
    @Override
    public void displayContours(Region region, int frame, double strokeWidth, Color color, boolean dashed) {
        Overlay o = getCurrentImageOverlay();
        if (o==null) return;
        if (strokeWidth<=0) strokeWidth = ImageWindowManagerFactory.getImageManager().ROI_STROKE_WIDTH;
        Roi3D roi = region.getRoi();
        if (roi == null) {
            region.createRoi();
            roi = region.getRoi();
        }
        roi = roi.duplicate();
        setFrameAndZ(roi, frame, getCurrentImage());
        for (Roi r : roi.values()) {
            r.setStrokeWidth(strokeWidth); // also sets scaleStrokeWidth = True
            if (dashed) {
                BasicStroke stroke = new BasicStroke((float)strokeWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 10, new float[]{9}, 0);
                r.setStroke(stroke);
            }
            r.setStrokeColor(color);
            o.add(r);
        }
    }

    @Override
    public void displayRegion(Region region, int frame, Color color) {
        Overlay o = getCurrentImageOverlay();
        if (o==null) return;
        Roi3D roi = region.getRoi();
        if (roi == null) {
            region.createRoi();
            roi = region.getRoi();
        }
        roi = roi.duplicate();
        setFrameAndZ(roi, frame, getCurrentImage());
        for (Roi r : roi.values()) {
            r.setFillColor(color);
            o.add(r);
        }
    }

    @Override
    public void displayArrow(Point start, Vector direction, int frame, boolean arrowStart, boolean arrowEnd, double strokeWidth, Color color) {
        Overlay o = getCurrentImageOverlay();
        if (o==null) return;
        if (strokeWidth<=0) strokeWidth = ImageWindowManagerFactory.getImageManager().ROI_STROKE_WIDTH;
        Point end = start.duplicate().translate(direction);
        if (arrowStart && !arrowEnd) {
            Point temp = start;
            start = end;
            end = temp;
        }
        Arrow arrow = new Arrow(start.getDoublePosition(0), start.getDoublePosition(1), end.getDoublePosition(0), end.getDoublePosition(1));
        arrow.enableSubPixelResolution();
        arrow.setStrokeWidth(strokeWidth);
        arrow.setHeadSize(strokeWidth * 1.1);
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
        setFrameAndZ(roi, frame, getCurrentImage());
        for (Roi r : roi.values()) o.add(r);
    }
    @Override
    public void updateDisplay() {
        ImagePlus ip = getCurrentImage();
        if (ip!=null) {
            ip.draw();
            logger.debug("updating display for image: {}", ip.getTitle());
        }
    }
}
