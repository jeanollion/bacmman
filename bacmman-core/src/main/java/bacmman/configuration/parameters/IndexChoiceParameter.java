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

import bacmman.utils.ArrayUtil;
import org.json.simple.JSONArray;
import bacmman.utils.Utils;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public abstract class IndexChoiceParameter<P extends IndexChoiceParameter<P>> extends ParameterImpl<P> implements ChoosableParameter<P>, ChoosableParameterMultiple<P> {
    protected int[] selectedIndices;
    protected boolean allowNoSelection, multipleSelection;
    
    public IndexChoiceParameter(String name) {
        this(name, -1, false, false);
    }
    
    public IndexChoiceParameter(String name, int selectedIndex, boolean allowNoSelection, boolean multipleSelection) {
        super(name);
        if (selectedIndex<0) this.selectedIndices=new int[0];
        else this.selectedIndices = new int[]{selectedIndex};
        this.allowNoSelection=allowNoSelection;
        this.multipleSelection=multipleSelection;
    }
    
    public IndexChoiceParameter(String name, int[] selectedIndicies, boolean allowNoSelection) {
        super(name);
        if (selectedIndicies==null) this.selectedIndices=new int[0];
        else this.selectedIndices = selectedIndicies;
        this.allowNoSelection=allowNoSelection;
        this.multipleSelection=true;
    }

    public boolean isMultipleSelection() {
        return multipleSelection;
    }

    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        return allowNoSelection || getSelectedIndex()>=0;
    }

    public P unique() {
        return addValidationFunction(cp -> cp.getSelectedIndices().length == IntStream.of(cp.getSelectedIndices()).distinct().count());
    }

    public <T extends IndexChoiceParameter> T setAllowNoSelection(boolean allowNoSelection) {
        this.allowNoSelection= allowNoSelection;
        return (T)this;
    }
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof IndexChoiceParameter) {
            IndexChoiceParameter otherP = (IndexChoiceParameter) other;
            if (!ParameterUtils.arraysEqual(selectedIndices, otherP.selectedIndices)) {
                logger.trace("IndexChoiceParameter: {}!={} : {} vs {}", this, other, selectedIndices, otherP.selectedIndices);
                return false;
            } else return true;
        } else return false;
    }
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof IndexChoiceParameter) {
            //bypassListeners=true;
            IndexChoiceParameter otherP = (IndexChoiceParameter) other;
            if (otherP.selectedIndices!=null) this.setSelectedIndices(Utils.copyArray(otherP.selectedIndices));
            else this.setSelectedIndex(-1);
            //bypassListeners=false;
            //logger.debug("ICP: {} recieve from: {} -> {} ({})", name, otherP.getSelectedItems(), this.getSelectedItems(), this.getSelectedIndex());
        } else throw new IllegalArgumentException("wrong parameter type");
        if (this instanceof Deactivable && other instanceof Deactivable) ((Deactivable)this).setActivated(((Deactivable)other).isActivated());
    }
    @Override
    public int getSelectedIndex() {
        if (selectedIndices==null || selectedIndices.length==0) return -1;
        return selectedIndices[0];
    }
    @Override
    public boolean isAllowNoSelection() {
        return allowNoSelection;
    }
    
    public void setMonoSelection(int selectedIndex) {
        this.multipleSelection=false;
        this.selectedIndices=new int[]{selectedIndex};
        fireListeners();
    }
    
    public void setMultipleSelection(int[] selectedIndicies) {
        this.multipleSelection=true;
        this.selectedIndices=selectedIndicies;
        fireListeners();
    }
    
    @Override 
    public String toString(){
        if (!multipleSelection) {
            int sel = getSelectedIndex();
            if (sel>=0 && getChoiceList().length>sel) return name+": "+getChoiceList()[sel];
            else if (allowNoSelection && sel==-1) return name+": "+getNoSelectionString();
            else return name+": object class #"+sel;
        } else return name +": "+ Utils.getStringArrayAsStringTrim(50, getSelectedItemsNames());
    }
    
    public String[] getSelectedItemsNames() {
        if (selectedIndices==null || selectedIndices.length==0) return new String[0];
        String[] res = new String[selectedIndices.length];
        String[] choices = this.getChoiceList();
        for (int i = 0 ; i<res.length; ++i) res[i] = selectedIndices[i]>=0&&selectedIndices[i]<choices.length?choices[selectedIndices[i]]:getNoSelectionString();
        return res;
    }

    public void setSelectedIndex(int selectedIndex) {
        if (selectedIndex>=0) this.selectedIndices = new int[]{selectedIndex};
        else this.selectedIndices=new int[0];
        fireListeners();
    }
    public void selectAll() {
        if (!multipleSelection) throw new RuntimeException("Multiple selection is disabled");
        setSelectedIndices(ArrayUtil.generateIntegerArray(getChoiceList().length));
    }
    
    // choosable parameter
    @Override
    public void setSelectedItem(String item) {
        setSelectedIndex(Utils.getIndex(getChoiceList(), item));
    }
    @Override
    public abstract String[] getChoiceList();

    // choosable parameter multiple
    @Override
    public void setSelectedIndices(int[] selectedItems) {
        this.selectedIndices=selectedItems;
        fireListeners();
    }
    @Override
    public int[] getSelectedIndices() {
        /*if (selectedIndices==null) {
            String[] list = getChoiceList();
            if (!allowNoSelection && list!=null) { // select all
                selectedIndices = new int[list.length];
                for (int i = 0; i<list.length; ++i) selectedIndices[i]=i;
            } else {
                selectedIndices = new int[0];
            }
        }*/
        if (selectedIndices==null || selectedIndices.length==0) selectedIndices = new int[0];
        return Arrays.copyOf(selectedIndices, selectedIndices.length);
    }
    @Override
    public Object toJSONEntry() {
        JSONArray res = new JSONArray();
        for (int i : selectedIndices) res.add(i);
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry instanceof JSONArray) {
            setSelectedIndices(((JSONArray)jsonEntry).stream().mapToInt(n -> ((Number)n).intValue()).toArray());
        } else if (jsonEntry instanceof Number) {
            int idx = ((Number)jsonEntry).intValue();
            setSelectedIndex(idx);
        }
    }
}
