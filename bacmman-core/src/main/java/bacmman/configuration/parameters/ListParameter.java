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

import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public interface ListParameter<T extends Parameter, L extends ListParameter<T, L>> extends ContainerParameter<T, L> { //<T extends Parameter>
    T createChildInstance();
    List<T> getChildren();
    /**
     *
     * @return the same instance of ListParameter
     */
    Class<T> getChildClass();
    void insert(T... child);
    void removeAllElements();
    int getUnMutableIndex();
    int getMaxChildCount();
    boolean isDeactivatable();
    void setActivatedAll(boolean activated);
    List<T> getActivatedChildren();
    T getChildByName(String name);
    boolean allowMoveChildren();
    boolean isEmpty();
}
