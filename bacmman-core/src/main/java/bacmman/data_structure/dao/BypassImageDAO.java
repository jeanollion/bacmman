package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.input_image.InputImage;
import bacmman.data_structure.input_image.InputImages;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.image.BlankMask;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
import bacmman.utils.HashMapGetCreate;

import java.io.InputStream;

public class BypassImageDAO implements ImageDAO {
    final Experiment xp;
    final HashMapGetCreate<String, InputImagesImpl> inputImages;

    public BypassImageDAO(Experiment xp) {
        this.xp=xp;
        this.inputImages = new HashMapGetCreate<>(p->xp.getPosition(p).getInputImages());
    }

    @Override
    public String getImageExtension() {
        return null;
    }

    @Override
    public InputStream openPreProcessedImageAsStream(int channelImageIdx, int timePoint, String microscopyFieldName) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, String microscopyFieldName) {
        InputImagesImpl ii = inputImages.getAndCreateIfNecessary(microscopyFieldName);
        Image res = ii.getImage(channelImageIdx, timePoint);
        ii.flush(channelImageIdx, timePoint);
        return res;
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, String microscopyFieldName, MutableBoundingBox bounds) {
        Image im = openPreProcessedImage(channelImageIdx, timePoint, microscopyFieldName);
        return im.crop(bounds);
    }

    @Override
    public BlankMask getPreProcessedImageProperties(int channelImageIdx, String microscopyFieldName) {
        return new BlankMask(openPreProcessedImage(channelImageIdx, 0, microscopyFieldName));
    }

    @Override
    public void writePreProcessedImage(Image image, int channelImageIdx, int timePoint, String microscopyFieldName) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public void writePreProcessedImage(InputStream image, int channelImageIdx, int timePoint, String microscopyFieldName) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public void deletePreProcessedImage(int channelImageIdx, int timePoint, String microscopyFieldName) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public void writeTrackImage(SegmentedObject trackHead, int channelImageIdx, Image image) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public Image openTrackImage(SegmentedObject trackHead, int channelImageIdx) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public InputStream openTrackImageAsStream(SegmentedObject trackHead, int channelImageIdx) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public void writeTrackImage(SegmentedObject trackHead, int channelImageIdx, InputStream image) {
        throw new IllegalArgumentException("Unsupported operation");
    }

    @Override
    public void deleteTrackImages(String position, int parentStructureIdx) {
        throw new IllegalArgumentException("Unsupported operation");
    }
}
