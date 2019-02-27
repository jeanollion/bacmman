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

/**
 *
 * @author Jean Ollion
 */
public class SiblingObjectClassParameter extends ObjectClassParameterAbstract<SiblingObjectClassParameter> {
    int parentStructureIdx=-2;
    int[] idxStructureMap;
    int selectedStructureIdx=-1;
    boolean includeParent=false;
    
    public SiblingObjectClassParameter(String name, int selectedStructure, int parentStructureIdx, boolean includeParent, boolean allowNoSelection) {
        super(name, -1, allowNoSelection, false);
        this.parentStructureIdx=parentStructureIdx;
        this.selectedStructureIdx=selectedStructure;
        this.includeParent=includeParent;
    }
    public SiblingObjectClassParameter(String name, int selectedStructure, boolean includeParent, boolean allowNoSelection) {
        this(name, selectedStructure, -2, includeParent, allowNoSelection);
    }
    public SiblingObjectClassParameter(String name, boolean includeParent) {
        this(name, -1, -2, includeParent, false);
    }
    public SiblingObjectClassParameter(String name) {
        this(name, -1, -2, false, false);
    }
    
    @Override public int getSelectedClassIdx() {
        return selectedStructureIdx;
    }
    
    @Override public void setSelectedIndex(int selectedIndex) {
        super.setSelectedIndex(selectedIndex);
        selectedStructureIdx = getIndexStructureMap()[super.getSelectedIndex()];
    }
    
    @Override public int getSelectedIndex() {
        if (selectedStructureIdx<0) return super.getSelectedIndex();
        else {
            int idx;
            if (selectedIndices==null) idx= -1;
            else idx= selectedIndices[0];
            if (idx==-1) {
                getIndexStructureMap();
                if (idxStructureMap!=null) {
                    for (int i = 0; i<idxStructureMap.length; ++i) {
                        if (idxStructureMap[i]==selectedStructureIdx) {
                            super.setSelectedIndex(i);
                            return i;
                        }
                    }
                }
                return -1;
            } else return idx;
        }
    }
    
    public void setParentStructureIdx(int parentStructureIdx) {
        String[] sel = this.getSelectedItemsNames();
        this.parentStructureIdx=parentStructureIdx;
        setIndexStructureMap();
        if (sel.length==1) this.setSelectedItem(sel[0]);
    }
    
    @Override public void setSelectedClassIdx(int structureIdx) {
        selectedStructureIdx = structureIdx;
    }
    
    protected void setIndexStructureMap() {
        logger.debug("sibling structure parameter: setIndexStructureMap getXP null: {}", getXP()==null);
        if (parentStructureIdx==-2) { //not configured -> look for a Structure in parents
            Structure s= ParameterUtils.getFirstParameterFromParents(Structure.class, this, false);
            if (s!=null) parentStructureIdx = s.getParentStructure();
            else parentStructureIdx = -1;
            //logger.debug("configuring parentStructureIdx: {}", parentStructureIdx);
        }
        if (getXP()==null && idxStructureMap==null) idxStructureMap=new int[]{-1};
        else {
            idxStructureMap =  getXP().experimentStructure.getAllChildStructures(parentStructureIdx);
            if (includeParent && parentStructureIdx!=-1) { // add Parent before
                int[] idxStructureMap2 = new int[idxStructureMap.length+1];
                System.arraycopy(idxStructureMap, 0, idxStructureMap2, 1, idxStructureMap.length);
                idxStructureMap2[0] = parentStructureIdx;
                idxStructureMap=idxStructureMap2;
            }
        }
    }
    @Override public boolean sameContent(Parameter other) {
        if (!super.sameContent(other)) return false;
        if (other instanceof SiblingObjectClassParameter) {
            SiblingObjectClassParameter otherP = (SiblingObjectClassParameter) other;
            if (selectedStructureIdx!=otherP.selectedStructureIdx) {
                logger.trace("SiblingStructureParameter {}!={}, selected structure idx: {} vs {}", name, otherP.name, selectedStructureIdx, otherP.selectedStructureIdx);
                return false;
            }
            return true;
        }else return false;
        
    }
    @Override public void setContentFrom(Parameter other) {
        super.setContentFrom(other);
        if (other instanceof SiblingObjectClassParameter) {
            SiblingObjectClassParameter otherP = (SiblingObjectClassParameter) other;
            parentStructureIdx=otherP.parentStructureIdx;
            selectedStructureIdx = otherP.selectedStructureIdx;
            //includeParent=otherP.includeParent;
        } else throw new IllegalArgumentException("wrong parameter type");
    }
    
    @Override protected void autoConfiguration() {
        if (getXP()!=null) {
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, this, false);
            if (s!=null) {
                if (includeParent) this.setSelectedClassIdx(s.getParentStructure());
                else this.setSelectedClassIdx(s.getIndex());
            }
        }
    }
    
    protected int[] getIndexStructureMap() {
        if (idxStructureMap==null) setIndexStructureMap();
        return idxStructureMap;
    }
    
    @Override
    public String[] getChoiceList() {
        if (getXP()!=null) {
            return getXP().experimentStructure.getObjectClassesNames(getIndexStructureMap());
        } else {
            return new String[]{"error: no xp found in tree"};
        }
    }

}
