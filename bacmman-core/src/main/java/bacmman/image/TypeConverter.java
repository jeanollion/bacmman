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
package bacmman.image;

import java.util.Arrays;
import java.util.function.DoubleFunction;
import java.util.function.DoubleToIntFunction;


/**
 *
 * @author Jean Ollion
 */
public class TypeConverter {
    
    /**
     * 
     * @param image input image to be converted
     * @param output image to cast values to. if null, a new image will be created
     * @return a new ImageFloat values casted as float
     */
    public static ImageFloat toFloat(Image image, ImageFloat output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageFloat(image.getName(), image);
        if (image instanceof ImageFloat) Image.pasteImage(image, output, null);
        else {
            float[][] newPixels = output.getPixelArray();
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy = 0; xy<image.sizeXY(); ++xy) {
                    newPixels[z][xy]=image.getPixel(xy, z);
                }
            }
        }
        return output;
    }
    public static ImageShort toShort(Image image, ImageShort output, boolean copyIfShort) {
        if (copyIfShort || !(image instanceof ImageShort)) return toShort(image, output);
        else return (ImageShort)image;
    }

    public static ImageByte toByte(Image image, ImageByte output, boolean copyIfByte) {
        if (copyIfByte || !(image instanceof ImageByte)) return toByte(image, output);
        else return (ImageByte)image;
    }

    public static ImageFloat toFloat(Image image, ImageFloat output, boolean copyIfFloat) {
        if (copyIfFloat || !(image instanceof ImageFloat)) return toFloat(image, output);
        else return (ImageFloat)image;
    }

    /**
     * 
     * @param image input image to be converted
     * @param output image to cast values to. if null, a new image will be created
     * @return a new ImageShort values casted as short (if values exceed 65535 they will be equal to 65535) 
     */
    public static ImageShort toShort(Image image, ImageShort output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageShort(image.getName(), image);
        if (image instanceof ImageShort) Image.pasteImage(image, output, null);
        else {
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy = 0; xy<image.sizeXY(); ++xy) {
                    output.setPixel(xy, z, image.getPixel(xy, z)+0.5);
                }
            }
        }
        return output;
    }
    public static ImageShort toShort(Image image, ImageShort output, DoubleToIntFunction function) {
        if (output==null || !output.sameDimensions(image)) output = new ImageShort(image.getName(), image);
        for (int z = 0; z<image.sizeZ(); ++z) {
            for (int xy = 0; xy<image.sizeXY(); ++xy) {
                output.setPixel(xy, z, (double)function.applyAsInt(image.getPixel(xy, z))); // convert to double in order to trim values
            }
        }
        return output;
    }
    
    /**
     * 
     * @param image input image to be converted
     * @param output image to cast values to. if null, a new image will be created
     * @return a new ImageByte with values transformed by {@param function} (if values exceed 255 they will be equal to 255)
     */
    public static ImageByte toByte(Image image, ImageByte output, DoubleToIntFunction function) {
        if (output==null || !output.sameDimensions(image)) output = new ImageByte(image.getName(), image);
        for (int z = 0; z<image.sizeZ(); ++z) {
            for (int xy = 0; xy<image.sizeXY(); ++xy) {
                output.setPixel(xy, z, (double)function.applyAsInt(image.getPixel(xy, z))); // convert to double in order to trim values
            }
        }
        return output;
    }
    public static ImageByte toByte(Image image, ImageByte output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageByte(image.getName(), image);
        if (image instanceof ImageByte) Image.pasteImage(image, output, null);
        else {
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy = 0; xy<image.sizeXY(); ++xy) {
                    output.setPixel(xy, z, image.getPixel(xy, z)+0.5);
                }
            }
        }
        return output;
    }
    public static ImageInteger toImageInteger(ImageMask image, ImageByte output) {
        if (image instanceof ImageInteger) return (ImageInteger)image;
        else return toByteMask(image, output, 1);
    }
    
    /**
     * 
     * @param image input image to be converted
     * @param output image to cast values to. if null, a new image will be created
     * @param value value of voxels contained in mask
     * @return a mask represented as an ImageByte, each non-zero voxel of {@param image} has a value of {@param value}
     */
    public static ImageByte toByteMask(ImageMask image, ImageByte output, int value) {
        if (output==null || !output.sameDimensions(image)) output = new ImageByte(image.getName(), image);
        if (value>255) value = 255;
        if (value<0) value = 0;
        byte  v = (byte)value;
        byte[][] newPixels = output.getPixelArray();
        if (image instanceof BlankMask) {
            for (int z = 0; z<image.sizeZ(); ++z) Arrays.fill(newPixels[z], (byte)value);
        } else {
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy = 0; xy<image.sizeXY(); ++xy) {
                    if (image.insideMask(xy, z)) newPixels[z][xy] = v;
                }
            }
        }
        return output;
    }
    public static boolean isCommonImageType(ImageProperties image) {
        return (image instanceof ImageByte || image instanceof ImageShort || image instanceof ImageFloat);
    }
    public static boolean isNumeric(ImageProperties image) {
        return (image instanceof ImageByte || image instanceof ImageShort || image instanceof ImageFloat || image instanceof ImageInt);
    }
    /**
     * 
     * @param image input image
     * @return an image of type ImageByte, ImageShort or ImageFloat. If {@param image} is of type ImageByte, ImageShort or ImageFloat {@Return}={@param image}. If {@param image} is of type ImageInt, it is cast as a ShortImage {@link TypeConverter#toShort(Image, ImageShort)}  } if its maximum value is inferior to 65535 or a FloatImage {@link TypeConverter#toFloat(Image, ImageFloat)}  }. If {@param image} is a mask if will be converted to a mask: {@link TypeConverter#toByteMask(ImageMask, ImageByte, int)}  }
     */
    public static Image toCommonImageType(ImageProperties image) {
        if (isCommonImageType(image)) return (Image)image;
        else if (image instanceof ImageInt) {
            double[] mm = ((Image)image).getMinAndMax(null);
            if (mm[1]>(65535)) return toFloat((Image)image, null);
            else return toShort((Image)image, null);
        }
        else if (image instanceof ImageMask) return toByteMask((ImageMask)image, null, 1);
        else return toFloat((Image)image, null);
    }
    
    public static <T extends Image> T cast(Image source, T output) {
        if (output instanceof ImageByte) {
            if (source instanceof ImageByte) return (T)source;
            return (T)toByte(source, (ImageByte)output);
        } else if (output instanceof ImageShort) {
            if (source instanceof ImageShort) return (T)source;
            return (T)toShort(source, (ImageShort)output);
        } else if (output instanceof ImageFloat) {
            if (source instanceof ImageFloat) return (T)source;
            return (T)toFloat(source, (ImageFloat)output);
        } else throw new IllegalArgumentException("Output should be of type byte, short, or float, but is: {}"+ output.getClass().getSimpleName());
    }

    public static void homogenizeBitDepth(Image[][] images) {
        boolean shortIm = false;
        boolean floatIm = false;
        for (Image[] im : images) {
            for (Image i : im) {
                if (i instanceof ImageShort) {
                    shortIm = true;
                } else if (i instanceof ImageFloat) {
                    floatIm = true;
                }
            }
        }
        if (floatIm) {
            for (int i = 0; i < images.length; i++) {
                for (int j = 0; j < images[i].length; j++) {
                    if (images[i][j] instanceof ImageByte || images[i][j] instanceof ImageShort) {
                        images[i][j] = TypeConverter.toFloat(images[i][j], null);
                    }
                }
            }
        } else if (shortIm) {
            for (int i = 0; i < images.length; i++) {
                for (int j = 0; j < images[i].length; j++) {
                    if (images[i][j] instanceof ImageByte) {
                        images[i][j] = TypeConverter.toShort(images[i][j], null);
                    }
                }
            }
        }
    }
}
