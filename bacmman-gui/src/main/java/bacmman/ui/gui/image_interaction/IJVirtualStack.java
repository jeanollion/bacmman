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
import bacmman.image.io.KymographFactory;
import bacmman.processing.Resize;
import bacmman.ui.GUI;
import ij.IJ;
import ij.ImagePlus;
import ij.VirtualStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.image.Image;
import static bacmman.image.Image.logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class IJVirtualStack extends VirtualStack {
    BiFunction<Integer, Integer, Image> imageOpenerCT;
    Function<Integer, int[]> getFCZ;
    int[] FCZCount;
    Image[][] imageCT;
    private final Object lock = new Object();
    IntConsumer setFrameCallback;
    public IJVirtualStack(int sizeX, int sizeY, int[] FCZCount, Function<Integer, int[]> getFCZ, BiFunction<Integer, Integer, Image> imageOpenerCT, IntConsumer setFrameCallback) {
        super(sizeX, sizeY, null, "");
        this.imageOpenerCT=imageOpenerCT;
        this.getFCZ=getFCZ;
        this.FCZCount=FCZCount;
        this.imageCT=new Image[FCZCount[1]][FCZCount[0]];
        this.setFrameCallback=setFrameCallback ==null ? i -> {} : setFrameCallback;
        for (int n = 0; n<FCZCount[0]*FCZCount[1]*FCZCount[2]; ++n) super.addSlice("");
    }
    
    @Override
    public ImageProcessor getProcessor(int n) {
        int[] fcz = getFCZ.apply(n);
        setFrameCallback.accept(fcz[0]);
        if (imageCT[fcz[1]][fcz[0]]==null) {
            synchronized(lock) {
                if (imageCT[fcz[1]][fcz[0]]==null) {
                    imageCT[fcz[1]][fcz[0]] = imageOpenerCT.apply(fcz[1], fcz[0]);
                    if (imageCT[fcz[1]][fcz[0]]==null) logger.error("could not open image: channel: {}, frame: {}", fcz[1], fcz[0]);
                }
            }
        }
        if (fcz[2]>= imageCT[fcz[1]][fcz[0]].sizeZ()) {
            if (imageCT[fcz[1]][fcz[0]].sizeZ()==1) fcz[2]=0; // case of reference images 
            else throw new IllegalArgumentException("Wrong Z size for channel: "+fcz[1] + " :"+ fcz[2]+"/"+imageCT[fcz[1]][fcz[0]].sizeZ());
        }
        return IJImageWrapper.getImagePlus(imageCT[fcz[1]][fcz[0]].getZPlane(fcz[2])).getProcessor();
    }
    public static void openVirtual(Experiment xp, String position, boolean preProcessed) {
        Position f = xp.getPosition(position);
        int channels = xp.getChannelImageCount(preProcessed);
        int frames = f.getFrameNumber(false);
        Image[] bdsC = new Image[channels];
        for (int c = 0; c<bdsC.length; ++c) bdsC[c]= preProcessed ? f.getImageDAO().openPreProcessedImage(c, 0) : f.getInputImages().getImage(c, 0);
        if (bdsC[0]==null) {
            GUI.log("No "+(preProcessed ? "preprocessed " : "input")+" images found for position: "+position);
            return;
        }
        logger.debug("scale: {}", bdsC[0].getScaleXY());
        logger.debug("image bounds per channel: {}", Arrays.stream(bdsC).map(Image::getBoundingBox).collect(Collectors.toList()));
        // case of reference image with only one Z -> duplicate
        int maxZ = Collections.max(Arrays.asList(bdsC), Comparator.comparingInt(SimpleBoundingBox::sizeZ)).sizeZ();
        int[] fcz = new int[]{frames, channels, maxZ};
        BiFunction<Integer, Integer, Image> imageOpenerCT  = preProcessed ? (c, t) -> f.getImageDAO().openPreProcessedImage(c, t) : (c, t) -> f.getInputImages().getImage(c, t);
        IJVirtualStack s = new IJVirtualStack(bdsC[0].sizeX(), bdsC[0].sizeY(), fcz, IJImageWrapper.getStackIndexFunctionRev(fcz), imageOpenerCT, null);
        ImagePlus ip = new ImagePlus();
        ip.setTitle((preProcessed ? "PreProcessed Images of position: #" : "Input Images of position: #")+f.getIndex());
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
        int[] fcz = new int[]{frames, channels, maxZ};
        BiFunction<Integer, Integer, Image> imageOpenerCT  = (c, t) -> interactiveImage.getImage(channelArray[c], true, Resize.EXPAND_MODE.BORDER);
        IJVirtualStack s = new IJVirtualStack(interactiveImage.maxParentSizeX, interactiveImage.maxParentSizeY, fcz, IJImageWrapper.getStackIndexFunctionRev(fcz), imageOpenerCT, interactiveImage::setIdx);
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
        Image hook = imageOpenerCT.apply(0, 0);
        if (interactive) ImageWindowManagerFactory.getImageManager().addHyperStack(hook, ip, interactiveImage);
        else ImageWindowManagerFactory.getImageManager().getDisplayer().putImage(hook, ip);
        return hook;
    }
}
