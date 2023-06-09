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

import bacmman.utils.Pair;
import bacmman.utils.Utils;

import java.util.Arrays;
import java.util.function.Function;

/**
 *
 * @author Jean Ollion
 * @param <P>
 */

public abstract class AbstractChoiceParameter<V, P extends AbstractChoiceParameter<V, P>> extends ParameterImpl<P> implements ActionableParameter<V, P>, ChoosableParameter<P>, ParameterWithLegacyInitialization<P, V>, Listenable<P> {
    String selectedItem;
    protected String[] listChoice;
    boolean allowNoSelection;
    private int selectedIndex=-2;
    ConditionalParameterAbstract<V, ? extends ConditionalParameterAbstract<V, ?>> cond;
    Function<String, V> mapper;

    public AbstractChoiceParameter(String name, String[] listChoice, String selectedItem, Function<String, V> mapper, boolean allowNoSelection) {
        super(name);
        this.listChoice=listChoice;
        setSelectedItem(selectedItem);
        this.allowNoSelection=allowNoSelection;
        this.mapper = mapper;
    }
    public P setAllowNoSelection(boolean allowNoSelection) {
        this.allowNoSelection=allowNoSelection;
        return (P)this;
    }
    public String getSelectedItem() {return selectedItem;}
    public int getSelectedIndex() {
        return selectedIndex;
    }
    
    @Override 
    public void setSelectedItem(String selectedItem) {
        if (selectedItem==null || selectedItem.length()==0 || selectedItem.equals(getNoSelectionString())) {
            this.selectedItem = getNoSelectionString();
            selectedIndex=-1;
        } else {
            this.selectedIndex=Utils.getIndex(listChoice, selectedItem);
            if (selectedIndex>=0) this.selectedItem=selectedItem;
            else {
                if (this.selectedItem==null) this.selectedItem=getNoSelectionString();
            }
        }

        fireListeners();
        setCondValue();
    }
    
    public void setSelectedIndex(int selectedIndex) {
        if (selectedIndex>=0) {
            this.selectedItem=listChoice[selectedIndex];
            this.selectedIndex=selectedIndex;
        } else {
            this.selectedIndex=-1;
            selectedItem=getNoSelectionString();
        }
        fireListeners();
        setCondValue();
    }
    
    @Override
    public String toString() {return name + ": "+ selectedItem;}

    @Override 
    public boolean isValid() {
        if (!super.isValid()) return false;
        return !(!allowNoSelection && this.selectedIndex<0);
    }
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof AbstractChoiceParameter) {
            if (this.getSelectedItem()==null) return ((AbstractChoiceParameter)other).getSelectedItem()==null;
            return this.getSelectedItem().equals(((AbstractChoiceParameter)other).getSelectedItem());
        }
        else return false;
        
    }
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof AbstractChoiceParameter) {
            //bypassListeners=true;
            AbstractChoiceParameter otherC = (AbstractChoiceParameter)other;
            setSelectedItem(otherC.getSelectedItem());
            //bypassListeners=false;
            //logger.debug("choice {} set content from: {} current item: {}, current idx {}, other item: {}, other idx : {}", this.hashCode(), otherC.hashCode(), this.getSelectedItem(), this.getSelectedIndex(), otherC.getSelectedItem(), otherC.getSelectedIndex());
        } //else throw new IllegalArgumentException("wrong parameter type: "+(other==null? "null":other.getClass()) +" instead of ChoiceParameter");
    }
    
    // choosable parameter
    @Override
    public boolean isAllowNoSelection() {
        return this.allowNoSelection;
    }
    public static String NO_SELECTION="no selection";
    @Override
    public String getNoSelectionString() {
        return NO_SELECTION;
    }
    
    // actionable parameter
    @Override
    public String[] getChoiceList() {
        return listChoice;
    }

    protected void setCondValue() {
        if (cond!=null) cond.setActionValue(selectedItem ==null ? null : mapper.apply(selectedItem));
    }
    @Override
    public void setConditionalParameter(ConditionalParameterAbstract<V, ? extends ConditionalParameterAbstract<V, ?>> cond) {
        this.cond=cond;
    }
    /**
     * 
     * @return the associated conditional parameter, or null if no conditionalParameter is associated
     */
    @Override
    public ConditionalParameterAbstract<V, ? extends ConditionalParameterAbstract<V, ?>> getConditionalParameter() {
        return cond;
    }
    
    private AbstractChoiceParameter(String name, String selectedItem) {
        super(name);
        this.selectedItem=selectedItem;
    }

    @Override
    public Object toJSONEntry() {
        return selectedItem;
    }

    @Override
    public void initFromJSONEntry(Object json) {
        if (json instanceof String) {
            setSelectedItem((String)json);
            if (getSelectedIndex()==-1) {
                if (legacyParameter!=null) legacyParameter.initFromJSONEntry(json);
                legacyInit();
            }
        } else logger.error("Error init: {} with {}", this, json);//else throw new IllegalArgumentException("JSON Entry is not String");
    }

    // legacy init

    /**
     * When parameter cannot be initialized, this value is used as default. Useful when parametrization of a module has changed.
     * @param value default value
     * @return this parameter for convenience
     */
    @Override
    public P setLegacyInitializationValue(V value) {
        this.legacyInitItem = value;
        return (P)this;
    }

    /**
     * This method is run when a parameter cannot be initialized, meaning that the parametrization of the module has changed.
     */
    @Override
    public void legacyInit() {
        if (legacyParameter!=null && setValue!=null) legacyInitItem = setValue.apply(legacyParameter);
        if (legacyInitItem !=null) {
            String value = Arrays.stream(getChoiceList()).filter(s -> legacyInitItem.equals(mapper.apply(s))).findAny().orElse(null);
            if (value!=null) this.setSelectedItem(value);
        }
    }
    V legacyInitItem;
    Parameter legacyParameter;
    Function<Parameter, V> setValue;

    /**
     * When a parameter A of a module has been replaced by B, this methods allows to initialize B using the former value of A
     * @param p
     * @param setValue
     * @return
     */
    public P setLegacyParameter(Parameter p, Function<Parameter, V> setValue) {
        this.legacyParameter = p;
        this.setValue = setValue;
        return (P)this;
    }
    @Override
    public Parameter getLegacyParameter() {
        return legacyParameter;
    }

}
