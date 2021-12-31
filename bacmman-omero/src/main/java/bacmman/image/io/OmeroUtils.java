package bacmman.image.io;

import bacmman.image.*;
import omero.gateway.rnd.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

public class OmeroUtils {
    public static final Logger logger = LoggerFactory.getLogger(OmeroUtils.class);
    /**
     * Converts the passed byte array to a buffered image.
     *
     * @param values The values to convert.
     * @return See above.
     * encoding process.
     */
    public static BufferedImage bytesToImage(byte[] values) {
        if (values == null)
            throw new IllegalArgumentException("No array specified.");
        ByteArrayInputStream stream = null;
        try {
            stream = new ByteArrayInputStream(values);
            BufferedImage image = ImageIO.read(stream);
            if (image != null) image.setAccelerationPriority(1f);
            return image;
        } catch (Exception e) {
            logger.error("error creating thmubnail", e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ex) {}
            }
        }
        return null;
    }
    public static Image convertPlane(byte[] source, Image buffer, int sizeX, int sizeY, String pixelsType) {
        if (DataSink.UINT_8.equals(pixelsType)) {
            return new ImageByte("", sizeX, new byte[][]{source});
        } else if (DataSink.INT_8.equals(pixelsType)) {
            ImageByte res = buffer!=null ? (ImageByte)buffer : new ImageByte("", sizeX, sizeY, 1);
            byte[] target = res.getPixelArray()[0];
            for (int i = 0; i<target.length; ++i) target[i] = (byte)intConv(source, i, 1);
            return res;
        } else if (DataSink.UINT_16.equals(pixelsType)) {
            ImageShort res = buffer!=null ? (ImageShort)buffer : new ImageShort("", sizeX, sizeY, 1);
            short[] target = res.getPixelArray()[0];
            for (int i = 0; i<target.length; ++i) target[i] = (short)uintConv(source, i, 2);
            return res;
        } else if (DataSink.INT_16.equals(pixelsType)) {
            ImageShort res = buffer!=null ? (ImageShort)buffer : new ImageShort("", sizeX, sizeY, 1);
            short[] target = res.getPixelArray()[0];
            for (int i = 0; i<target.length; ++i) target[i] = (short)intConv(source, i, 2);
            return res;
        } else if (DataSink.UINT_32.equals(pixelsType)) {
            ImageInt res = buffer!=null ? (ImageInt)buffer : new ImageInt("", sizeX, sizeY, 1);
            int[] target = res.getPixelArray()[0];
            for (int i = 0; i<target.length; ++i) target[i] = uintConv(source, i, 4);
            return res;
        } else if (DataSink.INT_32.equals(pixelsType)) {
            ImageInt res = buffer!=null ? (ImageInt)buffer : new ImageInt("", sizeX, sizeY, 1);
            int[] target = res.getPixelArray()[0];
            for (int i = 0; i<target.length; ++i) target[i] = intConv(source, i, 4);
            return res;
        } else if (DataSink.FLOAT.equals(pixelsType)) {
            ImageFloat res = buffer!=null ? (ImageFloat)buffer : new ImageFloat("", sizeX, sizeY, 1);
            float[] target = res.getPixelArray()[0];
            for (int i = 0; i<target.length; ++i) target[i] = floatConv(source, i, 4);
            return res;
        }
        return null;
    }
    public static int uintConv(byte[] data, int offset, int length) {
        int r = 0;
        for (int k = 0; k < length; ++k) {
            r |= (data[length*offset+k]&0xFF)<<(length-k-1)*8;
        }
        return r;
    }
    public static int intConv(byte[] data, int offset, int length) {
        int r = 0, paddingMask = -1;
        for (int k = 0; k < length; ++k) {
            r |= (data[length*offset+k]&0xFF)<<(length-k-1)*8;
            paddingMask <<= 8;
        }
        if (data[offset] < 0)   r |= paddingMask;  //Was negative, pad.
        return r;
    }
    public static float floatConv(byte[] data, int offset, int length) {
        int r = 0;
        for (int k = 0; k < length; ++k) {
            r |= (data[length*offset+k]&0xFF)<<(length-k-1)*8;
        }
        return Float.intBitsToFloat(r);
    }
}
