package bacmman.plugins.plugins.dl_engines;

import bacmman.configuration.parameters.*;
import bacmman.dl.INDArrayImageWrapper;
import bacmman.image.Image;
import bacmman.plugins.DLengine;
import bacmman.plugins.LambdaLayerDL4J;
import bacmman.processing.ResizeUtils;
import bacmman.utils.ArrayUtil;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Arrays;

import static bacmman.processing.ResizeUtils.*;

public abstract class DL4JEngine implements DLengine {
    FileChooser modelFile = new FileChooser("Keras model file", FileChooser.FileChooserOption.FILE_ONLY, false).setEmphasized(true);
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 64, 0, null);
    ArrayNumberParameter inputShape = new ArrayNumberParameter("Input shape", 2, new BoundedNumberParameter("", 0, 0, 0, null))
            .setMaxChildCount(4)
            .setNewInstanceNameFunction((l,i) -> {
                if (l.getChildCount()==3 && i<3) return "CYX".substring(i, i+1);
                else {
                    if (i>3) i = 3;
                    return "CZYX".substring(i, i+1);
                }
            }).setValue(2, 256, 32).setAllowMoveChildren(false)
            .addValidationFunction(l -> Arrays.stream(l.getArrayInt()).allMatch(i->i>0)).setEmphasized(true);
    SimpleListParameter<ArrayNumberParameter> inputShapes = new SimpleListParameter<>("Input Shapes", 0, inputShape).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1).setEmphasized(true);
    SimpleListParameter<PluginParameter<LambdaLayerDL4J>> lambdalayers = new SimpleListParameter<PluginParameter<LambdaLayerDL4J>>("Lambda layers", new PluginParameter<LambdaLayerDL4J>("lamda", LambdaLayerDL4J.class, false ));


    public DL4JEngine() {
        inputShape.resetName(null);
        inputShape.addListener(l -> l.resetName(null));
    }
    public DL4JEngine(int batchSize, int[][] inputShapes) {
        this();
        this.batchSize.setValue(batchSize);
        if (inputShapes==null || inputShapes.length==0) return;
        this.inputShapes.setChildrenNumber(inputShapes.length);
        for (int i = 0; i<inputShapes.length; ++i) {
            this.inputShapes.getChildAt(i).setValue(ArrayUtil.toDouble(inputShapes[i]));
        }
    }

    public int[][] getInputShapes() {
        return inputShapes.getActivatedChildren().stream().map(a -> a.getArrayInt()).toArray(int[][]::new);
    }


    @Override
    public abstract void init();

    protected abstract INDArray[] run(INDArray[] inputs);

    public synchronized Image[][][] process(Image[][]... inputNC) {
        int batchSize = this.batchSize.getValue().intValue();
        int[][] inputShapes = getInputShapes();
        int nSamples = inputNC[0].length;
        for (int i = 0; i<inputNC.length; ++i) {
            int[][] shapes = ResizeUtils.getShapes(inputNC[i], true);
            if (!ResizeUtils.allShapeEqual(shapes, inputShapes[i])) throw new IllegalArgumentException("Input # "+i+": At least one image have a shapes from input shape: "+inputShapes[i]);
            if (nSamples != inputNC[i].length) throw new IllegalArgumentException("nSamples from input #"+i+"("+inputNC[0].length+") differs from input #0 = "+nSamples);
        }
        Image[][][] res = new Image[getNumOutputArrays()][nSamples][];
        for (int idx = 0; idx<nSamples; idx+=batchSize) {
            int idxMax = Math.min(idx+batchSize, nSamples);
            logger.debug("batch: [{};{}[", idx, idxMax);
            final int fidx = idx;
            INDArray[] input = Arrays.stream(inputNC).map(in -> INDArrayImageWrapper.fromImagesNC(in, fidx, idxMax)).toArray(INDArray[]::new);
            INDArray[] output = run(input);
            for (int outI = 0; outI<res.length; ++outI) {
                for (int i = idx; i<idxMax; ++i) res[outI][i] = INDArrayImageWrapper.getImagesC(output[outI], i-idx);
            }
        }
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modelFile, batchSize, inputShapes, lambdalayers};
    }

    // x , y

}
