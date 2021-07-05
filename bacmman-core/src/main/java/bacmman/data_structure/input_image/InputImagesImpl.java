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
package bacmman.data_structure.input_image;

import bacmman.image.Image;
import static bacmman.image.Image.logger;
import bacmman.plugins.Autofocus;
import bacmman.plugins.MultichannelTransformation;
import bacmman.plugins.Transformation;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
/**
 *
 * @author Jean Ollion
 */
public class InputImagesImpl implements InputImages {
    InputImage[][] imageCT;
    int defaultTimePoint;
    int frameNumber;
    int autofocusChannel=-1;
    Autofocus autofocusAlgo = null;
    Integer[] autofocusPlanes;
    int freeMemoryFrameWindow = 0; // 200;

    public InputImagesImpl(InputImage[][] imageCT, int defaultTimePoint, Pair<Integer, Autofocus> autofocusConfig) {
        this.imageCT = imageCT;
        this.defaultTimePoint= defaultTimePoint;
        for (int c = 0; c<imageCT.length; ++c) if (imageCT[c].length>frameNumber) frameNumber = imageCT[c].length;
        if (autofocusConfig!=null) {
            this.autofocusChannel=autofocusConfig.key;
            this.autofocusAlgo=autofocusConfig.value;
        }
        autofocusPlanes = new Integer[frameNumber];
    }
    public void setDefaultTimePoint(int defaultTimePoint) {
        this.defaultTimePoint=defaultTimePoint;
    }
    
    public InputImagesImpl duplicate() {
        InputImage[][] imageCTDup = new InputImage[imageCT.length][];
        for (int i = 0; i<imageCT.length; ++i) {
            imageCTDup[i] = new InputImage[imageCT[i].length];
            for (int j = 0; j<imageCT[i].length; ++j) imageCTDup[i][j] = imageCT[i][j].duplicate();
        }
        return new InputImagesImpl(imageCTDup, defaultTimePoint, new Pair<>(autofocusChannel, autofocusAlgo));
    }
    public InputImagesImpl duplicate(int frameMin, int frameMaxExcluded) {
        if (frameMin<0) throw new IllegalArgumentException("Frame min must be >=0");
        if (frameMin>=frameNumber) throw new IllegalArgumentException("Frame min must be < to frame number");
        if (frameMaxExcluded>frameNumber) throw new IllegalArgumentException("Frame max must be <= to frame number ("+frameMaxExcluded+">"+frameNumber+")");
        InputImage[][] imageCTDup = new InputImage[imageCT.length][];
        for (int c = 0; c<imageCT.length; ++c) { //channel
            if (singleFrameChannel(c)) {
                imageCTDup[c] = new InputImage[]{imageCT[c][0].duplicate()};
            } else {
                imageCTDup[c] = new InputImage[frameMaxExcluded-frameMin];
                for (int t = frameMin; t<frameMaxExcluded; ++t) {
                    imageCTDup[c][t-frameMin] = imageCT[c][t].duplicate();
                    imageCTDup[c][t-frameMin].setTimePoint(t-frameMin);
                }
            }
        }
        return new InputImagesImpl(imageCTDup, Math.min(frameMaxExcluded-1-frameMin, Math.max(defaultTimePoint-frameMin, 0)), new Pair<>(autofocusChannel, autofocusAlgo));
    }

    @Override public int getBestFocusPlane(int timePoint) {
        if (autofocusChannel>=0 && autofocusAlgo!=null) {
            if (autofocusPlanes[timePoint]==null) {
                Image im = this.getImage(autofocusChannel, timePoint);
                autofocusPlanes[timePoint] = autofocusAlgo.getBestFocusPlane(im, null);
            } 
            return autofocusPlanes[timePoint];
        } else return -1;
    }
    @Override public int getFrameNumber() {return frameNumber;}
    @Override public int getChannelNumber() {return imageCT.length;}
    @Override public int getDefaultTimePoint() {return defaultTimePoint;}
    @Override public int getSourceSizeZ(int channelIdx) {return imageCT[channelIdx][0].imageSources.getSizeZ(channelIdx);}
    @Override public double getCalibratedTimePoint(int c, int t, int z) {
        if (imageCT==null) return Double.NaN;
        if (singleFrameChannel(c)) { // adjacent channel
            int c2=c;
            if (c>0) c2--;
            else {
                c2++;
                if (imageCT.length<=c2) return Double.NaN;
            }
            if (singleFrameChannel(c2)) {
                if (c>1) c2=c-2;
                else {
                    c2=c+2;
                    if (imageCT.length<=c2) return Double.NaN;
                }
                if (singleFrameChannel(c2)) return Double.NaN;
            }
            c=c2;
        }
        if (imageCT[c][t]==null || imageCT[c][t].imageSources==null || imageCT.length<=c || imageCT[c].length<=t) return Double.NaN;
        return imageCT[c][t].imageSources.getCalibratedTimePoint(t, c, z);
    }
    @Override public boolean singleFrameChannel(int channelIdx) {
        return imageCT[channelIdx].length==1;
    }
    public void addTransformation(int inputChannel, int[] channelIndices, Transformation transfo) {
        if (channelIndices!=null) for (int c : channelIndices) addTransformation(c, transfo);
        else { // null channel indices either same or all
            if (transfo instanceof MultichannelTransformation) {
                if (((MultichannelTransformation)transfo).getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.SAME) addTransformation(inputChannel, transfo);
                else for (int c = 0; c<getChannelNumber(); ++c) addTransformation(c, transfo); 
            } else if (inputChannel>=0) addTransformation(inputChannel, transfo);
            else for (int c = 0; c<getChannelNumber(); ++c) addTransformation(c, transfo); 
        }
    }
    
    public void addTransformation(int channelIdx, Transformation transfo) {
        for (int t = 0; t<this.imageCT[channelIdx].length; ++t) {
            imageCT[channelIdx][t].addTransformation(transfo);
        }
    }

    @Override public Image getImage(int channelIdx, int timePoint) { 
        if (imageCT[channelIdx].length==1) timePoint = 0;
        if (freeMemoryFrameWindow>0 && timePoint > freeMemoryFrameWindow) freeMemory(timePoint - freeMemoryFrameWindow);
        return imageCT[channelIdx][timePoint].getImage();
    }
    public boolean imageOpened(int channelIdx, int timePoint) {
        if (imageCT[channelIdx].length==1) timePoint = 0;
        return imageCT[channelIdx][timePoint].imageOpened();
    }
    public void flush(int channelIdx, int timePoint) {
        imageCT[channelIdx][timePoint].flush();
    }
    public Image[][] getImagesTC() {
        return getImagesTC(0, this.getFrameNumber());
    }
    public Image[][] getImagesTC(int frameMin, int frameMaxExcluded, int... channels) {
        if (channels==null || channels.length==0) channels = ArrayUtil.generateIntegerArray(this.getChannelNumber());
        if (frameMin>=this.getFrameNumber()) {
            frameMin=this.getFrameNumber()-1;
            frameMaxExcluded = this.getFrameNumber();
        }
        if (frameMaxExcluded<frameMin) {
            frameMin = 0;
            frameMaxExcluded = getFrameNumber();
        } else if (frameMaxExcluded==frameMin) frameMaxExcluded+=1;
        if (frameMaxExcluded>=this.getFrameNumber()) frameMaxExcluded = this.getFrameNumber();
        final Image[][] imagesTC = new Image[frameMaxExcluded-frameMin][channels.length];
        int cIdx = 0;
        for (int channelIdx : channels) {
            final int c = cIdx;
            final int fMin = frameMin;
            IntStream.range(fMin, frameMaxExcluded).parallel().forEach( f-> {
                imagesTC[f-fMin][c] = getImage(channelIdx, f).setName("Channel: "+channelIdx+" Frame: "+f);
                if (imagesTC[f-fMin][c]==null) throw new RuntimeException("could not open image: channel:" +channelIdx + " frame: "+f);
            });
            ++cIdx;
        }
        return imagesTC;
    }

    public void applyTranformationsAndSave(boolean close, boolean tempCheckPoint) {
        long tStart = System.currentTimeMillis();
        // start with modified channels
        List<Integer> modifiedChannels = IntStream.range(0, getChannelNumber()).filter(c -> IntStream.range(0, imageCT[c].length).anyMatch(f -> imageCT[c][f].modified())).mapToObj(c->c).sorted().collect(Collectors.toList());
        List<Integer> unmodifiedChannels = IntStream.range(0, getChannelNumber()).filter(c -> !modifiedChannels.contains(c)).mapToObj(c->c).sorted().collect(Collectors.toList());
        List<Integer> allChannels = new ArrayList<>(getChannelNumber());
        allChannels.addAll(modifiedChannels);
        allChannels.addAll(unmodifiedChannels);
        logger.debug("modified channels: {} unmodified: {}", modifiedChannels, unmodifiedChannels);
        allChannels.stream().forEachOrdered(c -> {
            InputImage[] imageF = imageCT[c];
            Consumer<Integer> ex = f-> {
                if (!tempCheckPoint || ((imageF[f].imageOpened() || imageF[f].hasHighMemoryTransformations()) && (imageF[f].modified() || imageF[f].hasTransformations()) )) {
                    imageF[f].getImage();
                    imageF[f].saveImage(tempCheckPoint);
                }
                if (close) imageF[f].flush();
            };
            ThreadRunner.parallelExecutionBySegments(ex, 0, imageF.length, 100);
            logger.debug("after applying transformation for channel: {} ", c, Utils.getMemoryUsage());
        });
        
        long tEnd = System.currentTimeMillis();
        logger.debug("apply transformation & save: total time: {}, for {} time points and {} channels", tEnd-tStart, getFrameNumber(), getChannelNumber() );
    }

    private void freeMemory(int maxFrame) {
        for (int c = 0; c<getChannelNumber(); ++c) {
            InputImage[] imageF = imageCT[c];
            for (int f = maxFrame-1; f>=0; --f) {
                InputImage im = imageF[f];
                if (im.imageOpened()) {
                    synchronized (im) {
                        if (im.imageOpened()) {
                            if (im.modified()) im.saveImage(true);
                            im.flush();
                        }
                    }
                }
            }
        }
    }

    public void deleteFromDAO() {
        for (int c = 0; c<getChannelNumber(); ++c) {
            for (int t = 0; t<imageCT[c].length; ++t) {
                imageCT[c][t].deleteFromDAO();
            }
        }
    }
    
    @Override 
    public void flush() {
        imageCT[0][0].imageSources.flush();
        for (int c = 0; c<getChannelNumber(); ++c) {
            for (int t = 0; t<imageCT[c].length; ++t) {
                imageCT[c][t].flush();
            }
        }
    }
    
    /**
     * Remove all time points excluding time points between {@param tStart} and {@param tEnd}, for testing purposes only
     * @param tStart
     * @param tEnd 
     */
    public void subSetTimePoints(int tStart, int tEnd) {
        if (tStart<0) tStart=0; 
        if (tEnd>=getFrameNumber()) tEnd = getFrameNumber()-1;
        InputImage[][] newImageCT = new InputImage[this.getChannelNumber()][];
        
        for (int c=0; c<getChannelNumber(); ++c) {
            if (imageCT[c].length==1) {
                newImageCT[c] = new InputImage[1];
                newImageCT[c][0] = imageCT[c][0];
            } else {
                newImageCT[c] = new InputImage[tEnd-tStart+1];
                for (int t=tStart; t<=tEnd; ++t) {
                    newImageCT[c][t-tStart] = imageCT[c][t];
                    imageCT[c][t].setTimePoint(t-tStart);
                }
            }
        }
        if (this.defaultTimePoint<tStart) defaultTimePoint = 0;
        if (this.defaultTimePoint>tEnd-tStart) defaultTimePoint = tEnd-tStart;
        logger.debug("default time Point: {}", defaultTimePoint);
        this.imageCT=newImageCT;
        this.frameNumber = tEnd-tStart+1;
    }

}
