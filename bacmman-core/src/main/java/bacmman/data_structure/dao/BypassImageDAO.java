package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class BypassImageDAO implements ImageDAO {
    final MultipleImageContainer sourceImages;
    int[] dupChannelMapChannel;
    public BypassImageDAO(Experiment xp, MultipleImageContainer sourceImages) {
        this.sourceImages=sourceImages;
        this.dupChannelMapChannel = getDupChannelMapChannel(xp);
    }
    public void updateXP(Experiment xp) {
        this.dupChannelMapChannel = getDupChannelMapChannel(xp);
    }
    protected int[] getDupChannelMapChannel(Experiment xp) {
        int[] dupChannelMapChannel = new int[xp.getChannelImageCount(true)];
        int[] dupSources = xp.getDuplicatedChannelSources();
        int nonDupSize = dupChannelMapChannel.length-dupSources.length;
        for (int c = 0; c<nonDupSize; ++c) dupChannelMapChannel[c] = c;
        for (int c = 0; c<dupSources.length; ++c) dupChannelMapChannel[c+nonDupSize] = dupSources[c];
        return dupChannelMapChannel;
    }

    @Override
    public void flush() {
        sourceImages.flush();
    }

    @Override
    public String getImageExtension() {
        return null;
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint) throws IOException {
        Image res = sourceImages.getImage(timePoint, dupChannelMapChannel[channelImageIdx]);
        return res;
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, BoundingBox bounds) throws IOException {
        Image im = openPreProcessedImage(channelImageIdx, timePoint);
        return im.crop(bounds);
    }

    @Override
    public Image openPreProcessedImagePlane(int z, int channelImageIdx, int timePoint) throws IOException {
        return sourceImages.getPlane(z, timePoint, dupChannelMapChannel[channelImageIdx]);
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
        // do nothing
    }
}
