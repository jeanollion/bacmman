package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Position;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;

import java.io.IOException;

public class BypassImageDAO implements ImageDAO {
    InputImagesImpl inputImages;
    final Position p;
    public BypassImageDAO(Position p) {
        this.p=p;
    }
    private InputImagesImpl getInputImages() {
        if (inputImages==null) {
            synchronized (p) {
                if (inputImages==null) inputImages = p.getInputImages();
            }
        }
        return inputImages;
    }

    @Override
    public void flush() {
        inputImages.flush();
    }

    @Override
    public String getImageExtension() {
        return null;
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint) throws IOException {
        InputImagesImpl ii = getInputImages();
        Image res = ii.getImage(channelImageIdx, timePoint);
        ii.flush(channelImageIdx, timePoint);
        return res;
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, BoundingBox bounds) throws IOException {
        Image im = openPreProcessedImage(channelImageIdx, timePoint);
        return im.crop(bounds);
    }

    @Override
    public Image openPreProcessedImagePlane(int z, int channelImageIdx, int timePoint) throws IOException {
        InputImagesImpl ii = getInputImages();
        return ii.getRawPlane(z, channelImageIdx, timePoint);
    }

    @Override
    public BlankMask getPreProcessedImageProperties(int channelImageIdx) throws IOException {
        return new BlankMask(openPreProcessedImage(channelImageIdx, 0));
    }

    @Override
    public void writePreProcessedImage(Image image, int channelImageIdx, int timePoint) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public void deletePreProcessedImage(int channelImageIdx, int timePoint) {

    }
}
