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

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.processing.Filters;
import bacmman.plugins.Hint;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.plugins.object_feature.IntensityMeasurementCore;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.SymetricalPair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class SNR extends IntensityMeasurement implements Hint {
    public enum FORMULA{AMPLITUDE_NORM_STD("(F-B)/std(B)"), AMPLITUDE("F-B");
        public final String name;
        FORMULA(String name) {
            this.name = name;
        }
    }
    public enum FOREGROUND_FORMULA{MEAN("mean"), MAX("max"), CENTER("value at center"), MEDIAN("median");
        public final String name;
        FOREGROUND_FORMULA(String name) {
            this.name = name;
        }
    }
    public enum BACKGROUND_FORMULA{MEAN("mean"), MEDIAN("median");
        public final String name;
        BACKGROUND_FORMULA(String name) {
            this.name = name;
        }
    }
    protected ObjectClassParameter backgroundStructure = new ObjectClassParameter("Background Object Class", -2, true, false).setNoSelectionString("Viewfield").setEmphasized(true);
    protected ScaleXYZParameter dilateExcluded = new ScaleXYZParameter("Dilatation radius for foreground object", 1, 0, false).setHint("Dilated foreground objects will be excluded from background mask").setLegacyParameter(new BoundedNumberParameter("Dilatation radius for foreground object", 1, 1, 0, null), p->((NumberParameter)p).getDoubleValue());

    protected ScaleXYZParameter erodeBorders = new ScaleXYZParameter("Radius for background mask erosion", 1, 0, false).setHint("Background mask will be eroded in order to avoid border effects (after removing foreground objects)").setLegacyParameter(new BoundedNumberParameter("Radius for background mask erosion", 1, 1, 0, null), p->((NumberParameter)p).getDoubleValue());
    protected EnumChoiceParameter<FORMULA> formula = new EnumChoiceParameter<>("Formula", FORMULA.values(), FORMULA.AMPLITUDE_NORM_STD, e->e.name).setEmphasized(true).setHint("formula for SNR estimation. F = Foreground, B = background, std = standard-deviation");
    protected EnumChoiceParameter<FOREGROUND_FORMULA> foregroundFormula = new EnumChoiceParameter<>("Foreground", FOREGROUND_FORMULA.values(), FOREGROUND_FORMULA.MEAN, e->e.name).setEmphasized(true).setHint("Foreground estimation method");
    protected EnumChoiceParameter<BACKGROUND_FORMULA> backgroundFormula = new EnumChoiceParameter<>("Background", BACKGROUND_FORMULA.values(), BACKGROUND_FORMULA.MEAN, e->e.name).setEmphasized(true).setHint("Background estimation method");

    @Override public Parameter[] getParameters() {return new Parameter[]{intensity, backgroundStructure, formula, foregroundFormula, backgroundFormula, dilateExcluded, erodeBorders};}
    HashMap<Region, Region> foregroundMapBackground;
    Offset foregroundOffset;
    Offset parentOffsetRev;
    public SNR() {}
    public SNR(int backgroundStructureIdx) {
        backgroundStructure.setSelectedClassIdx(backgroundStructureIdx);
    }
    public SNR setBackgroundObjectStructureIdx(int structureIdx) {
        backgroundStructure.setSelectedClassIdx(structureIdx);
        return this;
    }
    public SNR setRadii(double dilateForegroundRadius, double erodeBackgroundMaskRadius) {
        this.dilateExcluded.setScaleXY(dilateForegroundRadius);
        this.erodeBorders.setScaleXY(erodeBackgroundMaskRadius);
        return this;
    }
    public SNR setFormula(FORMULA formula, FOREGROUND_FORMULA foregroundFormula, BACKGROUND_FORMULA backgroundFormula) {
        this.formula.setSelectedEnum(formula);
        this.foregroundFormula.setSelectedEnum(foregroundFormula);
        this.backgroundFormula.setSelectedEnum(backgroundFormula);
        return this;
    }
    @Override public IntensityMeasurement setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation foregroundPopulation) {
        super.setUp(parent, childStructureIdx, foregroundPopulation);
        if (foregroundPopulation.getRegions().isEmpty()) return this;
        if (!foregroundPopulation.isAbsoluteLandmark()) foregroundOffset = parent.getBounds(); // the step it still at processing, thus their offset of objects is related to their direct parent
        else foregroundOffset = new SimpleOffset(0, 0, 0); // absolute offsets
        parentOffsetRev = new SimpleOffset(parent.getBounds()).reverseOffset();
        
        List<Region> backgroundObjects;
        if (backgroundStructure.getSelectedClassIdx()!=super.parent.getStructureIdx()) {
            backgroundObjects = ((SegmentedObject)parent).getChildRegionPopulation(backgroundStructure.getSelectedClassIdx()).getRegions();
        } else {
            backgroundObjects = new ArrayList<>(1);
            backgroundObjects.add(parent.getRegion());
        }
        if (backgroundObjects.get(0).is2D() && !foregroundPopulation.getRegions().get(0).is2D()) { // background is 2D and foreground is 3D: make background objects 3D
            Image raw = parent.getRawImage(getIntensityStructure());
            int nZ = raw.sizeZ();
            int offZ = raw.zMin();
            backgroundObjects = backgroundObjects.stream().map(b -> {
                ImageInteger plane = b.getMaskAsImageInteger();
                ImageInteger<?> mask = Image.mergeZPlanes(IntStream.range(0, nZ).mapToObj(i -> plane).collect(Collectors.toList()));
                mask.translate(0, 0, offZ);
                Region res = new Region(mask, b.getLabel(), false).setIsAbsoluteLandmark(b.isAbsoluteLandMark());
                //logger.debug("inflate background: {} -> {} size: {} -> {}", b.getBounds(), res.getBounds(), b.size(), res.size());
                return res;
            }).collect(Collectors.toList());
        }
        double erodeRad= this.erodeBorders.getScaleXY();
        double erodeRadZ = this.erodeBorders.getScaleZ(parent.getScaleXY(), parent.getScaleZ());
        double dilRad = this.dilateExcluded.getScaleXY();
        double dilRadZ = this.dilateExcluded.getScaleZ(parent.getScaleXY(), parent.getScaleZ());
        // assign parents to children by inclusion
        HashMapGetCreate<Region, List<SymetricalPair<Region>>> backgroundMapForeground = new HashMapGetCreate<>(backgroundObjects.size(), new HashMapGetCreate.ListFactory<>());
        for (Region o : foregroundPopulation.getRegions()) {
            Region p = o.getMostOverlappingRegion(backgroundObjects, foregroundOffset, null); // parents are in absolute offset
            if (p!=null) {
                Region oDil = o;
                if (dilRad>0)  {
                    ImageInteger oMask = o.getMaskAsImageInteger();
                    oMask = Filters.binaryMax(oMask, null, Filters.getNeighborhood(dilRad, dilRadZ, oMask), true, false);
                    oDil = new Region(oMask, o.getLabel(), o.is2D()).setIsAbsoluteLandmark(o.isAbsoluteLandMark());
                }
                backgroundMapForeground.getAndCreateIfNecessary(p).add(new SymetricalPair<>(o, oDil));
            }
        }
        
        // remove foreground objects from background mask & erode it
        foregroundMapBackground = new HashMap<>();
        for (Region backgroundRegion : backgroundObjects) {
            List<SymetricalPair<Region>> children = backgroundMapForeground.get(backgroundRegion);
            if (children!=null) {
                ImageInteger mask = backgroundRegion.getMask() instanceof ImageInteger ? backgroundRegion.getMaskAsImageInteger().duplicate() : backgroundRegion.getMaskAsImageInteger();
                for (Pair<Region, Region> o : backgroundMapForeground.get(backgroundRegion)) o.value.draw(mask, 0, foregroundOffset);// was with offset: absolute = 0 / relative = parent
                if (erodeRad>0) {
                    ImageByte maskErode = Filters.binaryMin(mask, null, Filters.getNeighborhood(erodeRad, erodeRadZ, mask), false);
                    if (maskErode.count()>0) mask = maskErode;
                }
                Region modifiedBackgroundRegion = new Region(mask, backgroundRegion.getLabel(), backgroundRegion.is2D()).setIsAbsoluteLandmark(true);
                for (Pair<Region, Region> o : children) foregroundMapBackground.put(o.key, modifiedBackgroundRegion);
                
                //ImageWindowManagerFactory.showImage( mask);
            }
        }
        //logger.debug("init SNR: parent {}, os: {} foreground with background: {}/{}", parent, childStructureIdx, foregroundMapBackground.size(), foregroundPopulation.getRegions().size());
        return this;
    }
    @Override
    public double performMeasurement(Region object) {

        if (core==null) synchronized(this) {
            if (core==null) {
                setUpOrAddCore(null, null);
            }
        }
        Region bckObject;
        if (foregroundMapBackground==null) bckObject = super.parent.getRegion();
        else bckObject=this.foregroundMapBackground.get(object);
        if (bckObject==null) return Double.NaN;
        IntensityMeasurementCore.IntensityMeasurements iBck = super.core.getIntensityMeasurements(bckObject);
        IntensityMeasurementCore.IntensityMeasurements fore = super.core.getIntensityMeasurements(object);
        return getValue(getForeValue(fore), getBackValue(iBck), iBck.sd);
    }
    public double[] getBackgroundMeanSD(Region foregroundRegion) {
        if (core==null) synchronized(this) {
            if (core==null) {
                setUpOrAddCore(null, null);
            }
        }
        Region parentObject;
        if (foregroundMapBackground==null || foregroundRegion==null) parentObject = super.parent.getRegion();
        else parentObject=this.foregroundMapBackground.get(foregroundRegion);
        IntensityMeasurementCore.IntensityMeasurements iParent = super.core.getIntensityMeasurements(parentObject);
        return new double[]{iParent.mean, iParent.sd};
    }
    protected double getBackValue(IntensityMeasurementCore.IntensityMeasurements back) {
        switch (backgroundFormula.getSelectedEnum()) {
            case MEAN:
            default: return back.mean;
            case MEDIAN: return back.getMedian();
        }
    }
    protected double getForeValue(IntensityMeasurementCore.IntensityMeasurements fore) {
        switch (foregroundFormula.getSelectedEnum()) {
            case MAX: return fore.max;
            case CENTER: return fore.getValueAtCenter();
            case MEDIAN: return fore.getMedian();
            case MEAN:
            default: return fore.mean;
        }     
    }
    
    protected double getValue(double fore, double back, double backSd) {
        switch(formula.getSelectedEnum()) {
            case AMPLITUDE_NORM_STD:
            default: return (fore-back)/backSd;
            case AMPLITUDE: return fore-back;
        }
    }

    @Override public String getDefaultName() {
        return "SNR";
    }

    @Override
    public String getHintText() {
        return "Estimation of the signal-to-noise ratio on the area of a segmented object";
    }
    
}
