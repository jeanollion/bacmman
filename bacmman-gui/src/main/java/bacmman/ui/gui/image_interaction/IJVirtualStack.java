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

import bacmman.image.ImageCoordinate;
import bacmman.image.LazyImage5D;
import bacmman.processing.ImageOperations;
import bacmman.ui.gui.Utils;
import ij.ImagePlus;
import ij.VirtualStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.image.Image;
import ij.process.ImageStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sc.fiji.i5d.Image5D;
import sc.fiji.i5d.cal.ChannelDisplayProperties;

import java.awt.*;
import java.awt.image.ColorModel;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class IJVirtualStack extends VirtualStack {
    public final static Logger logger = LoggerFactory.getLogger(IJVirtualStack.class);
    final Image source;
    final Function<Integer, ImageCoordinate> getFCZ;
    final BiPredicate<Integer, Integer> isSinglePlaneFC;
    IntConsumer setFrameCallback, setFrameCallbackLabile;
    Map<Integer, double[]> displayRange= new HashMap<>();
    boolean channelWiseDisplayRange = true;
    ImagePlus imp;
    int lastChannel=-1;
    boolean virtual = true;
    final int sizeF, sizeC;
    final LinkedList<ImageCoordinate> cacheQueue = new LinkedList<>();
    final Map<ImageCoordinate, ImageProcessor> cachedImages = new HashMap<>();
    public static int N_CACHED_FRAMES = 20; // TODO cache = memory limit
    BackgroundImageLoader backgroundImageLoader;
    public IJVirtualStack(Image image) {
        super(image.sizeX(), image.sizeY(), null, "");
        this.source= image;
        if (image instanceof LazyImage5D) {
            LazyImage5D li = (LazyImage5D) image;
            sizeF = li.getSizeF();
            sizeC = li.getSizeC();
            isSinglePlaneFC = li::isSinglePlane;
        } else {
            sizeF = 1;
            sizeC = 1;
            isSinglePlaneFC = (f, c) -> false;
        }
        getFCZ = IJImageWrapper.getStackIndexFunctionRev(new int[]{sizeF, sizeC, image.sizeZ()});
        logger.debug("create virtual stack for: {}, frames: {}, channels: {} z: {}", image.getName(), sizeF, sizeC, image.sizeZ());
        for (int n = 0; n<sizeF * sizeC * image.sizeZ(); ++n) super.addSlice("");
        backgroundImageLoader = new BackgroundImageLoader(this::getLoadedPositions, this::getProcessor, cachedImages, sizeF, 2, 1000);
    }

    public void flush() {
        if (backgroundImageLoader!=null) backgroundImageLoader.interrupt();
        cachedImages.clear();
        cacheQueue.clear();
    }

    public Set<ImageCoordinate> getLoadedPositions() {
        return Collections.unmodifiableSet(this.cachedImages.keySet());
    }

    public void generateImagePlus() {
        imp = new ImagePlus();
        imp.setTitle(source.getName());
        imp.setOpenAsHyperStack(true);
        imp.setStack(this, sizeC, source.sizeZ(), sizeF); // calls get processor
        int targetZ = source.sizeZ()>1 ? source.sizeZ()/2+1 : 1;
        int targetC = source instanceof LazyImage5D ? ((LazyImage5D)source).getChannel()+1 : 1;
        if (targetZ>1 || targetC>1) {
            if (!imp.isHyperStack()) {  // TODO bug when not displayed as hyperstack, slider is not updated
                //imp.setSlice(targetZ > 1 ? targetZ : targetC);
                logger.debug("could not set target Z {} or target C: {}", targetZ-1, targetC-1);
            } else {
                logger.debug("virtual stack creation: set Z: {} set C: {}", targetZ-1, targetC-1);
                imp.setPosition(targetC, targetZ, 1);
            }
        } else getProcessor(imp.getCurrentSlice());
        setCalibration();
        logger.debug("ip generated for virtual stack: {}", source.getName());
    }

    public void generateImage5D() {
        setVirtual(false);
        String[] channelNames, channelColors;
        if (source instanceof LazyImage5D) {
            LazyImage5D im5D = (LazyImage5D) source;
            channelNames = im5D.getChannelNames();
            channelColors = im5D.getChannelColors();
            if (channelNames == null) channelNames = IntStream.range(0, im5D.getSizeC()).mapToObj(i -> "Channel "+i).toArray(String[]::new);
            if (channelColors == null ) channelColors = new String[channelNames.length];
        } else {
            channelNames = new String[]{"Channel"};
            channelColors = new String[]{"Grey"};
        }
        Image5D i5d = new Image5D(source.getName(), this, sizeC, source.sizeZ(), sizeF);
        Color[] colors = Arrays.stream(channelColors).map(c -> c==null?null: Utils.getColor(c)).toArray(Color[]::new);
        for (int cidx = 0; cidx<channelNames.length; ++cidx) {
            i5d.getChannelCalibration(cidx+1).setLabel(channelNames[cidx]);
            if (colors[cidx]!=null) {
                ColorModel cm = ChannelDisplayProperties.createModelFromColor(colors[cidx]);
                i5d.setChannelColorModel(cidx + 1, cm);
            }
        }
        i5d.setDisplayMode(2);
        if (source.sizeZ()>1) i5d.setSlice(source.sizeZ()/2+1);
        if (source instanceof LazyImage5D) i5d.setChannel(((LazyImage5D)source).getChannel());
        this.imp = i5d;
        setMinAndMax(i5d);
        setCalibration();
    }

    protected void setCalibration() {
        Calibration cal = new Calibration();
        cal.pixelWidth=source.getScaleXY();
        cal.pixelHeight=source.getScaleXY();
        cal.pixelDepth=source.getScaleZ();
        imp.setCalibration(cal);
    }

    public void appendSetFrameCallback(IntConsumer otherSetFrameCallback, boolean labile) {
        if (labile) {
            if (this.setFrameCallbackLabile==null) this.setFrameCallbackLabile=otherSetFrameCallback;
            else this.setFrameCallbackLabile = setFrameCallbackLabile.andThen(otherSetFrameCallback);
        } else {
            if (this.setFrameCallback==null) this.setFrameCallback=otherSetFrameCallback;
            else this.setFrameCallback = setFrameCallback.andThen(otherSetFrameCallback);
        }

    }
    public void updateFrameCallback() {
        int frame = imp.getT()-1;
        if (setFrameCallback != null) this.setFrameCallback.accept(frame);
        if (setFrameCallbackLabile != null) this.setFrameCallbackLabile.accept(frame);
    }
    public void resetSetFrameCallback() {
        this.setFrameCallbackLabile=null;
    }
    @Override
    public boolean isVirtual() {
        return virtual;
    }
    public IJVirtualStack setVirtual(boolean virtual) {
        this.virtual = virtual;
        return this;
    }
    @Override
    public ImageProcessor getProcessor(int n) {
        long t0 = System.currentTimeMillis();
        //logger.debug("get processor: {} cb null ? {}", n, setFrameCallback==null);
        ImageCoordinate fcz = getFCZ.apply(n);
        //logger.debug("n: {} fcz: {} hyperstack: {}", n, fcz, imp!=null && imp.isDisplayedHyperStack());
        if (setFrameCallback!=null) setFrameCallback.accept(fcz.getFrame());
        if (setFrameCallbackLabile!=null) setFrameCallbackLabile.accept(fcz.getFrame());
        long t1 = System.currentTimeMillis();
        ImageProcessor res = getProcessor(fcz);
        long t2 = System.currentTimeMillis();
        synchronized (cacheQueue) { // put in front
            cacheQueue.remove(fcz);
            cacheQueue.add(fcz);
            if (N_CACHED_FRAMES>0) {
                List<ImageCoordinate> toRemove = new ArrayList<>();
                while (cacheQueue.size() > N_CACHED_FRAMES) {
                    toRemove.add(cacheQueue.pollFirst());
                }
                if (!toRemove.isEmpty()) {
                    synchronized (cachedImages) {
                        toRemove.forEach(cachedImages::remove);
                    }
                }
            }
        }
        long t3 = System.currentTimeMillis();
        if (backgroundImageLoader!=null) backgroundImageLoader.setPosition(fcz);
        long t4 = System.currentTimeMillis();
        //logger.debug("callbacks: {} get image: {} put in front: {} inform background loader: {}", t1-t0, t2-t1, t3-t2, t4-t3);
        return res;
    }

    protected ImageProcessor getProcessor(ImageCoordinate fcz) {
        ImageProcessor ip = cachedImages.get(fcz);
        boolean displaySet = false;
        boolean firstProcessor = false;
        if (ip == null) {
            synchronized (cachedImages) {
                firstProcessor = cachedImages.isEmpty();
                ip = cachedImages.get(fcz);
                if (ip == null) {
                    Image toConvert;
                    if (source instanceof LazyImage5D) {
                        //logger.debug("getting image from lazy image: {}", fcz);
                        toConvert = ((LazyImage5D)source).getImage(fcz.getFrame(), fcz.getChannel(), fcz.getZ());
                    } else {
                        toConvert = source.getZPlane(fcz.getZ());
                    }
                    ip = IJImageWrapper.getImagePlus(toConvert).getProcessor();
                    setDisplayRange(fcz.getChannel(), toConvert, ip);
                    displaySet = true;
                    cachedImages.put(fcz, ip);
                }
            }
        }
        if (!displaySet) setDisplayRange(fcz.getChannel(), null, ip);
        return firstProcessor ? ip.duplicate() : ip; // first image processor is used to display others
    }

    protected void setDisplayRange(int nextChannel, Image nextImage, ImageProcessor nextIP) {
        if (imp ==null || !channelWiseDisplayRange) return;
        if (nextChannel!=lastChannel) {
            if (lastChannel>=0) displayRange.put(lastChannel, new double[]{imp.getDisplayRangeMin(), imp.getDisplayRangeMax()}); // record display for last channel
            if (!displayRange.containsKey(nextChannel)) {
                if (nextImage == null) return;
                double[] minAndMax = ImageDisplayer.getDisplayRange(nextImage, null);
                //logger.debug("getting display range for channel {} -> {}", nextChannel, minAndMax);
                displayRange.put(nextChannel, minAndMax);
            }
            double[] curDisp = displayRange.get(nextChannel);
            if (imp.getProcessor()!=null) imp.getProcessor().setMinAndMax(curDisp[0], curDisp[1]); // the image processor stays the same
            else nextIP.setMinAndMax(curDisp[0], curDisp[1]); // this is the first image processor that will be set to the ip
            //logger.debug("disp range for channel {} = [{}; {}] (ip get processor=null?{})", nextChannel, curDisp[0], curDisp[1], imp.getProcessor()==null);
            lastChannel = nextChannel;
        }
    }
    public static boolean OpenAsImage5D = false;

    private void setMinAndMax(Image5D i5d) {
        for (int c = 0; c < sizeC; ++c) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            int sliceMin = source.sizeZ()/2;
            int sliceMax = sliceMin;
            for(int slice = sliceMin; slice <= sliceMax; ++slice) {
                ImageProcessor imp = getProcessor(new ImageCoordinate(0, c, slice));
                ImageStatistics is = imp.getStatistics();
                if (is.min < min) min = is.min;
                if (is.max > max) max = is.max;
            }
            i5d.setChannelMinMax(c + 1, min, max);
        }
    }
}
