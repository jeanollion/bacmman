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
import bacmman.utils.Utils;
import org.json.simple.JSONArray;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class MultipleEnumChoiceParameter<E extends Enum<E>> extends ParameterImpl implements ChoosableParameterMultiple {
    int[] selectedItems;
    final E[] enumChoiceList;
    Function<E, String> toString;
    ToIntFunction<E> getIndex;
    int displayTrimSize=75; // for toString method

    public MultipleEnumChoiceParameter(String name, E[] listChoice, Function<E, String> toString) {
        super(name);
        this.enumChoiceList=listChoice;
        getIndex = e->IntStream.range(0, enumChoiceList.length).filter(i -> enumChoiceList[i].equals(e)).findFirst().orElse(-1);
        setToStringFunction(toString);
    }

    public MultipleEnumChoiceParameter(String name, E[] listChoice, Function<E, String> toString, E... selectedItems) {
        this(name, listChoice, toString);
        setSelectedItems(selectedItems);
    }

    public MultipleEnumChoiceParameter(String name, E[] listChoice, Function<E, String> toString, boolean selectAll) {
        super(name);
        this.enumChoiceList=listChoice;
        setToStringFunction(toString);
        if (selectAll) setAllSelectedItems();
        else selectedItems=new int[0];
    }

    public MultipleEnumChoiceParameter setToStringFunction(Function<E, String> toString) {
        if (toString==null) this.toString = selectedItem -> selectedItem==null ? null : selectedItem.toString();
        else this.toString = selectedItem -> selectedItem==null ? null : toString.apply(selectedItem);
        return this;
    }
    
    public MultipleEnumChoiceParameter setTrimSize(int trimSize) {
        this.displayTrimSize=trimSize;
        return this;
    }
    protected E getEnum(String item) {
        return Arrays.stream(enumChoiceList).filter(e -> toString.apply(e).equals(item)).findFirst().orElse(null);
    }
    // multiple choice parameter implementation
    @Override
    public void setSelectedIndices(int[] selectedItems) {
        if (selectedItems==null) this.selectedItems=new int[0];
        else this.selectedItems = selectedItems;
        fireListeners();
    }
    public void setSelectedItems(E... selectedItems) {
        if (selectedItems==null) setSelectedIndices(new int[0]);
        else this.selectedItems = Arrays.stream(selectedItems).mapToInt(getIndex).toArray();
        fireListeners();
    }

    public void setAllSelectedItems() {
        this.selectedItems= ArrayUtil.generateIntegerArray(this.enumChoiceList.length);
        fireListeners();
    }
    @Override
    public int[] getSelectedIndices() {
        if (selectedItems==null || selectedItems.length==0 ) return new int[0];
        return Arrays.copyOf(selectedItems, selectedItems.length);
    }
    public boolean[] getSelectedItemsAsBoolean() {
        boolean[] res = new boolean[enumChoiceList.length];
        for (int i : getSelectedIndices()) res[i] = true;
        return res;
    }
     
    public List<E> getSelectedItems() {
        return Arrays.stream(getSelectedIndices()).filter(i -> i>=0 && i<enumChoiceList.length).mapToObj(i -> this.enumChoiceList[i]).collect(Collectors.toList());
    }
    
    @Override
    public String[] getChoiceList() {
        return Arrays.stream(enumChoiceList).map(toString).toArray(String[]::new);
    }

    // parameter implementation 
    
    @Override
    public String toString() {
        return name +": "+ Utils.getStringArrayAsStringTrim(displayTrimSize, getSelectedItems().stream().map(toString).toArray(String[]::new));
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
            E item = getEnum(sel);
            if (item!=null) this.selectedItems = new int[]{getIndex.applyAsInt(item)};
        } else throw new IllegalArgumentException("wrong parameter type");
        //bypassListeners=false;
    }
    
    @Override
    public MultipleEnumChoiceParameter duplicate() {
        MultipleEnumChoiceParameter res= new MultipleEnumChoiceParameter(name, enumChoiceList, toString);
        res.setSelectedIndices(getSelectedIndices());
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
