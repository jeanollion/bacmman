package bacmman.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class IconUtils {
    public static final Logger logger = LoggerFactory.getLogger(IconUtils.class);

    public static BufferedImage zoom(BufferedImage image, int factor) {
        if (image==null) return null;
        BufferedImage after = new BufferedImage(image.getWidth() * factor, image.getHeight() * factor, BufferedImage.TYPE_INT_ARGB);
        AffineTransform at = new AffineTransform();
        at.scale(factor, factor);
        AffineTransformOp scaleOp =  new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        return scaleOp.filter(image, after);
    }

    public static BufferedImage zoomToSize(BufferedImage image, int size) {
        if (image==null) return null;
        double factorX = (double)size/image.getWidth();
        double factorY = (double)size/image.getHeight();
        BufferedImage after = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        AffineTransform at = new AffineTransform();
        at.scale(factorX, factorY);
        AffineTransformOp scaleOp =  new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        return scaleOp.filter(image, after);
    }

    public static byte[] toByteArray(BufferedImage bi) {
        return toByteArray(bi, "png");
    }
    public static byte[] toByteArray(BufferedImage bi, String format) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bi, format, baos);
            byte[] bytes = baos.toByteArray();
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }
    public static BufferedImage toBufferedImage(byte[] bytes) {
        try {
        InputStream is = new ByteArrayInputStream(bytes);
        BufferedImage bi = ImageIO.read(is);
        return bi;
        } catch (IOException e) {
            return null;
        }
    }

    public static BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage) return (BufferedImage) img;
        // Create a buffered image with transparency
        BufferedImage bimage = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        // Draw the image on to the buffered image
        Graphics2D bGr = bimage.createGraphics();
        bGr.drawImage(img, 0, 0, null);
        bGr.dispose();
        return bimage;
    }
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
}
