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
import bacmman.utils.Utils;
import org.json.simple.JSONArray;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 * @param <P>
 */

public abstract class AbstractChoiceParameterMultiple<V, P extends AbstractChoiceParameterMultiple<V, P>> extends ParameterImpl<P> implements ChoosableParameterMultiple<P>, ChoosableParameter<P>, Listenable<P> {
    String[] selectedItems = new String[0];
    boolean allowNoSelection, allowMultipleSelection;
    Function<String, V> mapper;

    public AbstractChoiceParameterMultiple(String name, Function<String, V> mapper, boolean allowNoSelection, boolean allowMultipleSelection) {
        super(name);
        this.allowNoSelection=allowNoSelection;
        this.allowMultipleSelection = allowMultipleSelection;
        this.mapper = mapper;
    }
    public P setAllowNoSelection(boolean allowNoSelection) {
        this.allowNoSelection=allowNoSelection;
        return (P)this;
    }
    public P setMultipleSelection(boolean allowMultipleSelection) {
        this.allowMultipleSelection=allowMultipleSelection;
        return (P)this;
    }

    public boolean isMultipleSelection() {
        return allowMultipleSelection;
    }

    @Override
    public void setSelectedItem(String selectedItem) {
        if (selectedItem==null || selectedItem.isEmpty() || selectedItem.equals(getNoSelectionString())) {
            this.selectedItems = new String[0];
        } else {
            this.selectedItems = new String[]{selectedItem};
        }
        fireListeners();
    }


    public P setSelectedItems(String... selectedItems) {
        if (selectedItems==null) {
            this.selectedItems = new String[0];
        } else {
            this.selectedItems = selectedItems;
        }
        fireListeners();
        return (P)this;
    }

    public P addSelectedItems(String... selectedItems) {
        if (selectedItems != null && selectedItems.length>0) {
            if (this.selectedItems == null || this.selectedItems.length==0) return setSelectedItems(selectedItems);
            else return setSelectedItems(Stream.concat(Arrays.stream(this.selectedItems), Arrays.stream(selectedItems)).distinct().toArray(String[]::new));
        } else return (P)this;
    }

    public String getSelectedItem() {
        if (selectedItems.length == 0) return null;
        else return selectedItems[0];
    }
    public String[] getSelectedItems() {
        return Arrays.copyOf(selectedItems, selectedItems.length);
    }

    @Override
    public int getSelectedIndex() {
        if (selectedItems.length == 0) return -1;
        else {
            return Utils.getIndex(getChoiceList(), selectedItems[0]);
        }
    }
    @Override
    public void setSelectedIndices(int[] selectedItems) {
        if (selectedItems == null) selectedItems = new int[0];
        String[] l = getChoiceList();
        this.selectedItems = IntStream.of(selectedItems).filter(i->i>=0 && i<l.length).mapToObj(i -> l[i]).toArray(String[]::new);
    }
    @Override
    public int[] getSelectedIndices() {
        if (selectedItems.length==0) return new int[0];
        return Utils.getIndices(getChoiceList(), selectedItems);
    }

    @Override
    public String toString() {return name + ": "+ (allowMultipleSelection ? Utils.toStringArray(selectedItems) : (getSelectedItem()==null ? NO_SELECTION : getSelectedItem() ) ) ;}

    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        return allowNoSelection || selectedItems.length>0;
    }
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof AbstractChoiceParameterMultiple) {
            AbstractChoiceParameterMultiple otherC = (AbstractChoiceParameterMultiple) other;
            return Arrays.equals(getSelectedItems(), otherC.getSelectedItems());
        } else if (other instanceof AbstractChoiceParameter) {
            AbstractChoiceParameter otherC = (AbstractChoiceParameter) other;
            Object otherSel = otherC.getSelectedItem();
            String[] selItems = getSelectedItems();
            if (otherSel==null || otherSel.equals(otherC.getNoSelectionString())) return selItems.length == 0;
            else return selItems.length == 1 && otherSel.equals(selItems[0]);
        }
        else return false;

    }
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof AbstractChoiceParameterMultiple) {
            //bypassListeners=true;
            AbstractChoiceParameterMultiple otherC = (AbstractChoiceParameterMultiple)other;
            setSelectedItems(otherC.selectedItems);
            //bypassListeners=false;
            //logger.debug("choice {} set content from: {} current item: {}, current idx {}, other item: {}, other idx : {}", this.hashCode(), otherC.hashCode(), this.getSelectedItem(), this.getSelectedIndex(), otherC.getSelectedItem(), otherC.getSelectedIndex());
        } else if (other instanceof AbstractChoiceParameter) {
            AbstractChoiceParameter otherC = (AbstractChoiceParameter) other;
            String otherSel = otherC.getSelectedItem();
            if (otherSel==null || otherSel.equals(otherC.getNoSelectionString()) || otherSel.isEmpty()) setSelectedItems();
            else setSelectedItem(otherSel);
        }
        if (this instanceof Deactivable && other instanceof Deactivable) ((Deactivable)this).setActivated(((Deactivable)other).isActivated());
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

    @Override
    public Object toJSONEntry() {
        return JSONUtils.toJSONArray(getSelectedItems());
    }

    @Override
    public void initFromJSONEntry(Object json) {
        if (json == null) {
            return;
        } else if (json instanceof String) {
            setSelectedItem((String)json);
        } else if (json instanceof JSONArray) {
            selectedItems = JSONUtils.fromStringArray(((JSONArray)json));
        } else logger.error("Error init: {} with {}", this, json);//else throw new IllegalArgumentException("JSON Entry is not String");
    }

}
