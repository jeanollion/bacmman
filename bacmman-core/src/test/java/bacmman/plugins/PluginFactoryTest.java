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
package bacmman.plugins;

import bacmman.configuration.parameters.PluginParameter;
import org.junit.Assert;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author Jean Ollion
 */
public class PluginFactoryTest {
    
    @Test
    public void testInternalPlugin() {
        String pluginName="DummyThresholder";
        PluginFactory.findPlugins("bacmman.dummy_plugins");
        assertTrue("dummy thresholder found", PluginFactory.getPlugin(Thresholder.class, pluginName) instanceof Thresholder);
        Plugin pp = PluginFactory.getPlugin("DummySegmenter");
        if (pp==null) System.out.println("Dummy Segmenter not found ");
        else System.out.println("Dummy Segmenter search: "+pp.getClass());
        assertTrue("dummy segmenter found", PluginFactory.getPlugin(Segmenter.class, "DummySegmenter") instanceof Segmenter);
        PluginParameter<Thresholder> thresholder = new PluginParameter<Thresholder>("Tresholder", Thresholder.class, true);
        Assert.assertTrue("Internal plugin search:", thresholder.getPluginNames().contains(pluginName));
        thresholder.setPlugin(pluginName);
        Plugin p =  thresholder.instanciatePlugin();
        Assert.assertTrue("Internal plugin instanciation:", p instanceof Thresholder);
        
        
    }
}
