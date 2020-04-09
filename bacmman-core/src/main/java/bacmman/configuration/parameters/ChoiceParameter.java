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

public class ChoiceParameter extends AbstractChoiceParameter<String, ChoiceParameter>  {
    
    public ChoiceParameter(String name, String[] listChoice, String selectedItem, boolean allowNoSelection) {
        super(name, listChoice, selectedItem, Function.identity(), allowNoSelection);
    }
    @Override public ChoiceParameter duplicate() {
        ChoiceParameter res = new ChoiceParameter(name, Arrays.copyOf(listChoice, listChoice.length) ,this.selectedItem, this.allowNoSelection);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
    @Override
    public String getValue() {
        return getSelectedItem();
    }

    @Override
    public void setValue(String value) {
        this.setSelectedItem(value);
    }


    public static String NO_SELECTION="no selection";
    @Override
    public String getNoSelectionString() {
        return NO_SELECTION;
    }
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
