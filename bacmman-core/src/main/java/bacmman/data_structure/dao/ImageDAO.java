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
package bacmman.data_structure.dao;

import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;

/**
 *
 * @author Jean Ollion
 */
public interface ImageDAO {
    public void flush();
    public String getImageExtension();
    public Image openPreProcessedImage(int channelImageIdx, int timePoint);
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, BoundingBox bounds);
    public Image openPreProcessedImagePlane(int z, int channelImageIdx, int timePoint);
    public BlankMask getPreProcessedImageProperties(int channelImageIdx);
    public void writePreProcessedImage(Image image, int channelImageIdx, int timePoint);
    public void deletePreProcessedImage(int channelImageIdx, int timePoint);
    
}
