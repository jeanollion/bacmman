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

import java.util.Arrays;
import java.util.function.Function;

/**
 *
 * @author Jean Ollion
 */

public class EnumChoiceParameter<E extends Enum<E>> extends AbstractChoiceParameter<EnumChoiceParameter<E>>  {
    final E[] enumChoiceList;
    final Function<E, String> toString;
    public EnumChoiceParameter(String name, E[] enumChoiceList, E selectedItem, Function<E, String> toString, boolean allowNoSelection) {
        super(name, Arrays.stream(enumChoiceList)
                        .map(toString)
                        .toArray(String[]::new),
                selectedItem==null ? null : toString.apply(selectedItem), allowNoSelection);
        this.enumChoiceList=enumChoiceList;
        this.toString=toString;
    }
    public EnumChoiceParameter(String name, E[] enumChoiceList, E selectedItem, boolean allowNoSelection) {
        super(name, Arrays.stream(enumChoiceList)
                .map(Enum::toString)
                .toArray(String[]::new),
                selectedItem==null ? null : selectedItem.toString(), allowNoSelection);
        this.enumChoiceList=enumChoiceList;
        this.toString = Enum::toString;
    }

    @Override public EnumChoiceParameter<E> duplicate() {
        EnumChoiceParameter<E> res = new EnumChoiceParameter<E>(name, enumChoiceList ,getSelectedEnum(), toString, allowNoSelection);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }

    public E getSelectedEnum() {
        int idx = this.getSelectedIndex();
        if (idx>=0) return enumChoiceList[idx];
        else return null;
    }
    public EnumChoiceParameter<E> setSelectedEnum(E selectedEnum) {
        if (selectedEnum==null) this.setSelectedItem(null);
        else setSelectedItem(toString.apply(selectedEnum));
        return this;
    }
}
