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
package bacmman.data_structure.region_container;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BlankMask;

/**
 *
 * @author Jean Ollion
 */

public class RegionContainerBlankMask extends RegionContainer {
    
    public RegionContainerBlankMask(SegmentedObject structureObject) {
        super(structureObject);
    }
    
    public BlankMask getImage() {
        return new BlankMask(bounds, getScaleXY(), getScaleZ());
    }
    
    @Override
    public Region getRegion() {
        return new Region(getImage(), segmentedObject.getIdx()+1, is2D);
    }

    protected RegionContainerBlankMask() {}
}
