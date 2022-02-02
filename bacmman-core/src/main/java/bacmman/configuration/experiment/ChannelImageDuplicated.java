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
package bacmman.configuration.experiment;

import bacmman.configuration.experiment.Experiment.IMPORT_METHOD;
import bacmman.configuration.parameters.*;
import org.json.simple.JSONObject;

import java.util.function.Predicate;

import static bacmman.configuration.experiment.Experiment.IMPORT_METHOD.ONE_FILE_PER_CHANNEL_POSITION;

/**
 *
 * @author Jean Ollion
 */
public class ChannelImageDuplicated extends ContainerParameterImpl<ChannelImageDuplicated> {
    ChannelImageParameter sourceChannel = new ChannelImageParameter("Source Channel").setIncludeDuplicatedChannels(false);
    EnumChoiceParameter<ChannelImage.CHANNEL_COLOR> color = new EnumChoiceParameter<>("Color", ChannelImage.CHANNEL_COLOR.values(), null).setAllowNoSelection(true).setHint("Display color");

    public ChannelImageDuplicated(String name) {
        super(name);
    }

    public ChannelImageDuplicated(String name, int sourceChannelIdx) {
        this(name);
        sourceChannel.setSelectedIndex(sourceChannelIdx);
    }

    public int getSourceChannel() {
        return sourceChannel.getSelectedIndex();
    }
    public ChannelImage.CHANNEL_COLOR getColor() {return color.getSelectedEnum();}
    @Override
    protected void initChildList() {
        super.initChildren(sourceChannel, color);
    }
    @Override 
    public boolean isEmphasized() {
        return false;
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("name", name);
        res.put("source", sourceChannel.toJSONEntry());
        if (color.getSelectedEnum()!=null) res.put("color", this.color.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jsonO = (JSONObject)jsonEntry;
        name = (String)jsonO.get("name");
        sourceChannel.initFromJSONEntry(jsonO.get("source"));
        if (jsonO.containsKey("color")) this.color.initFromJSONEntry(jsonO.get("color"));

    }
    
}
