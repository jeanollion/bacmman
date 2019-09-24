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
    public static Tensor<Float> fromImagesNC(Image[][] imageNC, int fromIncl, int toExcl, float[][] bufferContainer) {
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
        for (int n = fromIncl; n<toExcl; ++n) {
            for (int z = 0; z<zSize; ++z) {
                for (int y = 0; y<ySize; ++y) {
                    for (int x = 0; x<xSize; ++x) {
                        for (int c = 0; c<cSize; ++c) {
                            buffer[idx++] = imageNC[n][c].getPixel(x, y, z);
                        }
                    }
                }
            }
        }
        long[] newShape = new long[shape.length+1];
        newShape[0] = nSize;
        if (hasZ) newShape[1] = zSize;
        newShape[hasZ ? 2 : 1] = ySize;
        newShape[hasZ ? 3 : 2] = xSize;
        newShape[hasZ ? 4 : 3] = cSize;
        logger.debug("shape: {}", newShape);
        return Tensor.create(newShape, FloatBuffer.wrap(buffer, 0, totalSize));
    }
    public static Image[][] getImagesNC(Tensor<Float> tensor, float[][] bufferContainer) {
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
        for (int n = 0; n<nSize; ++n) {
            for (int z = 0; z<zSize; ++z) {
                for (int y = 0; y<ySize; ++y) {
                    for (int x = 0; x<xSize; ++x) {
                        for (int c = 0; c<cSize; ++c) {
                            res[n][c].setPixel(x, y, z, buffer[idx++]);
                        }
                    }
                }
            }
        }
        return res;
    }
    /*public static Image[] getImagesC(Tensor<Float> tensor) {
        Img img = Tensors.imgFloat(tensor, new int[]{1, 0, 2});
        logger.debug("get image: shape: {}", shape(img));
        Image image = ImgLib2ImageWrapper.wrap(img);
        List<? extends Image> planes = image.splitZPlanes();
        return planes.stream().toArray(Image[]::new);
    }*/

    private static long[] shape(final Dimensions image) {
        long[] shape = new long[image.numDimensions()];
        for (int d = 0; d < shape.length; d++) {
            shape[d] = image.dimension(shape.length - d - 1);
        }
        return shape;
    }
}
