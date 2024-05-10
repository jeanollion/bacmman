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
import bacmman.image.*;
import bacmman.image.wrappers.ImgLib2ImageWrapper;
import bacmman.plugins.*;
import bacmman.plugins.plugins.pre_filters.IJSubtractBackground;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.data_structure.input_image.InputImages;
import bacmman.processing.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.List;

import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.ArrayUtil;
import bacmman.utils.ThreadRunner;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 *
 * @author Jean Ollion
 */
public class SelectBestFocusPlane implements ConfigurableTransformation, MultichannelTransformation, Autofocus, Hint {
    ArrayList<Integer> bestFocusPlaneIdxT = new ArrayList<Integer>();
    NumberParameter gradientScale = new BoundedNumberParameter("Gradient Scale", 5, 2, 0, 10);
    PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters");
    NumberParameter smooothScale = new BoundedNumberParameter("Smooth Scale", 5, 2, 1, 10);

    PluginParameter<SimpleThresholder> signalExclusionThreshold = new PluginParameter<>("Signal Exclusion Threshold", SimpleThresholder.class, new BackgroundThresholder(2.5, 3, 3), true).setHint("Gradient magnitude maximization is performed among pixels with value over this threshold"); //new ConstantValue(150)    Parameter[] parameters = new Parameter[]{gradientScale};
    BooleanParameter excludeUnderThld = new BooleanParameter("Exclude under Threshold", true);
    Parameter[] parameters = new Parameter[]{preFilters, gradientScale, smooothScale, signalExclusionThreshold, excludeUnderThld};
    public SelectBestFocusPlane() {}
    public SelectBestFocusPlane(double gradientScale) {
        this.gradientScale.setValue(gradientScale);
    }
    
    @Override
    public void computeConfigurationData(final int channelIdx, final InputImages inputImages)  throws IOException {
        final double scale = gradientScale.getValue().doubleValue();
        final double sscale = smooothScale.getValue().doubleValue();
        final Integer[] conf = new Integer[inputImages.getFrameNumber()];
        if (inputImages.getSourceSizeZ(channelIdx)>1) {
            IOException[] ioe = new IOException[1];
            IntConsumer ex = t -> {
                Image image = InputImages.getImage(inputImages, channelIdx, t, ioe);
                if (image == null) return;
                if (image.sizeZ()>1) {
                    List<Image> planes = image.splitZPlanes();
                    SimpleThresholder thlder = signalExclusionThreshold.instantiatePlugin();
                    conf[t] = (int)getBestFocusPlane(planes, scale, sscale, 1, thlder, excludeUnderThld.getSelected(), null, preFilters);
                    logger.debug("select best focus plane: time:{}, plane: {}", t, conf[t]);
                }
            };
            ThreadRunner.parallelExecutionBySegments(ex, 0, inputImages.getFrameNumber(), 100, s -> Core.freeMemory());
            if (ioe[0]!=null) throw ioe[0];
        }
        bestFocusPlaneIdxT.addAll(Arrays.asList(conf));
    }
    
    @Override
    public int getBestFocusPlane(Image image, ImageMask mask) {
        if (image.sizeZ()<=1) return 0;
        return (int)getBestFocusPlane(image.splitZPlanes(), this.gradientScale.getValue().doubleValue(), this.smooothScale.getDoubleValue(), 1, this.signalExclusionThreshold.instantiatePlugin(), this.excludeUnderThld.getSelected(), mask, preFilters);
    }
    
    public static double getBestFocusPlane(List<Image> planes, double scale, double smoothScale, int precisionFactor, SimpleThresholder thlder, boolean excludeUnder, ImageMask globalMask, PreFilterSequence preFilters) {
        ImageMask mask = null;
        double[] values = new double[planes.size()];
        for (int zz = 0; zz<planes.size(); ++zz) {
            if (thlder!=null) {
                final ImageMask maskThld = new PredicateMask(planes.get(zz), thlder.runSimpleThresholder(planes.get(zz), globalMask), excludeUnder, false);
                final int zzz = zz;
                if (globalMask!=null) mask = new PredicateMask(planes.get(zz), (x, y, z)->globalMask.insideMask(x, y, zzz)&&maskThld.insideMask(x, y, z), (xy, z)->globalMask.insideMask(xy, zzz)&&maskThld.insideMask(xy, z), true);
                else mask = maskThld;
                if (mask.count()==0) continue;
            } else if (globalMask!=null) {
                final int zzz = zz;
                mask = new PredicateMask(planes.get(zz), (x, y, z)->globalMask.insideMask(x, y, zzz), (xy, z)->globalMask.insideMask(xy, zzz), true);
            }
            Image plane = planes.get(zz);
            if (!preFilters.isEmpty()) plane = preFilters.filter(plane, null); // new ImageMask2D(globalMask, zz)
            values[zz] = evalPlane(plane, scale, mask);
        }
        ImageDouble im = new ImageDouble("", values.length, values);
        if (smoothScale>=1) im = (ImageDouble)ImageDerivatives.gaussianSmooth(im, smoothScale, true);
        if (precisionFactor>1) {
            ImageDouble imResample = Resize.resample(im, ImgLib2ImageWrapper.INTERPOLATION.LANCZOS5, values.length * precisionFactor);
            logger.debug("max:{} max resample: {} values: {} resample: {}", ArrayUtil.max(values), ArrayUtil.max(imResample.getPixelArray()[0]) / (double)precisionFactor, values, imResample.getPixelArray()[0]);
            return ArrayUtil.max(imResample.getPixelArray()[0]) / (double)precisionFactor;
        } else {
            logger.debug("max: {} values: {}", ArrayUtil.max(im.getPixelArray()[0]), im.getPixelArray()[0]);
            return ArrayUtil.max(im.getPixelArray()[0]);
        }
    }
    public static double evalPlane(Image plane, double scale, ImageMask mask) {
        if (scale>0) plane = ImageDerivatives.gaussianSmooth(plane, scale, true);
        Image gradient = ImageDerivatives.getGradientMagnitude(plane, 0, false, true, 0, 1);
        //Image gradient = ImageFeatures.getGradientMagnitude(plane, scale, false);
        return ImageOperations.getMeanAndSigma(gradient, mask, null)[0];
        /*Neighborhood n = Filters.getNeighborhood(Math.max(1.5, scale), plane);
        Image sigma = Filters.sigma(plane, null, n, true);
        Image mean = Filters.mean(plane, null, n, true);
        double[] cum = new double[2];
        BoundingBox.LoopFunction fun = (x, y, z) -> {
            cum[0] += sigma.getPixel(x, y, z) / mean.getPixel(x, y, z);
            ++cum[1];
        };
        if (mask == null) BoundingBox.loop(sigma.getBoundingBox().resetOffset(), fun);
        else ImageMask.loop(mask, fun);
        return cum[0] / cum[1];*/
    }
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (bestFocusPlaneIdxT==null || timePoint>=bestFocusPlaneIdxT.size()) throw new RuntimeException("SelectBestFocusPlane transformation is not configured");
        if (image.sizeZ()>1) return image.getZPlane(bestFocusPlaneIdxT.get(timePoint));
        else return image;
    }

    public ArrayList getConfigurationData() {
        return bestFocusPlaneIdxT;
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }
    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return bestFocusPlaneIdxT !=null && bestFocusPlaneIdxT.size() == totalTimePointNumber;
    }
    @Override
    public boolean highMemory() {return false;}
    @Override
    public String getHintText() {
        return "Selects the plane of best focus in a 3D stack, which is defined as the plane with the maximal gradient magnitude";
    }

    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.MULTIPLE;
    }
}
