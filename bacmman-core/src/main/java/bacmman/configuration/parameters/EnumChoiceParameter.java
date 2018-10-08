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

/**
 *
 * @author Jean Ollion
 */

public class EnumChoiceParameter<E extends Enum<E>> extends AbstractChoiceParameter<EnumChoiceParameter<E>>  {
    final E[] enumChoiceList;
    public EnumChoiceParameter(String name, E[] enumChoiceList, E selectedItem, boolean allowNoSelection) {
        super(name, Arrays.stream(enumChoiceList)
                .map(e -> e.toString())
                .toArray(l -> new String[l] ),
                selectedItem.toString(), allowNoSelection);
        this.enumChoiceList=enumChoiceList;
    }

    @Override public EnumChoiceParameter<E> duplicate() {
        EnumChoiceParameter res = new EnumChoiceParameter(name, enumChoiceList ,getSelectedEnum(), allowNoSelection);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        return res;
    }

    public E getSelectedEnum() {
        int idx = this.getSelectedIndex();
        if (idx>=0) return enumChoiceList[idx];
        else return null;
    }
    public EnumChoiceParameter<E> setSelectedEnum(E selectedEnum) {
        if (selectedEnum==null) this.setSelectedItem(null);
        else setSelectedItem(selectedEnum.toString());
        return this;
    }
}
