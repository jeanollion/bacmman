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

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;

import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public abstract class ObjectClassOrChannelParameter<T extends ObjectClassOrChannelParameter<T>> extends IndexChoiceParameter<T> {
    Consumer<T> autoConfiguration;
    String noSelection  ="NO SELECTION";
    public ObjectClassOrChannelParameter(String name) {
        super(name);
    }
    public ObjectClassOrChannelParameter(String name, int selectedStructure, boolean allowNoSelection, boolean multipleSelection) {
        super(name, selectedStructure, allowNoSelection, multipleSelection);
    }
    
    public ObjectClassOrChannelParameter(String name, int[] selectedStructures, boolean allowNoSelection) {
        super(name, selectedStructures, allowNoSelection);
    }
    
    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        // also check selected indices are within index choice range
        if (this.selectedIndices==null) return true;
        String[] ch = this.getChoiceList();
        if (ch!=null) {
            for (int i : selectedIndices) if (i>=ch.length) return false;
        } else return false;
        return true;
    }
    
    public T setAutoConfiguration(Consumer<T> autoConfiguration) {
        this.autoConfiguration=autoConfiguration;
        return (T)this;
    }

    public static ToIntFunction<ObjectClassOrChannelParameter> objectClassInParents() {
        return p->{
            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, p, false);
            int sIdx = (s!=null) ? s.getIndex(): -1;
            return sIdx;
        };
    }
    public static ToIntFunction<ObjectClassOrChannelParameter> structureParameterInParents() {
        return p->{
            ObjectClassOrChannelParameter s = ParameterUtils.getFirstParameterFromParents(ObjectClassOrChannelParameter.class, p, false);
            int sIdx = (s!=null) ? s.getSelectedClassIdx(): -1;
            return sIdx;
        };
    }

    protected void autoConfiguration() {
        if (autoConfiguration!=null) autoConfiguration.accept((T)this);
    }
    
    protected Experiment getXP() {
        return ParameterUtils.getExperiment(this);
    }
    
    public void setSelectedClassIdx(int structureIdx) {
        super.setSelectedIndex(structureIdx);
    }
    
    public int getSelectedClassIdx() {
        int idx = super.getSelectedIndex();
        if (idx==-1 && autoConfiguration!=null) {
            autoConfiguration();
            return super.getSelectedIndex();
        } else return idx;
    }

    @Override
    public String getNoSelectionString() {
        return noSelection;
    }

    public T setNoSelectionString(String noSelection) {
        this.noSelection = noSelection;
        return (T)this;
    }
    
}
