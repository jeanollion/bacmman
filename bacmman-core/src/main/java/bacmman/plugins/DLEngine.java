package bacmman.plugins;

import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.image.Image;
import bacmman.processing.ResizeUtils;

import java.util.Arrays;
import java.util.function.IntSupplier;
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
    default String[] getOutputNames() {return null;} // can return null
    default String[] getInputNames() {return null;} // can return null
    DLEngine setOutputNumber(int outputNumber);
    DLEngine setInputNumber(int inputNumber);
    void close();
    int[] getGPUs();

    enum Z_AXIS {Z, CHANNEL, BATCH}

    static boolean setZAxis(PluginParameter<DLEngine> pp, Z_AXIS zAxis) {
        ConditionalParameter<DLEngine.Z_AXIS> zAxisParam = ParameterUtils.getParameter(ConditionalParameter.class, pp.getParameters(), p -> p.getActionValue() instanceof DLEngine.Z_AXIS);
        if (zAxisParam == null) {
            EnumChoiceParameter<DLEngine.Z_AXIS> zAxisParamChoice = ParameterUtils.getParameter(EnumChoiceParameter.class, pp.getParameters(), p -> p.getSelectedEnum() instanceof DLEngine.Z_AXIS);
            if (zAxisParamChoice!=null) {
                zAxisParamChoice.setValue(zAxis);
                return true;
            }
        } else {
            zAxisParam.setActionValue(zAxis);
            return true;
        }
        return false;
    }

    static IntSupplier getRankSupplier(PluginParameter<DLEngine> pp, int defaultRank) {
        return () -> {
            if (pp.getParameters() == null) return defaultRank;
            ConditionalParameter<DLEngine.Z_AXIS> zAxisParam = ParameterUtils.getParameter(ConditionalParameter.class, pp.getParameters(), p -> p.getActionValue() instanceof DLEngine.Z_AXIS);
            if (zAxisParam == null) {
                EnumChoiceParameter<DLEngine.Z_AXIS> zAxisParamChoice = ParameterUtils.getParameter(EnumChoiceParameter.class, pp.getParameters(), p -> p.getSelectedEnum() instanceof DLEngine.Z_AXIS);
                if (zAxisParamChoice != null) return zAxisParamChoice.getSelectedEnum().equals(DLEngine.Z_AXIS.Z) ? 3 : 2;
            } else return zAxisParam.getActionValue().equals(DLEngine.Z_AXIS.Z) ? 3 : 2;
            return defaultRank;
        };
    }
}
