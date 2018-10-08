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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 
 * @author Jean Ollion
 * @param <T>
 */

public class SimpleListParameter<T extends Parameter> extends ListParameterImpl<T, SimpleListParameter<T>> {

    /**
     * 
     * @param name : name of the parameter
     * @param unMutableIndex : index of the last object that cannot be modified
     */
    public SimpleListParameter(String name, int unMutableIndex, Class<T> childClass) {
        super(name, unMutableIndex, childClass);
    }
    /**
     * 
     * @param name : name of the parameter
     */
    public SimpleListParameter(String name, Class<T> childClass) {
        super(name, childClass);
    }
    
    public SimpleListParameter(String name, int unMutableIndex, T childInstance) {
        super(name, unMutableIndex, childInstance);
    }
    
    public SimpleListParameter(String name, T childInstance) {
        super(name, childInstance);
    }
    

    @Override
    public SimpleListParameter<T> duplicate() {
        SimpleListParameter<T> res = new SimpleListParameter<>(name, unMutableIndex, getChildClass());
        res.setContentFrom(this);
        return res;
    }

}
