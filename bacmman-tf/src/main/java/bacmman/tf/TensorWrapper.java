package bacmman.tf;

import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.processing.ResizeUtils;
import net.imglib2.Dimensions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.Tensor;

import java.nio.FloatBuffer;
import java.util.Arrays;

public class TensorWrapper {
    public final static Logger logger = LoggerFactory.getLogger(TensorWrapper.class);
    public static Tensor<Float> fromImagesNC(Image[][] imageNC, int fromIncl, int toExcl, float[][] bufferContainer, boolean... flipXYZ) {
        if (imageNC==null) return null;
        int[][] shapes = ResizeUtils.getShapes(imageNC, true);
        if (Arrays.stream(shapes).anyMatch(s -> !Arrays.equals(s, shapes[0]))) throw new IllegalArgumentException("at least two images have different dimensiosns");
        int[] shape = shapes[0]; // dim order here is C (Z) Y X
        // dim order should be : N (Z) Y X C
        int nSize = toExcl - fromIncl;
        int nStride = (int)ResizeUtils.getSize(shape);
        int totalSize = nSize * (int)ResizeUtils.getSize(shape);
        float[] buffer = null;
        if (bufferContainer!=null) buffer = bufferContainer[0];
        if (buffer==null || buffer.length<totalSize) buffer = new float[totalSize];
        boolean hasZ = shape.length==4;
        int zSize = hasZ ? shape[1] : 1;
        int ySize = shape[hasZ ? 2 : 1];
        int xSize = shape[hasZ ? 3 : 2];
        int cSize = shape[0];
        int idx = 0;
        TransferToBuffer trans =  (flipXYZ!=null && flipXYZ.length>0) ? new TransferToBufferFlip(zSize, ySize, xSize, cSize, flipXYZ.length>=3 && flipXYZ[2], flipXYZ.length>=2 && flipXYZ[1], flipXYZ[0], false)
            : new TransferToBufferSimple(zSize, ySize, xSize, cSize, false);
        for (int n = fromIncl; n<toExcl; ++n) idx = trans.toBuffer(buffer, imageNC[n], idx);
        long[] newShape = new long[shape.length+1];
        newShape[0] = nSize;
        if (hasZ) newShape[1] = zSize;
        newShape[hasZ ? 2 : 1] = ySize;
        newShape[hasZ ? 3 : 2] = xSize;
        newShape[hasZ ? 4 : 3] = cSize;
        //logger.debug("shape: {}", newShape);
        return Tensor.create(newShape, FloatBuffer.wrap(buffer, 0, totalSize));
    }

    public static Image[][] getImagesNC(Tensor<Float> tensor, float[][] bufferContainer, boolean... flipXYZ) {
        long[] shape = tensor.shape(); // N (Z) Y X C
        boolean hasZ = shape.length==5;
        int nSize = (int)shape[0];
        int zSize = hasZ ? (int)shape[1] : 1;
        int ySize = (int)shape[hasZ ? 2 : 1];
        int xSize = (int)shape[hasZ ? 3 : 2];
        int cSize = (int)shape[hasZ ? 4 : 3];
        Image[][] res = new Image[nSize][cSize];
        int totalSize = (int)ResizeUtils.getSize(shape);
        float[] buffer = null;
        if (bufferContainer!=null) buffer = bufferContainer[0];
        if (buffer == null || buffer.length<totalSize) buffer = new float[totalSize];
        tensor.writeTo(FloatBuffer.wrap(buffer, 0, totalSize));
        int idx = 0;
        for (int n = 0; n<nSize; ++n){
            for (int c = 0; c<cSize; ++c) {
                res[n][c] = new ImageFloat("", xSize, ySize, zSize);
            }
        }
        TransferToBuffer trans =  (flipXYZ!=null && flipXYZ.length>0) ? new TransferToBufferFlip(zSize, ySize, xSize, cSize, flipXYZ.length>=3 && flipXYZ[2], flipXYZ.length>=2 && flipXYZ[1], flipXYZ[0], false)
                : new TransferToBufferSimple(zSize, ySize, xSize, cSize, false);
        for (int n = 0; n<nSize; ++n) idx = trans.toBuffer(buffer, res[n], idx);
        return res;
    }

    public static void addToImagesNC(Image[][] targetNC, Tensor<Float> tensor, float[][] bufferContainer, boolean... flipXYZ) {
        long[] shape = tensor.shape(); // N (Z) Y X C
        boolean hasZ = shape.length==5;
        int nSize = (int)shape[0];
        int zSize = hasZ ? (int)shape[1] : 1;
        int ySize = (int)shape[hasZ ? 2 : 1];
        int xSize = (int)shape[hasZ ? 3 : 2];
        int cSize = (int)shape[hasZ ? 4 : 3];
        int totalSize = (int)ResizeUtils.getSize(shape);
        float[] buffer = null;
        if (bufferContainer!=null) buffer = bufferContainer[0];
        if (buffer == null || buffer.length<totalSize) buffer = new float[totalSize];
        tensor.writeTo(FloatBuffer.wrap(buffer, 0, totalSize));
        int idx = 0;
        TransferToBuffer trans =  (flipXYZ!=null && flipXYZ.length>0) ? new TransferToBufferFlip(zSize, ySize, xSize, cSize, flipXYZ.length>=3 && flipXYZ[2], flipXYZ.length>=2 && flipXYZ[1], flipXYZ[0], true)
                : new TransferToBufferSimple(zSize, ySize, xSize, cSize, true);
        for (int n = 0; n<nSize; ++n) idx = trans.toBuffer(buffer, targetNC[n], idx);
    }

    private static long[] shape(final Dimensions image) {
        long[] shape = new long[image.numDimensions()];
        for (int d = 0; d < shape.length; d++) {
            shape[d] = image.dimension(shape.length - d - 1);
        }
        return shape;
    }

    static abstract class TransferToBuffer {
        final int zSize, ySize, xSize, cSize;
        final boolean add;
        protected TransferToBuffer(int zSize, int ySize, int xSize, int cSize, boolean add) {
            this.zSize = zSize;
            this.ySize = ySize;
            this.xSize = xSize;
            this.cSize = cSize;
            this.add=add;
        }
        abstract int toBuffer(float[] buffer, Image[] imageC, int offset);
        abstract int fromBuffer(float[] buffer, ImageFloat[] imageC, int offset);
    }
    private static class TransferToBufferSimple extends TransferToBuffer {

        protected TransferToBufferSimple(int zSize, int ySize, int xSize, int cSize, boolean add) {
            super(zSize, ySize, xSize, cSize, add);
        }

        public int toBuffer (float[] buffer, Image[] imageC, int offset) {
            for (int z = 0; z < zSize; ++z) {
                for (int y = 0; y < ySize; ++y) {
                    for (int x = 0; x < xSize; ++x) {
                        for (int c = 0; c < cSize; ++c) {
                            buffer[offset++] = imageC[c].getPixel(x, y, z);
                        }
                    }
                }
            }
            return offset;
        }
        public int fromBuffer (float[] buffer, ImageFloat[] imageC, int offset) {
            for (int z = 0; z<zSize; ++z) {
                for (int y = 0; y<ySize; ++y) {
                    for (int x = 0; x<xSize; ++x) {
                        for (int c = 0; c<cSize; ++c) {
                            imageC[c].setPixel(x, y, z, buffer[offset++]);
                        }
                    }
                }
            }
            return offset;
        }
    }
    @FunctionalInterface
    interface IntToIntFunction {
        int toInt(int i);
    }
    private static class TransferToBufferFlip extends TransferToBuffer {
        IntToIntFunction fZ, fY, fX;
        public TransferToBufferFlip(int zSize, int ySize, int xSize, int cSize, boolean flipZ, boolean flipY, boolean flipX, boolean add) {
            super(zSize, ySize, xSize, cSize, add);
            fX = flipX ? x -> xSize - x : x->x;
            fY = flipY ? y -> ySize - y : y->y;
            fZ = flipZ ? z -> zSize - z : z->z;
        }
        public int toBuffer ( float[] buffer, Image[] imageC, int offset) {
            for (int z = 0; z < zSize; ++z) {
                for (int y = 0; y < ySize; ++y) {
                    for (int x = 0; x < xSize; ++x) {
                        for (int c = 0; c < cSize; ++c) {
                            buffer[offset++] = imageC[c].getPixel(fX.toInt(x), fY.toInt(y), fZ.toInt(z));
                        }
                    }
                }
            }
            return offset;
        }
        public int fromBuffer (float[] buffer, ImageFloat[] imageC, int offset) {
            if (add) {
                for (int z = 0; z < zSize; ++z) {
                    for (int y = 0; y < ySize; ++y) {
                        for (int x = 0; x < xSize; ++x) {
                            for (int c = 0; c < cSize; ++c) {
                                imageC[c].addPixel(fX.toInt(x), fY.toInt(y), fZ.toInt(z), buffer[offset++]);
                            }
                        }
                    }
                }
            } else {
                for (int z = 0; z < zSize; ++z) {
                    for (int y = 0; y < ySize; ++y) {
                        for (int x = 0; x < xSize; ++x) {
                            for (int c = 0; c < cSize; ++c) {
                                imageC[c].setPixel(fX.toInt(x), fY.toInt(y), fZ.toInt(z), buffer[offset++]);
                            }
                        }
                    }
                }
            }
            return offset;
        }
    }
}
