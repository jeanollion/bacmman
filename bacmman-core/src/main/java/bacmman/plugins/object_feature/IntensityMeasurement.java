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

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import bacmman.image.ImageMask;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SimpleObjectFeature;
import bacmman.utils.HashMapGetCreate;

/**
 *
 * @author Jean Ollion
 */
public abstract class IntensityMeasurement extends SimpleObjectFeature implements ObjectFeatureWithCore {
    protected IntensityMeasurementCore core;
    protected ChannelImageParameter channel = new ChannelImageParameter("Channel").setEmphasized(true)
            .setLegacyParameter( (params, cp) -> {
                ObjectClassParameter oc = (ObjectClassParameter)params[0];
                //logger.debug("channel legacy init: {}/{} -> {} ({})", oc.getSelectedClassIdx(), ParameterUtils.getExperiment(cp).getStructureCount(), ParameterUtils.getExperiment(cp).experimentStructure.getChannelIdx(oc.getSelectedClassIdx()), ParameterUtils.getExperiment(cp).getStructureToChannelCorrespondance());
                cp.setChannelFromObjectClass(oc.getSelectedClassIdx());
            },  new ObjectClassParameter("Intensity"))
            .setAutoConfiguration( ChannelImageParameter.defaultAutoConfiguration() );
    protected Image intensityMap;
    protected int z = -1;
    protected Map<Region, Region> regionSlice = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(r -> {
        if (z >= 0 && !r.is2D()) return r.intersectWithZPlane(z, false, false);
        else return r;
    });
    public IntensityMeasurement setIntensityObjectClass(int structureIdx) {
        this.channel.setChannelFromObjectClass(structureIdx);
        return this;
    }
    public IntensityMeasurement setIntensityChannel(int channelIdx) {
        this.channel.setSelectedIndex(channelIdx);
        return this;
    }

    public IntensityMeasurement limitToZ(int z) {
        if (intensityMap != null) throw new RuntimeException("Limit to z should be called before setUp method");
        this.z = z;
        return this;
    }

    @Override
    public int getIntensityChannel() {
        return this.channel.getSelectedClassIdx();
    }
    @Override public IntensityMeasurement setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        super.setUp(parent, childStructureIdx, childPopulation);
        if (z >= 0 && !childPopulation.getRegions().isEmpty() && !childPopulation.getRegions().get(0).is2D()) {
            List<Region> regions = childPopulation.getRegions().stream()
                    .map(regionSlice::get)
                    .filter(Objects::nonNull).collect(Collectors.toList());
            childPopulation.clearLabelMap();
            childPopulation.getRegions().clear();
            childPopulation.addObjects(regions, false);
        }
        this.parent=parent;
        this.intensityMap= parent.getRawImageByChannel(channel.getSelectedIndex());
        if (this.intensityMap==null) throw new RuntimeException("Could not open image of channel "+channel.getSelectedIndex()+". Maybe experiment structure was modified after pre-processing was run ? ");
        return this;
    }
    
    @Override public Parameter[] getParameters() {return new Parameter[]{channel};}

    @Override
    public void setUpOrAddCore(Map<Image, IntensityMeasurementCore> availableCores, BiFunction<Image, ImageMask, Image> filters) {
        IntensityMeasurementCore existingCore = availableCores==null ? null : availableCores.get(intensityMap);
        if (existingCore==null || z>=0) {
            if (core==null) {
                core = new IntensityMeasurementCore().limitToZ(z);
                core.regionMapSlice = regionSlice;
                core.setUp(intensityMap, filters==null ? null : filters.apply(intensityMap, parent.getMask()));
            }
            if (availableCores!=null && z<0) availableCores.put(intensityMap, core); // only set if no z, for reuse
        } else core=existingCore;
    }
}
