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

public class ChoiceParameter extends AbstractChoiceParameter<ChoiceParameter>  {
    
    public ChoiceParameter(String name, String[] listChoice, String selectedItem, boolean allowNoSelection) {
        super(name, listChoice, selectedItem, allowNoSelection);
    }
    @Override public ChoiceParameter duplicate() {
        ChoiceParameter res = new ChoiceParameter(name, Arrays.copyOf(listChoice, listChoice.length) ,this.selectedItem, this.allowNoSelection);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        return res;
    }
}
