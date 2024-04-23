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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public interface InputImages {
    boolean sourceImagesLinked();
    public static Image getImage(InputImages images , int channelIdx, int timePoint, IOException[] exceptionContainer) {
        try {
            return images.getImage(channelIdx, timePoint);
        } catch (IOException e) {
            if (exceptionContainer!=null) {
                synchronized (exceptionContainer) {
                    exceptionContainer[0] = e;
                }
            }
            return null;
        }
    }
    public Image getImage(int channelIdx, int timePoint) throws IOException;
    public Image getRawPlane(int z, int channelIdx, int timePoint) throws IOException ;
    public int getFrameNumber();
    int getMinFrame();
    public int getChannelNumber();
    public int getDefaultTimePoint();
    public int getSourceSizeZ(int channelIdx);
    public int getBestFocusPlane(int timePoint);
    public void flush();
    public double getCalibratedTimePoint(int c, int t, int z);
    public boolean singleFrameChannel(int channelIdx);
    void setMemoryProportionLimit(double memoryProportionLimit);
    public static Image[] getImageForChannel(InputImages images, int channelIdx, boolean ensure2D) {
        if(channelIdx<0 || channelIdx>=images.getChannelNumber()) throw new IllegalArgumentException("invalid channel idx: "+channelIdx+" max idx: "+(images.getChannelNumber()-1));
        Function<Integer, Image> fun;
        if (!ensure2D) {
            fun  = f -> {
                try {
                    return images.getImage(channelIdx, f);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        } else {
            fun = f -> {
                Image<? extends Image> image = null;
                try {
                    image = images.getImage(channelIdx, f);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (image.sizeZ()>1) {
                    int plane = images.getBestFocusPlane(f);
                    if (plane<0) throw new RuntimeException("SaturateHistogramHyperFluoBacteria can only be run on 2D images AND no autofocus algorithm was set");
                    image = image.splitZPlanes().get(plane);
                }
                return image;
            };
        }
        List<Image> res = ThreadRunner.parallelExecutionBySegmentsFunction(fun, 0,  images.getFrameNumber(), 100, true);
        return res.toArray(new Image[0]);
    }
    
    public static Image getAverageFrame(InputImages images, int channelIdx, int frame,  int numberOfFramesToAverage) throws IOException{
        if (numberOfFramesToAverage<=1) return images.getImage(channelIdx, frame);
        List<Image> imagesToAv = new ArrayList<>(numberOfFramesToAverage);
        int fMin = Math.max(0, frame-numberOfFramesToAverage/2);
        int fMax = Math.min(images.getFrameNumber(), fMin+numberOfFramesToAverage);
        if (fMax-fMin<numberOfFramesToAverage) fMin = Math.max(0, fMax-numberOfFramesToAverage);
        for (int f = fMin; f<fMax; ++f) imagesToAv.add(images.getImage(channelIdx, f));
        return ImageOperations.meanZProjection(Image.mergeZPlanes(imagesToAv));
    }
    /**
     * See {@link #chooseNImagesWithSignal(java.util.function.Supplier, int, int) }
     * @param inputImages
     * @param channelIdx
     * @param n
     * @return 
     */
    public static List<Integer> chooseNImagesWithSignal(InputImages inputImages, int channelIdx, int n) {
        int modulo = Math.max(1, inputImages.getFrameNumber()/(12*n));
        if (modulo>1) logger.debug("choose {} images among {} -> modulo: {}", n, inputImages.getFrameNumber(), modulo);
        Supplier<Stream<Pair<Integer, Image>>> images = () -> IntStream.range(0, inputImages.getFrameNumber()).filter(f -> f%modulo==0).mapToObj(f -> {
            try {
                return new Pair<>(f, inputImages.getImage(channelIdx, f));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return chooseNImagesWithSignal(images, n, inputImages.getFrameNumber()/modulo);
    }
    /**
     * Measure amount of signal in each image by counting number of pixel above  a threshold computed by {@link BackgroundFit} method with parameter = 3.
     * @param images
     * @param n number of indices to return
     * @return list of image indexed from {@param images} list paired with signal measurement
     */
    public static List<Integer> chooseNImagesWithSignal(Supplier<Stream<Pair<Integer, Image>>> images, int n, int totalImagesNumber) {
        if (n>=totalImagesNumber) return Utils.toList(ArrayUtil.generateIntegerArray(totalImagesNumber));
        // signal is measured as number of
        long t0 = System.currentTimeMillis();
        double sTot = images.get().findAny().get().value.sizeXYZ();
        Histogram histo = HistogramFactory.getHistogram(()->images.get().parallel().flatMapToDouble(im -> im.value.stream()), HistogramFactory.BIN_SIZE_METHOD.BACKGROUND);
        //histo.plotIJ1("choose "+n+" images: histogram.", true);
        double thld = BackgroundFit.backgroundFit(histo, 3);

        List<Pair<Integer, Double>> signal = images.get().parallel()
                .map(im -> new Pair<>(im.key, im.value.stream().filter(v->v>thld).count() /  sTot))
                .sorted((p1, p2)->-Double.compare(p1.value, p2.value))
                .collect(Collectors.toList());
        if (n==1) return Collections.singletonList(signal.get(0).key);
        // choose n frames among the X frames with most signal
        int candidateNumber = Math.max(totalImagesNumber/4, n);
        double delta = Math.max((double)candidateNumber / (double)(n+1), 1);
        signal = signal.subList(0, candidateNumber);
        List<Pair<Integer, Double>> res = new ArrayList<>(n);
        for (int i =0; i<n; ++i) {
            int idx = (int)(delta*i);
            res.add(signal.get(idx));
        }
        long t1 = System.currentTimeMillis();
        logger.debug("choose {} images: {} t={} (among: {})", n, res, t1-t0, signal);
        return Pair.unpairKeys(res);
    }
    static Stream<Image> streamChannel(InputImages images, int channelIdx) {
        return IntStream.range(0, images.getFrameNumber()).mapToObj(f -> {
            try {
                return images.getImage(channelIdx, f);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
    static DoubleStream streamChannelValues(InputImages images, int channelIdx) {
        return streamChannel(images, channelIdx).flatMapToDouble(Image::stream);
    }
}
