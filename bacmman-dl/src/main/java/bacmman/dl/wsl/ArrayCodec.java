package bacmman.dl.wsl;

import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageFloat16;
import bacmman.image.PrimitiveType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Conversion between images and raw arrays exchanged with the WSL prediction server.
 * Arrays are little-endian, C-ordered, in channels-last layout: N, (Z), Y, X, C (same layout as TF2Engine tensors).
 * Flips are applied while encoding, and undone while accumulating predictions of flipped inputs.
 */
public class ArrayCodec {

    public enum DType {
        FLOAT32("float32", 4), INT32("int32", 4), FLOAT16("float16", 2);
        public final String name;
        public final int itemSize;
        DType(String name, int itemSize) {
            this.name = name;
            this.itemSize = itemSize;
        }
        public static DType fromName(String name) {
            for (DType t : values()) if (t.name.equals(name)) return t;
            throw new IllegalArgumentException("Unsupported array type: " + name + " (supported: float32, float16, int32)");
        }
    }

    public static class ArraySpec {
        public final String name;
        public final DType dtype;
        public final long[] shape;
        public ArraySpec(String name, DType dtype, long[] shape) {
            this.name = name;
            this.dtype = dtype;
            this.shape = shape;
        }
        public long byteCount() {
            long n = dtype.itemSize;
            for (long s : shape) n *= s;
            return n;
        }
        @Override
        public String toString() {
            return name + " " + dtype.name + " " + Arrays.toString(shape);
        }
    }

    /**
     * @return INT32 if no image is floating point (same rule as TF2Engine), FLOAT32 otherwise
     */
    public static DType inputType(Image[][] imagesNC) {
        return Arrays.stream(imagesNC).flatMap(Arrays::stream).anyMatch(PrimitiveType::floatingPoint) ? DType.FLOAT32 : DType.INT32;
    }

    /**
     * @return shape N, (Z), Y, X, C of samples [fromIncl; toExcl)
     */
    public static long[] shape(Image[][] imagesNC, int fromIncl, int toExcl) {
        Image ref = imagesNC[fromIncl][0];
        int nC = imagesNC[fromIncl].length;
        for (int n = fromIncl; n < toExcl; ++n) {
            if (imagesNC[n].length != nC) throw new IllegalArgumentException("samples have different channel numbers");
            for (Image im : imagesNC[n]) {
                if (im.sizeX() != ref.sizeX() || im.sizeY() != ref.sizeY() || im.sizeZ() != ref.sizeZ()) throw new IllegalArgumentException("at least two images have different dimensions");
            }
        }
        if (ref.sizeZ() > 1) return new long[]{toExcl - fromIncl, ref.sizeZ(), ref.sizeY(), ref.sizeX(), nC};
        else return new long[]{toExcl - fromIncl, ref.sizeY(), ref.sizeX(), nC};
    }

    public static long byteCountPerSample(Image[][] imagesNC, DType type) {
        Image ref = imagesNC[0][0];
        return (long) ref.sizeX() * ref.sizeY() * ref.sizeZ() * imagesNC[0].length * type.itemSize;
    }

    /**
     * Encodes samples [fromIncl; toExcl) into a raw array
     * @param flipXYZ optional flips along X, Y, Z
     */
    public static byte[] encode(Image[][] imagesNC, int fromIncl, int toExcl, DType type, boolean... flipXYZ) {
        long[] shape = shape(imagesNC, fromIncl, toExcl);
        long size = new ArraySpec("", type, shape).byteCount();
        if (size > Integer.MAX_VALUE - 16) throw new IllegalArgumentException("Batch too large to be transferred: reduce the batch size");
        byte[] data = new byte[(int) size];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        boolean fX = flipXYZ != null && flipXYZ.length > 0 && flipXYZ[0];
        boolean fY = flipXYZ != null && flipXYZ.length > 1 && flipXYZ[1];
        boolean fZ = flipXYZ != null && flipXYZ.length > 2 && flipXYZ[2];
        Image ref = imagesNC[fromIncl][0];
        int sX = ref.sizeX(), sY = ref.sizeY(), sZ = ref.sizeZ(), nC = imagesNC[fromIncl].length;
        FloatBuffer fb = type == DType.FLOAT32 ? bb.asFloatBuffer() : null;
        IntBuffer ib = type == DType.INT32 ? bb.asIntBuffer() : null;
        ShortBuffer sb = type == DType.FLOAT16 ? bb.asShortBuffer() : null;
        int idx = 0;
        float[][] floatPlanes = new float[nC][];
        for (int n = fromIncl; n < toExcl; ++n) {
            Image[] channels = imagesNC[n];
            for (int z = 0; z < sZ; ++z) {
                int zz = fZ ? sZ - 1 - z : z;
                for (int c = 0; c < nC; ++c) floatPlanes[c] = channels[c] instanceof ImageFloat ? ((ImageFloat) channels[c]).getPixelArray()[zz] : null;
                for (int y = 0; y < sY; ++y) {
                    int yy = fY ? sY - 1 - y : y;
                    for (int x = 0; x < sX; ++x) {
                        int xy = (fX ? sX - 1 - x : x) + yy * sX;
                        for (int c = 0; c < nC; ++c) {
                            double v = floatPlanes[c] != null ? floatPlanes[c][xy] : channels[c].getPixel(xy, zz);
                            switch (type) {
                                case FLOAT32:
                                    fb.put(idx, (float) v);
                                    break;
                                case INT32:
                                    ib.put(idx, (int) Math.round(v));
                                    break;
                                case FLOAT16:
                                    sb.put(idx, ImageFloat16.toShort((float) v));
                                    break;
                            }
                            ++idx;
                        }
                    }
                }
            }
        }
        return data;
    }

    /**
     * Decodes a prediction into new images (ImageFloat16 for float16 arrays, ImageFloat otherwise)
     * @return images N, C
     */
    public static Image[][] decode(byte[] data, ArraySpec spec) {
        int[] dims = dims(spec);
        int nN = dims[0], sZ = dims[1], sY = dims[2], sX = dims[3], nC = dims[4];
        int sXY = sX * sY;
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        Image[][] res = new Image[nN][nC];
        int idx = 0;
        switch (spec.dtype) {
            case FLOAT16: {
                ShortBuffer sb = bb.asShortBuffer();
                for (int n = 0; n < nN; ++n) {
                    short[][][] pix = new short[nC][sZ][sXY];
                    for (int z = 0; z < sZ; ++z) {
                        for (int xy = 0; xy < sXY; ++xy) {
                            for (int c = 0; c < nC; ++c) pix[c][z][xy] = sb.get(idx++);
                        }
                    }
                    for (int c = 0; c < nC; ++c) res[n][c] = new ImageFloat16("", sX, pix[c]);
                }
                break;
            }
            case FLOAT32:
            case INT32:
            default: {
                FloatBuffer fb = spec.dtype == DType.FLOAT32 ? bb.asFloatBuffer() : null;
                IntBuffer ib = spec.dtype == DType.INT32 ? bb.asIntBuffer() : null;
                for (int n = 0; n < nN; ++n) {
                    float[][][] pix = new float[nC][sZ][sXY];
                    for (int z = 0; z < sZ; ++z) {
                        for (int xy = 0; xy < sXY; ++xy) {
                            for (int c = 0; c < nC; ++c) {
                                pix[c][z][xy] = fb != null ? fb.get(idx) : ib.get(idx);
                                ++idx;
                            }
                        }
                    }
                    for (int c = 0; c < nC; ++c) res[n][c] = new ImageFloat("", sX, pix[c]);
                }
            }
        }
        return res;
    }

    /**
     * Adds a prediction made on flipped inputs to existing images, flipping it back
     * @param targetNC images N, C that receive the sum
     */
    public static void addTo(Image[][] targetNC, byte[] data, ArraySpec spec, boolean... flipXYZ) {
        int[] dims = dims(spec);
        int nN = dims[0], sZ = dims[1], sY = dims[2], sX = dims[3], nC = dims[4];
        boolean fX = flipXYZ != null && flipXYZ.length > 0 && flipXYZ[0];
        boolean fY = flipXYZ != null && flipXYZ.length > 1 && flipXYZ[1];
        boolean fZ = flipXYZ != null && flipXYZ.length > 2 && flipXYZ[2];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer fb = spec.dtype == DType.FLOAT32 ? bb.asFloatBuffer() : null;
        IntBuffer ib = spec.dtype == DType.INT32 ? bb.asIntBuffer() : null;
        ShortBuffer sb = spec.dtype == DType.FLOAT16 ? bb.asShortBuffer() : null;
        int idx = 0;
        for (int n = 0; n < nN; ++n) {
            for (int z = 0; z < sZ; ++z) {
                int zz = fZ ? sZ - 1 - z : z;
                for (int y = 0; y < sY; ++y) {
                    int yy = fY ? sY - 1 - y : y;
                    for (int x = 0; x < sX; ++x) {
                        int xy = (fX ? sX - 1 - x : x) + yy * sX;
                        for (int c = 0; c < nC; ++c) {
                            double v = fb != null ? fb.get(idx) : (ib != null ? ib.get(idx) : ImageFloat16.toHalfFloat(sb.get(idx)));
                            ++idx;
                            Image t = targetNC[n][c];
                            t.setPixel(xy, zz, t.getPixel(xy, zz) + v);
                        }
                    }
                }
            }
        }
    }

    /**
     * @return N, Z, Y, X, C of an array of shape N (Z) Y X C, or N C (scalar outputs)
     */
    static int[] dims(ArraySpec spec) {
        long[] s = spec.shape;
        switch (s.length) {
            case 2:
                return new int[]{(int) s[0], 1, 1, 1, (int) s[1]};
            case 4:
                return new int[]{(int) s[0], 1, (int) s[1], (int) s[2], (int) s[3]};
            case 5:
                return new int[]{(int) s[0], (int) s[1], (int) s[2], (int) s[3], (int) s[4]};
            default:
                throw new IllegalArgumentException("Unsupported output shape: " + Arrays.toString(s) + " expected N, (Z), Y, X, C");
        }
    }
}
