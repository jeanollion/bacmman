package bacmman.image.io;

import bacmman.core.OmeroGatewayI;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
import bacmman.image.SimpleBoundingBox;
import ome.model.units.BigResult;
import omero.ServerError;
import omero.api.RawPixelsStorePrx;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.ImageData;
import omero.gateway.model.PixelsData;
import omero.model.enums.UnitsLength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ImageReaderOmero implements ImageReader {
    public static final Logger logger = LoggerFactory.getLogger(ImageReaderOmero.class);
    OmeroGatewayI gateway;
    boolean invertTZ;
    RawPixelsStorePrx rawData;
    PixelsData pixels;
    long fileId;
    double scaleXY, scaleZ;
    public ImageReaderOmero(long fileID, OmeroGatewayI gateway) {
        this.fileId = fileID;
        this.gateway = gateway;
    }

    @Override
    public ImageReader setInvertTZ(boolean invertTZ) {
        this.invertTZ = invertTZ;
        return this;
    }

    private void initIfNecessary() {
        if (rawData == null) {
            synchronized (this) {
                if (rawData == null) init();
            }
        }
    }

    private void init() {
        try {
            rawData = gateway.gateway().createPixelsStore(gateway.securityContext());
            ImageData imData = gateway.browse().getImage(gateway.securityContext(), fileId);
            pixels = imData.getDefaultPixels();
            rawData.setPixelsId(pixels.getId(), false);
            scaleXY= pixels.getPixelSizeX(UnitsLength.MICROMETER)!=null? pixels.getPixelSizeX(UnitsLength.MICROMETER).getValue() : 1;
            scaleZ= pixels.getPixelSizeZ(UnitsLength.MICROMETER)!=null ? pixels.getPixelSizeZ(UnitsLength.MICROMETER).getValue() : 1;
        } catch (DSOutOfServiceException | DSAccessException | ServerError | BigResult e) {
            logger.debug("error while retrieving image5D: ", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void closeReader() {
        rawData = null;
        pixels = null;
        /*try {
            rawData.close(); // necessary ?
        } catch (ServerError e) {

        }*/
    }

    @Override
    public Image openImage(ImageIOCoordinates coords) {
        initIfNecessary();
        // TODO add possibility to open only tile from server
        MutableBoundingBox bounds = coords.getBounds() == null ? new MutableBoundingBox(0, pixels.getSizeX() - 1, 0, pixels.getSizeY() - 1, 0, invertTZ ? pixels.getSizeT() -1 : pixels.getSizeZ() - 1) : new MutableBoundingBox(coords.getBounds());
        if (bounds.sizeX()<=0) bounds.setxMin(0).setxMax(pixels.getSizeX()-1);
        if (bounds.sizeY()<=0) bounds.setyMin(0).setyMax(pixels.getSizeY()-1);
        List<Image> images = IntStream.rangeClosed(bounds.zMin(), bounds.zMax()).mapToObj(z -> gateway.getPlane(pixels, rawData, invertTZ ? coords.getTimePoint() : z, coords.getChannel(), !invertTZ ? coords.getTimePoint() : z)).collect(Collectors.toList());
        Image image = Image.mergeZPlanes(images);
        if (coords.getBounds() != null) image = image.crop(bounds.setzMin(0).setzMax(image.sizeZ()-1));
        return image;
    }

    @Override
    public double[] getScaleXYZ(double defaultValue) {
        return new double[]{scaleXY, scaleZ};
    }

    @Override
    public double getTimePoint(int c, int t, int z) {
        return Double.NaN; // TODO
    }
}