package bacmman.configuration.parameters;

import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.DLengine;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.plugins.scalers.IQRScaler;
import bacmman.processing.ResizeUtils;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.Triplet;
import bacmman.utils.Utils;
import net.imglib2.interpolation.InterpolatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class DLResampleAndScaleParameter extends ConditionalParameterAbstract<DLResampleAndScaleParameter> {
    Logger logger = LoggerFactory.getLogger(DLResampleAndScaleParameter.class);
    enum MODE {NO_RESAMPLING, HOMOGENIZE, TILE}
    ArrayNumberParameter targetShape = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{256, 32}, null).setEmphasized(true).setName("Resize Shape").setHint("Input shape expected by the DNN. If the DNN has no pre-defined shape for an axis, set 0, and define contraction number for the axis.");
    ArrayNumberParameter contractionNumber = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{2, 2}, null).setEmphasized(true).setName("Contraction number").setHint("Number of contraction of the network for each axis. Only used when shape is set to zero for the axis: ensures that resized shape on this axis can be divided by 2**(contraction number)");

    ArrayNumberParameter tileShape = InputShapesParameter.getInputShapeParameter(false, false,  new int[]{64, 64}, null).setEmphasized(true).setName("Tile Shape");
    ArrayNumberParameter minOverlap = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{0, 0}, null).setEmphasized(true).setName("Min Overlap").setHint("Minimum tile overlap");

    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS).setEmphasized(true).setHint("Interpolation used for resizing. Use Nearest Neighbor for label images");
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scaler", HistogramScaler.class, true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction");
    GroupParameter grp = new GroupParameter("Input", interpolation, scaler).setEmphasized(true);
    SimpleListParameter<GroupParameter> inputInterpAndScaling = new SimpleListParameter<>("Input Interpolation/Scaling", grp).setNewInstanceNameFunction((s, i)->"input #"+i).setEmphasized(true).setHint("Define here Interpolation mode and scaling mode for each input. All channels of each input will be processed together");
    SimplePluginParameterList<HistogramScaler> inputScaling = new SimplePluginParameterList<>("Input Scaling", "Scaler", HistogramScaler.class, new IQRScaler(), true).setNewInstanceNameFunction((s, i)->"scaler for input #"+i).setEmphasized(true).setHint("Define here scaling mode for each input. All channels of each input will be processed together");

    BoundedNumberParameter outputScalerIndex = new BoundedNumberParameter("Output scaler index", 0, 0, -1, null).setEmphasized(true).setHint("Index of input scaler used to rescale back the image. Set -1 for no rescaling");
    GroupParameter outputGrp = new GroupParameter("Output", interpolation, outputScalerIndex).setEmphasized(true);
    SimpleListParameter<GroupParameter> outputInterpAndScaling = new SimpleListParameter<>("Output Interpolation/Scaling", outputGrp).setNewInstanceNameFunction((s, i)->"output #"+i).setEmphasized(true).addValidationFunctionToChildren(grp -> ((BoundedNumberParameter)grp.getChildAt(1)).getValue().intValue()<inputInterpAndScaling.getChildCount()).setHint("For each output, set the interpolation mode and the index of the input scaler used to rescale back");
    SimpleListParameter<BoundedNumberParameter> outputScaling = new SimpleListParameter<>("Output Scaling", outputScalerIndex).setNewInstanceNameFunction((s, i)->"scaler for output #"+i).addValidationFunctionToChildren(idx -> idx.getValue().intValue()<inputScaling.getChildCount()).setEmphasized(true).setHint("For each output, set the index of the input scaler used to rescale back");

    public DLResampleAndScaleParameter(String name) {
        super(new EnumChoiceParameter<>(name, MODE.values(), MODE.HOMOGENIZE, false));
        this.setActionParameters(MODE.NO_RESAMPLING.toString(), inputScaling, outputScaling);
        this.setActionParameters(MODE.HOMOGENIZE.toString(), targetShape, contractionNumber, inputInterpAndScaling, outputInterpAndScaling);
        this.setActionParameters(MODE.TILE.toString(), tileShape, minOverlap, inputInterpAndScaling, outputInterpAndScaling);
        targetShape.addValidationFunction(InputShapesParameter.sameRankValidation());
        contractionNumber.addValidationFunction(InputShapesParameter.sameRankValidation());
        tileShape.addValidationFunction(InputShapesParameter.sameRankValidation());
        minOverlap.addValidationFunction(InputShapesParameter.sameRankValidation());
        setMinInputNumber(1);
        setMinOutputNumber(1);
        setHint("Prepares input images for Deep Neural Network processing: resize & scale images <br /><ul><li>NO_RESAMPLING: no resampling is performed. Shape of input image provided must be homogeneous to be processed by the dl engine.</li><li>HOMOGENIZE: choose this option to make a prediction on the whole image. Network can have pre-defined input shape or not</li><li>TILE: image is split into tiles on which predictions are made. NOT SUPPORTED YET</li></ul>");
    }
    public DLResampleAndScaleParameter addInputNumberValidation(IntSupplier inputNumber) {
        inputInterpAndScaling.addValidationFunction(list -> list.getChildCount()==inputNumber.getAsInt());
        inputScaling.addValidationFunction(list -> list.getChildCount()==inputNumber.getAsInt());
        return this;
    }
    public DLResampleAndScaleParameter addOutputNumberValidation(IntSupplier outputNumber) {
        outputInterpAndScaling.addValidationFunction(list -> list.getChildCount()==outputNumber.getAsInt());
        outputScaling.addValidationFunction(list -> list.getChildCount()==outputNumber.getAsInt());
        return this;
    }
    public DLResampleAndScaleParameter setMinInputNumber(int min) {
        inputInterpAndScaling.setUnmutableIndex(min-1);
        if (inputInterpAndScaling.getChildCount()<min) inputInterpAndScaling.setChildrenNumber(min);
        inputScaling.setUnmutableIndex(min-1);
        if (inputScaling.getChildCount()<min) inputScaling.setChildrenNumber(min);
        return this;
    }

    public DLResampleAndScaleParameter setMaxInputNumber(int max) {
        inputInterpAndScaling.setMaxChildCount(max);
        if (inputInterpAndScaling.getChildCount()>max) inputInterpAndScaling.setChildrenNumber(max);
        inputScaling.setMaxChildCount(max);
        if (inputScaling.getChildCount()>max) inputScaling.setChildrenNumber(max);
        return this;
    }
    public DLResampleAndScaleParameter setMinOutputNumber(int min) {
        outputInterpAndScaling.setUnmutableIndex(min-1);
        if (outputInterpAndScaling.getChildCount()<min) outputInterpAndScaling.setChildrenNumber(min);
        outputScaling.setUnmutableIndex(min-1);
        if (outputScaling.getChildCount()<min) outputScaling.setChildrenNumber(min);
        return this;
    }

    public DLResampleAndScaleParameter setMaxOutputNumber(int max) {
        outputInterpAndScaling.setMaxChildCount(max);
        if (outputInterpAndScaling.getChildCount()>max) outputInterpAndScaling.setChildrenNumber(max);
        outputScaling.setMaxChildCount(max);
        if (outputScaling.getChildCount()>max) outputScaling.setChildrenNumber(max);
        return this;
    }
    public DLResampleAndScaleParameter setEmphasized(boolean emp) {
        super.setEmphasized(emp);
        return this;
    }
    public MODE getMode() {
        return ((EnumChoiceParameter<MODE>)action).getSelectedEnum();
    }
    private static int getSize(double[] sizes, int nContraction) {
        int div = (int)Math.pow(2, nContraction);
        Histogram histo = HistogramFactory.getHistogram(()-> DoubleStream.of(sizes), 1.0);
        double total = (int)histo.count();
        int maxIdx = ArrayUtil.max(histo.getData());
        int[] candidate=null;
        if (histo.getData()[maxIdx]>=total/2.0) {
            int cand = (int)histo.getValueFromIdx(maxIdx);
            if (nContraction==0) return cand;
            int divisible = closestNumber(cand, div);
            if (divisible==cand) return cand;
            candidate = new int[]{cand, divisible};
        }
        // mediane:
        int cand = (int)Math.round(ArrayUtil.median(sizes));
        int divisible = closestNumber(cand, div);
        if (candidate == null || divisible==cand) return divisible;
        if (Math.abs(candidate[0]-candidate[1])<Math.abs(cand-divisible)) return candidate[1];
        else return divisible;
    }
    static int closestNumber(int n, int m) {
        int q = n / m;
        int n1 = m * q;
        int n2 = (n * m) > 0 ? (m * (q + 1)) : (m * (q - 1));
        if (Math.abs(n - n1) < Math.abs(n - n2))
            return n1;
        return n2;
    }

    public int[] getTargetImageShape(Image[][] imageNC) {
        int[] shape = ArrayUtil.reverse(targetShape.getArrayInt(), true);
        int[] nContractions = ArrayUtil.reverse(contractionNumber.getArrayInt(), true);
        for (int i = 0; i<shape.length; ++i) {
            if (shape[i]==0) {
                int dim = i;
                double[] sizes = Arrays.stream(imageNC).mapToDouble(a -> a[0].size(dim)).toArray();
                // get modal shape
                shape[i] = getSize(sizes, nContractions[i]);
            }
        }
        logger.debug("Target shape: {}", Utils.toStringArray(shape));
        return shape;
    }
    public Image[][][] predict(DLengine engine, Image[][][] inputINC) {
        Triplet<Image[][][], int[][][], HistogramScaler[]> in = getNetworkInput(inputINC);
        Image[][][] out = engine.process(in.v1);
        return processPrediction(out, in);
    }
    public Triplet<Image[][][], int[][][], HistogramScaler[]> getNetworkInput(Image[][][] inputINC) {
        HistogramScaler[] scalers = new HistogramScaler[inputINC.length];
        InterpolatorFactory[] interpols = new InterpolatorFactory[inputINC.length];
        for (int i = 0; i < inputINC.length; ++i) {
            GroupParameter params = inputInterpAndScaling.getChildAt(i);
            interpols[i] = ((InterpolationParameter) params.getChildAt(0)).getInterpolation();
            scalers[i] = ((PluginParameter<HistogramScaler>) params.getChildAt(1)).instantiatePlugin();
        }
        switch (getMode()) {
            case NO_RESAMPLING: {
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    Pair<Image[][], int[][]> res = resampleInput(inputINC[i], scalers[i]);
                    imINC[i] = res.key;
                    shapesIN[i] = res.value;
                }
                return new Triplet<>(imINC, shapesIN, scalers);
            }
            case HOMOGENIZE:
            default: {
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    Pair<Image[][], int[][]> res = scaleAndResampleInput(inputINC[i], interpols[i], scalers[i], getTargetImageShape(inputINC[i]));
                    imINC[i] = res.key;
                    shapesIN[i] = res.value;
                }
                return new Triplet<>(imINC, shapesIN, scalers);
            }
            case TILE:
                throw new UnsupportedOperationException("TILE processing Not supported yet");
        }
    }
    public Image[][][] processPrediction(Image[][][] predictionsONC, Triplet<Image[][][], int[][][], HistogramScaler[]> input) {
        switch (getMode()) {
            case NO_RESAMPLING: {
                Image[][][] outputONC = new Image[predictionsONC.length][][];
                for (int i = 0;i<predictionsONC.length; ++i) {
                    int scalerIndex = outputScaling.getChildCount()>i ? outputScaling.getChildAt(i).getValue().intValue() : -1;
                    if (scalerIndex>=0) outputONC[i] = scaleAndRessampleBack(input.v1[0], predictionsONC[i], null, input.v3[scalerIndex], null);
                    else outputONC[i] = predictionsONC[i];
                }
                return outputONC;
            }
            case HOMOGENIZE:
            default: {
                Image[][][] outputONC = new Image[predictionsONC.length][][];
                for (int i = 0;i<predictionsONC.length; ++i) {
                    GroupParameter params = outputInterpAndScaling.getChildAt(i);
                    InterpolatorFactory interpol = ((InterpolationParameter)params.getChildAt(0) ).getInterpolation();
                    int scalerIndex = ((NumberParameter)params.getChildAt(1)).getValue().intValue();
                    outputONC[i] = scaleAndRessampleBack(input.v1[scalerIndex>=0?scalerIndex:0], predictionsONC[i],interpol, scalerIndex>=0? input.v3[scalerIndex]: null, input.v2[scalerIndex>=0?scalerIndex:0]);
                }
                return outputONC;
            }
            case TILE:
                throw new UnsupportedOperationException("TILE processing Not supported yet");
        }
    }
    public static Pair<Image[][], int[][]> resampleInput(Image[][] inNC, HistogramScaler scaler) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        if (scaler==null) return new Pair(inNC, shapes);
        List<Image> allImages = ArrayUtil.flatmap(inNC).collect(Collectors.toList());
        scaler.setHistogram(HistogramFactory.getHistogram(()-> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
        scaler.transformInputImage(false);
        Image[][] res = IntStream.range(0, inNC.length).parallel().mapToObj(i -> IntStream.range(0, inNC[i].length).mapToObj(j -> scaler.scale(inNC[i][j]) ).toArray(Image[]::new)).toArray(Image[][]::new);
        return new Pair(res, shapes);
    }
    public static Pair<Image[][], int[][]> scaleAndResampleInput(Image[][] inNC, InterpolatorFactory interpolation, HistogramScaler scaler, int[] targetImageShape) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        IntStream.range(0, inNC.length).parallel().forEach(i -> IntStream.range(0, inNC[i].length).forEach(j -> inNC[i][j] = TypeConverter.toFloat(inNC[i][j], null, false) ));
        if (scaler!=null) { // scale
            List<Image> allImages = ArrayUtil.flatmap(inNC).collect(Collectors.toList());
            scaler.setHistogram(HistogramFactory.getHistogram(()-> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            scaler.transformInputImage(true);
        }
        Image[][] inResampledNC = ResizeUtils.resample(inNC, interpolation, new int[][]{targetImageShape});
        if (scaler!=null) IntStream.range(0, inResampledNC.length).parallel().forEach(i -> IntStream.range(0, inResampledNC[i].length).forEach(j -> inResampledNC[i][j] = scaler.scale(inResampledNC[i][j]) ));
        return new Pair<>(inResampledNC, shapes);
    }

    public static Image[][] scaleAndRessampleBack(Image[][] inputNC, Image[][] predNC, InterpolatorFactory interpolation, HistogramScaler scaler, int[][] shapes) {
        if (scaler!=null){
            scaler.transformInputImage(true);
            IntStream.range(0, predNC.length).parallel().forEach(i -> IntStream.range(0, predNC[i].length).forEach(j -> predNC[i][j] = scaler.reverseScale(predNC[i][j]) ));
        }
        Image[][] predictionResizedNC = interpolation!=null ? ResizeUtils.resample(predNC, interpolation, shapes) : predNC;
        if (inputNC!=null) {
            for (int idx = 0; idx < predictionResizedNC.length; ++idx) {
                for (int c = 0; c < predictionResizedNC[idx].length; ++c) {
                    predictionResizedNC[idx][c].setCalibration(inputNC[idx][0]);
                    predictionResizedNC[idx][c].resetOffset().translate(inputNC[idx][0]);
                }
            }
        }
        return predictionResizedNC;
    }

    @Override
    public DLResampleAndScaleParameter duplicate() {
        DLResampleAndScaleParameter res = new DLResampleAndScaleParameter(name);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
}
