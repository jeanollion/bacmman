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
package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.processing.Filters;
import bacmman.plugins.Hint;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.Offset;
import bacmman.image.SimpleOffset;
import bacmman.image.TypeConverter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class SNR extends IntensityMeasurement implements Hint {
    protected ObjectClassParameter backgroundStructure = new ObjectClassParameter("Background Object Class").setEmphasized(true);
    protected BoundedNumberParameter dilateExcluded = new BoundedNumberParameter("Dilatation radius for foreground object", 1, 1, 0, null).setHint("Dilated foreground objects will be excluded from background mask");
    protected BoundedNumberParameter erodeBorders = new BoundedNumberParameter("Radius for background mask erosion", 1, 1, 0, null).setHint("Background mask will be eroded in order to avoid border effects");
    protected ChoiceParameter formula = new ChoiceParameter("Formula", new String[]{"(F-B)/std(B)", "F-B"}, "(F-B)/std(B)", false).setEmphasized(true).setHint("formula for SNR estimation. F = Foreground, B = background, std = standard-deviation");
    protected ChoiceParameter foregroundFormula = new ChoiceParameter("Foreground", new String[]{"mean", "max", "value at center"}, "mean", false).setEmphasized(true).setHint("Foreground estimation method");
    @Override public Parameter[] getParameters() {return new Parameter[]{intensity, backgroundStructure, formula, foregroundFormula, dilateExcluded, erodeBorders};}
    HashMap<Region, Region> foregroundMapBackground;
    Offset foregorundOffset;
    Offset parentOffsetRev;
    public SNR() {}
    public SNR(int backgroundStructureIdx) {
        backgroundStructure.setSelectedClassIdx(backgroundStructureIdx);
    }
    public SNR setBackgroundObjectStructureIdx(int structureIdx) {
        backgroundStructure.setSelectedClassIdx(structureIdx);
        return this;
    }
    public SNR setRadii(double dilateRadius, double erodeRadius) {
        this.dilateExcluded.setValue(dilateRadius);
        this.erodeBorders.setValue(erodeRadius);
        return this;
    }
    public SNR setFormula(int formula, int foreground) {
        this.formula.setSelectedIndex(formula);
        this.foregroundFormula.setSelectedIndex(foreground);
        return this;
    }
    @Override public IntensityMeasurement setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation foregroundPopulation) {
        super.setUp(parent, childStructureIdx, foregroundPopulation);
        if (foregroundPopulation.getRegions().isEmpty()) return this;
        if (!foregroundPopulation.isAbsoluteLandmark()) foregorundOffset = parent.getBounds(); // the step it still at processing, thus their offset of objects is related to their direct parent
        else foregorundOffset = new SimpleOffset(0, 0, 0); // absolute offsets
        parentOffsetRev = new SimpleOffset(parent.getBounds()).reverseOffset();
        
        List<Region> backgroundObjects;
        if (backgroundStructure.getSelectedClassIdx()!=super.parent.getStructureIdx()) {
            backgroundObjects = ((SegmentedObject)parent).getChildRegionPopulation(backgroundStructure.getSelectedClassIdx()).getRegions();
        } else {
            backgroundObjects = new ArrayList<>(1);
            backgroundObjects.add(parent.getRegion());
        }
        double erodeRad= this.erodeBorders.getValue().doubleValue();
        double dilRad = this.dilateExcluded.getValue().doubleValue();
        // assign parents to children by inclusion
        HashMapGetCreate<Region, List<Pair<Region, Region>>> backgroundMapForeground = new HashMapGetCreate<>(backgroundObjects.size(), new HashMapGetCreate.ListFactory());
        for (Region o : foregroundPopulation.getRegions()) {
            Region p = o.getContainer(backgroundObjects, foregorundOffset, null); // parents are in absolute offset
            if (p!=null) {
                Region oDil = o;
                if (dilRad>0)  {
                    ImageInteger oMask = o.getMaskAsImageInteger();
                    oMask = Filters.binaryMax(oMask, null, Filters.getNeighborhood(dilRad, dilRad, oMask), false, true, false);
                    oDil = new Region(oMask, 1, o.is2D()).setIsAbsoluteLandmark(o.isAbsoluteLandMark());
                }
                backgroundMapForeground.getAndCreateIfNecessary(p).add(new Pair(o, oDil));
            }
        }
        
        // remove foreground objects from background mask & erodeit
        foregroundMapBackground = new HashMap<>();
        for (Region backgroundRegion : backgroundObjects) {
            ImageMask ref = backgroundRegion.getMask();
            List<Pair<Region, Region>> children = backgroundMapForeground.get(backgroundRegion);
            if (children!=null) {
                ImageByte mask  = TypeConverter.toByteMask(ref, null, 1).setName("SNR mask");
                for (Pair<Region, Region> o : backgroundMapForeground.get(backgroundRegion)) o.value.draw(mask, 0, foregorundOffset);// was with offset: absolute = 0 / relative = parent
                if (erodeRad>0) {
                    ImageByte maskErode = Filters.binaryMin(mask, null, Filters.getNeighborhood(erodeRad, erodeRad, mask), true, false); // erode mask // TODO dilate objects?
                    if (maskErode.count()>0) mask = maskErode;
                }
                Region modifiedBackgroundRegion = new Region(mask, 1, backgroundRegion.is2D()).setIsAbsoluteLandmark(true);
                for (Pair<Region, Region> o : children) foregroundMapBackground.put(o.key, modifiedBackgroundRegion);
                
                //ImageWindowManagerFactory.showImage( mask);
            }
        }
        //logger.debug("init SNR: (s: {}/b:{}) foreground with back: {}/{}", intensity.getSelectedStructureIdx(), this.backgroundStructure.getSelectedStructureIdx(), foregroundMapBackground.size(), foregroundPopulation.getRegions().size());
        return this;
    }
    @Override
    public double performMeasurement(Region object) {
        
        if (core==null) synchronized(this) {setUpOrAddCore(null, null);}
        Region parentObject; 
        if (foregroundMapBackground==null) parentObject = super.parent.getRegion();
        else parentObject=this.foregroundMapBackground.get(object);
        if (parentObject==null) return 0;
        IntensityMeasurementCore.IntensityMeasurements iParent = super.core.getIntensityMeasurements(parentObject);
        IntensityMeasurementCore.IntensityMeasurements fore = super.core.getIntensityMeasurements(object);
        //logger.debug("SNR: parent: {} object: {}, value: {}, fore:{}, back I: {} back SD: {}", super.parent, object.getLabel(), getValue(getForeValue(fore), iParent.mean, iParent.sd), getForeValue(fore), iParent.mean, iParent.sd);
        return getValue(getForeValue(fore), iParent.mean, iParent.sd);
    }
    
    protected double getForeValue(IntensityMeasurementCore.IntensityMeasurements fore) {
        switch (foregroundFormula.getSelectedIndex()) {
            case 0: return fore.mean;
            case 1: return fore.max;
            case 2: return fore.getValueAtCenter();
            default: return fore.mean;
        }     
    }
    
    protected double getValue(double fore, double back, double backSd) {
        if (this.formula.getSelectedIndex()==0) return (fore-back)/backSd;
        else return fore-back;
    }

    @Override public String getDefaultName() {
        return "SNR";
    }

    @Override
    public String getHintText() {
        return "Estimation of the signal-to-noise ratio on the area of a segmented object";
    }
    
}
