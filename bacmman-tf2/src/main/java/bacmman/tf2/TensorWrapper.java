package bacmman.tf2;

import bacmman.image.*;
import bacmman.processing.ResizeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tensorflow.ndarray.FloatNdArray;
import org.tensorflow.ndarray.Shape;
import org.tensorflow.ndarray.buffer.DataBuffers;
import org.tensorflow.ndarray.buffer.FloatDataBuffer;
import org.tensorflow.types.TFloat16;
import org.tensorflow.types.TFloat32;

import java.util.Arrays;

public class TensorWrapper {
    public final static Logger logger = LoggerFactory.getLogger(TensorWrapper.class);
    public static TFloat32 fromImagesNC(Image[][] imageNC, int fromIncl, int toExcl, FloatDataBuffer[] bufferContainer, boolean... flipXYZ) {
        if (imageNC==null) return null;
        int[][] shapes = ResizeUtils.getShapes(imageNC, true);
        if (Arrays.stream(shapes).anyMatch(s -> !Arrays.equals(s, shapes[0]))) throw new IllegalArgumentException("at least two images have different dimensiosns");
        int[] shape = shapes[0]; // dim order here is C (Z) Y X
        // dim order should be : N (Z) Y X C
        int nSize = toExcl - fromIncl;
        int nStride = (int)ResizeUtils.getSize(shape);
        int totalSize = nSize * (int)ResizeUtils.getSize(shape);

        FloatDataBuffer buffer = null;
        if (bufferContainer!=null) buffer = bufferContainer[0];
        if (buffer==null || buffer.size()<totalSize) buffer = DataBuffers.ofFloats(totalSize);

        boolean hasZ = shape.length==4;
        int zSize = hasZ ? shape[1] : 1;
        int ySize = shape[hasZ ? 2 : 1];
        int xSize = shape[hasZ ? 3 : 2];
        int cSize = shape[0];
        int idx = 0;

        DataTransfer trans =  (flipXYZ!=null && flipXYZ.length>0) ? new DataTransferFlip(zSize, ySize, xSize, cSize, flipXYZ.length>=3 && flipXYZ[2], flipXYZ.length>=2 && flipXYZ[1], flipXYZ[0], false)
                : new DataTransferSimple(zSize, ySize, xSize, cSize, false);
        for (int n = fromIncl; n<toExcl; ++n) idx = trans.toBuffer(buffer, imageNC[n], idx);
        long[] newShape = new long[shape.length+1];
        newShape[0] = nSize;
        if (hasZ) newShape[1] = zSize;
        newShape[hasZ ? 2 : 1] = ySize;
        newShape[hasZ ? 3 : 2] = xSize;
        newShape[hasZ ? 4 : 3] = cSize;
        return TFloat32.tensorOf(Shape.of(newShape), buffer);
    }
    public static Image[][] getImagesNC(FloatNdArray tensor, boolean forceHalfPrecision, boolean... flipXYZ) {
        long[] shape = tensor.shape().asArray(); // N (Z) Y X C
        boolean hasZ = shape.length==5;
        int nSize = (int)shape[0];
        int zSize = hasZ ? (int)shape[1] : 1;
        int ySize = (int)shape[hasZ ? 2 : 1];
        int xSize = (int)shape[hasZ ? 3 : 2];
        int cSize = (int)shape[hasZ ? 4 : 3];
        Image[][] res = new Image[nSize][cSize];

        for (int n = 0; n<nSize; ++n){
            for (int c = 0; c<cSize; ++c) {
                res[n][c] = (forceHalfPrecision || tensor instanceof TFloat16) ? new ImageFloat16("", xSize, ySize, zSize) : new ImageFloat("", xSize, ySize, zSize);
            }
        }
        DataTransfer trans =  (flipXYZ!=null && flipXYZ.length>0) ? new DataTransferFlip(zSize, ySize, xSize, cSize, flipXYZ.length>=3 && flipXYZ[2], flipXYZ.length>=2 && flipXYZ[1], flipXYZ[0], false)
                : new DataTransferSimple(zSize, ySize, xSize, cSize, false);
        for (int n = 0; n<nSize; ++n) trans.fromArray(tensor.get(n), res[n]);
        return res;
    }

    public static void addToImagesNC(Image[][] targetNC, FloatNdArray tensor, boolean... flipXYZ) {
        long[] shape = tensor.shape().asArray(); // N (Z) Y X C
        boolean hasZ = shape.length==5;
        int nSize = (int)shape[0];
        int zSize = hasZ ? (int)shape[1] : 1;
        int ySize = (int)shape[hasZ ? 2 : 1];
        int xSize = (int)shape[hasZ ? 3 : 2];
        int cSize = (int)shape[hasZ ? 4 : 3];

        DataTransfer trans =  (flipXYZ!=null && flipXYZ.length>0) ? new DataTransferFlip(zSize, ySize, xSize, cSize, flipXYZ.length>=3 && flipXYZ[2], flipXYZ.length>=2 && flipXYZ[1], flipXYZ[0], true)
                : new DataTransferSimple(zSize, ySize, xSize, cSize, true);
        for (int n = 0; n<nSize; ++n) trans.fromArray(tensor.get(n), targetNC[n]);
    }

    @FunctionalInterface
    public interface LoopFunction {
        void loop(float value);
    }
    public static double[] getMinAndMax(FloatNdArray array) {
        double[] mm = new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        loop(array, v -> {
            if (mm[0]>v) mm[0]=v;
            if (mm[1]<v) mm[1]=v;
        });
        return mm;
    }
    public static void loop(FloatNdArray array, LoopFunction function) {
        long[] shape = array.shape().asArray();
        if (array.rank()==4) {
            for (int z = 0; z < shape[0]; ++z) {
                for (int y = 0; y < shape[1]; ++y) {
                    for (int x = 0; x < shape[2]; ++x) {
                        for (int c = 0; c < shape[3]; ++c) {
                            function.loop(array.getFloat(z, y, x, c));
                        }
                    }
                }
            }
        } else if (array.rank()==3) {
            for (int y = 0; y < shape[0]; ++y) {
                for (int x = 0; x < shape[1]; ++x) {
                    for (int c = 0; c < shape[2]; ++c) {
                        function.loop(array.getFloat(y, x, c));
                    }
                }
            }
        } else throw new IllegalArgumentException("Rank not supported, should be in [3, 4]");
    }

    static abstract class DataTransfer {
        final int zSize, ySize, xSize, cSize;
        final boolean add;
        protected DataTransfer(int zSize, int ySize, int xSize, int cSize, boolean add) {
            this.zSize = zSize;
            this.ySize = ySize;
            this.xSize = xSize;
            this.cSize = cSize;
            this.add=add;
        }
        abstract int toBuffer(FloatDataBuffer buffer, Image[] imageC, int offset);
        abstract void fromArray(FloatNdArray array, Image[] imageC);
    }
    private static class DataTransferSimple extends DataTransfer {

        protected DataTransferSimple(int zSize, int ySize, int xSize, int cSize, boolean add) {
            super(zSize, ySize, xSize, cSize, add);
        }

        public int toBuffer (FloatDataBuffer buffer, Image[] imageC, int offset) {
            for (int z = 0; z < zSize; ++z) {
                for (int y = 0; y < ySize; ++y) {
                    for (int x = 0; x < xSize; ++x) {
                        for (int c = 0; c < cSize; ++c) {
                            buffer.setFloat(imageC[c].getPixel(x, y, z), offset++);
                        }
                    }
                }
            }
            return offset;
        }
        public void fromArray(FloatNdArray array, Image[] imageC) {
            if (add) {
                if (array.rank()==4) {
                    for (int z = 0; z < zSize; ++z) {
                        for (int y = 0; y < ySize; ++y) {
                            for (int x = 0; x < xSize; ++x) {
                                for (int c = 0; c < cSize; ++c) {
                                    imageC[c].addPixel(x, y, z, array.getFloat(z, y, x, c));
                                }
                            }
                        }
                    }
                } else {
                    for (int y = 0; y < ySize; ++y) {
                        for (int x = 0; x < xSize; ++x) {
                            for (int c = 0; c < cSize; ++c) {
                                imageC[c].addPixel(x, y, 0, array.getFloat(y, x, c));
                            }
                        }
                    }
                }
            } else {
                if (array.rank()==4) {
                    for (int z = 0; z < zSize; ++z) {
                        for (int y = 0; y < ySize; ++y) {
                            for (int x = 0; x < xSize; ++x) {
                                for (int c = 0; c < cSize; ++c) {
                                    imageC[c].setPixel(x, y, z, array.getFloat(z, y, x, c));
                                }
                            }
                        }
                    }
                } else {
                    for (int y = 0; y < ySize; ++y) {
                        for (int x = 0; x < xSize; ++x) {
                            for (int c = 0; c < cSize; ++c) {
                                imageC[c].setPixel(x, y, 0, array.getFloat(y, x, c));
                            }
                        }
                    }
                }
            }
        }
    }
    @FunctionalInterface
    interface IntToIntFunction {
        int toInt(int i);
    }
    private static class DataTransferFlip extends DataTransfer {
        IntToIntFunction fZ, fY, fX;
        public DataTransferFlip(int zSize, int ySize, int xSize, int cSize, boolean flipZ, boolean flipY, boolean flipX, boolean add) {
            super(zSize, ySize, xSize, cSize, add);
            fX = flipX ? x -> xSize - 1 - x : x->x;
            fY = flipY ? y -> ySize - 1 - y : y->y;
            fZ = flipZ ? z -> zSize - 1 - z : z->z;
        }
        public int toBuffer ( FloatDataBuffer buffer, Image[] imageC, int offset) {
            for (int z = 0; z < zSize; ++z) {
                for (int y = 0; y < ySize; ++y) {
                    for (int x = 0; x < xSize; ++x) {
                        for (int c = 0; c < cSize; ++c) {
                            buffer.setFloat(imageC[c].getPixel(fX.toInt(x), fY.toInt(y), fZ.toInt(z)), offset++);
                        }
                    }
                }
            }
            return offset;
        }
        public void fromArray (FloatNdArray array, Image[] imageC) {
            if (add) {
                if (array.rank()==4) {
                    for (int z = 0; z < zSize; ++z) {
                        for (int y = 0; y < ySize; ++y) {
                            for (int x = 0; x < xSize; ++x) {
                                for (int c = 0; c < cSize; ++c) {
                                    imageC[c].addPixel(fX.toInt(x), fY.toInt(y), fZ.toInt(z), array.getFloat(z, y, x, c));
                                }
                            }
                        }
                    }
                } else {
                    for (int y = 0; y < ySize; ++y) {
                        for (int x = 0; x < xSize; ++x) {
                            for (int c = 0; c < cSize; ++c) {
                                imageC[c].addPixel(fX.toInt(x), fY.toInt(y), 0, array.getFloat(y, x, c));
                            }
                        }
                    }
                }
            } else {
                if (array.rank()==4) {
                    for (int z = 0; z < zSize; ++z) {
                        for (int y = 0; y < ySize; ++y) {
                            for (int x = 0; x < xSize; ++x) {
                                for (int c = 0; c < cSize; ++c) {
                                    imageC[c].setPixel(fX.toInt(x), fY.toInt(y), fZ.toInt(z), array.getFloat(z, y, x, c));
                                }
                            }
                        }
                    }
                } else {
                    for (int y = 0; y < ySize; ++y) {
                        for (int x = 0; x < xSize; ++x) {
                            for (int c = 0; c < cSize; ++c) {
                                imageC[c].setPixel(fX.toInt(x), fY.toInt(y), 0, array.getFloat(y, x, c));
                            }
                        }
                    }
                }
            }
        }
    }
}
