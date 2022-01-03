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
import bacmman.data_structure.SegmentedObject;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.TypeConverter;
import bacmman.image.io.KymographFactory;
import bacmman.processing.ImageOperations;
import bacmman.processing.Resize;
import bacmman.ui.GUI;
import ij.IJ;
import ij.ImagePlus;
import ij.VirtualStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static bacmman.image.Image.logger;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
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
    @Override
    public ImageProcessor getProcessor(int n) {
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
            if (!displayRange.containsKey(nextChannel)) { // initialize with actual range // TODO initialize with more elaborated algorithm ?
                double[] minAndMax = ImageOperations.getQuantiles(nextImage, null, null, 0.01, 99.9);
                displayRange.put(nextChannel, minAndMax);
            }
            double[] curDisp = displayRange.get(nextChannel);
            if (ip.getProcessor()!=null) ip.getProcessor().setMinAndMax(curDisp[0], curDisp[1]); // the image processor stays the same
            else nextIP.setMinAndMax(curDisp[0], curDisp[1]);
            //logger.debug("disp range for channel {} = [{}; {}]", nextChannel, curDisp[0], curDisp[1]);
            lastChannel = nextChannel;
        }
    }
    public void setImagePlus(ImagePlus ip) {
        this.ip = ip;
    }
    public static void openVirtual(Experiment xp, String position, boolean preProcessed) {
        Position f = xp.getPosition(position);
        int channels = xp.getChannelImageCount(preProcessed);
        int frames = f.getFrameNumber(false);

        Image bds = preProcessed ? f.getImageDAO().openPreProcessedImagePlane(0, 0, 0) : f.getInputImages().getRawPlane(0, 0, 0);
        if (bds==null) {
            GUI.log("No "+(preProcessed ? "preprocessed " : "input")+" images found for position: "+position);
            return;
        }
        logger.debug("scale: {}", bds.getScaleXY());
        // case of reference image with only one Z -> duplicate
        int maxZ = 0;
        IntUnaryOperator getSizeZC = preProcessed ? c -> f.getImageDAO().getPreProcessedImageProperties(c).sizeZ() : c -> f.getInputImages().getSourceSizeZ(c);
        int[] sizeZC = new int[channels];
        for (int c=0; c<channels; ++c) {
            sizeZC[c] = getSizeZC.applyAsInt(c);
            maxZ = Math.max(maxZ, sizeZC[c]);
        }

        int[] fczSize = new int[]{frames, channels, maxZ};
        Function<int[], Image> imageOpenerCT  = preProcessed ? (fcz) -> fcz[0]==0&&fcz[1]==0&&fcz[2]==0? bds : f.getImageDAO().openPreProcessedImagePlane(fcz[2], fcz[1], fcz[0]) : (fcz) -> f.getInputImages().getRawPlane(fcz[2], fcz[1], fcz[0]);
        IJVirtualStack s = new IJVirtualStack(bds.sizeX(), bds.sizeY(), bds.getBitDepth(), fczSize, sizeZC, IJImageWrapper.getStackIndexFunctionRev(fczSize), imageOpenerCT);
        ImagePlus ip = new ImagePlus();
        s.setImagePlus(ip);
        ip.setTitle((preProcessed ? "PreProcessed Images of position: #" : "Input Images of position: #")+f.getIndex());
        ip.setStack(s, channels,maxZ, frames);
        ip.setOpenAsHyperStack(true);
        ip.setDisplayMode( IJ.COMPOSITE );
        Calibration cal = new Calibration();
        cal.pixelWidth=bds.getScaleXY();
        cal.pixelHeight=bds.getScaleXY();
        cal.pixelDepth=bds.getScaleZ();
        ip.setCalibration(cal);
        ip.show();
        ImageWindowManagerFactory.getImageManager().addLocalZoom(ip.getCanvas());
        ImageWindowManagerFactory.getImageManager().addInputImage(position, ip, !preProcessed);
    }
    public static Image openVirtual(List<SegmentedObject> parentTrack, int interactiveOC, boolean interactive, int... objectClassIdx) {
        KymographT interactiveImage = null;
        if (interactive) interactiveImage = (KymographT)ImageWindowManagerFactory.getImageManager().getImageTrackObjectInterface(parentTrack, interactiveOC, InteractiveImageKey.TYPE.FRAME_STACK);
        if (interactiveImage==null) {
            KymographFactory.KymographData data = KymographFactory.generateKymographDataTime(parentTrack, true);
            interactiveImage = new KymographT(data, interactiveOC);
        }
        return openVirtual(parentTrack, interactiveImage, interactive, objectClassIdx);
    }
    public static Image openVirtual(List<SegmentedObject> parentTrack, KymographT interactiveImage, boolean interactive, int... objectClassIdx) {
        if (parentTrack.isEmpty()) return null;
        int[] channelArray = objectClassIdx.length==0 ? new int[]{parentTrack.get(0).getStructureIdx()} : objectClassIdx;
        int channels = channelArray.length;
        int frames = parentTrack.size();
        Image[] bdsC = new Image[channels];
        for (int c = 0; c<bdsC.length; ++c) bdsC[c]= parentTrack.get(0).getRawImage(channelArray[c]);
        if (bdsC[0]==null) {
            GUI.log("Could not open raw images");
            return null;
        }
        logger.debug("scale: {}", bdsC[0].getScaleXY());
        logger.debug("image bounds per channel: {}", Arrays.stream(bdsC).map(Image::getBoundingBox).collect(Collectors.toList()));
        // case of reference image with only one Z -> duplicate
        int maxZ = Collections.max(Arrays.asList(bdsC), Comparator.comparingInt(SimpleBoundingBox::sizeZ)).sizeZ();
        int[] sizeZC = IntStream.range(0, channels).map(i -> bdsC[i].sizeZ()).toArray();
        int[] fczSize = new int[]{frames, channels, maxZ};
        Function<int[], Image> imageOpenerCT  = (fcz) -> interactiveImage.getImage(channelArray[fcz[1]], true, Resize.EXPAND_MODE.BORDER).getZPlane(fcz[2]);
        IJVirtualStack s = new IJVirtualStack(interactiveImage.maxParentSizeX, interactiveImage.maxParentSizeY, bdsC[0].getBitDepth(), fczSize, sizeZC, IJImageWrapper.getStackIndexFunctionRev(fczSize), imageOpenerCT);
        ImagePlus ip = new ImagePlus();
        ip.setTitle(("HyperStack of Track: "+parentTrack.get(0).toStringShort()));
        ip.setStack(s, channels,maxZ, frames);
        ip.setOpenAsHyperStack(true);
        ip.setDisplayMode( IJ.COMPOSITE );
        Calibration cal = new Calibration();
        cal.pixelWidth=bdsC[0].getScaleXY();
        cal.pixelHeight=bdsC[0].getScaleXY();
        cal.pixelDepth=bdsC[0].getScaleZ();
        ip.setCalibration(cal);
        ip.show();
        ImageWindowManagerFactory.getImageManager().addLocalZoom(ip.getCanvas());
        Image hook = imageOpenerCT.apply(new int[]{0, 0, 0});
        if (interactive) ImageWindowManagerFactory.getImageManager().addHyperStack(hook, ip, interactiveImage);
        else ImageWindowManagerFactory.getImageManager().getDisplayer().putImage(hook, ip);
        return hook;
    }
}
