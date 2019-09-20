package bacmman.plugins.plugins.dl_engines.dl4jlambda;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.TextParameter;
import bacmman.plugins.LambdaLayerDL4J;
import org.deeplearning4j.nn.conf.layers.samediff.SameDiffLambdaLayer;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ops.impl.shape.Slice;

import java.util.Arrays;

public class ExtractSliceLayer extends SameDiffLambdaLayer implements LambdaLayerDL4J {
    BoundedNumberParameter channelIdx = new BoundedNumberParameter("Channel index", 0, 0, 0, null);
    BoundedNumberParameter channelDim = new BoundedNumberParameter("Channel dimension", 0, 1, 0, null);
    TextParameter layerName = new TextParameter("Layer name", "lambda_", true);

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{layerName, channelDim, channelIdx};
    }

    @Override
    public SDVariable defineLayer(SameDiff sameDiff, SDVariable layerInput) {
        long[] shape = layerInput.getShape();
        int[] begin = new int[shape.length];
        begin[channelDim.getValue().intValue()] = channelIdx.getValue().intValue();
        int[] sizes = Arrays.stream(shape).mapToInt(l->(int)l).toArray();
        sizes[channelDim.getValue().intValue()] = 1;
        Slice slicer = new Slice(sameDiff, layerInput, begin, sizes);
        logger.debug("slicer input: {}, slicer output: {}", shape, slicer.outputVariable().shape());
        return slicer.outputVariable();
    }

    @Override
    public String getLayerName() {
        return layerName.getValue();
    }
}
