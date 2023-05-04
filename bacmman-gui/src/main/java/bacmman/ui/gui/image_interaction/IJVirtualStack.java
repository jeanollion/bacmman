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
import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.TypeConverter;
import bacmman.image.io.KymographFactory;
import bacmman.processing.ImageOperations;
import bacmman.processing.Resize;
import bacmman.ui.GUI;
import bacmman.ui.gui.Utils;
import bacmman.utils.ArrayUtil;
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
    Function<int[], Image> imageOpenerCT;
    Function<Integer, int[]> getFCZ;
    int[] FCZCount;
    Image[][][] imagesFCZ;
    private final Object lock = new Object();
    IntConsumer setFrameCallback;
    final int bitdepth;
    Map<Integer, double[]> displayRange= new HashMap<>();
    boolean channelWiseDisplayRange = true;
    ImagePlus ip;
    int lastChannel=-1;
    int[] sizeZC;
    boolean virtual = true;
    public IJVirtualStack(int sizeX, int sizeY, int bitdepth, int[] FCZCount, int[] sizeZC, Function<Integer, int[]> getFCZ, Function<int[], Image> imageOpenerCT) {
        super(sizeX, sizeY, null, "");
        this.imageOpenerCT=imageOpenerCT;
        this.getFCZ=getFCZ;
        this.FCZCount=FCZCount;
        this.bitdepth = bitdepth;
        this.sizeZC=sizeZC;
        imagesFCZ = new Image[FCZCount[0]][FCZCount[1]][FCZCount[2]];
        for (int n = 0; n<FCZCount[0]*FCZCount[1]*FCZCount[2]; ++n) super.addSlice("");
    }
    public void appendSetFrameCallback(IntConsumer otherSetFrameCallback) {
        if (this.setFrameCallback==null) this.setFrameCallback=otherSetFrameCallback;
        else this.setFrameCallback = setFrameCallback.andThen(otherSetFrameCallback);
    }
    public void updateFrameCallback() {
        if (this.setFrameCallback==null) return;
        this.setFrameCallback.accept(ip.getT()-1);
    }
    public void resetSetFrameCallback() {
        this.setFrameCallback=null;
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
        //logger.debug("get processor: {} cb null ? {}", n, setFrameCallback==null);
        int[] fcz = getFCZ.apply(n);
        if (fcz[2]>= sizeZC[fcz[1]]) {
            if (sizeZC[fcz[1]]==1) fcz[2]=0; // case of reference images -> only one Z -> open first Z
            else throw new IllegalArgumentException("Wrong Z size for channel: "+fcz[1] + " :"+ fcz[2]+"/"+ sizeZC[fcz[1]]);
        }
        if (setFrameCallback!=null) setFrameCallback.accept(fcz[0]);
        if (imagesFCZ[fcz[0]][fcz[1]][fcz[2]]==null) {
            synchronized(lock) {
                if (imagesFCZ[fcz[0]][fcz[1]][fcz[2]]==null) {
                    Image plane = imageOpenerCT.apply(fcz);
                    if (plane==null) logger.error("could not open image: channel: {}, frame: {}", fcz[1], fcz[0]);
                    else {
                        if (plane.getBitDepth()!=bitdepth) plane = TypeConverter.convert(plane, bitdepth);
                        imagesFCZ[fcz[0]][fcz[1]][fcz[2]]= plane;
                    }
                }
            }
        }

        ImageProcessor ip = IJImageWrapper.getImagePlus(imagesFCZ[fcz[0]][fcz[1]][fcz[2]]).getProcessor();
        setDisplayRange(fcz[1], imagesFCZ[fcz[0]][fcz[1]][fcz[2]], ip);
        return ip;
    }

    protected void setDisplayRange(int nextChannel, Image nextImage, ImageProcessor nextIP) {
        if (ip==null || !channelWiseDisplayRange) return;
        if (nextChannel!=lastChannel) {
            if (lastChannel>=0) displayRange.put(lastChannel, new double[]{ip.getDisplayRangeMin(), ip.getDisplayRangeMax()}); // record display for last channel
            if (!displayRange.containsKey(nextChannel)) { // TODO initialize with more elaborated algorithm ?
                double[] minAndMax = ImageOperations.getQuantiles(nextImage, null, null, 0.01, 99.9);
                //logger.debug("getting display range for channel {} -> {}", nextChannel, minAndMax);
                displayRange.put(nextChannel, minAndMax);
            }
            double[] curDisp = displayRange.get(nextChannel);
            if (ip.getProcessor()!=null) ip.getProcessor().setMinAndMax(curDisp[0], curDisp[1]); // the image processor stays the same
            else nextIP.setMinAndMax(curDisp[0], curDisp[1]); // this is the first image processor that will be set to the ip
            //logger.debug("disp range for channel {} = [{}; {}] (ip get processor=null?{})", nextChannel, curDisp[0], curDisp[1], ip.getProcessor()==null);
            lastChannel = nextChannel;
        }
    }
    public void setImagePlus(ImagePlus ip) {
        this.ip = ip;
    }
    public static boolean OpenAsImage5D = false;
    public static void openVirtual(Experiment xp, String position, boolean preProcessed, boolean image5D) {
        Position f = xp.getPosition(position);
        int channels = xp.getChannelImageCount(preProcessed);
        int frames = f.getFrameNumber(false);
        Function<int[], Image> imageOpenerCT  = preProcessed ? (fcz) -> f.getImageDAO().openPreProcessedImagePlane(fcz[2], fcz[1], fcz[0]) : (fcz) -> f.getInputImages().getRawPlane(fcz[2], fcz[1], fcz[0]);
        Image[] planes0 = IntStream.range(0, channels).mapToObj(c -> imageOpenerCT.apply(new int[]{0, c, 0})).toArray(Image[]::new);
        int maxBitDepth = IntStream.range(0, channels).map(c -> planes0[c].getBitDepth()).max().getAsInt();
        Function<int[], Image> imageOpenerCT2 = fcz -> fcz[0]==0 && fcz[2]==0 ? planes0[fcz[1]] : imageOpenerCT.apply(fcz);
        if (Arrays.stream(planes0).anyMatch(p -> p==null)) {
            GUI.log("Missing "+(preProcessed ? "preprocessed " : "input")+" images found for position: "+position);
            return;
        }
        boolean invalidXY = IntStream.range(1, channels).anyMatch(c -> planes0[c].sizeX()!=planes0[0].sizeX() || planes0[c].sizeY()!=planes0[0].sizeY());
        if (invalidXY) {
            GUI.log("At least 2 channels have XY dimensions that differ");
            return;
        }
        // case of reference image with only one Z -> duplicate
        IntUnaryOperator getSizeZC = preProcessed ? c -> f.getImageDAO().getPreProcessedImageProperties(c).sizeZ() : c -> f.getInputImages().getSourceSizeZ(c);
        int[] sizeZC = IntStream.range(0, channels).map(getSizeZC).toArray();
        int maxZIdx = ArrayUtil.max(sizeZC);
        int maxZ = sizeZC[maxZIdx];
        int[] fczSize = new int[]{frames, channels, maxZ};
        IJVirtualStack s = new IJVirtualStack(planes0[0].sizeX(), planes0[0].sizeY(), maxBitDepth, fczSize, sizeZC, IJImageWrapper.getStackIndexFunctionRev(fczSize), imageOpenerCT2);
        String title = (preProcessed ? "PreProcessed Images of position: #" : "Input Images of position: #")+f.getIndex();
        ImagePlus ip;
        if (image5D) {
            s.setVirtual(false);
            Image5D i5d = new Image5D(title, s, channels, maxZ, frames);
            String[] cNames = xp.getChannelImagesAsString(true);
            Color[] colors = xp.getChannelColor(true).map(c -> c==null?null: Utils.getColor(c.toString())).toArray(Color[]::new);
            for (int cidx = 0; cidx<channels; ++cidx) {
                i5d.getChannelCalibration(cidx+1).setLabel(cNames[cidx]);
                if (colors[cidx]!=null) {
                    ColorModel cm = ChannelDisplayProperties.createModelFromColor(colors[cidx]);
                    i5d.setChannelColorModel(cidx + 1, cm);
                }
            }
            setMinAndMax(i5d);
            i5d.setDisplayMode(2);
            if (maxZ>1) i5d.setSlice(maxZ/2+1);
            ip = i5d;
        } else {
            ip = new ImagePlus();
            ip.setTitle(title);
            ip.setStack(s, channels, maxZ, frames);
            ip.setOpenAsHyperStack(true);
            if (maxZ>1) ip.setZ(maxZ/2+1);
            s.setImagePlus(ip);
            s.getProcessor(ip.getCurrentSlice()); // update display range
        }
        Calibration cal = new Calibration();
        cal.pixelWidth=planes0[0].getScaleXY();
        cal.pixelHeight=planes0[0].getScaleXY();
        cal.pixelDepth=planes0[maxZIdx].getScaleZ();
        ip.setCalibration(cal);
        ip.show();
        ImageWindowManagerFactory.getImageManager().addLocalZoom(ip.getCanvas());
        ImageWindowManagerFactory.getImageManager().addInputImage(position, ip, !preProcessed);
        Image hook = imageOpenerCT.apply(new int[]{0, 0, 0});
        ImageWindowManagerFactory.getImageManager().getDisplayer().putImage(hook, ip);
    }

    public static Image openVirtual(List<SegmentedObject> parentTrack, int interactiveOC, boolean interactive, int objectClassIdx, boolean image5D) {
        HyperStack interactiveImage = null;
        if (interactive) interactiveImage = (HyperStack)ImageWindowManagerFactory.getImageManager().getImageTrackObjectInterface(parentTrack, interactiveOC, InteractiveImageKey.TYPE.HYPERSTACK);
        if (interactiveImage==null) {
            KymographFactory.KymographData data = KymographFactory.generateHyperstackData(parentTrack, true);
            interactiveImage = new HyperStack(data, interactiveOC, false);
        }
        return openVirtual(parentTrack, interactiveImage, interactive, objectClassIdx, image5D);
    }

    public static Image openVirtual(List<SegmentedObject> parentTrack, HyperStack interactiveImage, boolean interactive, int objectClassIdx, boolean image5D) {
        if (parentTrack.isEmpty()) return null;
        int[] channelArray = interactiveImage.isSingleChannel()? new int[]{0} : ArrayUtil.generateIntegerArray(parentTrack.get(0).getExperimentStructure().getObjectClassesAsString().length);
        int channels = channelArray.length;
        int frames = parentTrack.size();

        // case of reference image with only one Z -> duplicate
        int[] sizeZC = IntStream.range(0, channels).map(interactiveImage::getSizeZ).toArray();
        int maxZIdx = ArrayUtil.max(sizeZC);
        int maxZ = sizeZC[maxZIdx];
        int[] fczSize = new int[]{frames, channels, maxZ};
        //logger.debug("sizeZ per channel C: {}, frames: {} maxZ: {}, isSingleChannel: {}", sizeZC, frames, maxZ, interactiveImage.isSingleChannel());
        Function<int[], Image> imageOpenerCT  = (fcz) -> interactiveImage.getPlane(fcz[2], channelArray[fcz[1]], true, Resize.EXPAND_MODE.BORDER);
        Image[] planes0 = IntStream.range(0, channels).mapToObj(c -> imageOpenerCT.apply(new int[]{0, c, 0})).toArray(Image[]::new);
        int maxBitDepth = IntStream.range(0, channels).map(c -> planes0[c].getBitDepth()).max().getAsInt();
        Function<int[], Image> imageOpenerCT2 = fcz -> fcz[0]==0 && fcz[2]==0 ? planes0[fcz[1]] : imageOpenerCT.apply(fcz);
        IJVirtualStack s = new IJVirtualStack(interactiveImage.maxParentSizeX, interactiveImage.maxParentSizeY, maxBitDepth, fczSize, sizeZC, IJImageWrapper.getStackIndexFunctionRev(fczSize), imageOpenerCT2);
        String title = interactiveImage.getName() == null || interactiveImage.getName().length()==0 ? (parentTrack.get(0).isRoot() ? "HyperStack of Position: #"+parentTrack.get(0).getPositionIdx() : "HyperStack of Track: "+parentTrack.get(0).toStringShort()): interactiveImage.getName();
        ImagePlus ip;
        if (image5D) {
            s.setVirtual(false);
            Image5D i5d = new Image5D(title, s, channels, maxZ, frames);
            ExperimentStructure xp = parentTrack.get(0).getExperimentStructure();
            String[] cNames = xp.getObjectClassNames();
            Color[] colors = xp.getChannelColors().map(c -> c==null?null:Utils.getColor(c.toString())).toArray(Color[]::new);
            Set<Integer> dispChannels=new HashSet<>();
            for (int ocidx = 0; ocidx<channels; ++ocidx) {
                i5d.getChannelCalibration(ocidx+1).setLabel(cNames[ocidx]);
                int cidx= xp.getChannelIdx(ocidx);
                if (cidx>=0 && dispChannels.contains(cidx)) i5d.setDisplayedInOverlay(ocidx+1, false);
                dispChannels.add(cidx);
                if (cidx>=0 && colors[cidx]!=null) {
                    ColorModel cm = ChannelDisplayProperties.createModelFromColor(colors[cidx]);
                    i5d.setChannelColorModel(ocidx + 1, cm);
                }
            }
            setMinAndMax(i5d);
            i5d.setDisplayMode(2);
            if (maxZ>1) i5d.setSlice(maxZ/2+1);
            ip = i5d;
        } else {
            ip = new ImagePlus();
            ip.setTitle(title);
            ip.setStack(s, channels, maxZ, frames);
            ip.setOpenAsHyperStack(true);
            if (maxZ>1) ip.setZ(maxZ/2+1);
            s.setImagePlus(ip);
            s.getProcessor(ip.getCurrentSlice()); // update display range
        }

        Calibration cal = new Calibration();
        cal.pixelWidth=planes0[0].getScaleXY();
        cal.pixelHeight=planes0[0].getScaleXY();
        cal.pixelDepth=planes0[maxZIdx].getScaleZ();
        ip.setCalibration(cal);
        ip.setC(objectClassIdx+1);
        ip.show();
        ImageWindowManagerFactory.getImageManager().addLocalZoom(ip.getCanvas());
        Image hook = imageOpenerCT.apply(new int[]{0, 0, 0});
        if (interactive) ImageWindowManagerFactory.getImageManager().addHyperStack(hook, ip, interactiveImage);
        else {
            ImageWindowManagerFactory.getImageManager().getDisplayer().putImage(hook, ip);
            ImageWindowManagerFactory.getImageManager().registerInteractiveHyperStackFrameCallback(hook, interactiveImage, false);
        }
        return hook;
    }
    private static void setMinAndMax(Image5D i5d) {
        for (int i = 0; i < i5d.getNChannels(); ++i) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for(int slice = 1; slice <= i5d.getNSlices(); ++slice) {
                i5d.setSlice(slice);
                ImagePlus chan = i5d.getChannelImagePlus(i + 1);
                ImageStatistics is = chan.getStatistics();
                if (is.min < min) min = is.min;
                if (is.max > max) max = is.max;
            }
            i5d.setChannelMinMax(i + 1, min, max);
        }
    }
}
