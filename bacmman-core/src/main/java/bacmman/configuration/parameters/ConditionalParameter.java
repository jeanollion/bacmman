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
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.List;
import java.util.function.Function;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.utils.JSONUtils;

/**
 *
 * @author Jean Ollion
 */

public class ConditionalParameter<V> extends ConditionalParameterAbstract<V, ConditionalParameter<V>> {

    public ConditionalParameter(ActionableParameter<V, ?> action) {
        super(action);
    }
    
    public ConditionalParameter(ActionableParameter<V, ?> action, HashMap<V, List<Parameter>> parameters, List<Parameter> defaultParameters) {
        super(action, parameters, defaultParameters);
    }

    @Override
    public ConditionalParameter<V> duplicate() {
        ConditionalParameter<V> res = new ConditionalParameter(action.duplicate());
        parameters.forEach((v, p) -> res.setActionParameters(v, p.stream().map(Parameter::duplicate).toArray(Parameter[]::new)));
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
}