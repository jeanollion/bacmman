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

/**
 *
 * @author Jean Ollion
 * @param <P>
 */

public abstract class AbstractChoiceParameter<P extends AbstractChoiceParameter<P>> extends ParameterImpl<P> implements ActionableParameter<P>, ChoosableParameter<P>, Listenable<P> {
    String selectedItem;
    protected String[] listChoice;
    boolean allowNoSelection;
    private int selectedIndex=-2;
    ConditionalParameterAbstract cond;
    
    public AbstractChoiceParameter(String name, String[] listChoice, String selectedItem, boolean allowNoSelection) {
        super(name);
        this.listChoice=listChoice;
        setSelectedItem(selectedItem);
        this.allowNoSelection=allowNoSelection;
    }
    
    public String getSelectedItem() {return selectedItem;}
    public int getSelectedIndex() {
        return selectedIndex;
    }
    
    @Override 
    public void setSelectedItem(String selectedItem) {
        if (selectedItem==null || selectedItem.length()==0 || getNoSelectionString().equals(selectedItem)) {
            this.selectedItem = getNoSelectionString();
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
            selectedIndex=-1;
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
            return this.getSelectedItem().equals(((AbstractChoiceParameter)other).getSelectedItem());
        }
        else return false;
        
    }
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof AbstractChoiceParameter) {
            bypassListeners=true;
            AbstractChoiceParameter otherC = (AbstractChoiceParameter)other;
            setSelectedItem(otherC.getSelectedItem());
            bypassListeners=false;
            //logger.debug("choice {} set content from: {} current item: {}, current idx {}, other item: {}, other idx : {}", this.hashCode(), otherC.hashCode(), this.getSelectedItem(), this.getSelectedIndex(), otherC.getSelectedItem(), otherC.getSelectedIndex());
        } else throw new IllegalArgumentException("wrong parameter type: "+(other==null? "null":other.getClass()) +" instead of ChoiceParameter");
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
        if (cond!=null) cond.setActionValue(selectedItem);
    }
    
    public String getValue() {
        return getSelectedItem();
    }

    public void setValue(String value) {
        this.setSelectedItem(value);
    }
    
    public void setConditionalParameter(ConditionalParameterAbstract cond) {
        this.cond=cond;
    }
    /**
     * 
     * @return the associated conditional parameter, or null if no conditionalParameter is associated
     */
    public ConditionalParameterAbstract getConditionalParameter() {
        return cond;
    }
    
    
    private AbstractChoiceParameter(String name, String selectedItem) {
        super(name);
        this.selectedItem=selectedItem;
    }
    
    //@PostLoad
    /*public void postLoad() {
        if (!postLoaded) {
            selectedIndex=Utils.getIndex(listChoice, selectedItem); 
            postLoaded = true;
        }
    }
    */

    @Override
    public Object toJSONEntry() {
        return selectedItem;
    }

    @Override
    public void initFromJSONEntry(Object json) {
        if (json instanceof String) {
            setSelectedItem((String)json);
        } else throw new IllegalArgumentException("JSON Entry is not String");
    }
}
