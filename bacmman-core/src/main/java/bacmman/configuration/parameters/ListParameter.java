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
public interface ListParameter<T extends Parameter, L extends ListParameter<T, L>> extends ContainerParameter<L> { //<T extends Parameter>
    public abstract T createChildInstance();
    public List<T> getChildren();
    /**
     *
     * @return the same instance of ListParameter
     */
    public Class<T> getChildClass();
    public void insert(T... child);
    public void removeAllElements();
    public int getUnMutableIndex();
    public boolean isDeactivatable();
    public void setActivatedAll(boolean activated);
    public List<T> getActivatedChildren();
    public T getChildByName(String name);
    public boolean allowMoveChildren();
}
