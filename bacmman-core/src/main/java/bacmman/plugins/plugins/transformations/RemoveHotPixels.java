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

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.data_structure.input_image.InputImages;
import bacmman.data_structure.Voxel;
import static bacmman.image.BoundingBox.loop;
import bacmman.image.Image;
import bacmman.image.ImageFloat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import bacmman.plugins.TestableOperation;
import bacmman.processing.Filters;
import bacmman.processing.Filters.Median;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Hint;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.SlidingOperator;
import bacmman.utils.ThreadRunner;

import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class RemoveHotPixels implements ConfigurableTransformation, TestableOperation, Hint {
    NumberParameter threshold = new BoundedNumberParameter("Local Threshold", 5, 30, 0, null).setHint("Difference between pixels and median of the direct neighbors is computed. If difference is higher than this threshold pixel is considered as dead and will be replaced by the median value");
    NumberParameter frameRadius = new BoundedNumberParameter("Frame Radius", 0, 4, 1, null).setHint("Number of frame to average. Set 1 to perform transformation Frame by Frame. A higher value will average previous frames");
    HashMapGetCreate<Integer, Set<Voxel>> configMapF;
    public RemoveHotPixels(){}
    public RemoveHotPixels(double threshold, int frameRadius) {
        this.threshold.setValue(threshold);
        this.frameRadius.setValue(frameRadius);
    }
    public Map<Integer, Set<Voxel>> getDeadVoxels() {
        return configMapF;
    }
    @Override
    public boolean highMemory() {return false;}
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages)   { 
        configMapF = new HashMapGetCreate<>(new HashMapGetCreate.SetFactory<>());
        Image median = new ImageFloat("", inputImages.getImage(channelIdx, 0));
        Neighborhood n =  new EllipsoidalNeighborhood(1.5, true); // excludes center pixel // only on same plane
        double thld= threshold.getValue().doubleValue();
        int frameRadius = this.frameRadius.getValue().intValue();
        double fRd= (double)frameRadius;
        final Image[][] testMeanTC= testMode.testSimple() ? new Image[inputImages.getFrameNumber()][1] : null;
        final Image[][] testMedianTC= testMode.testSimple() ? new Image[inputImages.getFrameNumber()][1] : null;
        // perform sliding mean of image
        SlidingOperator<Image, Pair<Integer, Image>, Void> operator = new SlidingOperator<Image, Pair<Integer, Image>, Void>() {
            @Override public Pair<Integer, Image> instanciateAccumulator() {
                return new Pair(-1, new ImageFloat("", median));
            }
            @Override public void slide(Image removeElement, Image addElement, Pair<Integer, Image> accumulator) {
                if (frameRadius<=1) { // no averaging in time
                    accumulator.value=addElement;
                } else {
                    if (removeElement!=null && addElement!=null) {
                        loop(accumulator.value.getBoundingBox().resetOffset(), (x, y, z)->{
                            accumulator.value.setPixel(x, y, z, accumulator.value.getPixel(x, y, z)+(addElement.getPixel(x, y, z)-removeElement.getPixel(x, y, z))/fRd);
                        }, true);
                    }else if (addElement!=null) {
                        loop(accumulator.value.getBoundingBox().resetOffset(), (x, y, z)->{
                            accumulator.value.setPixel(x, y, z, accumulator.value.getPixel(x, y, z)+addElement.getPixel(x, y, z)/fRd);
                        }, true);
                    } else if (removeElement!=null) {
                        loop(accumulator.value.getBoundingBox().resetOffset(), (x, y, z)->{
                            accumulator.value.setPixel(x, y, z, accumulator.value.getPixel(x, y, z)-removeElement.getPixel(x, y, z)/fRd);
                        }, true);
                    }
                }
                accumulator.key = accumulator.key+1; /// keep track of current frame
            }
            @Override public Void compute(Pair<Integer, Image> accumulator) {   
                Filters.median(accumulator.value, median, n, true);
                //Filters.median(inputImages.getImage(channelIdx, accumulator.key), median, n);
                if (testMode.testSimple()) {
                    testMeanTC[accumulator.key][0] = accumulator.value.duplicate();
                    testMedianTC[accumulator.key][0] = median.duplicate();
                }
                loop(median.getBoundingBox().resetOffset(), (x, y, z)->{
                    float med = median.getPixel(x, y, z);
                    if (accumulator.value.getPixel(x, y, z)-med>= thld) {
                        Voxel v =new Voxel(x, y, z, med);
                        for (int f = Math.max(0, accumulator.key-frameRadius); f<=accumulator.key; ++f) {
                            configMapF.getAndCreateIfNecessary(f).add(v);
                            //Set<Voxel> set = configMapF.getAndCreateIfNecessarySync(f);
                            //synchronized (set) {set.add(v);}
                        }
                    }
                });  // not parallele
                return null;
            }
        };
        List<Image> imList = Arrays.asList(InputImages.getImageForChannel(inputImages, channelIdx, false));
        if (frameRadius>=1) SlidingOperator.performSlideLeft(imList, frameRadius, operator);
        else ThreadRunner.parallelExecutionBySegments(i-> operator.compute(new Pair<>(i, imList.get(i))), 0, imList.size(), 100);
        if (testMode.testSimple()) {
            // first frames are not computed
            for (int f = 0; f<frameRadius-1; ++f) testMeanTC[f][0] = testMeanTC[frameRadius-1][0];
            for (int f = 0; f<frameRadius-1; ++f) testMedianTC[f][0] = testMedianTC[frameRadius-1][0];
            Core.showImage5D("Sliding median", testMedianTC);
            Core.showImage5D("Sliding mean", testMeanTC);
            logger.debug("number of dead voxels detected: {}", configMapF.size());
        }
    }

    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return (configMapF!=null); // when config is computed put 1 vox and frame -1 to set config. 
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        /*double thld= threshold.getValue().doubleValue();
        Image median = Filters.median(image, null, Filters.getNeighborhood(1.5, 1, image));
        //Image blurred = ImageFeatures.gaussianSmooth(image, 5, 5, false);
        median.getBoundingBox().translateToOrigin().loop((x, y, z)->{
            float med = median.getPixel(x, y, z);
            if (image.getPixel(x, y, z)-med>= thld) {
                image.setPixel(x, y, z, med);
                //if (testMode) logger.debug("pixel @ [{};{};{}] f={}, value: {}, median: {}, diff: {}", timePoint, x, y, z, image.getPixel(x, y, z), med, image.getPixel(x, y, z)-med );
            }
        });
        return image;
        */
        Map<Integer, Set<Voxel>> map = this.getDeadVoxels();
        if (map.containsKey(timePoint)) {
            Set<Voxel> dv= map.get(timePoint);
            if (!dv.isEmpty()) {
                Median m = new Median();
                m.setUp(image, image.sizeZ()>1 ? new EllipsoidalNeighborhood(1.5, 1, true) : new EllipsoidalNeighborhood(1.5, true)); // excludes center pixel);
                for (Voxel v : dv) image.setPixel(v.x, v.y, v.z, m.applyFilter(v.x, v.y, v.z)); //
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
