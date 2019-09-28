package bacmman.plugins;

import bacmman.image.Image;

public interface DLengine extends Plugin {
    /**
     *
     * @param inputNC
     * @return prediction. shape: output / batch / channel
     */
    Image[][][] process(Image[][]... inputNC);
    void init();
    int[][] getInputShapes();
    int getNumOutputArrays();
    void close();
}
