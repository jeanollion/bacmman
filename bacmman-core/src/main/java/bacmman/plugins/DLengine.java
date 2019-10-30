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
    int[][] getInputShapes();
    int getNumOutputArrays();
    void close();

    static void scale(Image[][] imageNC, HistogramScaler scaler) {
        if (scaler==null) return;
        int n_im = imageNC.length;
        int n_chan = imageNC[0].length;
        IntStream.range(0, n_im * n_chan).parallel().forEach(i -> {
            int im_idx = i / n_chan;
            int chan_idx = i % n_chan;
            imageNC[im_idx][chan_idx] =  scaler.scale(imageNC[im_idx][chan_idx]);
        });
    }
}
