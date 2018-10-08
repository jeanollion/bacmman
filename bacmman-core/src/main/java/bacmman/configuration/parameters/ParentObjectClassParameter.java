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

import java.util.function.Consumer;


/**
 *
 * @author Jean Ollion
 */
public class ParentObjectClassParameter extends ObjectClassParameterAbstract<ParentObjectClassParameter> {
    int maxStructure;
    
    public ParentObjectClassParameter(String name) {
        this(name, -1, -1);
    }
    
    public ParentObjectClassParameter(String name, int selectedStructure, int maxStructure) {
        super(name, selectedStructure, true, false);
        this.maxStructure=maxStructure;
    }

    public void setMaxStructureIdx(int maxStructureExcl) {
        this.maxStructure = maxStructureExcl;
        if (maxStructure>=0 && super.getSelectedIndex()>=maxStructure) setSelectedIndex(-1);
    }
    @Override public int getSelectedIndex() {
        int idx = super.getSelectedIndex();
        if (maxStructure>=0 && idx>=maxStructure) {
            setSelectedIndex(-1);
            idx= -1;
        }
        //logger.debug("parent structure parameter:{}. sel idx: {} max idx : {}", name, idx, maxStructure);
        //if (maxStructure>=0 && idx>=maxStructure) throw new IllegalArgumentException("ParentStructureParameter "+name+"sel structure:"+idx+" superior to max structure: "+maxStructure);
        return idx;
    }
    
    public int getMaxStructureIdx() {
        return maxStructure;
    }
    
    @Override
    public String[] getChoiceList() {
        String[] choices;
        if (getXP()!=null) {
            choices=getXP().getStructuresAsString();
        } else {
           return new String[]{"error: no xp found in tree"};
        }
        if (maxStructure<=0 && this.autoConfiguration!=null) autoConfiguration();
        if (maxStructure<=0) return new String[]{};
        String[] res = new String[maxStructure];
        System.arraycopy(choices, 0, res, 0, maxStructure);
        return res;
    }
    
    @Override
    public void setSelectedIndex(int structureIdx) {
        if (maxStructure>=0 && structureIdx>=maxStructure) throw new IllegalArgumentException("Parent Structure ("+structureIdx+") cannot be superior to max structure ("+maxStructure+")");
        super.setSelectedIndex(structureIdx);
    }
    
    public static Consumer<ParentObjectClassParameter> defaultAutoConfigurationParent() {
        return (p)-> p.setSelectedClassIdx(structureInParents().applyAsInt(p));
    }
    
}
