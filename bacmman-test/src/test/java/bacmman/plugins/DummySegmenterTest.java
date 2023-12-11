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

import bacmman.test_utils.TestUtils;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.processing.ImageLabeller;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import bacmman.dummy_plugins.DummySegmenter;

/**
 *
 * @author Jean Ollion
 */
public class DummySegmenterTest {
    @Test
    public void testDummySegmenter() {
        DummySegmenter s = new DummySegmenter(true, 2);
        ImageByte in = new ImageByte("", 50, 50, 2);
        RegionPopulation pop = s.runSegmenter(in, 0, null);
        assertEquals("number of objects", 2, pop.getRegions().size());
        ImageInteger image = pop.getLabelMap();
        Region[] obs = ImageLabeller.labelImage(image);
        assertEquals("number of objects from image", 2, obs.length);
        
        // reconstruction de l'image
        ImageInteger res2 = ImageInteger.mergeBinary(in, obs[0].getMask(), obs[1].getMask());
        TestUtils.assertImage((ImageByte)res2, (ImageByte)image, 0);
        
    }
}
