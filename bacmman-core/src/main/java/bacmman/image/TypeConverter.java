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

import bacmman.processing.ImageOperations;

import java.util.Arrays;
import java.util.function.DoubleToIntFunction;
import java.util.stream.Stream;


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
                    newPixels[z][xy]=(float)image.getPixel(xy, z);
                }
            }
        }
        return output;
    }

    /**
     *
     * @param image input image to be converted
     * @param output image to cast values to. if null, a new image will be created
     * @return a new ImageFloat values casted as double
     */
    public static ImageDouble toDouble(Image image, ImageDouble output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageDouble(image.getName(), image);
        if (image instanceof ImageDouble) Image.pasteImage(image, output, null);
        else {
            double[][] newPixels = output.getPixelArray();
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy = 0; xy<image.sizeXY(); ++xy) {
                    newPixels[z][xy]=image.getPixel(xy, z);
                }
            }
        }
        return output;
    }

    public static ImageFloat16Scale toFloat16(Image image, ImageFloat16Scale output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageFloat16Scale(image.getName(), image, output==null ? ImageFloat16Scale.getOptimalScale(image.getMinAndMax(null)) : output.getScale());
        if (image instanceof ImageFloat16Scale) Image.pasteImage(image, output, null);
        for (int z = 0; z<image.sizeZ(); ++z) {
            for (int xy = 0; xy<image.sizeXY(); ++xy) {
                output.setPixel(xy, z, image.getPixel(xy, z));
            }
        }
        return output;
    }
    public static ImageFloat16 toHalfFloat(Image image, ImageFloat16 output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageFloat16(image.getName(), image);
        if (image instanceof ImageFloat16) Image.pasteImage(image, output, null);
        for (int z = 0; z<image.sizeZ(); ++z) {
            for (int xy = 0; xy<image.sizeXY(); ++xy) {
                output.setPixel(xy, z, image.getPixel(xy, z));
            }
        }
        return output;
    }
    public static ImageFloat8Scale toFloat8(Image image, ImageFloat8Scale output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageFloat8Scale(image.getName(), image, output==null ? ImageFloat8Scale.getOptimalScale(image.getMinAndMax(null)) : output.getScale());
        if (image instanceof ImageFloat8Scale) Image.pasteImage(image, output, null);
        for (int z = 0; z<image.sizeZ(); ++z) {
            for (int xy = 0; xy<image.sizeXY(); ++xy) {
                output.setPixel(xy, z, image.getPixel(xy, z));
            }
        }
        return output;
    }
    public static ImageFloatU8Scale toFloatU8(Image image, ImageFloatU8Scale output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageFloatU8Scale(image.getName(), image, output==null ? ImageFloatU8Scale.getOptimalScale(image.getMinAndMax(null)) : output.getScale());
        if (image instanceof ImageFloatU8Scale) Image.pasteImage(image, output, null);
        for (int z = 0; z<image.sizeZ(); ++z) {
            for (int xy = 0; xy<image.sizeXY(); ++xy) {
                output.setPixel(xy, z, image.getPixel(xy, z));
            }
        }
        return output;
    }

    public static ImageShort toShort(Image image, ImageShort output, boolean forceCopy) {
        if (forceCopy || !(image instanceof ImageShort)) return toShort(image, output);
        else return (ImageShort)image;
    }

    public static ImageByte toByte(Image image, ImageByte output, boolean forceCopy) {
        if (forceCopy || !(image instanceof ImageByte)) return toByte(image, output);
        else return (ImageByte)image;
    }

    public static ImageDouble toDouble(Image image, ImageDouble output, boolean forceCopy) {
        if (forceCopy || !(image instanceof ImageDouble)) return toDouble(image, output);
        else return (ImageDouble)image;
    }

    public static ImageFloat toFloat(Image image, ImageFloat output, boolean forceCopy) {
        if (forceCopy || !(image instanceof ImageFloat)) return toFloat(image, output);
        else return (ImageFloat)image;
    }

    public static ImageFloat16 toHalfFloat(Image image, ImageFloat16 output, boolean forceCopy) {
        if (forceCopy || !(image instanceof ImageFloat16)) return toHalfFloat(image, output);
        else return (ImageFloat16)image;
    }

    public static ImageFloatingPoint toFloatingPoint(Image image, boolean forceCopy, boolean halfPrecision) {
        if (!forceCopy) {
            if (image instanceof ImageFloatingPoint) return (ImageFloatingPoint)image;
        }
        if (halfPrecision) return toHalfFloat(image, null);
        else return toFloat(image, null);
    }

    public static ImageInt toInt(Image image, ImageInt output) {
        if (output==null || !output.sameDimensions(image)) output = new ImageInt(image.getName(), image);
        if (image instanceof ImageInt) Image.pasteImage(image, output, null);
        else {
            for (int z = 0; z<image.sizeZ(); ++z) {
                for (int xy = 0; xy<image.sizeXY(); ++xy) {
                    output.setPixel(xy, z, image.getPixel(xy, z)+0.5);
                }
            }
        }
        return output;
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
    public static ImageInteger maskToImageInteger(ImageMask image, ImageByte output) {
        if (image instanceof ImageInteger) return (ImageInteger)image;
        else return toByteMask(image, output, 1);
    }

    /**
     * Converts {@param image} to an image of integer type (byte short or int). Only casts if already image integer
     * If {@param image} has negative values, its mean value will be added to the whole image (so it is modified)
     * @param image image to convert
     * @param output
     * @return
     */
    public static ImageInteger asImageInteger(Image image, ImageInteger output) {
        if (image instanceof ImageInteger) return (ImageInteger)image;
        else {
            // check max & min values
            double[] minAndMax = image.getMinAndMax(null);
            if (minAndMax[0]<-0.5) {
                image = ImageOperations.affineOpMulAdd(image, image, 1, -minAndMax[0]);
                minAndMax[1] -= minAndMax[0];
                minAndMax[0] = 0;
            }
            if (minAndMax[1]<256) {
                if (output instanceof ImageShort && output.sameDimensions(image)) return toShort(image, (ImageShort)output);
                else if (output instanceof ImageInt && output.sameDimensions(image)) return toInt(image, (ImageInt)output);
                else return toByte(image, output instanceof ImageByte ? (ImageByte)output : null);
            } else if (minAndMax[1]<65536) {
                if (output instanceof ImageInt && output.sameDimensions(image)) return toInt(image, (ImageInt)output);
                else return toShort(image, output instanceof ImageShort ? (ImageShort)output : null);
            } else return toInt(image, output instanceof ImageInt ? (ImageInt)output : null);
        }
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

    public static <T extends Image<T>> T convert(Image source, T output, boolean forceCopy) {
        if (forceCopy && source.getClass().equals(output.getClass())) return ((T)source.duplicate(source.getName()));
        else return cast(source, output);
    }

    public static <T extends Image<T>> T cast(Image source, T output) {
        if (output instanceof ImageByte) {
            if (source instanceof ImageByte) return (T)source;
            return (T)toByte(source, (ImageByte)output);
        } else if (output instanceof ImageShort) {
            if (source instanceof ImageShort) return (T)source;
            return (T)toShort(source, (ImageShort)output);
        } else if (output instanceof ImageFloat) {
            if (source instanceof ImageFloat) return (T)source;
            return (T)toFloat(source, (ImageFloat)output);
        } else if (output instanceof ImageFloat16) {
            if (source instanceof ImageFloat16) return (T)source;
            return (T)toHalfFloat(source, (ImageFloat16)output);
        } else if (output instanceof ImageFloat16Scale) {
            if (source instanceof ImageFloat16Scale) return (T)source;
            return (T)toFloat16(source, (ImageFloat16Scale)output);
        } else if (output instanceof ImageFloat8Scale) {
            if (source instanceof ImageFloat8Scale) return (T)source;
            return (T)toFloat8(source, (ImageFloat8Scale)output);
        } else if (output instanceof ImageFloatU8Scale) {
            if (source instanceof ImageFloatU8Scale) return (T)source;
            return (T)toFloatU8(source, (ImageFloatU8Scale)output);
        } else throw new IllegalArgumentException("Unsupported Image Type: {}"+ output.getClass().getSimpleName());
    }

    public static Image getIJ1DisplayType(Stream<Image> images) {
        boolean[] imFloat = new boolean[1];
        int byteCount = images.peek(i -> {if (i.floatingPoint()) imFloat[0]=true;}).mapToInt(Image::byteCount).max().getAsInt();
        if (imFloat[0]) return new ImageFloat("", 0, 0, 0);
        if (byteCount == 1) return new ImageByte("", 0, 0, 0);
        else if (byteCount == 2) return new ImageShort("", 0, 0, 0);
        else return new ImageFloat("", 0, 0, 0);
    }

    public static boolean isIJ1ImageType(ImageProperties image) {
        return (image instanceof ImageByte || image instanceof ImageShort || image instanceof ImageFloat);
    }

    /**
     *
     * @param image input image
     * @return an image of type ImageByte, ImageShort or ImageFloat. If {@param image} is of type ImageByte, ImageShort or ImageFloat {@Return}={@param image}. If {@param image} is of type ImageInt, it is cast as a ShortImage {@link TypeConverter#toShort(Image, ImageShort)}  } if its maximum value is inferior to 65535 or a FloatImage {@link TypeConverter#toFloat(Image, ImageFloat)}  }. If {@param image} is a mask if will be converted to a mask: {@link TypeConverter#toByteMask(ImageMask, ImageByte, int)}  }
     */
    public static Image castToIJ1ImageType(ImageProperties image) {
        if (isIJ1ImageType(image)) return (Image)image;
        else if (image instanceof PrimitiveType) {
            PrimitiveType t = (PrimitiveType)image;
            if (t.floatingPoint()) return toFloat((Image)image, null);
            else { // int image
                double[] mm = ((Image) image).getMinAndMax(null);
                if (mm[1] > (65535)) return toFloat((Image) image, null);
                else if (mm[1] < 256) return toByte((Image) image, null);
                else return toShort((Image) image, null);
            }
        } else if (image instanceof ImageMask) return toByteMask((ImageMask)image, null, 1);
        else return toFloat((Image)image, null);
    }

    public static void castToIJ1DisplayType(Image[][] images) {
        boolean intIm = false;
        boolean shortIm = false;
        boolean floatIm = false;
        for (Image[] im : images) {
            for (Image i : im) {
                if (i.floatingPoint()) floatIm = true;
                else if (i instanceof ImageShort) shortIm = true;
                else if (i instanceof ImageInt) intIm = true;
            }
        }
        if (floatIm || intIm) {
            for (int i = 0; i < images.length; i++) {
                for (int j = 0; j < images[i].length; j++) {
                    images[i][j] = TypeConverter.toFloat(images[i][j], null, false);
                }
            }
        } else if (shortIm) {
            for (int i = 0; i < images.length; i++) {
                for (int j = 0; j < images[i].length; j++) {
                    images[i][j] = TypeConverter.toShort(images[i][j], null, false);
                }
            }
        } else {
            for (int i = 0; i < images.length; i++) {
                for (int j = 0; j < images[i].length; j++) {
                    if (images[i][j] instanceof ImageByte) {
                        images[i][j] = TypeConverter.toByte(images[i][j], null, false);
                    }
                }
            }
        }
    }
}
