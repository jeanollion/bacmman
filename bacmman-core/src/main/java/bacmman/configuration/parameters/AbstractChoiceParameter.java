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
import bacmman.utils.Utils;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 *
 * @author Jean Ollion
 * @param <P>
 */

public abstract class AbstractChoiceParameter<V, P extends AbstractChoiceParameter<V, P>> extends ParameterImpl<P> implements ActionableParameter<V, P>, ChoosableParameter<P>, ParameterWithLegacyInitialization<P, V>, Listenable<P> {
    V selectedItem;
    boolean allowNoSelection;
    ConditionalParameterAbstract<V, ? extends ConditionalParameterAbstract<V, ?>> cond;
    Function<String, V> mapper;
    Function<V, String> toString;
    public AbstractChoiceParameter(String name, V selectedItem, Function<String, V> mapper, Function<V, String> toString, boolean allowNoSelection) {
        super(name);
        this.selectedItem = selectedItem;
        this.allowNoSelection=allowNoSelection;
        this.mapper = mapper;
        this.toString=toString;
    }
    public P setMapper(Function<String, V> mapper) {
        this.mapper = mapper;
        return (P)this;
    }
    public P setAllowNoSelection(boolean allowNoSelection) {
        this.allowNoSelection=allowNoSelection;
        return (P)this;
    }
    public int getSelectedIndex() {
        if (selectedItem == null) return -1;
        return Utils.getIndex(getChoiceList(), toString.apply(selectedItem));
    }

    @Override
    public V getValue() {
        return this.selectedItem;
    }

    public String getSelectedItem() {
        if (this.selectedItem == null) return NO_SELECTION;
        else return toString.apply(selectedItem);
    }

    @Override 
    public void setSelectedItem(String selectedItem) {
        if (selectedItem==null || selectedItem.isEmpty() || selectedItem.equals(getNoSelectionString())) {
            this.selectedItem = null;
        } else {
            this.selectedItem = mapper.apply(selectedItem);
        }
        fireListeners();
        setCondValue();
    }
    
    public void setSelectedIndex(int selectedIndex) {
        if (selectedIndex>=0) {
            this.selectedItem=mapper.apply(getChoiceList()[selectedIndex]);
        } else {
            selectedItem=null;
        }
        fireListeners();
        setCondValue();
    }
    
    @Override
    public String toString() {
        return name + ": "+ (selectedItem == null ? getNoSelectionString() : toString.apply(selectedItem));
    }

    @Override 
    public boolean isValid() {
        if (!super.isValid()) return false;
        return !(!allowNoSelection && this.selectedItem==null);
    }
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof AbstractChoiceParameter) {
            if (this.getValue()==null) return ((AbstractChoiceParameter)other).getValue()==null;
            return this.getValue().equals(((AbstractChoiceParameter)other).getValue());
        }
        else return false;
        
    }
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof AbstractChoiceParameter) {
            //bypassListeners=true;
            AbstractChoiceParameter otherC = (AbstractChoiceParameter)other;
            if (otherC.selectedItem == null) {
                selectedItem = null;
            } else {
                try {
                    selectedItem = (V) otherC.selectedItem;
                } catch (ClassCastException e) {
                    setSelectedItem(otherC.getValue().toString());
                }
            }
            fireListeners();
            setCondValue();
            //bypassListeners=false;
            //logger.debug("choice {} set content from: {} current item: {}, current idx {}, other item: {}, other idx : {}", this.hashCode(), otherC.hashCode(), this.getSelectedItem(), this.getSelectedIndex(), otherC.getSelectedItem(), otherC.getSelectedIndex());
        } //else throw new IllegalArgumentException("wrong parameter type: "+(other==null? "null":other.getClass()) +" instead of ChoiceParameter");
    }
    
    // choosable parameter
    @Override
    public boolean isAllowNoSelection() {
        return this.allowNoSelection;
    }
    public static String NO_SELECTION="NO SELECTION";
    @Override
    public String getNoSelectionString() {
        return NO_SELECTION;
    }
    
    // actionable parameter

    public abstract String[] getChoiceList();

    protected void setCondValue() {
        if (cond!=null) cond.setActionValue(selectedItem);
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

    @Override
    public Object toJSONEntry() {
        if (selectedItem == null) return "";
        else return toString.apply(selectedItem);
    }

    @Override
    public void initFromJSONEntry(Object json) {
        if (json instanceof String) {
            if (((String) json).isEmpty()) setSelectedItem(null);
            else setSelectedItem((String)json);
        } else throw new IllegalArgumentException("JSON Entry is not String");
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
        if (legacyInitItem != null) setSelectedItem(toString.apply(legacyInitItem));
        if (legacyParameter!=null && setValue!=null) setValue.accept(legacyParameter, (P)this);
    }
    V legacyInitItem;
    Parameter[] legacyParameter;
    BiConsumer<Parameter[], P> setValue;

    /**
     * When a parameter A of a module has been replaced by B, this methods allows to initialize B using the former value of A
     * @param p
     * @param setValue
     * @return
     */
    @Override
    public P setLegacyParameter(BiConsumer<Parameter[], P> setValue, Parameter... p) {
        this.legacyParameter = p;
        this.setValue = setValue;
        return (P)this;
    }
    @Override
    public Parameter[] getLegacyParameters() {
        return legacyParameter;
    }

}
