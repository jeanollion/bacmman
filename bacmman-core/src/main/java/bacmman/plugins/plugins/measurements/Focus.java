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

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.processing.ImageFeatures;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Measurement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 *
 * @author Jean Ollion
 */
public class Focus implements Measurement, DevPlugin {
    ObjectClassParameter structure = new ObjectClassParameter("Structure");
    NumberParameter scale = new BoundedNumberParameter("Gradient Scale", 1, 2, 1, null);
    
    public Focus() {}
    public Focus(int structureIdx) {
        this.structure.setSelectedIndex(structureIdx);
    }
    public Focus setGradientScale(double scale) {
        this.scale.setValue(scale);
        return this;
    }
    @Override
    public int getCallObjectClassIdx() {
        return structure.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>(2);
        res.add(new MeasurementKeyObject("Focus", structure.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("Focus_"+structure.getSelectedClassIdx(), structure.getParentObjectClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        int structureIdx = structure.getSelectedClassIdx();
        Image input = object.getRawImage(structure.getParentObjectClassIdx());
        double mid = input.sizeZ()/2.0;
        if (input.sizeZ()>1) {
            throw new RuntimeException("Focus measurement cannot be run on 3D images");
            /*List<Double> centers = Utils.transform(object.getChildren(structureIdx), o->{
                double[] center = object.getRegion().getGeomCenter(false);
                if (center.length==3) return center[2];
                else return mid;
            });
            input = input.getZPlane((int)(ArrayUtil.mean(centers)+0.5));*/
        }
        Image grad = ImageFeatures.getGradientMagnitude(input, scale.getValue().doubleValue(), false);
        double[] grad_int_bCount_count= new double[4];
        object.getChildren(structureIdx).forEachOrdered(o-> {
            Set<Voxel> contour = o.getRegion().getContour();
            for (Voxel v : contour) grad_int_bCount_count[0]+=grad.getPixelWithOffset(v.x, v.y, v.z);
            grad_int_bCount_count[2] += contour.size();
            grad_int_bCount_count[3]+=o.getRegion().size();
            grad_int_bCount_count[1]+= BasicMeasurements.getSum(o.getRegion(), input);
        });
        grad_int_bCount_count[0]/=grad_int_bCount_count[2];
        grad_int_bCount_count[1]/=grad_int_bCount_count[3];
        double value = grad_int_bCount_count[0] / grad_int_bCount_count[1];
        object.getChildren(structureIdx).forEach(o-> o.getMeasurements().setValue("Focus", value));
        object.getMeasurements().setValue("Focus_"+structureIdx, value);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{structure, scale};
    }
    
}
