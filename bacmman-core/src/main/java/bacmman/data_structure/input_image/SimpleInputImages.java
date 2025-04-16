package bacmman.data_structure.input_image;

import bacmman.image.Image;

public class SimpleInputImages implements InputImages {
    Image[][] imagesCT;
    final String tmpDir;

    public SimpleInputImages(String tmpDir, Image[]... imagesCT) {
        this.imagesCT = imagesCT;
        this.tmpDir = tmpDir;
    }

    @Override
    public String getTmpDirectory() {
        return tmpDir;
    }

    @Override
    public boolean sourceImagesLinked() {
        return true;
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
    public int getMinFrame() {
        return 0;
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
    public void freeMemory() {

    }

    @Override
    public double getCalibratedTimePoint(int c, int t, int z) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean singleFrameChannel(int channelIdx) {
        return false;
    }

    @Override
    public void setMemoryProportionLimit(double memoryProportionLimit) {

    }
}
