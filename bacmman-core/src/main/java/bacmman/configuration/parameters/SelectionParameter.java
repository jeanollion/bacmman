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

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.Selection;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SelectionParameter extends ParameterImpl<SelectionParameter> implements ChoosableParameter<SelectionParameter> {
    protected String selectionName = null;
    protected Experiment xp;
    protected Supplier<Stream<Selection>> selectionSupplier;
    protected Predicate<Selection> selectionFilter;
    protected boolean allowNoSelection;
    public SelectionParameter(String name) {
        this(name, false);
    }

    public SelectionParameter(String name, boolean allowNoSelection) {
        super(name);
        this.allowNoSelection = allowNoSelection;
    }
    public SelectionParameter setSelectionFilter(Predicate<Selection> filter) {
        this.selectionFilter = filter;
        return this;
    }

    public SelectionParameter setSelectionObjectClass(int oc) {
        this.selectionFilter = s -> s.getStructureIdx() == oc;
        return this;
    }

    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof SelectionParameter) {
            setSelectedItem(((SelectionParameter)other).getSelectedItem());
        } else throw new IllegalArgumentException("wrong parameter type");
    }

    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof SelectionParameter) {
            return Objects.equals(((SelectionParameter)other).getSelectedItem(), getSelectedItem());
        } else return false;
    }

    @Override
    public boolean isValid() {
        if (selectionName == null || getNoSelectionString().equals(selectionName)) return isAllowNoSelection();
        return Arrays.stream(getChoiceList()).anyMatch(s -> s.equals(selectionName));
    }

    @Override
    public void setSelectedItem(String item) {
        if (getNoSelectionString().equals(item)) this.selectionName = null;
        else this.selectionName = item;
    }

    public String getSelectedItem() {
        if (getNoSelectionString().equals(selectionName)) return null;
        return selectionName;
    }

    public Selection getSelectedSelection() {
        if (selectionName == null) return null;
        if (getSelectionSupplier() == null) throw new RuntimeException("No selection supplier");
        return getSelectionSupplier().get().filter(s -> s.getName().equals(selectionName)).findFirst().orElse(null);
    }
    public Supplier<Stream<Selection>> getSelectionSupplier() {
        Supplier<Stream<Selection>> selectionSupp = null;
        if (selectionSupplier!=null) {
            selectionSupp = selectionSupplier;
        } else if (getXP() != null) {
            selectionSupp = xp.getSelectionSupplier();
        }
        return selectionSupp;
    }

    public SelectionParameter setSelectionSupplier(Supplier<Stream<Selection>> selectionSupplier) {
        this.selectionSupplier = selectionSupplier;
        return this;
    }

    @Override
    public String[] getChoiceList() {
        Supplier<Stream<Selection>> selectionSupp = getSelectionSupplier();
        if (selectionSupp == null) return new String[0];
        else return selectionSupp.get().filter(selectionFilter==null ? s->true:selectionFilter).map(Selection::getName).toArray(String[]::new);
    }

    @Override
    public int getSelectedIndex() {
        String[] choices = getChoiceList();
        for (int i = 0; i<choices.length; ++i) {
            if (choices[i].equals(selectionName)) return i;
        }
        return -1;
    }

    @Override
    public boolean isAllowNoSelection() {
        return allowNoSelection;
    }

    protected Experiment getXP() {
        if (xp==null) xp= ParameterUtils.getExperiment(this);
        return xp;
    }

    @Override
    public String getNoSelectionString() {
        return "NO SELECTION";
    }

    @Override
    public Object toJSONEntry() {
        if (getNoSelectionString().equals(selectionName)) return null;
        return selectionName;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry == null) this.selectionName = null;
        else this.selectionName=(String)jsonEntry;
    }

    @Override
    public SelectionParameter duplicate() {
        SelectionParameter dup = super.duplicate();
        dup.setSelectionSupplier(selectionSupplier);
        dup.setSelectionFilter(selectionFilter);
        dup.allowNoSelection = allowNoSelection;
        return dup;
    }

    @Override
    public String toString() {return name + ": "+ (selectionName==null ? getNoSelectionString() : selectionName);}
}
