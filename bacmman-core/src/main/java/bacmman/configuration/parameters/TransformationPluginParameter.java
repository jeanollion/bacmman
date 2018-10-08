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

import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.MultichannelTransformation;

import java.util.ArrayList;
import java.util.List;
import org.json.simple.JSONObject;
import bacmman.plugins.Transformation;

/**
 *
 * @author Jean Ollion
 */
public class TransformationPluginParameter<T extends Transformation> extends PluginParameter<T> {
    //List configurationData;
    ChannelImageParameter inputChannel = null;
    ChannelImageParameter outputChannel = null;
    boolean allChannels = false;
    //Parameter inputTimePoints;
    
    @Override
    public JSONObject toJSONEntry() {
        JSONObject res = super.toJSONEntry();
        if (inputChannel!=null) res.put("inputChannel", inputChannel.toJSONEntry());
        if (outputChannel!=null) res.put("outputChannel", outputChannel.toJSONEntry());
        //if (configurationData!=null && !configurationData.isEmpty()) res.put("configurationData", JSONUtils.toJSONList(configurationData));
        return res;
    }
    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        super.initFromJSONEntry(jsonEntry);
        JSONObject jsonO = (JSONObject)jsonEntry;
        if (jsonO.containsKey("inputChannel")) {
            if (inputChannel==null) initInputChannel();
            inputChannel.initFromJSONEntry(jsonO.get(("inputChannel")));
        }
        if (jsonO.containsKey("outputChannel")) {
            if (outputChannel==null) initOutputChannel(null);
            outputChannel.initFromJSONEntry(jsonO.get(("outputChannel")));
        }
        //if (jsonO.containsKey("configurationData")) configurationData = (List)jsonO.get("configurationData");
    }
    
    public TransformationPluginParameter(String name, Class<T> pluginType, boolean allowNoSelection) {
        super(name, pluginType, allowNoSelection);
    }
    
    public TransformationPluginParameter(String name, Class<T> pluginType, String defaultMethod, boolean allowNoSelection) {
        super(name, pluginType, defaultMethod, allowNoSelection);
    }
    
    // constructeur désactivé car la methode setPlugin a besoin de l'experience
    /*public TransformationPluginParameter(String name, boolean allowNoSelection, Class<T> pluginType, T pluginInstance) {
        super(name, allowNoSelection, pluginType, pluginInstance);
    }*/
    private void initInputChannel() {
        inputChannel = new ChannelImageParameter("Configuration Channel", -1);
    }
    private void initOutputChannel(int... selectedChannels) {
        outputChannel = new ChannelImageParameter("Channels on which apply transformation", selectedChannels);
    }
    @Override 
    public TransformationPluginParameter<T> setPlugin(T pluginInstance) {
        if (pluginInstance instanceof ConfigurableTransformation) initInputChannel();
        else inputChannel = null;
        
        if (pluginInstance instanceof MultichannelTransformation) {  
            switch (((MultichannelTransformation)pluginInstance).getOutputChannelSelectionMode()) {
                case MULTIPLE:
                    initOutputChannel(null);
                    break;
                case SINGLE:
                    initOutputChannel(-1);
                    break;
                case ALL:
                    allChannels=true;
                    outputChannel=null;
                    break;
                case SAME:
                    if (inputChannel==null) throw new IllegalArgumentException("Invalid Transformation: Output channel == SAME & not configurable transformation");
                    outputChannel=null;
                    allChannels=false;
                    break;
            }
        }
        super.setPlugin(pluginInstance);
        //configurationData = ParameterUtils.duplicateConfigurationDataList(pluginInstance.getConfigurationData());
        return this;
    }
    
    public void setConfigurationData(List configurationData) {
        //this.configurationData = ParameterUtils.duplicateConfigurationDataList(configurationData);
    }
    
    public void setOutputChannel(int... channelIdx) { // null -> all selected OR same channel selected
        if (outputChannel!=null) outputChannel.setSelectedIndicies(channelIdx);
    }
    
    public void setInputChannel(int channelIdx) {
        if (inputChannel!=null) this.inputChannel.setSelectedIndex(channelIdx);
    }
    
    public int[] getOutputChannels() { // if null -> all selected or same as input...
        if (outputChannel==null) {
            if (allChannels) return null;
            else return inputChannel.getSelectedItems();
        }
        else return outputChannel.getSelectedItems();
    }
    
    public int getInputChannel() {
        if (inputChannel!=null) return inputChannel.getSelectedIndex();
        return -1;
    }
    
    @Override
    protected void initChildList() {
        ArrayList<Parameter> p = new ArrayList<>(2+(pluginParameters!=null?pluginParameters.size():0));
        if (inputChannel!=null) p.add(inputChannel);
        if (outputChannel!=null) p.add(outputChannel);
        if (pluginParameters!=null) p.addAll(pluginParameters);
        if (additionalParameters!=null) p.addAll(additionalParameters);
        //System.out.println("init child list! for: "+toString()+ " number of pp:"+(pluginParameters==null?0:pluginParameters.length)+" number total:"+p.size());
        super.initChildren(p);
    }
    
    @Override
    public T instanciatePlugin() {
        T instance = super.instanciatePlugin();
        if (instance!=null) {
            //List target = instance.getConfigurationData();
            //if (target!=null && configurationData!=null) for (Object o : configurationData) target.add(ParameterUtils.duplicateConfigurationData(o));
            //logger.debug("copied configuration data to transformation: {}: config:{}", instance.getClass().getSimpleName(), instance.getConfigurationData());
        }
        return instance;
    }
    @Override
    public boolean isValid() {
        if (inputChannel!=null && inputChannel.getSelectedIndex()<0) return false; 
        if (outputChannel!=null && outputChannel.getSelectedIndex()<0) return false; 
        return super.isValid();
    }
    @Override
    public boolean sameContent(Parameter other) {
        if (!super.sameContent(other)) return false;
        if (other instanceof TransformationPluginParameter) {
            TransformationPluginParameter otherPP = (TransformationPluginParameter) other;
            if ((outputChannel==null && otherPP.outputChannel!=null) || (outputChannel!=null && !outputChannel.sameContent(otherPP.outputChannel))) {
                logger.debug("transformationPP {}!={} differ in output channel: {} vs {}", name, otherPP.name, outputChannel, otherPP.outputChannel);
                return false;
            }
            if ((inputChannel==null && otherPP.inputChannel!=null) || (inputChannel!=null && !inputChannel.sameContent(otherPP.inputChannel))) {
                logger.debug("transformationPP {}!={} differ in input channel: {} vs {}", name, otherPP.name, inputChannel, otherPP.inputChannel);
                return false;
            }
            return true;
        } else return false;
    }
    
    @Override
    public void setContentFrom(Parameter other) {
        super.setContentFrom(other);
        if (other instanceof TransformationPluginParameter && ((TransformationPluginParameter)other).getPluginType().equals(getPluginType())) {
            TransformationPluginParameter otherPP = (TransformationPluginParameter) other;
            //this.configurationData=ParameterUtils.duplicateConfigurationDataList(otherPP.configurationData);
            boolean init = false;
            if (otherPP.inputChannel==null) inputChannel=null;
            else {
                if (inputChannel==null) initInputChannel();
                inputChannel.setContentFrom(otherPP.inputChannel);
                init = true;
            }
            if (otherPP.outputChannel==null) this.outputChannel=null;
            else {
                if (outputChannel==null) initOutputChannel(((MultichannelTransformation)otherPP).getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.SINGLE?-1:null);
                this.outputChannel=otherPP.outputChannel.duplicate();
                init = true;
            }
            if (init) initChildList();
        } else throw new IllegalArgumentException("wrong parameter type");
    }
    
    @Override
    public TransformationPluginParameter<T> duplicate() {
        TransformationPluginParameter res = new TransformationPluginParameter(name, getPluginType(), allowNoSelection);
        res.setListeners(listeners);
        res.setContentFrom(this);
        return res;
    }

}
