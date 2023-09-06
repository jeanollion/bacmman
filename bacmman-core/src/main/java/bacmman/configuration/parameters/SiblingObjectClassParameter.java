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

import bacmman.configuration.experiment.Structure;

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class SiblingObjectClassParameter extends ObjectClassParameterAbstract<SiblingObjectClassParameter> {
    int parentStructureIdx=-2;
    boolean includeParent=false, includeCurrent=false;
    int maxStructure=-1;

    public SiblingObjectClassParameter(String name, int selectedStructure, int parentStructureIdx, boolean includeParent, boolean includeCurrent, boolean allowNoSelection) {
        super(name, -1, allowNoSelection, false);
        this.parentStructureIdx=parentStructureIdx;
        this.includeParent=includeParent;
        this.includeCurrent=includeCurrent;
        setSelectedClassIdx(selectedStructure);
    }
    public SiblingObjectClassParameter(String name, int selectedStructure, boolean includeParent, boolean includeCurrent, boolean allowNoSelection) {
        this(name, selectedStructure, -2, includeParent, includeCurrent, allowNoSelection);
    }
    public SiblingObjectClassParameter(String name, boolean includeParent, boolean includeCurrent) {
        this(name, -1, -2, includeParent, includeCurrent, false);
    }
    public SiblingObjectClassParameter(String name) {
        this(name, -1, -2, true, false, false);
    }
    public void setMaxStructureIdx(int maxStructureExcl) {
        this.maxStructure = maxStructureExcl;
    }
    @Override public int getSelectedClassIdx() {
        int idx = super.getSelectedIndex();
        int[] idxObjectClassMap = getIndexObjectClassMap();
        if (idx<0 || idx>= idxObjectClassMap.length) return -2;
        return idxObjectClassMap[idx];
    }
    
    @Override public void setSelectedIndex(int selectedIndex) {
        super.setSelectedIndex(selectedIndex);
    }
    public boolean includeCurrent() {
        return includeCurrent;
    }
    @Override public int getSelectedIndex() {
        return super.getSelectedIndex();
    }

    public void setParentObjectClassIdx(int parentStructureIdx) {
        if (this.parentStructureIdx==-2) {
            this.parentStructureIdx = parentStructureIdx;
        } else if (parentStructureIdx!=this.parentStructureIdx) {
            String[] sel = this.getSelectedItemsNames();
            this.parentStructureIdx = parentStructureIdx;
            if (sel.length == 1) this.setSelectedItem(sel[0]);
        }
    }
    
    @Override public void setSelectedClassIdx(int structureIdx) {
        if (structureIdx<0) super.setSelectedIndex(-1);
        else {
            super.setSelectedIndex(-1);
            int[] idxObjectClassMap = getIndexObjectClassMap();
            if (idxObjectClassMap !=null) {
                for (int i = 0; i< idxObjectClassMap.length; ++i) {
                    if (idxObjectClassMap[i]==structureIdx) {
                        super.setSelectedIndex(i);
                    }
                }
            }
        }
    }
    
    public int[] getIndexObjectClassMap() {
        //logger.debug("sibling structure parameter: setIndexStructureMap getXP null: {}", getXP()==null);
        if (parentStructureIdx<-1) autoConfiguration();
        if ((getXP()==null) || parentStructureIdx<-1) return new int[]{-1};
        else {
            int[] idxObjectClassMap =  getXP().experimentStructure.getAllChildStructures(parentStructureIdx);
            if (maxStructure>=0) idxObjectClassMap = Arrays.stream(idxObjectClassMap).filter(i->i<maxStructure).toArray();
            if (includeParent && parentStructureIdx!=-1) { // add Parent before
                int[] idxStructureMap2 = new int[idxObjectClassMap.length+1];
                System.arraycopy(idxObjectClassMap, 0, idxStructureMap2, 1, idxObjectClassMap.length);
                idxStructureMap2[0] = parentStructureIdx;
                return idxStructureMap2;
            } else return idxObjectClassMap;
        }
    }
    
    @Override protected void autoConfiguration() {
        if (getXP()!=null) {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, this, false);
            if (s!=null) {
                this.setParentObjectClassIdx(s.getParentStructure());
                if (!this.includeCurrent) this.setMaxStructureIdx(s.getIndex());
            }
        }
    }
    
    @Override
    public String[] getChoiceList() {
        if (getXP()!=null) {
            return getXP().experimentStructure.getObjectClassesNames(getIndexObjectClassMap());
        } else {
            return new String[]{"error: no xp found in tree"};
        }
    }
    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        return maxStructure<0 || getSelectedClassIdx()<maxStructure;
    }
}
