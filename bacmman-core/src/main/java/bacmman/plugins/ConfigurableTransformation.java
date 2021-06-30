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

import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Image;

/**
 *
 * @author Jean Ollion
 */
public interface ConfigurableTransformation extends Transformation {
    /**
     * This method compute configuration data necessary for {@link Transformation#applyTransformation(int, int, Image)} method; in this method the objects should not be modified but created de novo.
     * @param channelIdx
     * @param inputImages 
     */
    public void computeConfigurationData(int channelIdx, InputImages inputImages);
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber);
    boolean highMemory();
}
