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
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.utils.JSONUtils;

/**
 *
 * @author Jean Ollion
 */

public class ConditionalParameter extends ContainerParameterImpl<ConditionalParameter> {
    ActionableParameter action;
    HashMap<String, List<Parameter>> parameters;
    List<Parameter> defaultParameters;
    String currentValue;
    
    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("action", action.toJSONEntry());
        if (getCurrentParameters()!=null) res.put("params", JSONUtils.toJSONArrayMap(getCurrentParameters()));
        return res;
    }

    @Override
    public void initFromJSONEntry(Object json) {
        if (json instanceof JSONObject) {
            JSONObject jsonO = (JSONObject)json;
            action.initFromJSONEntry(jsonO.get("action"));
            currentValue = action.getValue();
            if (jsonO.containsKey("params") && getCurrentParameters()!=null) {
                JSONArray params =  jsonO.get("params") instanceof JSONArray ? (JSONArray) jsonO.get("params") :  (JSONArray)((JSONObject)jsonO.get("params")).get(currentValue);
                JSONUtils.fromJSONArrayMap(getCurrentParameters(), params);
            }
        } else if (json instanceof String && action instanceof ChoiceParameter && Arrays.asList(((ChoiceParameter)action).listChoice).contains((String)json)) { // only action
            action.initFromJSONEntry(json);
        } else throw new IllegalArgumentException("JSON Entry is not JSONObject");
    }
    
    public ConditionalParameter(ActionableParameter action) {
        this(action, new HashMap<String, List<Parameter>>(), null);
    }
    
    public ConditionalParameter(ActionableParameter action, HashMap<String, List<Parameter>> parameters, List<Parameter> defaultParameters) {
        super(action.getName());
        this.action=action;
        this.parameters=parameters;
        this.defaultParameters=defaultParameters;
        action.setConditionalParameter(this);
        setActionValue(action.getValue());
    }
    public ConditionalParameter setActionParameters(String actionValue, Parameter... parameters) {
        return setActionParameters(actionValue, parameters, false);
    }
    public List<Parameter> getActionParameters(String actionValue) {
        if (parameters.containsKey(actionValue)) return parameters.get(actionValue);
        else return defaultParameters;
    }
    
    public ConditionalParameter setActionParameters(String actionValue, Parameter[] parameters, boolean setContentFromAlreadyPresent) {
        List<Parameter> paramToSet = Arrays.asList(parameters);
        if (setContentFromAlreadyPresent) {
            List<Parameter> p = this.parameters.get(actionValue);
            if (p!=null && p.size()==parameters.length) ParameterUtils.setContent(paramToSet, p);
        }
        this.parameters.put(actionValue, paramToSet);
        if (actionValue.equals(action.getValue())) setActionValue(action.getValue()); // to update parameters
        //logger.debug("setActionValue: {}, class: {}, nParams: {}, allActions: {}", actionValue, actionValue.getClass().getSimpleName(), parameters.length, this.parameters.keySet());
        return this;
    }
    public void replaceActionParameter(ActionableParameter action) {
        action.setContentFrom(this.action);
        action.setValue(this.action.getValue());
        this.action=action;
        action.setConditionalParameter(this);
    }

    
    public ConditionalParameter setDefaultParameters(Parameter[] defaultParameters) {
        this.defaultParameters=Arrays.asList(defaultParameters);
        initChildList();
        return this;
    } 

    @Override
    public boolean isValid() {
        return action.isValid() && super.isValid();
    }
    
    @Override
    public boolean isEmphasized() {
        if (action.isEmphasized()) return true;
        return super.isEmphasized();
    }
    
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof ConditionalParameter) {
            if (!((ConditionalParameter)other).getActionableParameter().sameContent(getActionableParameter())) {
                logger.debug("ConditionalParam: {} != {} action parameter differ", this, other);
                return false;
            }
            return super.sameContent(other);
        } else return false;
    }
    
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof ConditionalParameter) {
            bypassListeners=true;
            ConditionalParameter otherC = (ConditionalParameter)other;
            action.setConditionalParameter(null);
            action.setContentFrom(otherC.action);
            action.setConditionalParameter(this);
            String currentAction = otherC.currentValue;
            List<Parameter> currentParameters = currentAction==null? null : otherC.getParameters(currentAction);
            for (Entry<String, List<Parameter>> e : otherC.parameters.entrySet()) {
                if (e.getKey().equals(currentAction)) continue; // current action at the end, in case that parameters are used 
                ParameterUtils.setContent(parameters.get(e.getKey()), e.getValue());
            }
            if (otherC.defaultParameters!=null && defaultParameters!=null) {
                ParameterUtils.setContent(defaultParameters, otherC.defaultParameters);
            } else this.defaultParameters=null;
            if (currentAction!=null && currentParameters!=null) { // set current action @ the end
                ParameterUtils.setContent(parameters.get(currentAction), currentParameters);
            }
            setActionValue(action.getValue());
            bypassListeners=false;
        } else throw new IllegalArgumentException("wrong parameter type");
    }
    
    public ActionableParameter getActionableParameter() {return action;}
    
    protected void setActionValue(String actionValue) {
        if (actionValue==null) return;
        currentValue = actionValue;
        if (!action.getValue().equals(actionValue)) this.action.setValue(actionValue); // avoid loop
        fireListeners();
        initChildList();
        //logger.debug("setActionValue: {} value: {}, class: {}, children: {}, allActions: {}",this.hashCode(), actionValue, actionValue.getClass().getSimpleName(), getCurrentParameters()==null ? "null" : getCurrentParameters().size(), this.parameters.keySet());
    }
    
    public List<Parameter> getParameters(String actionValue) {
        List<Parameter> p = this.parameters.get(actionValue);
        if (p==null) return defaultParameters;
        else return p;
    }
    
    public List<Parameter> getCurrentParameters() {
        if (parameters.containsKey(currentValue)) return parameters.get(currentValue);
        else return defaultParameters;
    }
    
    @Override
    public String toString() {
        return action.toString();
    }

    @Override
    protected void initChildList() {
        super.initChildren(getCurrentParameters());
    }
    @Override public ConditionalParameter duplicate() {
        ConditionalParameter res = new ConditionalParameter((ActionableParameter)action.duplicate()); 
        res.setListeners(listeners);
        res.setContentFrom(this);
        return res;
    }

}
