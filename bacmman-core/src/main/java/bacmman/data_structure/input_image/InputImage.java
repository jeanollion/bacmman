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

import bacmman.data_structure.dao.ImageDAO;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.image.BlankMask;
import bacmman.image.Image;

import java.util.ArrayList;
import java.util.Iterator;

import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Transformation;
import bacmman.plugins.TransformationNoInput;
import bacmman.plugins.plugins.transformations.CopyTo;

/**
 *
 * @author Jean Ollion
 */

public class InputImage {
    MultipleImageContainer imageSources;
    ImageDAO dao;
    int inputChannelIdx, channelIdx, frame, inputFrame;
    String microscopyFieldName;
    Image originalImageType;
    Image image;
    boolean intermediateImageSavedToDAO=false, modified=false;
    ArrayList<Transformation> transformationsToApply;
    double scaleXY=Double.NaN, scaleZ= Double.NaN;
    public InputImage(int inputChannelIdx, int channelIdx, int inputFrame, int frame, String microscopyFieldName, MultipleImageContainer imageSources, ImageDAO dao) {
        this.imageSources = imageSources;
        this.dao = dao;
        this.inputChannelIdx=inputChannelIdx;
        this.channelIdx = channelIdx;
        this.frame = frame;
        this.inputFrame = inputFrame;
        this.microscopyFieldName = microscopyFieldName;
        transformationsToApply=new ArrayList<>();
    }
    public void overwriteCalibration(double scaleXY, double scaleZ) {
        this.scaleXY=scaleXY;
        this.scaleZ=scaleZ;
    }
    public boolean modified() {
        return modified;
    }
    public InputImage duplicate() {
        InputImage res = new InputImage(inputChannelIdx, channelIdx, inputFrame, frame, microscopyFieldName, imageSources, dao);
        res.overwriteCalibration(scaleXY, scaleZ);
        if (image!=null) {
            res.image = image.duplicate();
            res.originalImageType=originalImageType.duplicate();
        }
        return res;
    }
    
    public void addTransformation(Transformation t) {
        transformationsToApply.add(t);
    }
    
    public MultipleImageContainer duplicateContainer() {
        return imageSources.duplicate();
    }
    public boolean imageOpened() {
        return image!=null;
    }
    public boolean hasTransformations() {return !transformationsToApply.isEmpty();}
    public boolean hasHighMemoryTransformations() {
        for (Transformation t: transformationsToApply) {
            if (t instanceof ConfigurableTransformation) return ((ConfigurableTransformation)t).highMemory();
        }
        return false;
    }
    public Image getImage() {
        if (image == null && requiresInputImage()) {
            synchronized (this) {
                if (image==null) {
                    if (intermediateImageSavedToDAO) image = dao.openPreProcessedImage(channelIdx, frame, microscopyFieldName); //try to open from DAO
                    if (image==null) {
                        image = imageSources.getImage(inputFrame, inputChannelIdx);
                        if (image==null) throw new RuntimeException("Image not found: position:"+microscopyFieldName+" channel:"+inputChannelIdx+" frame:"+inputFrame);
                        if (!Double.isNaN(scaleXY)) image.setCalibration(scaleXY, scaleZ);
                        originalImageType = Image.createEmptyImage("source Type", image, new BlankMask( 0, 0, 0));
                    }
                }
            }
        }
        applyTransformations();
        return image;
    }
    
    void deleteFromDAO() {dao.deletePreProcessedImage(channelIdx, frame, microscopyFieldName);}
    
    public void flush() {
        if (image!=null) image=null;
        //imageSources.close();
    }
    private boolean requiresInputImage() {
        if (transformationsToApply.size()>=1 && transformationsToApply.get(0) instanceof TransformationNoInput) return false;
        return true;
    }
    private void applyTransformations() {
        if (transformationsToApply!=null && !transformationsToApply.isEmpty()) {
            synchronized(transformationsToApply) {
                if (transformationsToApply.isEmpty()) return;
                modified=true;
                Iterator<Transformation> it = transformationsToApply.iterator();
                while(it.hasNext()) {
                    Transformation t = it.next();
                    image = t.applyTransformation(channelIdx, frame, image);
                    it.remove();
                }
            }
        }
    }

    public void saveImage(boolean intermediate) {
        dao.writePreProcessedImage(image, channelIdx, frame, microscopyFieldName);
        this.intermediateImageSavedToDAO = intermediate;
        if (intermediate) modified=false;
    }
    
    void setTimePoint(int timePoint) {
        this.frame=timePoint;
    }
}
