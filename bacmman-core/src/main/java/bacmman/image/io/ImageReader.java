package bacmman.image.io;

import bacmman.image.Image;

public interface ImageReader {
    ImageReader setInvertTZ(boolean invertTZ);
    void closeReader();
    Image openImage(ImageIOCoordinates coords);
    double[] getScaleXYZ(double defaultValue);
    double getTimePoint(int c, int t, int z);
}
