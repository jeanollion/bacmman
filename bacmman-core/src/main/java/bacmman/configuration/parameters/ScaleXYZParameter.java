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
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.json.simple.JSONObject;


/**
 *
 * @author Jean Ollion
 */

public class ScaleXYZParameter extends ContainerParameterImpl<ScaleXYZParameter> implements ParameterWithLegacyInitialization<ScaleXYZParameter, Double> {
    BoundedNumberParameter scaleXY = new BoundedNumberParameter("ScaleXY (pix)", 3, 1, 0, null).setEmphasized(true);
    BoundedNumberParameter scaleZ = new BoundedNumberParameter("ScaleZ (pix)", 3, 1, 0, null).setEmphasized(true);
    BooleanParameter useImageCalibration = new BooleanParameter("Use image calibration for Z-scale", true).setEmphasized(true);
    ConditionalParameter<Boolean> cond = new ConditionalParameter<>(useImageCalibration).setActionParameters(false, scaleZ);
    
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
        if (jsonEntry instanceof Number) {
            scaleXY.setValue((Number)jsonEntry);
            useImageCalibration.setSelected(true);
        } else {
            JSONObject jsonO = (JSONObject) jsonEntry;
            scaleXY.initFromJSONEntry(jsonO.get("scaleXY"));
            scaleZ.initFromJSONEntry(jsonO.get("scaleZ"));
            useImageCalibration.initFromJSONEntry(jsonO.get("useImageCalibration"));
        }
    }

    public ScaleXYZParameter setDecimalPlaces(int decimalPlaces) {
        scaleXY.setDecimalPlaces(decimalPlaces);
        scaleZ.setDecimalPlaces(decimalPlaces);
        return this;
    }
    public ScaleXYZParameter setParameterName(String nameXY, String nameZ) {
        scaleXY.setName(nameXY);
        scaleZ.setName(nameZ);
        return this;
    }

    public ScaleXYZParameter(String name) {
        super(name);
    }
    public ScaleXYZParameter(String name, double scaleXY) {
        super(name);
        this.scaleXY.setValue(scaleXY);
    }
    public ScaleXYZParameter(String name, double scaleXY, double scaleZ, boolean useCalibration) {
        super(name);
        this.scaleXY.setValue(scaleXY);
        this.scaleZ.setValue(scaleZ);
        useImageCalibration.setSelected(useCalibration);
    }
    public ScaleXYZParameter setNumberParameters(Number lowerBound, Number upperBound, int decimalPlaces, boolean toXY, boolean toZ) {
        if (toXY) {
            scaleXY.setUpperBound(upperBound);
            scaleXY.setLowerBound(lowerBound);
            scaleXY.setDecimalPlaces(decimalPlaces);
        }
        if (toZ) {
            scaleZ.setUpperBound(upperBound);
            scaleZ.setLowerBound(lowerBound);
            scaleZ.setDecimalPlaces(decimalPlaces);
        }
        return this;
    }

    @Override
    protected void initChildList() {
        super.initChildren(scaleXY, cond);
    }
    public double getScaleXY() {
        return scaleXY.getValue().doubleValue();
    }
    public double getScaleZ(double calXY, double calZ) {
        if (useImageCalibration.getSelected()) {
            return calXY * getScaleXY() / calZ;
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

    // legacy init interface
    @Override
    public void legacyInit() {
        if (legacyInitValue != null) scaleXY.setValue(legacyInitValue);
        if (this.legacyInitFun!=null && legacyInitParam!=null) legacyInitFun.accept(legacyInitParam, this);
    }
    Parameter[] legacyInitParam;
    BiConsumer<Parameter[], ScaleXYZParameter> legacyInitFun;
    Double legacyInitValue;
    @Override
    public Parameter[] getLegacyParameters() {
        return legacyInitParam;
    }

    @Override
    public ScaleXYZParameter setLegacyParameter(BiConsumer<Parameter[], ScaleXYZParameter> setValue, Parameter... p) {
        this.legacyInitParam = p;
        this.legacyInitFun = setValue;
        return this;
    }

    @Override
    public ScaleXYZParameter setLegacyInitializationValue(Double value) {
        legacyInitValue = value;
        return this;
    }
}
