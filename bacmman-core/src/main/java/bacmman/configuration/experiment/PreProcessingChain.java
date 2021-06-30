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
package bacmman.configuration.experiment;

import bacmman.configuration.parameters.*;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.MultichannelTransformation;
import java.util.List;
import org.json.simple.JSONObject;
import bacmman.plugins.Transformation;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */
public class PreProcessingChain extends ContainerParameterImpl<PreProcessingChain> {
    BooleanParameter useImageScale = new BooleanParameter("Voxel Calibration", "Use Image Calibration", "Custom Calibration", true).setHint("Voxel calibration (voxel size in x, y, z axis). If <em>Custom calibration</em> is set, the image calibration will be overwritten.<br />Pre-processing must be re-run after modifying the calibration");
    BoundedNumberParameter scaleXY = new BoundedNumberParameter("Scale XY", 5, 1, 0.00001, null);
    BoundedNumberParameter scaleZ = new BoundedNumberParameter("Scale Z", 5, 1, 0.00001, null);
    ConditionalParameter<Boolean> imageScaleCond = new ConditionalParameter<>(useImageScale).setActionParameters(false, scaleXY, scaleZ);
    BoundedNumberParameter frameDuration= new BoundedNumberParameter("Time Step", 4, 4, 0, null).setHint("Time between two frames. This parameter is used only when the time step cannot be found in the image metadata");
    IntervalParameter trimFrames = new IntervalParameter("Trim Frames", 0, 0, 0, 0, 0).setHint("Frame interval (inclusive) to be pre-processed. [0;0] = no trimming");
    SimpleListParameter<TransformationPluginParameter<Transformation>> transformations = new SimpleListParameter<>("Pre-Processing pipeline", new TransformationPluginParameter<>("Transformation", Transformation.class, false));
    final boolean template;
    @Override
    public JSONObject toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("scaleXY", scaleXY.toJSONEntry());
        res.put("scaleZ", scaleZ.toJSONEntry());
        res.put("useImageScale", useImageScale.toJSONEntry());
        res.put("frameDuration", frameDuration.toJSONEntry());
        res.put("trimFrames", trimFrames.toJSONEntry());
        res.put("transformations", transformations.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        scaleXY.initFromJSONEntry(jsonO.get("scaleXY"));
        scaleZ.initFromJSONEntry(jsonO.get("scaleZ"));
        useImageScale.initFromJSONEntry(jsonO.get("useImageScale"));
        frameDuration.initFromJSONEntry(jsonO.get("frameDuration"));
        if (jsonO.containsKey("trimFrames")) trimFrames.initFromJSONEntry(jsonO.get("trimFrames"));
        transformations.initFromJSONEntry(jsonO.get("transformations"));
    }
    
    public PreProcessingChain(String name, boolean template) {
        super(name);
        this.template=template;
        //logger.debug("new PPC: {}", name);
        initChildList();
        if (template) this.trimFrames.setUpperBound(null);
        else {
            Consumer<IntervalParameter> pl = sourceParameter -> {
                Position pos = ParameterUtils.getMicroscopyField(sourceParameter);
                if (pos != null) {
                    pos.flushImages(true, true);
                    pos.setDefaultTimePointBounds();
                }
            };
            trimFrames.addListener(pl);
        }
    }
    public boolean isEmpty() {
        return transformations.isEmpty();
    }
    public PreProcessingChain setCustomScale(double scaleXY, double scaleZ) {
        if (Double.isNaN(scaleXY) || Double.isInfinite(scaleXY)) throw new IllegalArgumentException("Invalid scale value");
        if (scaleXY<=0) throw new IllegalArgumentException("Scale should be >=0");
        if (scaleZ<=0) scaleZ=1;
        useImageScale.setSelected(false); // custom calibration
        this.scaleXY.setValue(scaleXY);
        this.scaleZ.setValue(scaleZ);
        return this;
    }
    @Override 
    public boolean isEmphasized() {
        return false;
    }
    public boolean useCustomScale() {return !useImageScale.getSelected();}
    public double getScaleXY() {return scaleXY.getValue().doubleValue();}
    public double getScaleZ() {return scaleZ.getValue().doubleValue();}
    public double getFrameDuration() {return frameDuration.getValue().doubleValue();}
    public void setFrameDuration(double frameDuration) {
        this.frameDuration.setValue(frameDuration);
    }
    @Override
    protected void initChildList() {
        //logger.debug("PreProc chain: {}, init list..", name);
        super.initChildren(imageScaleCond, transformations, trimFrames, frameDuration);
    }
    
    public List<TransformationPluginParameter<Transformation>> getTransformations(boolean onlyActivated) {
        return onlyActivated ? transformations.getActivatedChildren() : transformations.getChildren();
    }
    
    public void removeAllTransformations() {
        transformations.removeAllElements();
    }
    public SimpleListParameter<TransformationPluginParameter<Transformation>> getTransformations() {
        return transformations;
    }
    public void setTrimFrames(int startFrame, int endFrame) {
        if (startFrame>endFrame) throw new IllegalArgumentException("start frame should be inferior to end frame");
        this.trimFrames.setValues(startFrame, endFrame);
    }
    
    /**
     * 
     * @param inputChannel channel on which compute transformation parameters
     * @param outputChannel channel(s) on which apply transformation (null = all channels or same channel, depending {@link MultichannelTransformation#getOutputChannelSelectionMode() })
     * @param transformation 
     */
    public TransformationPluginParameter<Transformation> addTransformation(int idx, int inputChannel, int[] outputChannel, Transformation transformation) {
        if (inputChannel<-1 && (transformation instanceof ConfigurableTransformation) || (transformation instanceof MultichannelTransformation && ((MultichannelTransformation)transformation).getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.SAME)) throw new IllegalArgumentException("Input channel should be >=0");
        Experiment xp = ParameterUtils.getExperiment(this);
        if (xp!=null &&  inputChannel>=xp.getChannelImageCount(true)) throw new IllegalArgumentException("Input channel should be inferior to number of detection channels ("+xp.getChannelImageCount(true)+")");
        TransformationPluginParameter<Transformation> tpp= new TransformationPluginParameter<>("Transformation", Transformation.class, false);
        transformations.insert(tpp, idx);
        tpp.setPlugin(transformation);
        tpp.setInputChannel(inputChannel);
        if (transformation instanceof MultichannelTransformation) {
            MultichannelTransformation mct = (MultichannelTransformation)transformation;
            if (outputChannel==null && (mct.getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.MULTIPLE || mct.getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.SINGLE) ) outputChannel = new int[]{inputChannel};
            tpp.setOutputChannel(outputChannel);
        }
        return tpp;
    }
    public TransformationPluginParameter<Transformation> addTransformation(int inputChannel, int[] outputChannel, Transformation transformation) {
        return addTransformation(this.transformations.getChildCount(), inputChannel, outputChannel, transformation);
    }

}
