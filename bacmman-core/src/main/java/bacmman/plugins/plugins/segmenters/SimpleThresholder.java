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
package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.processing.ImageOperations;
import bacmman.plugins.Segmenter;
import bacmman.plugins.Thresholder;
import bacmman.plugins.plugins.thresholders.ConstantValue;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.image.ImageLabeller;
import bacmman.image.ImageMask;

import java.util.ArrayList;
import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class SimpleThresholder implements Segmenter {
    PluginParameter<Thresholder> threshold = new PluginParameter<>("Threshold", Thresholder.class, false);
    
    public SimpleThresholder() {

    }
    
    public SimpleThresholder(Thresholder thresholder) {
        this.threshold.setPlugin(thresholder);
    }
    
    public SimpleThresholder(double threshold) {
        this(new ConstantValue(threshold));
    }
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject structureObject) {
        ImageByte mask = new ImageByte("mask", input);
        Thresholder t =  threshold.instantiatePlugin();
        double thresh = t.runThresholder(input, structureObject);
        byte[][] pixels = mask.getPixelArray();
        for (int z = 0; z<input.sizeZ(); ++z) {
            for (int xy = 0; xy<input.sizeXY(); ++xy) {
                if (input.getPixel(xy, z)>=thresh) pixels[z][xy]=1;
            }
        }
        Region[] objects = ImageLabeller.labelImage(mask);
        //logger.debug("seg objects: for class: {} = {}, parent bounds: {}", objectClassIdx, Utils.toStringArray(objects, o->o.getBounds()), structureObject.getBounds());
        logger.trace("simple thresholder: image: {} number of objects: {}", input.getName(), objects.length);
        return  new RegionPopulation(new ArrayList<>(Arrays.asList(objects)), input);
        
    }
    
    public static RegionPopulation run(Image input, Thresholder thresholder, SegmentedObject structureObject) {
        double thresh = thresholder.runThresholder(input, structureObject);
        return run(input, thresh, structureObject.getMask()); 
    }
    
    
    public static RegionPopulation run(Image input, double threhsold, ImageMask mask) {
        ImageInteger maskR = ImageOperations.threshold(input, threhsold, true, false, false, null);
        if (mask!=null) ImageOperations.and(maskR, mask, maskR);
        Region[] objects = ImageLabeller.labelImage(maskR);
        return new RegionPopulation(new ArrayList<>(Arrays.asList(objects)), input);
    }
    public static RegionPopulation runUnder(Image input, double threhsold, ImageMask mask) {
        ImageInteger maskR = ImageOperations.threshold(input, threhsold, false, false, false, null);
        if (mask!=null) ImageOperations.and(maskR, mask, maskR);
        Region[] objects = ImageLabeller.labelImage(maskR);
        return new RegionPopulation(new ArrayList<>(Arrays.asList(objects)), input);
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{threshold};
    }

}
