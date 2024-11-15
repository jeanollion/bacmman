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
import bacmman.plugins.TestableOperation;
import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.image.PredicateMask;
import bacmman.image.TypeConverter;
import bacmman.plugins.ConfigurableTransformation;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.Hint;
import bacmman.utils.SlidingOperatorDouble;
import bacmman.utils.ThreadRunner;
import ij.process.AutoThresholder;

import java.util.function.IntConsumer;

/**
 *
 * @author Jean Ollion
 */
public class RemoveStripesSignalExclusion implements ConfigurableTransformation, TestableOperation, Hint {
    ChannelImageParameter signalExclusion = new ChannelImageParameter("Channel for Signal Exclusion", -1, true).setEmphasized(true);
    PluginParameter<SimpleThresholder> signalExclusionThreshold = new PluginParameter<>("Signal Exclusion Threshold", SimpleThresholder.class, new BackgroundFit(5), false).setEmphasized(true); //new ConstantValue(150)
    BooleanParameter signalExclusionBool2 = new BooleanParameter("Second Signal Exclusion", false);
    ChannelImageParameter signalExclusion2 = new ChannelImageParameter("Channel for Signal Exclusion 2", -1, false);
    PluginParameter<SimpleThresholder> signalExclusionThreshold2 = new PluginParameter<>("Signal Exclusion Threshold 2", SimpleThresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu), false);
    ConditionalParameter<Boolean> signalExclusionCond = new ConditionalParameter<>(signalExclusionBool2).setActionParameters(true, signalExclusion2, signalExclusionThreshold2);
    BooleanParameter addGlobalMean = new BooleanParameter("Add global mean", false).setEmphasized(true).setHint("If this option is set to <em>true</em>, the global mean value of pixel intensities is added to all pixels. This option ensures that this operation will not set negative values to pixels. There is also the option to add local mean instead of global mean, for this set this parameter to false and set the sub-parameter <em>Sliding half-window</em>. Use this option in case of uneven background along Y-axis");
    BoundedNumberParameter slidingHalfWindow = new BoundedNumberParameter("Sliding mean half-window", 0, 40, 0, null).setEmphasized(true).setHint("If a positive value is set, the sliding mean of background values along Y axis will be added to each pixel");
    ConditionalParameter<Boolean> addGMCond = new ConditionalParameter<>(addGlobalMean).setActionParameters(false, slidingHalfWindow);
    ScaleXYZParameter maskSmoothScale = new ScaleXYZParameter("Smooth Scale for mask", 0, 1, true).setHint("if zero -> mask channel is not smoothed");
    ScaleXYZParameter smoothScale = new ScaleXYZParameter("RemoveBackground: Smooth Scale", 0, 1, true).setHint("Background removal. If a value greater than zero is set, the input image will be blurred (excluding values within mask) and subtracted to the image").setEmphasized(true);

    Parameter[] parameters = new Parameter[]{signalExclusion, signalExclusionThreshold, maskSmoothScale, signalExclusionCond, addGMCond, smoothScale};
    float[][][] meanFZY;
    Image[] backgroundMask;
    @Override
    public boolean highMemory() {return smoothScale.getScaleXY()>0;}
    public RemoveStripesSignalExclusion() {}
    
    public RemoveStripesSignalExclusion(int signalExclusion) {
        if (signalExclusion>=0) this.signalExclusion.setSelectedIndex(signalExclusion);
    }
    @Override
    public String getHintText() {
        return "Removes banding noise. Noise is computed line-wise (so this transformation should be set before any rotation) and outside foreground, defined by the <em>Signal Exclusion</em> parameters.";
    }
    public RemoveStripesSignalExclusion setAddGlobalMean(boolean addGlobalMean) {
        this.addGlobalMean.setSelected(addGlobalMean);
        return this;
    }

    public RemoveStripesSignalExclusion setMethod(SimpleThresholder thlder) {
        this.signalExclusionThreshold.setPlugin(thlder);
        return this;
    }
    public RemoveStripesSignalExclusion setSecondSignalExclusion(int channel2, SimpleThresholder thlder) {
        if (channel2>=0 && thlder!=null) {
            this.signalExclusionThreshold2.setPlugin(thlder);
            this.signalExclusion2.setSelectedIndex(channel2);
            this.signalExclusionBool2.setSelected(true);
        } else this.signalExclusionBool2.setSelected(false);
        return this;
    }
    Map<Integer, Image> testMasks, testMasks2;
    @Override
    public void computeConfigurationData(final int channelIdx, final InputImages inputImages) throws IOException {
        final int chExcl = signalExclusion.getSelectedIndex();
        final int chExcl2 = this.signalExclusionBool2.getSelected() ? signalExclusion2.getSelectedIndex() : -1;
        if (testMode.testExpert() && chExcl>=0) {
            testMasks = new ConcurrentHashMap<>();
            if (chExcl2>=0) testMasks2 = new ConcurrentHashMap<>();
        }
        final boolean addGlobalMean = this.addGlobalMean.getSelected();
        final int slidingHalfWindow = this.slidingHalfWindow.getValue().intValue();
        //logger.debug("remove stripes thld: {}", exclThld);
        meanFZY = new float[inputImages.getFrameNumber()][][];
        Image ref = inputImages.getImage(channelIdx, inputImages.getDefaultTimePoint());
        double scale = smoothScale.getScaleXY();
        double scaleZ = smoothScale.getScaleZ(ref.getScaleXY(), ref.getScaleZ());
        if (scale>0) backgroundMask = new Image[inputImages.getFrameNumber()];
        double mScale = maskSmoothScale.getScaleXY();
        double mScaleZ = maskSmoothScale.getScaleZ(ref.getScaleXY(), ref.getScaleZ());
        IntConsumer ex = frame -> {
            Image currentImage;
            try {
                currentImage = inputImages.getImage(channelIdx, frame);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            ImageMask m;
            if (chExcl>=0) {
                Image se1;
                try {
                    se1 = inputImages.getImage(chExcl, frame);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (mScale>0) se1 = ImageFeatures.gaussianSmooth(se1, mScale, mScaleZ, false);
                double thld1 = signalExclusionThreshold.instantiatePlugin().runSimpleThresholder(se1, null);
                PredicateMask mask = currentImage.sizeZ()>1 && se1.sizeZ()==1 ? new PredicateMask(se1, thld1, true, true, 0):new PredicateMask(se1, thld1, true, true);
                if (testMode.testExpert()) synchronized(testMasks) {testMasks.put(frame, TypeConverter.toByteMask(mask, null, 1));}
                if (chExcl2>=0) {
                    Image se2;
                    try {
                        se2 = inputImages.getImage(chExcl2, frame);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (mScale>0) se2 = ImageFeatures.gaussianSmooth(se2, mScale, mScaleZ, false);
                    double thld2 = signalExclusionThreshold2.instantiatePlugin().runSimpleThresholder(se2, null);
                    PredicateMask mask2 = currentImage.sizeZ()>1 && se2.sizeZ()==1 ? new PredicateMask(se2, thld2, true, true, 0):new PredicateMask(se2, thld2, true, true);
                    if (testMode.testExpert()) synchronized(testMasks2) {testMasks2.put(frame, TypeConverter.toByteMask(mask2, null, 1));}
                    mask = PredicateMask.or(mask, mask2);
                }
                m = mask;
            } else m = new BlankMask(currentImage);
            meanFZY[frame] = computeMeanX(currentImage, m, addGlobalMean, slidingHalfWindow);
            if (backgroundMask!=null) backgroundMask[frame] = SubtractGaussSignalExclusion.getBackgroundImage(currentImage, m, scale, scaleZ);
        };
        try {
            ThreadRunner.parallelExecutionBySegments(ex, 0, inputImages.getFrameNumber(), Core.PRE_PROCESSING_WINDOW, s -> Core.freeMemory());
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            else throw e;
        }
        if (testMode.testExpert()) { // make stripes images
            Image[][] stripesTC = new Image[meanFZY.length][1];
            for (int f = 0; f<meanFZY.length; ++f) {
                stripesTC[f][0] = new ImageFloat("removeStripes", inputImages.getImage(channelIdx, f));
                for (int z = 0; z<stripesTC[f][0].sizeZ(); ++z) {
                    for (int y = 0; y<stripesTC[f][0].sizeY(); ++y) {
                        for (int x = 0; x<stripesTC[f][0].sizeX(); ++x) {
                            stripesTC[f][0].setPixel(x, y, z, meanFZY[f][z][y]);
                        }
                    }
                }
            }
            Core.showImage5D("Stripes", stripesTC);
        }
        if (testMode.testSimple()) {
            if (testMasks!=null && !testMasks.isEmpty()) {
                Image[][] maskTC = new Image[testMasks.size()][1];
                for (Map.Entry<Integer, Image> e : testMasks.entrySet()) maskTC[e.getKey()][0] = e.getValue();
                Core.showImage5D("Exclusion signal mask", maskTC);
                testMasks.clear();
            }
            if (testMasks2!=null && !testMasks2.isEmpty()) {
                Image[][] maskTC = new Image[testMasks2.size()][1];
                for (Map.Entry<Integer, Image> e : testMasks2.entrySet()) maskTC[e.getKey()][0] = e.getValue();
                Core.showImage5D("Exclusion signal mask2", maskTC);
                testMasks2.clear();
            }
            if (backgroundMask!=null) {
                Image[][] maskTC = Arrays.stream(backgroundMask).map(a->new Image[]{a}).toArray(Image[][]::new);
                Core.showImage5D("Background subtraction", maskTC);
            }
        }
    }
    
    public static float[][] computeMeanX(Image image, ImageMask mask, boolean addGlobalMean, int slidingHalfWindow) {
        float[][] res = new float[image.sizeZ()][image.sizeY()];
        double globalSum=0;
        double globalCount=0;
        for (int z=0; z<image.sizeZ(); ++z) {
            for (int y = 0; y<image.sizeY(); ++y) {
                double sum = 0;
                double count = 0;
                for (int x = 0; x<image.sizeX(); ++x) {
                    if (!mask.insideMask(x, y, z)) {
                        ++count;
                        sum+=image.getPixel(x, y, z);
                    }
                }
                res[z][y] = count>0 ? (float)(sum/count) : 0f;
                globalSum+=sum;
                globalCount+=count;
            }
        }
        if (addGlobalMean) {
            double globalMean = globalCount>0?globalSum/globalCount : 0;
            for (int z=0; z<image.sizeZ(); ++z) {
                for (int y = 0; y<image.sizeY(); ++y) res[z][y]-=globalMean;
            }
        } else if (slidingHalfWindow>0) {
            SlidingOperatorDouble<double[]> meanSlider = SlidingOperatorDouble.slidingMean();
            for (int z=0; z<image.sizeZ(); ++z) {
                float[] smoothed = SlidingOperatorDouble.performSlideFloatArray(res[z], slidingHalfWindow, meanSlider);
                for (int y = 0; y<image.sizeY(); ++y) res[z][y]-= smoothed[y];
            }

        }
        return res;
    }
    public static Image removeMeanX(Image source, Image output, float[][] muZY) {
        if (output==null || !output.sameDimensions(source)) {
            output = new ImageFloat("", source);
        }
        for (int z = 0; z<output.sizeZ(); ++z) {
            for (int y = 0; y<output.sizeY(); ++y) {
                for (int x = 0; x<output.sizeX(); ++x) {
                    output.setPixel(x, y, z, source.getPixel(x, y, z)-muZY[z][y]);
                }
            }
        }
        
        return output;
    }
    public static Image removeStripes(Image image, ImageMask exclusionSignalMask, boolean addGlobalMean, int slidingHalfWindow) {
        return removeMeanX(image, image instanceof ImageFloat ? image : null, computeMeanX(image, exclusionSignalMask, addGlobalMean, slidingHalfWindow));
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (meanFZY==null || meanFZY.length<timePoint) throw new RuntimeException("RemoveStripes transformation not configured");
        Image res = removeMeanX(image, image instanceof ImageFloat ? image : null, meanFZY[timePoint]);
        if (backgroundMask!=null) res = ImageOperations.addImage(res, backgroundMask[timePoint], res, -1);
        return res;
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return meanFZY!=null && meanFZY.length==totalTimePointNumber;
    }

    TEST_MODE testMode=TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TEST_MODE testMode) {this.testMode=testMode;}

}
