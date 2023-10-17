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

import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 *
 * @author Jean Ollion
 */

public abstract class ConditionalParameterAbstract<V, T extends ConditionalParameterAbstract<V, T>> extends ContainerParameterImpl<T> implements ParameterWithLegacyInitialization<T, V>, Listenable<T> {
    ActionableParameter<V, ? extends ActionableParameter<V, ?>> action;
    HashMap<V, List<Parameter>> parameters;
    HashMap<V, Supplier<List<Parameter>>> parameterSupplier = new HashMap<>();
    List<Parameter> defaultParameters;
    V currentValue;

    @Override
    public T setName(String name) {
        super.setName(name);
        if (action!=null) action.setName(name);
        return (T)this;
    }

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
        } else if (json instanceof String && action instanceof AbstractChoiceParameter && Arrays.asList(((AbstractChoiceParameter)action).getChoiceList()).contains((String)json)) { // only action
            action.initFromJSONEntry(json);
        } else throw new IllegalArgumentException("JSON Entry is not JSONObject");
    }

    public ConditionalParameterAbstract(ActionableParameter<V, ? extends ActionableParameter<V, ?>> action) {
        this(action, new HashMap<>(), null);
    }

    public ConditionalParameterAbstract(ActionableParameter<V, ? extends ActionableParameter<V, ?>> action, HashMap<V, List<Parameter>> parameters, List<Parameter> defaultParameters) {
        super(action.getName());
        this.action=action;
        this.parameters=parameters;
        this.defaultParameters=defaultParameters;
        action.setConditionalParameter(this);
        setActionValue(action.getValue());
    }
    public T setActionParameterSupplier(V actionValue, Supplier<List<Parameter>> parameterSupplier) {
        this.parameterSupplier.put(actionValue, parameterSupplier);
        return (T)this;
    }
    public T setActionParameters(V actionValue, Parameter... parameters) {
        return setActionParameters(actionValue, parameters, false);
    }

    public T setActionParameters(V actionValue, List<Parameter> parameters) {
        return setActionParameters(actionValue, parameters, false);
    }

    public V getActionValue() {
        return this.currentValue;
    }
    public List<Parameter> getParameters(V actionValue) {
        List<Parameter> params = getOrCreateParameters(actionValue);
        if (params==null) return defaultParameters;
        else return params;
    }

    protected List<Parameter> getOrCreateParameters(V actionValue) {
        if (parameters.containsKey(actionValue)) return parameters.get(actionValue);
        else if (parameterSupplier.containsKey(actionValue)) {
            parameters.put(actionValue, parameterSupplier.get(actionValue).get());
            return parameters.get(actionValue);
        } else return null;
    }

    public List<Parameter> getCurrentParameters() {
        return getParameters(currentValue);
    }

    public T setActionParameters(V actionValue, List<Parameter> parameters, boolean setContentFromAlreadyPresent) {
        if (setContentFromAlreadyPresent) {
            List<Parameter> p = this.parameters.get(actionValue);
            if (p!=null && p.size()==parameters.size()) ParameterUtils.setContent(parameters, p);
        }
        this.parameters.put(actionValue, parameters);
        if (actionValue.equals(action.getValue())) setActionValue(action.getValue()); // to update parameters
        //logger.debug("setActionValue: {}, class: {}, nParams: {}, allActions: {}", actionValue, actionValue.getClass().getSimpleName(), parameters.length, this.parameters.keySet());
        return (T)this;
    }

    public T setActionParameters(V actionValue, Parameter[] parameters, boolean setContentFromAlreadyPresent) {
        return setActionParameters(actionValue, Arrays.asList(parameters), setContentFromAlreadyPresent);
    }
    public void replaceActionParameter(ActionableParameter action) {
        action.setContentFrom(this.action);
        action.setValue(this.action.getValue());
        this.action=action;
        action.setConditionalParameter(this);
    }


    public T setDefaultParameters(Parameter... defaultParameters) {
        this.defaultParameters=Arrays.asList(defaultParameters);
        initChildList();
        return (T)this;
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
        if (other instanceof ConditionalParameterAbstract) {
            if (!((ConditionalParameterAbstract)other).getActionableParameter().sameContent(getActionableParameter())) {
                logger.trace("ConditionalParam: {} != {} action parameter differ", this, other);
                return false;
            }
            return super.sameContent(other);
        } else return false;
    }

    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof ConditionalParameterAbstract) {
            bypassListeners=true;
            ConditionalParameterAbstract<V, ?> otherC = (ConditionalParameterAbstract<V, ?>)other;
            action.setConditionalParameter(null);
            action.setContentFrom(otherC.action);
            action.setConditionalParameter(this);
            V currentAction = otherC.currentValue;
            Map<V, List<Parameter>> otherParams = otherC.parameters;
            for (Entry<V, List<Parameter>> e : otherParams.entrySet()) {
                if (e.getKey().equals(currentAction)) continue; // current action at the end, in case that parameters are used
                ParameterUtils.setContent(getOrCreateParameters(e.getKey()), e.getValue());
            }
            if (otherC.defaultParameters!=null && defaultParameters!=null) {
                ParameterUtils.setContent(defaultParameters, otherC.defaultParameters);
            } else this.defaultParameters=null;
            List<Parameter> currentParameters = currentAction==null? null : otherC.getOrCreateParameters(currentAction);
            if (currentAction!=null && currentParameters!=null) { // set current action @ the end
                ParameterUtils.setContent(getOrCreateParameters(currentAction), currentParameters);
            }
            setActionValue(action.getValue());
            bypassListeners=false;
        } else throw new IllegalArgumentException("wrong parameter type");
    }

    public ActionableParameter<V, ? extends ActionableParameter<V, ?>> getActionableParameter() {return action;}

    public T setActionValue(V actionValue) {
        if (actionValue==null) return (T)this;
        currentValue = actionValue;
        if (!action.getValue().equals(actionValue)) this.action.setValue(actionValue); // avoid loop
        initChildList();
        fireListeners();
        //logger.debug("setActionValue: {} value: {}, class: {}, children: {}, allActions: {}",this.hashCode(), actionValue, actionValue.getClass().getSimpleName(), getCurrentParameters()==null ? "null" : getCurrentParameters().size(), this.parameters.keySet());
        return (T)this;
    }

    @Override
    public String toString() {
        return action.toString();
    }

    @Override
    protected void initChildList() {
        super.initChildren(getCurrentParameters());
    }

    // legacy init: transfer to actionable parameter
    public void legacyInit() {
        if (action instanceof ParameterWithLegacyInitialization) ((ParameterWithLegacyInitialization)action).legacyInit();
        if (legacyInitParam != null && legacyInit !=null) legacyInit.accept(legacyInitParam, (T)this);
    }
    public Parameter[] getLegacyParameters() {
        return legacyInitParam;
    }
    Parameter[] legacyInitParam;
    BiConsumer<Parameter[], T> legacyInit;
    public T setLegacyParameter(BiConsumer<Parameter[], T> setValue, Parameter... p) {
        this.legacyInit = setValue;
        this.legacyInitParam = p;
        return (T)this;
    }

    @Override
    public T duplicate() {
        ConditionalParameterAbstract<V, T> res = super.duplicate();
        res.parameterSupplier.putAll(parameterSupplier);
        return (T)res;
    }
}