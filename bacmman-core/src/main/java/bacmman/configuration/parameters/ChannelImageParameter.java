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

/**
 *
 * @author Jean Ollion
 */
public class ChannelImageParameter extends ObjectClassOrChannelParameter<ChannelImageParameter> {
    boolean includeDuplicated=true;

    public ChannelImageParameter() {
        this("");
    }
    
    public ChannelImageParameter(String name) {
        this(name, -1);
    }

    public ChannelImageParameter(String name, boolean allowNoSelection, boolean multipleSelection) {
        super(name, -1, allowNoSelection, multipleSelection);
    }
    
    public ChannelImageParameter(String name, int selectedChannel) {
        super(name, selectedChannel, false, false);
    }
    
    public ChannelImageParameter(String name, int selectedChannel, boolean allowNoSelection) {
        super(name, selectedChannel, allowNoSelection, false);
    }
    
    public ChannelImageParameter(String name, int[] selectedChannels) {
        super(name, selectedChannels, false);
    }

    public ChannelImageParameter setIncludeDuplicatedChannels(boolean includeDuplicated) {
        this.includeDuplicated=includeDuplicated;
        return this;
    }

    public ChannelImageParameter setChannelFromObjectClass(int ocIdx) {
        if (ocIdx < 0) setSelectedIndex(-1);
        else {
            Experiment xp = ParameterUtils.getExperiment(this);
            if (xp != null) setSelectedIndex(xp.experimentStructure.getChannelIdx(ocIdx));
            else
                throw new RuntimeException("Cannot set channel from object class: no experiment found in parameter tree");
        }
        return this;
    }

    @Override
    public String[] getChoiceList() {
        if (getXP()!=null) {
            return getXP().getChannelImagesAsString(includeDuplicated);
        } else {
            return new String[0];
        }
    }

    public static <T extends ObjectClassOrChannelParameter<T>> Consumer<T> defaultAutoConfiguration() {
        return p -> {
            int ocIdx = objectClassInParents().applyAsInt(p);
            if (ocIdx >= 0) {
                Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, p, false);
                p.setSelectedClassIdx(s.getChannelImage());
            }
        };
    }
}
