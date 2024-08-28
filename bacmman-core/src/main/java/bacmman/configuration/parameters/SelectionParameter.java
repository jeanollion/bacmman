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
import bacmman.data_structure.Selection;
import bacmman.utils.Utils;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SelectionParameter extends AbstractChoiceParameterMultiple<String, SelectionParameter> {
    protected Supplier<Stream<Selection>> selectionSupplier;
    protected Predicate<Selection> selectionFilter;

    public SelectionParameter(String name, boolean allowMultiple) {
        this(name, false, allowMultiple);
    }

    public SelectionParameter(String name, boolean allowNoSelection ,boolean allowMultiple) {
        super(name, s->s, allowNoSelection, allowMultiple);
    }
    public SelectionParameter setSelectionFilter(Predicate<Selection> filter) {
        this.selectionFilter = filter;
        return this;
    }

    public SelectionParameter setSelectionObjectClass(int oc) {
        this.selectionFilter = s -> s.getStructureIdx() == oc;
        return this;
    }

    public SelectionParameter sameObjectClassValidation() {
        addValidationFunction(selParam -> Utils.objectsAllHaveSameProperty(selParam.getSelectedSelections(), Selection::getStructureIdx));
        return this;
    }

    public Selection getSelectedSelection() {
        if (getSelectedItem() == null) return null;
        if (getSelectionSupplier() == null) throw new RuntimeException("No selection supplier");
        return getSelectionSupplier().get().filter(s -> s.getName().equals(getSelectedItem())).findFirst().orElse(null);
    }

    public Stream<Selection> getSelectedSelections() {
        if (selectedItems.length == 0) return Stream.empty();
        if (getSelectionSupplier() == null) throw new RuntimeException("No selection supplier");
        if (selectedItems.length == 1) return Stream.of(getSelectedSelection());
        Map<String, Selection> nameMapSel = getSelectionSupplier().get().collect(Collectors.toMap(Selection::getName, s->s));
        return Arrays.stream(selectedItems).map(nameMapSel::get);
    }

    public Supplier<Stream<Selection>> getSelectionSupplier() {
        Supplier<Stream<Selection>> selectionSupp = null;
        if (selectionSupplier!=null) {
            selectionSupp = selectionSupplier;
        } else if (getXP() != null) {
            selectionSupp = getXP().getSelectionSupplier();
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
    public boolean isValid() {
        if (!super.isValid()) return false;
        List<String> choice = Arrays.asList(getChoiceList());
        for (String s : getSelectedItems()) {
            if (!choice.contains(s)) return false;
        }
        return true;
    }

    protected Experiment getXP() {
        return ParameterUtils.getExperiment(this);
    }

    @Override
    public SelectionParameter duplicate() {
        SelectionParameter dup = new SelectionParameter(name, allowNoSelection, allowMultipleSelection);
        dup.setSelectionSupplier(selectionSupplier);
        dup.setSelectionFilter(selectionFilter);
        dup.setContentFrom(this);
        transferStateArguments(this, dup);
        return dup;
    }

}
