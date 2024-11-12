package bacmman.image.io;
import bacmman.image.Image;
import java.io.IOException;

public interface ImageReader {
    ImageReader setInvertTZ(boolean invertTZ);
    void closeReader();
    Image openImage(ImageIOCoordinates coords) throws IOException;
    double[] getScaleXYZ(double defaultValue);
    double getTimePoint(int c, int t, int z);
    boolean imageExists();
}
