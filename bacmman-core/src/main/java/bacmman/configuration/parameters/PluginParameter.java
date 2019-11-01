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

import static bacmman.configuration.parameters.ChoiceParameter.NO_SELECTION;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.DLengineProvider;
import bacmman.plugins.DLengine;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.plugins.Plugin;
import bacmman.plugins.PluginFactory;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 * @param <T> type of plugin
 */
public class PluginParameter<T extends Plugin> extends ContainerParameterImpl<PluginParameter<T>> implements Deactivatable, ChoosableParameter<PluginParameter<T>> {
    
    public final static HashMapGetCreate<Class<? extends Plugin>, List<String>> PLUGIN_NAMES=new HashMapGetCreate<Class<? extends Plugin>, List<String>>(c -> PluginFactory.getPluginNames(c));
    protected List<Parameter> pluginParameters;
    protected String pluginName=NO_SELECTION;
    private Class<T> pluginType;
    protected String pluginTypeName;
    protected boolean allowNoSelection;
    protected boolean activated=true;
    protected List<Parameter> additionalParameters;
    protected Consumer<T> newInstanceConfiguration;
    @Override
    public JSONObject toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("pluginName", pluginName);
        //res.put("pluginTypeName", pluginTypeName);
        if (!activated) res.put("activated", activated);
        if (additionalParameters!=null && !additionalParameters.isEmpty()) res.put("addParams", JSONUtils.toJSONArrayMap(additionalParameters)); // was: toJSON
        if (pluginParameters!=null && !pluginParameters.isEmpty()) res.put("params", JSONUtils.toJSONArrayMap(pluginParameters)); // was: toJSON
        return res;
    }
    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        setPlugin((String)jsonO.get("pluginName"));
        activated = (Boolean)jsonO.getOrDefault("activated", true);
        if (jsonO.containsKey("addParams") && additionalParameters!=null) {
            Object o = jsonO.get("addParams");
            boolean addParamSet;
            if (JSONUtils.isJSONArrayMap(o)) addParamSet=JSONUtils.fromJSONArrayMap(additionalParameters, (JSONArray)o);
            else addParamSet=JSONUtils.fromJSON(additionalParameters, (JSONArray)o);
        }
        if (jsonO.containsKey("params") && pluginParameters!=null) {
            boolean paramSet;
            Object o = jsonO.get("params");
            if (JSONUtils.isJSONArrayMap(o))
                paramSet=JSONUtils.fromJSONArrayMap(pluginParameters, (JSONArray)o);
            else paramSet=JSONUtils.fromJSON(pluginParameters, (JSONArray)o);
            if (!paramSet) logger.warn("Could not initialize plugin-parameter: {} plugin: {} type: {}, #parameters: {}, JSON parameters: {}", name, this.pluginName, this.pluginType, pluginParameters, jsonO.get("params") );
        }
    }
    
    public PluginParameter(String name, Class<T> pluginType, boolean allowNoSelection) {
        super(name);
        this.pluginType=pluginType;
        this.pluginTypeName=pluginType.getName();
        this.allowNoSelection=allowNoSelection;
        super.initChildren();
    }
    
    public PluginParameter(String name, Class<T> pluginType, String defautlMethod, boolean allowNoSelection) {
        this(name, pluginType, allowNoSelection);
        this.pluginName=defautlMethod; // do not call setPlugin Method because plugins are no initiated at startup
    }
    
    public PluginParameter(String name, Class<T> pluginType, T pluginInstance, boolean allowNoSelection) {
        this(name, pluginType, allowNoSelection);
        setPlugin(pluginInstance);
    }
    public PluginParameter<T> setNewInstanceConfiguration(Consumer<T> newInstanceConfiguration) {
        this.newInstanceConfiguration = newInstanceConfiguration;
        return this;
    }
    public PluginParameter<T> setAdditionalParameters(List<Parameter> additionalParameters) {
        if (additionalParameters.isEmpty()) return this;
        this.additionalParameters=additionalParameters;
        initChildList();
        return this;
    }
    
    public PluginParameter<T> setAdditionalParameters(Parameter... additionalParameters) {
        if (additionalParameters.length==0) return this;
        return setAdditionalParameters(new ArrayList<>(Arrays.asList(additionalParameters)));
    }
    
    public List<Parameter> getAdditionalParameters() {
        return additionalParameters;
    }
    
    public List<Parameter> getParameters() {
        return this.pluginParameters;
    }
    
    public PluginParameter<T> setPlugin(T pluginInstance) {
        if (pluginInstance==null) setPlugin(NO_SELECTION);
        else {
            Parameter[] parameters = pluginInstance.getParameters();
            if (parameters ==null) parameters = new Parameter[0];
            this.pluginParameters=new ArrayList<>(Arrays.asList(parameters));
            initChildList();
            this.pluginName=PluginFactory.getPluginName(pluginInstance.getClass());
        }
        return this;
    }
    
    @Override
    protected void initChildList() {
        if (pluginParameters!=null && additionalParameters!=null) {
            ArrayList<Parameter> al = new ArrayList<>(pluginParameters);
            al.addAll(additionalParameters);
            super.initChildren(al); 
        } else if (pluginParameters!=null) super.initChildren(pluginParameters);
        else if (additionalParameters!=null) super.initChildren(additionalParameters);
        else super.initChildren();
    }
    
    
    public String getPluginName() {
        return pluginName;
    }
    
    public boolean isOnePluginSet() {
        if (pluginParameters==null && !NO_SELECTION.equals(pluginName) && pluginName!=null && pluginName.length()>0) setPlugin(pluginName); // case of constructor with default method
        return pluginName!=null && (!NO_SELECTION.equals(pluginName) || pluginParameters!=null);
    }
    @Override
    public boolean isValid() {
        if (!allowNoSelection && !isOnePluginSet()) return false;
        return super.isValid();
    }
    public void setPlugin(String pluginName) {
        //System.out.println(toString()+ ": set plugin: "+pluginName+ " currentStatus: pluginSet?"+pluginSet+" plugin name: "+pluginName);
        if (pluginName==null || NO_SELECTION.equals(pluginName)) {
            this.pluginParameters=null;
            this.pluginName=NO_SELECTION;
            super.initChildren();
        } else if (pluginParameters==null || !pluginName.equals(this.pluginName)) {
            T instance = PluginFactory.getPlugin(getPluginType(), pluginName);
            if (instance==null) {
                logger.error("Couldn't find plugin: {}", pluginName);
                this.pluginName=ChoiceParameter.NO_SELECTION;
                this.pluginParameters=null;
                return;
            }
            if (newInstanceConfiguration !=null) newInstanceConfiguration.accept(instance);
            setPlugin(instance);
        }
    }

    public T instanciatePlugin() {
        if (!isOnePluginSet()) return null;
        Supplier<T> pluginFactory = () -> {
            T instance = PluginFactory.getPlugin(getPluginType(), pluginName);
            //Parameter.logger.debug("instanciating plugin: type {}, name {} instance==null? {} current parameters {}", pluginType, pluginName, instance==null, pluginParameters.size());
            if (instance==null) return null;
            Parameter[] params = instance.getParameters();
            if (params !=null) {
                ParameterUtils.setContent(Arrays.asList(params), pluginParameters);
                for (Parameter p : params) p.setParent(this);
            }
            if (newInstanceConfiguration !=null) newInstanceConfiguration.accept(instance);
            return instance;
        };
        if (DLengine.class.isAssignableFrom(this.getPluginType())) { // shared instance of DL engine in order to avoid re-loading the model each time
            Experiment xp = ParameterUtils.getExperiment(this);
            if (xp==null) return pluginFactory.get(); // no xp found in tree -> instance cannot be shared
            DLengineProvider dlEngineProvider = xp.getDLengineProvider();
            return (T)dlEngineProvider.getEngine(getPluginType(), pluginParameters, (Supplier<DLengine>)pluginFactory);
        } else return pluginFactory.get();
    }
    
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof PluginParameter) {
            PluginParameter otherPP = (PluginParameter) other;
            if (!otherPP.getPluginType().equals(getPluginType())) {
                logger.trace("PluginParameter: {}!={} differ in plugin type: {} vs {}", this, other, getPluginType(), otherPP.getPluginType());
                return false;
            }
            if ((getPluginName()==null && otherPP.getPluginName()!=null) || (getPluginName()!=null && !getPluginName().equals(otherPP.getPluginName()))) {
                logger.trace("PluginParameter: {}!={} differ in plugin name: {} vs {}", this, other, getPluginName(), otherPP.getPluginName());
                return false;
            }
            if (!ParameterUtils.sameContent(additionalParameters, otherPP.additionalParameters, "PluginParameter: "+name+"!="+otherPP.name+ " Additional Parameters")) return false;
            if (!ParameterUtils.sameContent(children, otherPP.children, "PluginParameter: "+this.toString()+"!="+otherPP.toString()+ " Parameters")) return false;
            if (activated!=otherPP.activated) {
                logger.trace("PluginParameter: {}!={} differ in activation : {} vs {}", this, other, activated, otherPP.activated);
                return false;
            }
            return true;
        }
        return false;
    }
    
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof PluginParameter && ((PluginParameter)other).getPluginType().equals(getPluginType())) {
            bypassListeners=true;
            PluginParameter otherPP = (PluginParameter) other;
            //logger.debug("set content PP: type: {} current: {} other: {}",this.pluginTypeName, this.pluginName, otherPP.pluginName);
            this.activated=otherPP.activated;
            boolean toInit = false;
            if (otherPP.additionalParameters!=null) {
                if (!ParameterUtils.setContent(additionalParameters, otherPP.additionalParameters)) {
                    toInit=true;
                }
            } else this.additionalParameters=null;
            
            boolean setPlug = false;
            if (otherPP.pluginName != null && otherPP.pluginName.equals(this.pluginName) && pluginParameters!=null) {
                if (!ParameterUtils.setContent(pluginParameters, otherPP.pluginParameters)) {
                    setPlug=true;
                }
            } else setPlug=true;
            if (setPlug) {
                toInit = false;
                this.setPlugin(otherPP.pluginName);
                if (isOnePluginSet() && !ParameterUtils.setContent(pluginParameters, otherPP.pluginParameters)) {
                    logger.warn("pluginParameter ({}): parameters for: {}({}) could not be loaded (current: {}/source:{}) (c:{}/s:{})", pluginTypeName, name, otherPP.pluginName, pluginParameters!=null?pluginParameters.size():null, otherPP.pluginParameters!=null?otherPP.pluginParameters.size():null, Utils.toStringList(pluginParameters), Utils.toStringList(otherPP.pluginParameters));
                }
            }
            if (toInit) initChildList();
            bypassListeners=false;
        } else throw new IllegalArgumentException("wrong parameter type");
    }

    @Override
    public PluginParameter<T> duplicate() {
        PluginParameter res = new PluginParameter(name, getPluginType(), allowNoSelection);
        if (additionalParameters!=null) res.setAdditionalParameters(ParameterUtils.duplicateList(additionalParameters));
        res.setContentFrom(this);
        transferStateArguments(this, res);
        res.setNewInstanceConfiguration(newInstanceConfiguration);
        return res;
    }
    
    @Override
    public String toString() {
        String res = ((name!=null && name.length()>0) ? name+ ": " : "")+this.getPluginName();
        if (isActivated()) return res;
        else return "<HTML><S>"+res+"<HTML></S>";
    }
    
    // deactivatable interface
    @Override public boolean isActivated() {
        return activated;
    }

    @Override public void setActivated(boolean activated) {
        this.activated=activated;
    }
    
    // choosable parameter interface
    @Override
    public void setSelectedItem(String item) {
        this.setPlugin(item);
        fireListeners();
    }
    
    public List<String> getPluginNames() {
        return PLUGIN_NAMES.getAndCreateIfNecessarySync(getPluginType());
    }

    @Override
    public String[] getChoiceList() {
        List<String> res = PLUGIN_NAMES.getAndCreateIfNecessarySync(getPluginType());
        return res.toArray(new String[res.size()]);
    }

    @Override
    public int getSelectedIndex() {
        String[] choices = getChoiceList();
        for (int i = 0; i<choices.length; ++i) {
            if (choices[i].equals(pluginName)) return i;
        }
        return -1;
    }

    @Override
    public boolean isAllowNoSelection() {
        return allowNoSelection;
    }
    
    @Override
    public String getNoSelectionString() {
        return ChoiceParameter.NO_SELECTION;
    }
    
    public Class<T> getPluginType() {
        if (pluginType==null) {
            try {
                pluginType = (Class<T>) Class.forName(pluginTypeName);
            } catch (ClassNotFoundException ex) {
                logger.error("error init pluginparameter: {}, plugin: {} not found", name, pluginTypeName);
            }
        }
        return pluginType;
    }

}
