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

/**
 *
 * @author Jean Ollion
 */
public abstract class ObjectClassParameterAbstract<T extends ObjectClassParameterAbstract<T>> extends ObjectClassOrChannelParameter<T> {

    public ObjectClassParameterAbstract(String name) {
        super(name);
    }
    public ObjectClassParameterAbstract(String name, int selectedStructure, boolean allowNoSelection, boolean multipleSelection) {
        super(name, selectedStructure, allowNoSelection, multipleSelection);
    }
    
    public ObjectClassParameterAbstract(String name, int[] selectedStructures, boolean allowNoSelection) {
        super(name, selectedStructures, allowNoSelection);
    }
    public int getParentObjectClassIdx() {
        if (getXP()==null) logger.error("StructureParameter#getParentStructureIdx(): {}, could not get dataset", name);
        if (getSelectedIndex()==-1) return -1;
        else return getXP().getStructure(getSelectedIndex()).getParentStructure();
    }
    
    public int getFirstCommonParentObjectClassIdx(int otherStructureIdx) {
        if (getSelectedIndex()==-1 || otherStructureIdx==-1) return -1;
        if (getXP()==null) {
            logger.error("StructureParameter#getParentStructureIdx(): {}, could not get dataset", name);
            return -1;
        }
        else return getXP().experimentStructure.getFirstCommonParentObjectClassIdx(getSelectedIndex(), otherStructureIdx);
    }

}
