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
package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.CoordCollection;
import bacmman.data_structure.input_image.InputImages;

import static bacmman.image.BoundingBox.loop;

import bacmman.image.*;

import java.io.IOException;
import java.util.*;

import bacmman.plugins.TestableOperation;
import bacmman.processing.Filters;
import bacmman.processing.Filters.Median;
import bacmman.processing.ImageOperations;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Hint;
import bacmman.utils.*;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class RemoveHotPixels implements ConfigurableTransformation, TestableOperation, Hint {
    NumberParameter threshold = new BoundedNumberParameter("Local Threshold", 5, 30, 0, null).setHint("Difference between pixels and median of the direct neighbors is computed. If difference is higher than this threshold pixel is considered as dead and will be replaced by the median value");
    NumberParameter frameRadius = new BoundedNumberParameter("Frame Window", 0, 1, 1, null).setHint("Number of frame to average. Set 1 to perform transformation Frame by Frame. A higher value will average previous frames");
    Map<Integer, CoordCollection> configMapF;
    public RemoveHotPixels(){}
    public RemoveHotPixels(double threshold, int frameRadius) {
        this.threshold.setValue(threshold);
        this.frameRadius.setValue(frameRadius);
    }
    protected Map<Integer, CoordCollection> getHotPixels() {
        return configMapF;
    }
    @Override
    public boolean highMemory() {return false;}
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) throws IOException {
        ImageProperties bds = new SimpleImageProperties(inputImages.getImage(channelIdx, 0));
        Function<Integer, CoordCollection> coordCollectionFactory = bds.sizeZ() == 1 ? time -> new CoordCollection.CoordCollection2D(bds.sizeX(), bds.sizeY()) : time -> new CoordCollection.CoordCollection3D(bds.sizeX(), bds.sizeY(), bds.sizeZ());
        configMapF = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(coordCollectionFactory);
        SynchronizedPool<Image> medianPool = new SynchronizedPool<>(() -> new ImageFloat("", bds));
        SynchronizedPool<Neighborhood> nPool = new SynchronizedPool<>(() -> new EllipsoidalNeighborhood(1.5, true)); // excludes center pixel // only on same plane
        double thld= threshold.getDoubleValue();
        int frameWindow = Math.min(this.frameRadius.getValue().intValue(), inputImages.getFrameNumber());
        double fRd= (double)frameWindow;
        final Image[][] testMeanTC= testMode.testSimple() ? new Image[inputImages.getFrameNumber()][1] : null;
        final Image[][] testMedianTC= testMode.testSimple() ? new Image[inputImages.getFrameNumber()][1] : null;
        boolean parallelPerFrame = frameWindow > 1;
        // perform sliding mean of image
        SlidingOperator<Integer, Pair<Integer, Image>, Void> operator = new SlidingOperator<Integer, Pair<Integer, Image>, Void>() {
            @Override public Pair<Integer, Image> instanciateAccumulator() {
                return new Pair<>(-1, new ImageFloat("", bds));
            }
            @Override public void slide(Integer removeElementIdx, Integer addElementIdx, Pair<Integer, Image> accumulator) {
                Image removeElement, addElement;
                try {
                    addElement = addElementIdx==null?null:inputImages.getImage(channelIdx, addElementIdx);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (frameWindow<=1) { // no averaging in time
                    accumulator.value=addElement;
                } else { // averaging in time
                    try {
                        removeElement = removeElementIdx==null?null:inputImages.getImage(channelIdx, removeElementIdx);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (removeElement!=null && addElement!=null) {
                        loop(accumulator.value.getBoundingBox().resetOffset(), (x, y, z)->{
                            accumulator.value.addPixel(x, y, z, (addElement.getPixel(x, y, z)-removeElement.getPixel(x, y, z))/fRd);
                        }, true);
                    } else if (addElement!=null) {
                        loop(accumulator.value.getBoundingBox().resetOffset(), (x, y, z)->{
                            accumulator.value.addPixel(x, y, z, addElement.getPixel(x, y, z)/fRd);
                        }, true);
                    } else if (removeElement!=null) {
                        loop(accumulator.value.getBoundingBox().resetOffset(), (x, y, z)->{
                            accumulator.value.addPixel(x, y, z, -removeElement.getPixel(x, y, z)/fRd);
                        }, true);
                    }
                }
                accumulator.key = accumulator.key+1; /// keep track of current frame
            }
            @Override public Void compute(Pair<Integer, Image> accumulator) {
                Image median = medianPool.pull();
                Neighborhood n = nPool.pull();
                Filters.median(accumulator.value, median, n, parallelPerFrame);
                if (testMode.testSimple()) {
                    testMeanTC[accumulator.key][0] = accumulator.value.duplicate();
                    testMedianTC[accumulator.key][0] = median.duplicate();
                }
                SynchronizedPool<CoordCollection> ccolPool = new SynchronizedPool<>(() -> coordCollectionFactory.apply(accumulator.key));
                Supplier<BoundingBox.LoopFunction> loopFun = () -> {
                    CoordCollection ccolLocal = parallelPerFrame ? ccolPool.pull() : configMapF.get(accumulator.key);
                    return (x, y, z) -> {
                        double med = median.getPixel(x, y, z);
                        if (accumulator.value.getPixel(x, y, z) - med >= thld) {
                            long coord = ccolLocal.toCoord(x, y, z);
                            ccolLocal.add(coord);
                        }
                    };
                };
                loop(median.getBoundingBox().resetOffset(), loopFun, parallelPerFrame);
                if (parallelPerFrame) { // collect local coordCollections
                    CoordCollection ccol = configMapF.get(accumulator.key);
                    ccolPool.streamPool().flatMapToLong(CoordCollection::stream).forEach(ccol::add);
                }
                medianPool.push(median);
                nPool.push(n);
                return null;
            }
        };
        if (frameWindow>1) {
            List<Integer> frameList = IntStream.range(0, inputImages.getFrameNumber()).boxed().collect(Collectors.toList());
            SlidingOperator.performSlideLeft(frameList, frameWindow, operator);
        } else {
            try {
                IntStream.range(0, inputImages.getFrameNumber()).parallel().forEach(f -> {
                    try {
                        operator.compute(new Pair<>(f, inputImages.getImage(channelIdx, f)));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                } else throw e;
            }
            medianPool.flush();
        }
        if (testMode.testExpert()) {
            // first frames are not computed
            for (int f = 0; f<frameWindow-1; ++f) testMeanTC[f][0] = testMeanTC[frameWindow-1][0];
            for (int f = 0; f<frameWindow-1; ++f) testMedianTC[f][0] = testMedianTC[frameWindow-1][0];
            Core.showImage5D("Sliding median", testMedianTC);
            Core.showImage5D("Sliding mean", testMeanTC);
        }
        if (testMode.testSimple()) {
            List<String> nPerFrame = configMapF.entrySet().stream().map(e -> e.getKey()+"->"+e.getValue().size()).collect(Collectors.toList());
            logger.debug("number of hot pixels detected per frame: {}", nPerFrame);
            Core.userLog("number of hot pixels detected per frame: " + nPerFrame);
            Image[] diff = InputImages.getImageForChannel(inputImages, channelIdx, false);
            for (int i = 0; i<diff.length; ++i) {
                diff[i] = diff[i].duplicate();
                Image a = applyTransformation(channelIdx, i, diff[i].duplicate());
                ImageOperations.addImage(diff[i], a, diff[i], -1);
            }
            Core.showImage5D("Modified Pixels", diff, true);
        }
    }

    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return (configMapF!=null); // when config is computed put 1 vox and frame -1 to set config.
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Map<Integer, CoordCollection> map = this.getHotPixels();
        if (map.containsKey(timePoint)) {
            CoordCollection ccol= map.get(timePoint);
            int[] coords=new int[3];
            if (!ccol.isEmpty()) {
                Median m = new Median();
                if (image.sizeZ()>1) {
                    m.setUp(image, new EllipsoidalNeighborhood(1.5, 1, true));
                    ccol.stream().forEach(c -> {
                        ccol.parse(c, coords);
                        image.setPixel(coords[0], coords[1], coords[2], m.applyFilter(coords[0], coords[1], coords[2]));
                    });
                } else {
                    m.setUp(image, new EllipsoidalNeighborhood(1.5, true)); // excludes center pixel);
                    CoordCollection.CoordCollection2D ccol2D = (CoordCollection.CoordCollection2D) ccol;
                    ccol2D.intStream().forEach(c -> {
                        ccol2D.parse(c, coords);
                        image.setPixel(coords[0], coords[1], 0, m.applyFilter(coords[0], coords[1], 0));
                    });
                }
            }
        }
        return image;
    }

    TEST_MODE testMode=TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TEST_MODE testMode) {this.testMode=testMode;}

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{threshold, frameRadius};
    }

    @Override
    public String getHintText() {
        return "Removes pixels that have much higher values than their surroundings (in space & time)";
    }

}
