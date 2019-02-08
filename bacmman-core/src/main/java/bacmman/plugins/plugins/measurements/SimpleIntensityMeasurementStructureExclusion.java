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
package bacmman.plugins.plugins.measurements;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.processing.Filters;
import bacmman.processing.ImageOperations;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Measurement;
import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.TextParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SimpleIntensityMeasurementStructureExclusion implements Measurement, DevPlugin {
    protected ObjectClassParameter structureObject = new ObjectClassParameter("Object", -1, false, false);
    protected ObjectClassParameter excludedStructure = new ObjectClassParameter("Excluded Structure", -1, false, false);
    protected ObjectClassParameter structureImage = new ObjectClassParameter("Image", -1, false, false);
    protected BoundedNumberParameter dilateExcluded = new BoundedNumberParameter("Radius for excluded structure dilatation", 1, 2, 0, null);
    protected BoundedNumberParameter erodeBorders = new BoundedNumberParameter("Radius for border erosion", 1, 2, 0, null);
    protected BooleanParameter addMeasurementToExcludedStructure = new BooleanParameter("set Measurement to excluded structure", false);
    TextParameter prefix = new TextParameter("Prefix", "Intensity", false);
    protected Parameter[] parameters = new Parameter[]{structureObject, structureImage, excludedStructure, dilateExcluded, erodeBorders, prefix, addMeasurementToExcludedStructure};
    
    public SimpleIntensityMeasurementStructureExclusion() {}
    
    public SimpleIntensityMeasurementStructureExclusion(int object, int image, int exclude) {
        this.structureObject.setSelectedClassIdx(object);
        this.structureImage.setSelectedClassIdx(image);
        this.excludedStructure.setSelectedClassIdx(exclude);
    }
    
    public SimpleIntensityMeasurementStructureExclusion setRadii(double dilateExclude, double erodeMainObject) {
        this.dilateExcluded.setValue(dilateExclude);
        this.erodeBorders.setValue(erodeMainObject);
        return this;
    } 
    
    public SimpleIntensityMeasurementStructureExclusion setPrefix(String prefix) {
        this.prefix.setValue(prefix);
        return this;
    } 
    
    @Override public int getCallObjectClassIdx() {
        return structureObject.getSelectedClassIdx();
    }

    @Override public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override public List<MeasurementKey> getMeasurementKeys() {
        int structureIdx = addMeasurementToExcludedStructure.getSelected() ? excludedStructure.getSelectedClassIdx() : structureObject.getSelectedClassIdx();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix.getValue()+"Mean", structureIdx));
        res.add(new MeasurementKeyObject(prefix.getValue()+"Sigma", structureIdx));
        res.add(new MeasurementKeyObject(prefix.getValue()+"PixCount", structureIdx));
        return res;
    }

    @Override public void performMeasurement(SegmentedObject object) {
        SegmentedObject parent = object.isRoot() ? object : object.getParent();
        Image image = parent.getRawImage(structureImage.getSelectedClassIdx());
        double[] meanSd = ImageOperations.getMeanAndSigmaWithOffset(image, getMask(object, excludedStructure.getSelectedClassIdx(), dilateExcluded.getValue().doubleValue(), erodeBorders.getValue().doubleValue()), null, false);
        object.getMeasurements().setValue(prefix.getValue()+"Mean", meanSd[0]);
        object.getMeasurements().setValue(prefix.getValue()+"Sigma", meanSd[1]);
        object.getMeasurements().setValue(prefix.getValue()+"PixCount", meanSd[2]);
    }
    
    public static ImageByte getMask(SegmentedObject parent, int excludeStructureIdx, double dilateExcludeRadius, double erodeObjectRaduis) {
        Stream<SegmentedObject> children = parent.getChildren(excludeStructureIdx);
        ImageByte mask  = TypeConverter.toByteMask(parent.getMask(), null, 1).setName("mask:");
        if (erodeObjectRaduis>0) {
            ImageByte maskErode = Filters.binaryMin(mask, null, Filters.getNeighborhood(erodeObjectRaduis, mask), true, false);
            //if (maskErode.count()>0) mask = maskErode;
            mask = maskErode;
        }
        ImageByte m = mask;
        children.forEach(o-> {
            Region ob = o.getRegion();
            if (dilateExcludeRadius>0)  {
                ImageInteger oMask = o.getRegion().getMaskAsImageInteger();
                oMask = Filters.binaryMax(oMask, null, Filters.getNeighborhood(dilateExcludeRadius, oMask), false, true, false);
                ob = new Region(oMask, 1, ob.is2D());
            }
            ob.draw(m, 0, null);
        });
        return m;
    }

    @Override public Parameter[] getParameters() {
        return parameters;
    }
    
}
