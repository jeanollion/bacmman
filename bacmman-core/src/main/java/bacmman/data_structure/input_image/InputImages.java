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

import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import static bacmman.image.Image.logger;

import bacmman.processing.ImageOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public interface InputImages {
    public Image getImage(int channelIdx, int timePoint);
    public int getFrameNumber();
    public int getChannelNumber();
    public int getDefaultTimePoint();
    public int getSourceSizeZ(int channelIdx);
    public int getBestFocusPlane(int timePoint);
    public void flush();
    public double getCalibratedTimePoint(int c, int t, int z);
    public boolean singleFrameChannel(int channelIdx);
    public static Image[] getImageForChannel(InputImages images, int channelIdx, boolean ensure2D) {
        if(channelIdx<0 || channelIdx>=images.getChannelNumber()) throw new IllegalArgumentException("invalid channel idx: "+channelIdx+" max idx: "+(images.getChannelNumber()-1));
        if (!ensure2D) return IntStream.range(0, images.getFrameNumber()).parallel().mapToObj(f -> images.getImage(channelIdx, f)).toArray(l -> new Image[l]);
        else {
            return IntStream.range(0, images.getFrameNumber()).parallel().mapToObj(f -> {
                Image<? extends Image> image = images.getImage(channelIdx, f);
                if (image.sizeZ()>1) {
                    int plane = images.getBestFocusPlane(f);
                    if (plane<0) throw new RuntimeException("SaturateHistogramHyperFluoBacteria can only be run on 2D images AND no autofocus algorithm was set");
                    image = image.splitZPlanes().get(plane);
                }
                return image;
            }).toArray(l -> new Image[l]);
        }
    }
    
    public static Image getAverageFrame(InputImages images, int channelIdx, int frame,  int numberOfFramesToAverage) {
        if (numberOfFramesToAverage<=1) return images.getImage(channelIdx, frame);
        List<Image> imagesToAv = new ArrayList<>(numberOfFramesToAverage);
        int fMin = Math.max(0, frame-numberOfFramesToAverage/2);
        int fMax = Math.min(images.getFrameNumber(), fMin+numberOfFramesToAverage);
        if (fMax-fMin<numberOfFramesToAverage) fMin = Math.max(0, fMax-numberOfFramesToAverage);
        for (int f = fMin; f<fMax; ++f) imagesToAv.add(images.getImage(channelIdx, f));
        return ImageOperations.meanZProjection(Image.mergeZPlanes(imagesToAv));
    }
    /**
     * See {@link #chooseNImagesWithSignal(java.util.List, int) }
     * @param inputImages
     * @param channelIdx
     * @param n
     * @return 
     */
    public static List<Integer> chooseNImagesWithSignal(InputImages inputImages, int channelIdx, int n) {
        List<Image> imagesByFrame = Arrays.asList(InputImages.getImageForChannel(inputImages, channelIdx, false));
        return chooseNImagesWithSignal(imagesByFrame, n);

    }
    /**
     * Measure amount of signal in each image by counting number of pixel above  a threshold computed by {@link BackgroundFit} method with parameter = 3.
     * @param images
     * @param n number of indices to return
     * @return list of image indexed from {@param images} list paired with signal measurement
     */
    public static List<Integer> chooseNImagesWithSignal(List<Image> images, int n) {
        if (n>=images.size()) return Utils.toList(ArrayUtil.generateIntegerArray(images.size()));
        // signal is measured as number of 
        long t0 = System.currentTimeMillis();
        double sTot = images.get(0).sizeXYZ();
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(images).parallel(), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
        //histo.plotIJ1("choose "+n+" images: histogram.", true);
        double thld = BackgroundFit.backgroundFit(histo, 3);
        
        List<Pair<Integer, Double>> signal = IntStream.range(0, images.size()).parallel()
                .mapToObj((int i) ->  new Pair<>(i, images.get(i).stream().filter(v->v>thld).count() /  sTot))
                .sorted((p1, p2)->-Double.compare(p1.value, p2.value)).collect(Collectors.toList());
        if (n==1) return Arrays.asList(new Integer[]{signal.get(0).key});
        // choose n frames among the X frames with most signal
        int candidateNumber = Math.max(images.size()/4, n);
        double delta = Math.max((double)candidateNumber / (double)(n+1), 1);
        signal = signal.subList(0, candidateNumber);
        List<Pair<Integer, Double>> res = new ArrayList<>(n);
        for (int i =0; i<n; ++i) {
            int idx = (int)(delta*i);
            res.add(signal.get(idx));
        }
        long t1 = System.currentTimeMillis();
        logger.debug("choose {} images: {} t={} (among: {})", n, res, t1-t0, signal);
        return Pair.unpairKeys(res);
    }
}
