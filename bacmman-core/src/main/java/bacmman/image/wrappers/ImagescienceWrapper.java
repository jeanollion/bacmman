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
package bacmman.image.wrappers;

import bacmman.image.Image;
import ij.ImagePlus;



/**
 *
 * @author Jean Ollion
 */
public class ImagescienceWrapper {
    public static imagescience.image.Image getImagescience(Image image) {
        ImagePlus ip = IJImageWrapper.getImagePlus(image);
        return imagescience.image.Image.wrap(ip);
    }
    
    public static  <T extends Image<T>> Image<T> wrap(imagescience.image.Image image) {
        return IJImageWrapper.wrap(image.imageplus());
    }
    
}
