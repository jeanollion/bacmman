package bacmman.image;

import java.util.Comparator;

public interface PrimitiveType {
    Object[] getPixelArray();
    int byteCount();
    boolean floatingPoint();
    interface ByteType extends PrimitiveType {
        byte[][] getPixelArray();
        default int byteCount() {return 1;}
        default boolean floatingPoint() {return false;}
    }
    interface ShortType extends PrimitiveType {
        short[][] getPixelArray();
        default int byteCount() {return 2;}
        default boolean floatingPoint() {return false;}
    }
    interface FloatType extends PrimitiveType {
        float[][] getPixelArray();
        default int byteCount() {return 4;}
        default boolean floatingPoint() {return true;}
    }
    interface IntType extends PrimitiveType {
        int[][] getPixelArray();
        default int byteCount() {return 4;}
        default boolean floatingPoint() {return false;}
    }
    interface DoubleType extends PrimitiveType {
        double[][] getPixelArray();
        default int byteCount() {return 8;}
        default boolean floatingPoint() {return true;}
    }

    static Comparator<Image> typeComparator() { // floating point last,
        return (i1, i2) -> {
            int c = Boolean.compare(i1.floatingPoint(), i2.floatingPoint());
            if (c == 0) {
                return Integer.compare(i1.byteCount(), i2.byteCount());
            } else return c;
        };
    }
}
