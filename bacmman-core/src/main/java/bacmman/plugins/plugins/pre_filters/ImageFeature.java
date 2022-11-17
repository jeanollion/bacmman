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
package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.plugins.Filter;
import bacmman.plugins.Hint;
import bacmman.processing.ImageFeatures;
import bacmman.plugins.PreFilter;
import bacmman.processing.ImageOperations;
import bacmman.utils.Utils;

import static bacmman.plugins.plugins.pre_filters.ImageFeature.Feature.StructureMax;

import java.util.Arrays;
import java.util.function.Function;

/**
 *
 * @author Jean Ollion
 */
public class ImageFeature implements PreFilter, Filter, Hint {

    @Override
    public String getHintText() {
        return "Collection of image transformations frequently used in image analysis" +
                "<ul><li><em>Gaussian Smooth</em>: blurs the image using a Gaussian function, typically reduces the noise and the details</li>" +
                "<li><em>Gradient Magnitude</em>: detects the amplitude edges, where the pixel grey levels change abruptly</li>" +
                "<li><em>Laplacian</em>: The Laplacian is an isotropic measure of the 2nd spatial derivative of an image. It highlights the regions of rapid intensity change and is therefore often used for edge detection or spot-like structure detection</li>" +
                "<li><em>Hessian</em>: Hessian matrix or Hessian is a square matrix of second-order partial derivatives of the image intensity. It describes its local curvature. <em>Hessian Max</em> is the maximum eigenvalue, <em>Hessian Min</em> is the minimum eigenvalue and <em>Hessian Det</em> is the determinant</li>" +
                "<li><em>Structure</em>: The structure tensor, also referred to as the second-moment matrix, is a matrix derived from the gradient of the image. <em>Structure Max</em> is the maximum eigenvalue of the structure matrix. <em>Structure Det</em> is the determinant of the structure matrix.</li>" +
                "<li><em>Shape Index</em>: Shape index 2/PI * atan( (k2+K2)/(k2-k1) ) where k1 & k2 are eignevalues of the Hessian. See Koenderink JJ van Doorn AJ (1992) Surface shape and curvature scales Image and Vision Computing 10:557–564. https://doi.org/10.1016/0262-8856(92)90076-F </li</ul>>" +
                "Using <em>ImageScience</em> library: <a href='https://imagescience.org/meijering/software/featurej/'>https://imagescience.org/meijering/software/featurej/</a>";
    }

    public static enum Feature {
        GAUSS("Gaussian Smooth"),
        GRAD("Gradient"), 
        LoG("Laplacian"), 
        HessianDet("Hessian Det"), 
        HessianMax("Hessian Max"),
        HessianMin("Hessian Min"),
        StructureMax("Structure Max"),
        StructureDet("Structure Det"),
        ShapeIndex("Shape Index");
        final String name;
        Feature(String name) {
            this.name=name;
        }
        public static  Feature getFeature(String name) {
            return Utils.getFirst(Arrays.asList(Feature.values()), f->f.name.equals(name));
        }
    }
    ChoiceParameter feature = new ChoiceParameter("Feature", Utils.transform(Feature.values(), new String[Feature.values().length], f->f.name), Feature.GAUSS.name, false).setEmphasized(true);
    ScaleXYZParameter scale = new ScaleXYZParameter("Scale", 2, 1, true).setEmphasized(true).setHint("Scale of the operation in pixels").setEmphasized(true);
    ScaleXYZParameter smoothScale = new ScaleXYZParameter("Smooth Scale", 2, 1, true).setEmphasized(true);
    ConditionalParameter<String> cond = new ConditionalParameter<>(feature).setDefaultParameters(new Parameter[]{scale}).setActionParameters(StructureMax.name, scale, smoothScale);
    BooleanParameter applySliceBySlice = new BooleanParameter("Apply Slice by Slice", false).setEmphasized(true).setHint("For 3D image, computes the feature slice by slice i.e. returns a stack of 2D features");

    public ImageFeature() {}
    public ImageFeature setFeature(Feature f) {
        this.feature.setValue(f.name);
        return this;
    }
    public ImageFeature set2D(boolean applySlicebySlice) {
        this.applySliceBySlice.setSelected(applySlicebySlice);
        return this;
    }
    public ImageFeature setScale(double scale) {
        this.scale.setScaleXY(scale);
        this.scale.setUseImageCalibration(true);
        return this;
    }
    public ImageFeature setSmoothScale(double scale) {
        this.smoothScale.setScaleXY(scale);
        this.smoothScale.setUseImageCalibration(true);
        return this;
    }
    public ImageFeature setScale(double scaleXY, double scaleZ) {
        this.scale.setScaleXY(scaleXY);
        this.scale.setScaleZ(scaleZ);
        this.scale.setUseImageCalibration(false);
        return this;
    }
    
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean canModifyImage) {
        //logger.debug("ImageFeature: feature equasl: {}, scale equals: {}, normScale equals: {}", feature==cond.getActionableParameter(), scale == cond.getCurrentParameters().get(0), normScale == cond.getParameters("Normalized Hessian Max").get(1));
        //logger.debug("ImageFeauture: feature: {}, scale: {}, scaleZ: {} (from image: {}) normScale: {}", feature.getSelectedItem(), scale.getScaleXY(), scale.getScaleZ(mask.getScaleXY(), mask.getScaleZ()), scale.getUseImageCalibration(), normScale.getValue());
        double scaleXY = scale.getScaleXY();
        double scaleZ = scale.getScaleZ(input.getScaleXY(), input.getScaleZ());
        Function<Image, Image> fun = getFeatureFunction(scaleXY, scaleZ, canModifyImage);
        if (applySliceBySlice.getSelected()) return ImageOperations.applyPlaneByPlane(input, fun);
        else return fun.apply(input);
    }

    public Function<Image, Image> getFeatureFunction(double scaleXY, double scaleZ, boolean canModifyImage) {
        Feature f = Feature.getFeature(feature.getSelectedItem());
        switch(f) {
            case GAUSS:
                return input -> ImageFeatures.gaussianSmooth(input, scaleXY, scaleZ, canModifyImage);
            case GRAD:
                return input -> ImageFeatures.getGradientMagnitude(input, scaleXY, canModifyImage);
            case LoG:
                return input -> ImageFeatures.getLaplacian(input, scaleXY, true, canModifyImage);
            case HessianDet:
                return input -> ImageFeatures.getHessianMaxAndDeterminant(input, scaleXY, canModifyImage)[1];
            case HessianMax:
                return input -> ImageFeatures.getHessian(input, scaleXY, scaleZ, canModifyImage)[0];
            case HessianMin:
                return input -> {
                        ImageFloat[] hess = ImageFeatures.getHessian(input, scaleXY, scaleZ, canModifyImage);
                        return hess[hess.length-1];
                };
            case StructureMax:
                return input -> ImageFeatures.getStructure(input, smoothScale.getScaleXY(), scale.getScaleXY(), canModifyImage)[0];
            case StructureDet:
                return input -> ImageFeatures.getStructureMaxAndDeterminant(input, smoothScale.getScaleXY(), scale.getScaleXY(), canModifyImage)[1];
            case ShapeIndex:
                return input -> ImageFeatures.getShapeIndex(input, smoothScale.getScaleXY(), canModifyImage);
            default:
                throw new IllegalArgumentException("Feature "+feature.getSelectedItem()+"not supported");
        }
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return runPreFilter(image, null, true);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{cond, applySliceBySlice};
    }
    
}
