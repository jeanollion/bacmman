package bacmman.dl;

import bacmman.image.*;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.util.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.IntStream;

public class INDArrayImageWrapper {
    public final static Logger logger = LoggerFactory.getLogger(INDArrayImageWrapper.class);

    public static INDArray fromImagesNC(Image[] imagesN, int fromIncl, int toExcl) {
        return Nd4j.concat(0, IntStream.range(fromIncl, toExcl).mapToObj(i->fromImage(imagesN[i])).toArray(INDArray[]::new));
    }

    public static INDArray fromImagesNC(Image[][] imagesNC, int fromIncl, int toExcl) {
        return Nd4j.concat(0, IntStream.range(fromIncl, toExcl).mapToObj(i->fromImagesC(imagesNC[i])).toArray(INDArray[]::new));
    }
    public static INDArray fromImagesC(Image... imagesC) {
        return Nd4j.concat(1, Arrays.stream(imagesC).map(i->fromImage(i)).toArray(INDArray[]::new));
    }

    public static INDArray fromImage(Image image) {
        image = TypeConverter.toCommonImageType(image);
        if (image.sizeZ()==1) {
            switch (image.getBitDepth()) {
                case 8:
                    return Nd4j.create(((ImageByte) image).getPixelArray()[0], new long[]{1, 1, image.sizeY(), image.sizeX()}, DataType.BYTE);
                case 16:
                    return Nd4j.create(((ImageShort) image).getPixelArray()[0], new long[]{1, 1, image.sizeY(), image.sizeX()}, DataType.SHORT);
                case 32:
                    return Nd4j.create(((ImageFloat) image).getPixelArray()[0], new long[]{1, 1, image.sizeY(), image.sizeX()}, DataType.FLOAT);
                default:
                    throw new IllegalArgumentException("Unsupported image type");
            }
        } else {
            switch (image.getBitDepth()) {
                case 8:
                    return Nd4j.create(ArrayUtil.flatten(((ImageByte) image).getPixelArray()), new long[]{1, 1, image.sizeZ(), image.sizeY(), image.sizeX()}, DataType.BYTE);
                case 16:
                    return Nd4j.create(ArrayUtil.flatten(((ImageShort) image).getPixelArray()), new long[]{1, 1, image.sizeZ(), image.sizeY(), image.sizeX()}, DataType.SHORT);
                case 32:
                    return Nd4j.create(ArrayUtil.flatten(((ImageFloat) image).getPixelArray()), new long[]{1, 1, image.sizeZ(), image.sizeY(), image.sizeX()}, DataType.FLOAT);
                default:
                    throw new IllegalArgumentException("Unsupported image type");
            }
        }
    }

    public static Image getImage(INDArray array, int imageIdx, int channelIdx) {
        long[] shape = array.shape();
        if (shape.length!=4) throw new IllegalArgumentException("only rank 4 arrays are currently supported");
        INDArray subArray = array.get(NDArrayIndex.point(imageIdx), NDArrayIndex.point(channelIdx), NDArrayIndex.all(), NDArrayIndex.all());
        float[] values =  (imageIdx==0 && channelIdx==0) ? subArray.data().getFloatsAt(0, (int)subArray.length()): // in case offset == 0, the buffer of the subArray is the buffer of the original array to the whole original array is returned by the asFloat() method
                                                            subArray.data().asFloat();
        return ImageFloat.createImageFrom2DPixelArray("", values, (int)shape[3]);
    }
    public static Image[] getImagesC(INDArray array, int imageIdx) {
        return IntStream.range(0, (int)array.shape()[1]).mapToObj(c -> getImage(array, imageIdx, c)).toArray(Image[]::new);
    }
}
