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

import static bacmman.image.wrappers.IJImageWrapper.getImagePlus;
//import mpicbg.imglib.image.display.imagej.ImageJFunctions;
//import mpicbg.imglib.type.numeric.RealType;

/**
 *
 * @author Jean Ollion
 */
public class ImgLib1ImageWrapper {
    /*
    public static < T extends RealType< T >> Image wrap(mpicbg.imglib.image.Image<T> image) {
        ImagePlus ip = ImageJFunctions.copyToImagePlus(image);
        return IJImageWrapper.wrap(ip);
    }
    
    public static  mpicbg.imglib.image.Image getImage(Image image) { //<T extends RealType<T>>
        image = TypeConverter.toCommonImageType(image);
        ImagePlus ip = IJImageWrapper.getImagePlus(image);
        if (image instanceof ImageFloat) return ImageJFunctions.wrapFloat(ip);
        else if (image instanceof ImageShort) return ImageJFunctions.wrapShort(ip);
        else if (image instanceof ImageByte) return ImageJFunctions.wrapByte(ip);
        else return null;
    }
    */
}
