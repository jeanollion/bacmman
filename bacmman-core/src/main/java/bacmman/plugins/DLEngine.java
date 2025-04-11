package bacmman.plugins;

import bacmman.image.Image;
import bacmman.processing.ResizeUtils;

import java.util.Arrays;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

public interface DLEngine extends Plugin, PersistentConfiguration {
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

    static int[] parseGPUList(String gpuList) {
        if (gpuList==null || gpuList.isEmpty()) return new int[0];
        String[] split = gpuList.split(",");
        return Arrays.stream(split).filter(s->!s.isEmpty()).mapToInt(Integer::parseInt).toArray();
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
    DLEngine setOutputNumber(int outputNumber);
    DLEngine setInputNumber(int outputNumber);
    void close();
    int[] getGPUs();

    enum Z_AXIS {Z, CHANNEL, BATCH}
}
