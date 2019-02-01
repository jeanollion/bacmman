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
package bacmman.plugins.plugins.thresholders;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import static bacmman.image.BoundingBox.loop;
import bacmman.image.Image;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Thresholder;
import bacmman.processing.ImageFeatures;

/**
 *
 * @author Jean Ollion
 */
public class LocalContrastThresholder implements Thresholder, DevPlugin {
    BoundedNumberParameter scale = new BoundedNumberParameter("Contrast Scale", 1, 2, 1, null);
    BoundedNumberParameter constrastThreshold = new BoundedNumberParameter("Contrast Threshold", 3, 0.02,  0.001, 0.2);
    Parameter[] parameters = new Parameter[]{scale, constrastThreshold};
    
    public LocalContrastThresholder() {}
    public LocalContrastThresholder(double scale, double thld) {
        this.scale.setValue(scale);
        this.constrastThreshold.setValue(thld);
    }
    
    public static Image getLocalContrast(Image input, double scale) {
        //input = ImageOperations.normalize(input, null, null);
        Image localContrast=ImageFeatures.getGradientMagnitude(input, scale, false);
        return localContrast;
    }
    /*public static Image[] getLocalContrastAndSmooth(Image input, double scale) {
        double mean = ImageOperations.getMeanAndSigma(input, null)[0];
        ImageFloat inputMinusMean = ImageOperations.addValue(input, -mean, new ImageFloat("", 0, 0, 0));
        Image localContrast=ImageFeatures.getGradientMagnitude(inputMinusMean, scale, false);
        Image smooth = ImageFeatures.gaussianSmooth(inputMinusMean, scale, scale * input.getScaleXY() / input.getScaleZ() , false);
        ImageOperations.divide(localContrast, smooth, localContrast);
        return new Image[] {localContrast,smooth};
    }*/
    public static double getThreshold(Image input, double min, double scale) {
        final Image localContrast= getLocalContrast(input, scale);
        double[] meanCount = new double[2];
        loop(input.getBoundingBox().resetOffset(), (int x, int y, int z) -> {
            double v = localContrast.getPixel(x, y, z);
            if (v>=min) {
                v = input.getPixel(x, y, z);
                //if (v > thld == backgroundUnderThld) {
                    meanCount[0]+=v;
                    ++meanCount[1];
                //}
            }
        });
        double fore = meanCount[0]/meanCount[1];
        /*double[] meanCountBck = new double[2];
        // background
        input.getBoundingBox().translateToOrigin().loop((int x, int y, int z) -> {
            double v = input.getPixel(x, y, z);
            if (v<fore) {
                meanCountBck[0]+=v;
                ++meanCountBck[1];
            }
        });
        
        double back = meanCountBck[1]>meanCount[1]*0.1 ? meanCountBck[0]/meanCountBck[1] : fore;
        logger.debug("fore: {}, back {}", fore, back);
        return (fore+back)/2;*/
        return fore;
    }
    
    
    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        return getThreshold(input, constrastThreshold.getValue().doubleValue(), scale.getValue().doubleValue());
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
}
