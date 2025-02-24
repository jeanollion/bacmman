package bacmman.plugins;

import bacmman.configuration.parameters.DLMetadataConfigurable;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

public interface DLengine extends Plugin, PersistentConfiguration {
    static int getSizeZ(Image[][]... inputNC) {
        ToIntFunction<Image[][]> getZ = iNC -> {
            int[] sizeZ = IntStream.range(0, iNC[0].length).map(c -> ResizeUtils.getSizeZ(iNC, c)).distinct().toArray();
            assert sizeZ.length == 1 : "different sizeZ among channels";
            return sizeZ[0];
        };
        int[] sizeZ = Arrays.stream(inputNC).mapToInt(getZ).distinct().toArray();
        assert sizeZ.length == 1 : "different sizeZ among inputs";
        return sizeZ[0];
    }

    /**
     *
     * @param inputNC
     * @return prediction. shape: output / batch / channel
     */
    Image[][][] process(Image[][]... inputNC); // O, N, C
    void init();
    int getNumOutputArrays();
    int getNumInputArrays();
    DLengine setOutputNumber(int outputNumber);
    DLengine setInputNumber(int outputNumber);
    void close();

    enum Z_AXIS {Z, CHANNEL, BATCH}
}
