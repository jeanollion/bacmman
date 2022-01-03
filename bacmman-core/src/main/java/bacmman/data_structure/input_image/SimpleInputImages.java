package bacmman.data_structure.input_image;

import bacmman.image.Image;

public class SimpleInputImages implements InputImages {
    Image[][] imagesCT;
    public SimpleInputImages(Image[]... imagesCT) {
        this.imagesCT = imagesCT;
    }
    @Override
    public Image getImage(int channelIdx, int timePoint) {
        return imagesCT[channelIdx][timePoint];
    }

    @Override
    public Image getRawPlane(int z, int channelIdx, int timePoint) {
        return imagesCT[channelIdx][timePoint].getZPlane(z);
    }

    @Override
    public int getFrameNumber() {
        return imagesCT[0].length;
    }

    @Override
    public int getChannelNumber() {
        return imagesCT.length;
    }

    @Override
    public int getDefaultTimePoint() {
        return 0;
    }

    @Override
    public int getSourceSizeZ(int channelIdx) {
        return imagesCT[channelIdx][0].sizeZ();
    }

    @Override
    public int getBestFocusPlane(int timePoint) {
        return 0;
    }

    @Override
    public void flush() {

    }

    @Override
    public double getCalibratedTimePoint(int c, int t, int z) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean singleFrameChannel(int channelIdx) {
        return false;
    }
}
