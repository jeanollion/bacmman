package bacmman.image.io;

import bacmman.core.OmeroGatewayI;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    private void initIfNecessary() throws IOException {
        if (rawData == null) {
            synchronized (this) {
                if (rawData == null) init();
            }
        }
    }

    private void init() throws IOException {
        try {
            rawData = gateway.gateway().createPixelsStore(gateway.securityContext());
            ImageData imData = gateway.browse().getImage(gateway.securityContext(), fileId);
            pixels = imData.getDefaultPixels();
            rawData.setPixelsId(pixels.getId(), false);
            scaleXY= pixels.getPixelSizeX(UnitsLength.MICROMETER)!=null? pixels.getPixelSizeX(UnitsLength.MICROMETER).getValue() : 1;
            scaleZ= pixels.getPixelSizeZ(UnitsLength.MICROMETER)!=null ? pixels.getPixelSizeZ(UnitsLength.MICROMETER).getValue() : 1;
        } catch (DSOutOfServiceException | DSAccessException | ServerError | BigResult e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean imageExists() {
        try {
            initIfNecessary();
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public void closeReader() {
        try {
            rawData.close();
        } catch (ServerError e) {
        } finally {
            rawData = null;
            pixels = null;
        }
    }

    @Override
    public Image openImage(ImageIOCoordinates coords) throws IOException {
        initIfNecessary();
        // TODO add possibility to open only tile from server
        MutableBoundingBox bounds = coords.getBounds() == null ? new MutableBoundingBox(0, pixels.getSizeX() - 1, 0, pixels.getSizeY() - 1, 0, invertTZ ? pixels.getSizeT() -1 : pixels.getSizeZ() - 1) : new MutableBoundingBox(coords.getBounds());
        if (bounds.sizeX()<=0) bounds.setxMin(0).setxMax(pixels.getSizeX()-1);
        if (bounds.sizeY()<=0) bounds.setyMin(0).setyMax(pixels.getSizeY()-1);
        List<Image> images = new ArrayList<>(bounds.sizeZ());
        for (int z = bounds.zMin(); z<=bounds.zMax(); ++z) images.add(gateway.getPlane(pixels, rawData, invertTZ ? coords.getTimePoint() : z, coords.getChannel(), !invertTZ ? coords.getTimePoint() : z));
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