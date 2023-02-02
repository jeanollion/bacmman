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

import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Hint;
import bacmman.plugins.MultichannelTransformation;

/**
 *
 * @author Jean Ollion
 */
public class SimpleCrop implements MultichannelTransformation, Hint {
    NumberParameter xMin = new NumberParameter<>("X-Min", 0, 0).setEmphasized(true);
    NumberParameter yMin = new NumberParameter<>("Y-Min", 0, 0).setEmphasized(true);
    NumberParameter zMin = new NumberParameter<>("Z-Min", 0, 0).setEmphasized(true);
    NumberParameter xLength = new NumberParameter<>("X-Length", 0, 0).setEmphasized(true);
    NumberParameter yLength = new NumberParameter<>("Y-Length", 0, 0).setEmphasized(true);
    NumberParameter zLength = new NumberParameter<>("Z-Length", 0, 0).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{xMin, xLength, yMin, yLength, zMin, zLength};
    MutableBoundingBox bounds;
    public SimpleCrop(){}
    public SimpleCrop(int x, int xL, int y, int yL, int z, int zL){
        xMin.setValue(x);
        xLength.setValue(xL);
        yMin.setValue(y);
        yLength.setValue(yL);
        zMin.setValue(z);
        zLength.setValue(zL);
    }
    public SimpleCrop yMin(int y) {
        this.yMin.setValue(y);
        return this;
    }
    public SimpleCrop xMin(int x) {
        this.xMin.setValue(x);
        return this;
    }
    public SimpleCrop zMin(int z) {
        this.zMin.setValue(z);
        return this;
    }
    public SimpleCrop(int... bounds){
        if (bounds.length>0) xMin.setValue(bounds[0]);
        if (bounds.length>1) xLength.setValue(bounds[1]);
        if (bounds.length>2) yMin.setValue(bounds[2]);
        if (bounds.length>3) yLength.setValue(bounds[3]);
        if (bounds.length>4) zMin.setValue(bounds[4]);
        if (bounds.length>5) zLength.setValue(bounds[5]);
    }
    /*@Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
       return bounds!=null;
    }
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        Image input = inputImages.getImage(channelIdx, inputImages.getDefaultTimePoint());
        if (xLength.getValue().intValue()==0) xLength.setValue(input.sizeX()-xMin.getValue().intValue());
        if (yLength.getValue().intValue()==0) yLength.setValue(input.sizeY()-yMin.getValue().intValue());
        if (zLength.getValue().intValue()==0) zLength.setValue(input.sizeZ()-zMin.getValue().intValue());
        bounds = new MutableBoundingBox(xMin.getValue().intValue(), xMin.getValue().intValue()+xLength.getValue().intValue()-1, 
        yMin.getValue().intValue(), yMin.getValue().intValue()+yLength.getValue().intValue()-1, 
        zMin.getValue().intValue(), zMin.getValue().intValue()+zLength.getValue().intValue()-1);
        bounds.trim(input.getBoundingBox());
        
    }*/
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        BoundingBox bds = ensureValidBounds(new SimpleBoundingBox(image).resetOffset());
        return image.crop(bds);
    }

    private BoundingBox ensureValidBounds(BoundingBox bb) {
        if (bounds!=null && bounds.getSizeXYZ()!=0) return bounds;
        else synchronized (this) {
            MutableBoundingBox currentBounds;
            if (bounds == null) currentBounds = new MutableBoundingBox(xMin.getValue().intValue(), yMin.getValue().intValue(), zMin.getValue().intValue());
            else currentBounds = new MutableBoundingBox(bounds);
            currentBounds.setxMax(xLength.getValue().intValue()==0 ? bb.xMax() : currentBounds.xMin()+xLength.getValue().intValue()-1);
            currentBounds.setyMax(yLength.getValue().intValue()==0 ? bb.yMax() : currentBounds.yMin()+yLength.getValue().intValue()-1);
            currentBounds.setzMax(zLength.getValue().intValue()==0 ? bb.zMax() : currentBounds.zMin()+zLength.getValue().intValue()-1);
            //logger.debug("simple crop bounds: {}", currentBounds);
            currentBounds.trim(bb);
            return currentBounds;
        }
        
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.ALL;
    }

    @Override
    public String getHintText() {
        return "Crop All preprocessed images within a constant bounding box.";
    }

    
}
