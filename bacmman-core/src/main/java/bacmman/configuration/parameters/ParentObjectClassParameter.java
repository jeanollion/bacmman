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

import java.util.function.Consumer;
import java.util.function.ToIntFunction;


/**
 *
 * @author Jean Ollion
 */
public class ParentObjectClassParameter extends ObjectClassParameterAbstract<ParentObjectClassParameter> {
    int maxStructure=-1;
    
    public ParentObjectClassParameter(String name) {
        this(name, -1, -1);
    }
    
    public ParentObjectClassParameter(String name, int selectedStructure, int maxStructure) {
        super(name, selectedStructure, true, false);
        this.maxStructure=maxStructure;
    }

    public void setMaxStructureIdx(int maxStructureExcl) {
        this.maxStructure = maxStructureExcl;
    }

    public int getMaxStructureIdx() {
        return maxStructure;
    }

    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        return maxStructure<0 || getSelectedIndex()<maxStructure;
    }

    @Override
    public String[] getChoiceList() {
        String[] choices;
        if (getXP()!=null) {
            choices=getXP().experimentStructure.getObjectClassesAsString();
        } else {
           return new String[]{"error: no xp found in tree"};
        }
        if (maxStructure<=0) autoConfiguration();
        if (maxStructure<=0) return new String[]{};
        String[] res = new String[maxStructure];
        System.arraycopy(choices, 0, res, 0, maxStructure);
        return res;
    }
    public static ToIntFunction<ObjectClassOrChannelParameter> structureInParents() {
        return p->{
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, p, false);
            return (s!=null) ? s.getIndex(): -1;
        };
    }
    @Override
    protected void autoConfiguration() {
        if (autoConfiguration!=null) autoConfiguration.accept(this);
        else maxStructure = structureInParents().applyAsInt(this);
    }
    
    public static Consumer<ParentObjectClassParameter> defaultAutoConfigurationParent() {
        return (p)-> p.setSelectedClassIdx(structureInParents().applyAsInt(p));
    }
    
}
