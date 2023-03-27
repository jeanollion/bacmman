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
package bacmman.plugins.object_feature;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import bacmman.image.ImageMask;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SimpleObjectFeature;

/**
 *
 * @author Jean Ollion
 */
public abstract class IntensityMeasurement extends SimpleObjectFeature implements ObjectFeatureWithCore {
    protected IntensityMeasurementCore core;
    protected ObjectClassParameter intensity = new ObjectClassParameter("Intensity").setEmphasized(true).setAutoConfiguration(ObjectClassParameter.defaultAutoConfiguration()).setHint("The channel image associated to the selected object class will be used for the intensity measurement");
    protected Image intensityMap;
    protected int z = -1;
    protected boolean usePreFilteredImage = false;
    public IntensityMeasurement setIntensityStructure(int structureIdx) {
        this.intensity.setSelectedClassIdx(structureIdx);
        return this;
    }
    public IntensityMeasurement setUsePreFilteredImage() {
        this.usePreFilteredImage = true;
        return this;
    }

    public IntensityMeasurement limitToZ(int z) {
        this.z = z;
        return this;
    }

    @Override
    public int getIntensityStructure() {
        return this.intensity.getSelectedClassIdx();
    }
    @Override public IntensityMeasurement setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        super.setUp(parent, childStructureIdx, childPopulation);
        this.parent=parent;
        if (intensity.getSelectedIndex()==-1) intensity.setSelectedIndex(childStructureIdx);
        this.intensityMap=usePreFilteredImage ? parent.getPreFilteredImage(intensity.getSelectedIndex()) : parent.getRawImage(intensity.getSelectedIndex());
        if (this.intensity==null) throw new RuntimeException("Could not open raw image of object class "+intensity.getSelectedIndex()+". Maybe experiment structure was modified after pre-processing was run ? ");
        return this;
    }
    
    @Override public Parameter[] getParameters() {return new Parameter[]{intensity};}

    @Override
    public void setUpOrAddCore(Map<Image, IntensityMeasurementCore> availableCores, BiFunction<Image, ImageMask, Image> filters) {
        IntensityMeasurementCore existingCore = availableCores==null ? null : availableCores.get(intensityMap);
        if (existingCore==null || z>=0) {
            if (core==null) {
                core = new IntensityMeasurementCore().limitToZ(z);
                core.setUp(intensityMap, filters==null ? null : filters.apply(intensityMap, parent.getMask()));
            }
            if (availableCores!=null && z==-1) availableCores.put(intensityMap, core);
        } else core=existingCore;
    }
}
