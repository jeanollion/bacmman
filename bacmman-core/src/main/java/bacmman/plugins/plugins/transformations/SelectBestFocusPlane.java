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

import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.processing.ImageOperations;
import java.util.ArrayList;
import java.util.Arrays;
import bacmman.plugins.SimpleThresholder;
import bacmman.processing.ImageFeatures;
import bacmman.image.ThresholdMask;
import java.util.List;
import bacmman.plugins.Autofocus;
import bacmman.plugins.ConfigurableTransformation;
import java.util.stream.IntStream;
/**
 *
 * @author Jean Ollion
 */
public class SelectBestFocusPlane implements ConfigurableTransformation, Autofocus {
    ArrayList<Integer> bestFocusPlaneIdxT = new ArrayList<Integer>();
    NumberParameter gradientScale = new BoundedNumberParameter("Gradient Scale", 0, 3, 1, 10);
    PluginParameter<SimpleThresholder> signalExclusionThreshold = new PluginParameter<>("Signal Exclusion Threshold", SimpleThresholder.class, new BackgroundThresholder(2.5, 3, 3), true); //new ConstantValue(150)    Parameter[] parameters = new Parameter[]{gradientScale};
    Parameter[] parameters = new Parameter[]{gradientScale, signalExclusionThreshold};
    public SelectBestFocusPlane() {}
    public SelectBestFocusPlane(double gradientScale) {
        this.gradientScale.setValue(gradientScale);
    }
    
    @Override
    public void computeConfigurationData(final int channelIdx, final InputImages inputImages)  {
        final double scale = gradientScale.getValue().doubleValue();
        final Integer[] conf = new Integer[inputImages.getFrameNumber()];
        if (inputImages.getSizeZ(channelIdx)>1) {
            IntStream.range(0, inputImages.getFrameNumber()).parallel().forEach(t -> { 
                Image image = inputImages.getImage(channelIdx, t);
                if (image.sizeZ()>1) {
                    List<Image> planes = image.splitZPlanes();
                    SimpleThresholder thlder = signalExclusionThreshold.instanciatePlugin();
                    conf[t] = getBestFocusPlane(planes, scale, thlder, null);
                    logger.debug("select best focus plane: time:{}, plane: {}", t, conf[t]);
                }
            });
        }
        bestFocusPlaneIdxT.addAll(Arrays.asList(conf));
    }
    
    @Override
    public int getBestFocusPlane(Image image, ImageMask mask) {
        if (image.sizeZ()<=1) return 0;
        return getBestFocusPlane(image.splitZPlanes(), this.gradientScale.getValue().doubleValue(), this.signalExclusionThreshold.instanciatePlugin(), mask);
    }
    
    public static int getBestFocusPlane(List<Image> planes, double scale, SimpleThresholder thlder, ImageMask globalMask) {
        double maxValues = -Double.MAX_VALUE;
        ImageMask mask = null;
        int max=-1;
        for (int zz = 0; zz<planes.size(); ++zz) {
            if (thlder!=null) {
                final ImageMask maskThld = new ThresholdMask(planes.get(zz), thlder.runSimpleThresholder(planes.get(zz), globalMask), true, false);
                final int zzz = zz;
                if (globalMask!=null) mask = new ThresholdMask(planes.get(zz), (x, y, z)->globalMask.insideMask(x, y, zzz)&&maskThld.insideMask(x, y, z), (xy, z)->globalMask.insideMask(xy, zzz)&&maskThld.insideMask(xy, z), true);
                else mask = maskThld;
                if (mask.count()==0) continue;
            } else if (globalMask!=null) {
                final int zzz = zz;
                mask = new ThresholdMask(planes.get(zz), (x, y, z)->globalMask.insideMask(x, y, zzz), (xy, z)->globalMask.insideMask(xy, zzz), true);
            }
            double temp = evalPlane(planes.get(zz), scale, mask);
            if (temp>maxValues) {
                maxValues = temp;
                max = zz;
            }
        }
        logger.debug("get best focus plane: {}/{}", max, planes.size());
        if (max==-1) max = planes.size()/2;
        return max;
    }
    public static double evalPlane(Image plane, double scale, ImageMask mask) {
        Image gradient = ImageFeatures.getGradientMagnitude(plane, scale, false);
        return ImageOperations.getMeanAndSigma(gradient, mask, null)[0];
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
    boolean testMode;
    @Override public void setTestMode(boolean testMode) {this.testMode=testMode;}

    
}
