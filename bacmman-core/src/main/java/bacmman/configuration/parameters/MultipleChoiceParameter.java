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

import org.json.simple.JSONArray;
import bacmman.utils.Utils;

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class MultipleChoiceParameter extends ParameterImpl implements ChoosableParameterMultiple {
    int[] selectedItems;
    String[] listChoice;
     int displayTrimSize=75; // for toString method
    
    public MultipleChoiceParameter(String name, String[] listChoice, int... selectedItems) {
        super(name);
        this.listChoice=listChoice;
        this.selectedItems=selectedItems==null?new int[0] : selectedItems;
    }
    
    public MultipleChoiceParameter(String name, String[] listChoice, boolean selectAll) {
        super(name);
        this.listChoice=listChoice;
        if (selectAll) setAllSelectedItems();
        else selectedItems=new int[0];
    }
    
    
    public void setTrimSize(int trimSize) {
        this.displayTrimSize=trimSize;
    }

    // multiple choice parameter implementation
    @Override
    public void setSelectedIndices(int[] selectedItems) {
        if (selectedItems==null) this.selectedItems=new int[0];
        else this.selectedItems = selectedItems;
        fireListeners();
    }
    
    public void setAllSelectedItems() {
        this.selectedItems=new int[listChoice.length];
        for (int i = 0; i<selectedItems.length; ++i) selectedItems[i]=i;
        fireListeners();
    }
    @Override
    public int[] getSelectedIndices() {
        if (selectedItems==null || selectedItems.length==0) return new int[0];
        return Arrays.copyOf(selectedItems, selectedItems.length);
    }
    public boolean[] getSelectedItemsAsBoolean() {
        boolean[] res = new boolean[listChoice.length];
        for (int i : getSelectedIndices()) res[i] = true;
        return res;
    }
     
    public String[] getSelectedItemsNames() {
        String[] res = new String[getSelectedIndices().length];
        for (int i = 0 ; i<res.length; ++i) res[i] = listChoice[selectedItems[i]];
        return res;
    }

    public boolean isSelected(int idx) {
        for (int i : selectedItems) if (i==idx) return true;
        return false;
    }

    public boolean isSelected(String item) {
        for (int i : selectedItems) if (listChoice[i].equals(item)) return true;
        return false;
    }
    
    @Override
    public String[] getChoiceList() {
        return listChoice;
    }

    // parameter implementation 
    
    @Override
    public String toString() {
        return name +": "+ Utils.getStringArrayAsStringTrim(displayTrimSize, getSelectedItemsNames());
    }
    @Override
    public boolean sameContent(Parameter other) { // checks only indicies
        if (other instanceof ChoosableParameterMultiple) {
            if (!ParameterUtils.arraysEqual(getSelectedIndices(), ((ChoosableParameterMultiple)other).getSelectedIndices())) {
                logger.trace("MultipleChoiceParameter: {}!={} {} vs {}", this, other , getSelectedIndices(),((ChoosableParameterMultiple)other).getSelectedIndices());
                return false;
            } else return true;
        } else return false;
    }
    @Override
    public void setContentFrom(Parameter other) {
        //bypassListeners=true;
        if (other instanceof ChoosableParameterMultiple) {
            setSelectedIndices(((ChoosableParameterMultiple)other).getSelectedIndices());
        } else if (other instanceof ChoosableParameter) {
            String sel = ((ChoosableParameter)other).getChoiceList()[((ChoosableParameter)other).getSelectedIndex()];
            int i = Utils.getIndex(listChoice, sel);
            if (i>=0) this.selectedItems=new int[]{i};
        } else throw new IllegalArgumentException("wrong parameter type");
        //bypassListeners=false;
    }
    
    @Override
    public MultipleChoiceParameter duplicate() {
        MultipleChoiceParameter res= new MultipleChoiceParameter(name, listChoice, selectedItems);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
    
    @Override
    public Object toJSONEntry() {
        JSONArray res = new JSONArray();
        for (int i : selectedItems) res.add(i);
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONArray source = (JSONArray)jsonEntry;
        this.selectedItems=new int[source.size()];
        for (int i = 0; i<source.size(); ++i) selectedItems[i] = ((Number)source.get(i)).intValue();
    }
}
