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

import bacmman.data_structure.dao.BypassImageDAO;
import bacmman.data_structure.dao.ImageDAO;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.image.BlankMask;
import bacmman.image.Image;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Transformation;
import bacmman.plugins.TransformationApplyDirectly;
import bacmman.plugins.TransformationNoInput;

/**
 *
 * @author Jean Ollion
 */

public class InputImage {
    final MultipleImageContainer imageSources;
    ImageDAO dao, daoTemp;
    int inputChannelIdx, channelIdx, frame, inputFrame;
    String microscopyFieldName;
    Image originalImageType;
    Image image;
    boolean intermediateImageSavedToDAO=false, modified=false, transformationHaveBeenApplied=false;
    final List<Transformation> transformationsToApply;
    double scaleXY=Double.NaN, scaleZ= Double.NaN;
    public InputImage(int inputChannelIdx, int channelIdx, int inputFrame, int frame, String microscopyFieldName, MultipleImageContainer imageSources, ImageDAO dao, ImageDAO daoTemp) {
        this.imageSources = imageSources;
        this.dao = dao;
        this.daoTemp = daoTemp;
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
        return duplicate(dao, daoTemp);
    }
    public InputImage duplicate(ImageDAO dao, ImageDAO daoTemp) {
        InputImage res = new InputImage(inputChannelIdx, channelIdx, inputFrame, frame, microscopyFieldName, imageSources, dao, daoTemp);
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
            if (t instanceof ConfigurableTransformation && ((ConfigurableTransformation)t).highMemory()) return true;
        }
        return false;
    }
    public boolean hasApplyDirectlyTransformations() {
        for (Transformation t: transformationsToApply) {
            if (t instanceof TransformationApplyDirectly) return true;
        }
        return false;
    }
    public Image getImage() throws IOException {
        if (image == null && requiresInputImage()) {
            synchronized (imageSources) {
                if (image==null) {
                    if (intermediateImageSavedToDAO) image = daoTemp.openPreProcessedImage(channelIdx, frame); //try to open from DAO
                    else {
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
    public Image getRawPlane(int z) throws IOException {
        if (image!=null && !transformationHaveBeenApplied) return image.getZPlane(z);
        Image plane = imageSources.getPlane(z, inputFrame, inputChannelIdx);
        if (!Double.isNaN(scaleXY)) plane.setCalibration(scaleXY, scaleZ);
        return plane;
    }

    void deleteFromDAO(boolean temp) {
        if (!temp) dao.deletePreProcessedImage(channelIdx, frame);
        else daoTemp.deletePreProcessedImage(channelIdx, frame);
    }
    
    public void freeMemory() {
        image=null;
    }

    private boolean requiresInputImage() {
        return transformationsToApply.isEmpty() || !(transformationsToApply.get(0) instanceof TransformationNoInput);
    }

    private void applyTransformations() {
        if (!transformationsToApply.isEmpty()) {
            synchronized(this) {
                if (transformationsToApply.isEmpty()) return;
                modified=true;
                transformationHaveBeenApplied=true;
                Iterator<Transformation> it = transformationsToApply.iterator();
                while(it.hasNext()) {
                    Transformation t = it.next();
                    image = t.applyTransformation(channelIdx, frame, image);
                    if (image == null) throw new RuntimeException("Transformation "+t.getClass()+ " returned null image for frame: "+frame+" channel: "+channelIdx);
                    it.remove();
                }
            }
            if (intermediateImageSavedToDAO && modified) {
                intermediateImageSavedToDAO = false;
                deleteFromDAO(true);
            }
        }
    }

    public void saveImage(boolean intermediate) {
        ImageDAO dao = intermediate ? this.daoTemp : this.dao;
        dao.writePreProcessedImage(image, channelIdx, frame);
        this.intermediateImageSavedToDAO = intermediate && !(dao instanceof BypassImageDAO);
        if (intermediate) modified=false;
    }
    
    void setTimePoint(int timePoint) {
        this.frame=timePoint;
    }
}
