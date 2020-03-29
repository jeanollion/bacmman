package bacmman.plugins;

import bacmman.image.Image;
import bacmman.processing.ImageOperations;

import java.util.stream.IntStream;

public interface DLengine extends Plugin {
    /**
     *
     * @param inputNC
     * @return prediction. shape: output / batch / channel
     */
    Image[][][] process(Image[][]... inputNC);
    void init();
    int getNumOutputArrays();
    int getNumInputArrays();
    DLengine setOutputNumber(int outputNumber);
    DLengine setInputNumber(int outputNumber);
    void close();
}
