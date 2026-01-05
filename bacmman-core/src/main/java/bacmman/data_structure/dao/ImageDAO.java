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

import java.io.IOException;

/**
 *
 * @author Jean Ollion
 */
public interface ImageDAO {
    void freeMemory();
    void eraseAll();
    String getImageExtension();
    Image openPreProcessedImage(int channelImageIdx, int timePoint) throws IOException;
    Image openPreProcessedImage(int channelImageIdx, int timePoint, BoundingBox bounds) throws IOException;
    Image openPreProcessedImagePlane(int z, int channelImageIdx, int timePoint) throws IOException;
    BlankMask getPreProcessedImageProperties(int channelImageIdx) throws IOException;
    void writePreProcessedImage(Image image, int channelImageIdx, int timePoint) throws IOException;
    void deletePreProcessedImage(int channelImageIdx, int timePoint) throws IOException;

    boolean isEmpty();
}
