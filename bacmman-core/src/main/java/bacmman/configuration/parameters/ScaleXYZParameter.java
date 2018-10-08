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
package bacmman.configuration.parameters;

import java.util.Arrays;
import org.json.simple.JSONObject;


/**
 *
 * @author Jean Ollion
 */

public class ScaleXYZParameter extends ContainerParameterImpl<ScaleXYZParameter> {
    BoundedNumberParameter scaleXY = new BoundedNumberParameter("ScaleXY (pix)", 3, 1, 0, null);
    BoundedNumberParameter scaleZ = new BoundedNumberParameter("ScaleZ (pix)", 3, 1, 0, null);
    BooleanParameter useImageCalibration = new BooleanParameter("Use image calibration for Z-scale", true);
    ConditionalParameter cond = new ConditionalParameter(useImageCalibration).setActionParameters("false", new Parameter[]{scaleZ}, false);
    
    @Override
    public Object toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("scaleXY", scaleXY.toJSONEntry());
        res.put("scaleZ", scaleZ.toJSONEntry());
        res.put("useImageCalibration", useImageCalibration.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        scaleXY.initFromJSONEntry(jsonO.get("scaleXY"));
        scaleZ.initFromJSONEntry(jsonO.get("scaleZ"));
        useImageCalibration.initFromJSONEntry(jsonO.get("useImageCalibration"));
    }
    
    public ScaleXYZParameter(String name) {
        super(name);
    }
    public ScaleXYZParameter(String name, double scaleXY, double scaleZ, boolean useCalibration) {
        super(name);
        this.scaleXY.setValue(scaleXY);
        this.scaleZ.setValue(scaleZ);
        useImageCalibration.setSelected(useCalibration);
    }

    @Override
    protected void initChildList() {
        super.initChildren(scaleXY, cond);
    }
    public double getScaleXY() {
        return scaleXY.getValue().doubleValue();
    }
    public double getScaleZ(double theoScaleXY, double theoScaleZ) {
        if (useImageCalibration.getSelected()) {
            return theoScaleZ * getScaleXY() / theoScaleXY;
        } else return scaleZ.getValue().doubleValue();
    }
    public ScaleXYZParameter setScaleXY(double scaleXY) {
        this.scaleXY.setValue(scaleXY);
        return this;
    }
    public ScaleXYZParameter setScaleZ(double scaleZ) {
        if (Double.isNaN(scaleZ) || Double.isInfinite(scaleZ) || scaleZ<=0) useImageCalibration.setSelected(true);
        else {
            useImageCalibration.setSelected(false);
            this.scaleZ.setValue(scaleZ);
        }
        return this;
    }
    public ScaleXYZParameter setUseImageCalibration(boolean useImageCal) {
        this.useImageCalibration.setSelected(useImageCal);
        return this;
    }
    public boolean getUseImageCalibration() {
        return this.useImageCalibration.getSelected();
    }
    @Override public boolean sameContent(Parameter other) {
        if (other instanceof ScaleXYZParameter) {
            ScaleXYZParameter otherP = (ScaleXYZParameter) other;
            return ParameterUtils.sameContent(Arrays.asList(new Parameter[]{scaleXY, scaleZ, useImageCalibration}), Arrays.asList(new Parameter[]{otherP.scaleXY, otherP.scaleZ, otherP.useImageCalibration}), "ScaleXYZParameter: "+name+"!="+otherP.name);
        } else return false;
    }
    @Override public void setContentFrom(Parameter other) { // need to override because the super class's method only set the content from children parameters (children parameter = transient conditional parameter)
        if (other instanceof ScaleXYZParameter) {
            ScaleXYZParameter otherP = (ScaleXYZParameter) other;
            scaleXY.setContentFrom(otherP.scaleXY);
            scaleZ.setContentFrom(otherP.scaleZ);
            useImageCalibration.setContentFrom(useImageCalibration);
        } else {
            throw new IllegalArgumentException("wrong parameter type");
        }
    }
    

    
}
