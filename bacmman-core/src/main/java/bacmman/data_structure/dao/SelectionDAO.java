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
package bacmman.data_structure.dao;

import bacmman.data_structure.Selection;

import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public interface SelectionDAO {
    void clearCache();
    
    Selection getOrCreate(String name, boolean clearIfExisting);
    
    List<Selection> getSelections();
        
    void store(Selection s);
    
    void delete(String id);
    
    void delete(Selection o);
    
    public void deleteAllObjects();
    void erase();
    boolean isReadOnly();
}
