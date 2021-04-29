package bacmman.configuration.parameters;

import bacmman.core.Core;
import bacmman.dl.TileUtils;
import bacmman.image.*;
import bacmman.plugins.DLengine;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.plugins.scalers.IQRScaler;
import bacmman.processing.Resize;
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

public class DLResizeAndScaleParameter extends ConditionalParameterAbstract<DLResizeAndScaleParameter.MODE, DLResizeAndScaleParameter> {
    static Logger logger = LoggerFactory.getLogger(DLResizeAndScaleParameter.class);
    enum MODE {SCALE_ONLY, RESAMPLE, PAD, TILE}
    ArrayNumberParameter targetShape = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{0, 0}, null).setEmphasized(true).setName("Resize Shape").setHint("Input shape expected by the DNN. If the DNN has no pre-defined shape for an axis, set 0, and define contraction number for the axis.");
    ArrayNumberParameter contractionNumber = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{2, 2}, null).setEmphasized(true).setName("Contraction number").setHint("Contraction/Upsampling level number of the network for each axis. Only used when shape is set to zero for the axis: ensures that resized shape on this axis can be divided by 2<sup>contraction number</sup>");

    ArrayNumberParameter tileShape = InputShapesParameter.getInputShapeParameter(false, false,  new int[]{64, 64}, null).setEmphasized(true).setName("Tile Shape");
    ArrayNumberParameter minOverlap = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{0, 0}, null).setEmphasized(true).setName("Min Overlap").setHint("Minimum tile overlap");
    EnumChoiceParameter<Resize.EXPAND_MODE> paddingMode = new EnumChoiceParameter<>("Padding Mode", Resize.EXPAND_MODE.values(), Resize.EXPAND_MODE.MIRROR);
    ArrayNumberParameter minPad = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{5, 5}, null).setEmphasized(true).setName("Minimum Padding").setHint("Minimum Padding added on each side of the image");
    BooleanParameter padTiles = new BooleanParameter("Pad border tiles", true).setHint("If true, border tiles will be padded by minimum overlap");
    ConditionalParameter<Boolean> padTilesCond = new ConditionalParameter<>(padTiles).setActionParameters(true, paddingMode);

    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS).setEmphasized(true).setHint("Interpolation used for resizing. Use Nearest Neighbor for label images");
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scaler", HistogramScaler.class, true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction");
    GroupParameter grp = new GroupParameter("Input", interpolation, scaler).setEmphasized(true);
    SimpleListParameter<GroupParameter> inputInterpAndScaling = new SimpleListParameter<>("Input Interpolation/Scaling", grp).setNewInstanceNameFunction((s, i)->"Input #"+i).setEmphasized(true).setHint("Define here Interpolation mode and scaling mode for each input. All channels of each input will be processed together");
    SimplePluginParameterList<HistogramScaler> inputScaling = new SimplePluginParameterList<>("Input Scaling", "Scaler", HistogramScaler.class, new IQRScaler(), true).setNewInstanceNameFunction((s, i)->"Scaler for input #"+i).setEmphasized(true).setHint("Define here histogram scaling mode for each input. All channels of each input will be processed together");
    BooleanParameter scaleFrameByFrame = new BooleanParameter("Scale Frame by Frame", false).setHint("If true, scaling factors are computed on the histogram of the whole track, if false scale is computed frame by frame");

    BoundedNumberParameter outputScalerIndex = new BoundedNumberParameter("Output scaler index", 0, 0, -1, null).setEmphasized(true).setHint("Index of input scaler used to rescale back the image. -1 no reverse scaling");
    BooleanParameter reverseScaling = new BooleanParameter("Reverse scaling", true).setEmphasized(true);
    GroupParameter outputGrp;
    SimpleListParameter<GroupParameter> outputInterpAndScaling;
    SimpleListParameter<? extends Parameter> outputScaling;

    private void initOutput(boolean singleOutput) {
        outputGrp = new GroupParameter("Output", interpolation, inputInterpAndScaling.getMaxChildCount()==1 ? reverseScaling : outputScalerIndex).setEmphasized(true);
        outputInterpAndScaling = new SimpleListParameter<>("Output Interpolation/Scaling", outputGrp).setEmphasized(true).setHint("For each output, set the interpolation mode and the index of the input scaler used to reverse histogram scaling");
        if (inputInterpAndScaling.getMaxChildCount()>1) {
            outputInterpAndScaling.addValidationFunctionToChildren(grp -> ((BoundedNumberParameter)grp.getChildAt(1)).getValue().intValue()<inputInterpAndScaling.getChildCount());
        }
        if (inputScaling.getMaxChildCount()==1) outputScaling = new SimpleListParameter("Output Scaling", reverseScaling);
        else outputScaling = new SimpleListParameter<>("Output Scaling", outputScalerIndex).addValidationFunctionToChildren(idx -> ((NumberParameter)idx).getValue().intValue()<inputScaling.getChildCount());

        if (!singleOutput) {
            outputInterpAndScaling.setNewInstanceNameFunction((s, i)->"Output #"+i);
            outputScaling.setNewInstanceNameFunction((s, i)->"Scaler index for output #"+i);
            outputScaling.setEmphasized(true).setHint("For each output, set the index of the input scaler used to reverse histogram scaling");
        }
        setMinOutputNumber(1);
    }

    public DLResizeAndScaleParameter(String name) {
        super(new EnumChoiceParameter<>(name, MODE.values(), MODE.PAD));
        targetShape.addValidationFunction(InputShapesParameter.sameRankValidation());
        contractionNumber.addValidationFunction(InputShapesParameter.sameRankValidation());
        tileShape.addValidationFunction(InputShapesParameter.sameRankValidation());
        minOverlap.addValidationFunction(InputShapesParameter.sameRankValidation());
        minPad.addValidationFunction(InputShapesParameter.sameRankValidation());
        setMinInputNumber(1);
        initOutput(false);
        setConditionalParameter();
        setHint("Prepares input images for Deep Neural Network processing: resize & scale images <br /><ul><li>NO_RESAMPLING: no resampling is performed. Shape of input image provided must be homogeneous to be processed by the dl engine.</li><li>HOMOGENIZE: choose this option to make a prediction on the whole image. Network can have pre-defined input shape or not</li><li>PAD: expand image to a fixed shape</li><li>TILE: image is split into tiles on which predictions are made. Tiles are re-assembled by averaging the overlapping part. To limit border effects, border defined by the <em>min overlap</em> parameter are removed before assembling tiles.</li></ul>");
    }
    public DLResizeAndScaleParameter addInputNumberValidation(IntSupplier inputNumber) {
        inputInterpAndScaling.addValidationFunction(list -> list.getChildCount()==inputNumber.getAsInt());
        inputScaling.addValidationFunction(list -> list.getChildCount()==inputNumber.getAsInt());
        return this;
    }
    public DLResizeAndScaleParameter addOutputNumberValidation(IntSupplier outputNumber) {
        outputInterpAndScaling.addValidationFunction(list -> list.getChildCount()==outputNumber.getAsInt());
        outputScaling.addValidationFunction(list -> list.getChildCount()==outputNumber.getAsInt());
        return this;
    }
    public DLResizeAndScaleParameter setMinInputNumber(int min) {
        inputInterpAndScaling.setUnmutableIndex(min-1);
        if (inputInterpAndScaling.getChildCount()<min) inputInterpAndScaling.setChildrenNumber(min);
        inputScaling.setUnmutableIndex(min-1);
        if (inputScaling.getChildCount()<min) inputScaling.setChildrenNumber(min);
        return this;
    }

    public DLResizeAndScaleParameter setMaxInputNumber(int max) {
        inputInterpAndScaling.setMaxChildCount(max);
        if (max==1) {
            inputInterpAndScaling.setNewInstanceNameFunction((s, i)->"Input");
            inputInterpAndScaling.resetName((s, i)->"Input");
        }
        if (inputInterpAndScaling.getChildCount()>max) inputInterpAndScaling.setChildrenNumber(max);
        inputScaling.setMaxChildCount(max);
        if (max==1) {
            inputScaling.setNewInstanceNameFunction((s, i)->"Input Scaler");
            inputScaling.resetName((s, i)->"Input Scaler");
        }
        if (inputScaling.getChildCount()>max) inputScaling.setChildrenNumber(max);
        // As output parameter depend on input parameters, also re-init output parameters
        int maxOut = outputInterpAndScaling==null? 0 : outputInterpAndScaling.getMaxChildCount();
        if (maxOut!=0) {
            setMaxOutputNumber(maxOut);
        } else {
            initOutput(false);
            setConditionalParameter();
        }
        return this;
    }
    public DLResizeAndScaleParameter setMinOutputNumber(int min) {
        outputInterpAndScaling.setUnmutableIndex(min-1);
        if (outputInterpAndScaling.getChildCount()<min) outputInterpAndScaling.setChildrenNumber(min);
        outputScaling.setUnmutableIndex(min-1);
        if (outputScaling.getChildCount()<min) outputScaling.setChildrenNumber(min);
        return this;
    }

    public DLResizeAndScaleParameter setMaxOutputNumber(int max) {
        initOutput(max==1);
        outputInterpAndScaling.setMaxChildCount(max);
        if (outputInterpAndScaling.getChildCount()>max) outputInterpAndScaling.setChildrenNumber(max);
        outputScaling.setMaxChildCount(max);
        if (outputScaling.getChildCount()>max) outputScaling.setChildrenNumber(max);
        setConditionalParameter();
        return this;
    }

    private void setConditionalParameter() {
        Parameter iS = inputScaling.getMaxChildCount()==1 ? inputScaling.getChildAt(0) : inputScaling;
        Parameter oS = outputScaling.getMaxChildCount()==1 ? outputScaling.getChildAt(0) : outputScaling;
        Parameter iIS = inputInterpAndScaling.getMaxChildCount()==1 ? inputInterpAndScaling.getChildAt(0) : inputInterpAndScaling;
        Parameter oIS = outputInterpAndScaling.getMaxChildCount()==1 ? outputInterpAndScaling.getChildAt(0) : outputInterpAndScaling;
        iS.setEmphasized(true);
        oS.setEmphasized(true);
        iIS.setEmphasized(true);
        oIS.setEmphasized(true);
        this.setActionParameters(MODE.SCALE_ONLY, iS, oS, scaleFrameByFrame);
        this.setActionParameters(MODE.RESAMPLE, targetShape, contractionNumber, iIS, oIS ,scaleFrameByFrame);
        this.setActionParameters(MODE.PAD, targetShape, contractionNumber, paddingMode, minPad, iS, oS, scaleFrameByFrame);
        this.setActionParameters(MODE.TILE, tileShape, minOverlap, padTilesCond, iS, oS, scaleFrameByFrame);
        initChildList();
    }
    public DLResizeAndScaleParameter setEmphasized(boolean emp) {
        super.setEmphasized(emp);
        return this;
    }
    public MODE getMode() {
        return ((EnumChoiceParameter<MODE>)action).getSelectedEnum();
    }
    private static int getSize(double[] sizes, int nContraction, boolean pad, int minPad) {
        int div = (int)Math.pow(2, nContraction);
        if (pad) {
            int maxSize = (int)sizes[ArrayUtil.max(sizes)] + 2 * minPad;
            return closestNumber(maxSize, div, true);
        }
        Histogram histo = HistogramFactory.getHistogram(()-> DoubleStream.of(sizes), 1.0);
        double total = (int)histo.count();
        int maxIdx = ArrayUtil.max(histo.getData());
        int[] candidate=null;
        if (histo.getData()[maxIdx]>=total/2.0) {
            int cand = (int)histo.getValueFromIdx(maxIdx);
            if (nContraction==0) return cand;
            int divisible = closestNumber(cand, div, false);
            if (divisible==cand) return cand;
            candidate = new int[]{cand, divisible};
        }
        // mediane:
        int cand = (int)Math.round(ArrayUtil.median(sizes));
        int divisible = closestNumber(cand, div, false);
        if (candidate == null || divisible==cand) return divisible;
        if (Math.abs(candidate[0]-candidate[1])<Math.abs(cand-divisible)) return candidate[1];
        else return divisible;
    }
    private static int closestNumber(int n, int div, boolean max) {
        int q = n / div;
        int n1 = div * q;
        if (n1==n) return n1;
        int n2 = (n * div) > 0 ? (div * (q + 1)) : (div * (q - 1));
        if (max) return Math.max(n1, n2);
        if (Math.abs(n - n1) < Math.abs(n - n2))
            return n1;
        return n2;
    }

    public int[] getTargetImageShape(Image[][] imageNC, boolean pad) {
        int[] shape = ArrayUtil.reverse(targetShape.getArrayInt(), true);
        int[] nContractions = ArrayUtil.reverse(contractionNumber.getArrayInt(), true);
        int[] minPadA = ArrayUtil.reverse(minPad.getArrayInt(), true);
        for (int i = 0; i<shape.length; ++i) {
            int dim = i;
            if (shape[i]==0) {
                double[] sizes = Arrays.stream(imageNC).mapToDouble(a -> a[0].size(dim)).toArray();
                // get modal shape
                shape[i] = getSize(sizes, nContractions[i], pad, minPadA[i]);
            } else if (pad) { // check that size is smaller than target size
                int minTargetShape = Arrays.stream(imageNC).mapToInt(a -> a[0].size(dim)).max().getAsInt() + 2 * minPadA[i];
                if (shape[i]<minTargetShape) throw new RuntimeException("Error while resizing in padding mode on axis="+i+":  max image size (with min pad)="+minTargetShape+" > target size="+shape[i]);
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
        switch (getMode()) {
            case SCALE_ONLY: {
                HistogramScaler[] scalers = inputScaling.get().toArray(new HistogramScaler[0]);
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    Pair<Image[][], int[][]> res = scaleInput(inputINC[i], scalers[i], scaleFrameByFrame.getSelected());
                    imINC[i] = res.key;
                    shapesIN[i] = res.value;
                }
                return new Triplet<>(imINC, shapesIN, scalers);
            }
            case RESAMPLE:
            default: {
                HistogramScaler[] scalers = new HistogramScaler[inputINC.length];
                InterpolatorFactory[] interpols = new InterpolatorFactory[inputINC.length];
                for (int i = 0; i < inputINC.length; ++i) {
                    GroupParameter params = inputInterpAndScaling.getChildAt(i);
                    interpols[i] = ((InterpolationParameter) params.getChildAt(0)).getInterpolation();
                    scalers[i] = ((PluginParameter<HistogramScaler>) params.getChildAt(1)).instantiatePlugin();
                }
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    Pair<Image[][], int[][]> res = scaleAndResampleInput(inputINC[i], interpols[i], scalers[i], getTargetImageShape(inputINC[i], false), scaleFrameByFrame.getSelected());
                    imINC[i] = res.key;
                    shapesIN[i] = res.value;
                }
                return new Triplet<>(imINC, shapesIN, scalers);
            }
            case PAD: {
                HistogramScaler[] scalers = inputScaling.get().toArray(new HistogramScaler[0]);
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    Pair<Image[][], int[][]> res = scaleAndPadInput(inputINC[i], paddingMode.getSelectedEnum(), scalers[i], getTargetImageShape(inputINC[i], true), scaleFrameByFrame.getSelected());
                    imINC[i] = res.key;
                    shapesIN[i] = res.value;
                }
                return new Triplet<>(imINC, shapesIN, scalers);
            }
            case TILE: {
                Resize.EXPAND_MODE padding = padTiles.getSelected() ? paddingMode.getSelectedEnum() : null;
                HistogramScaler[] scalers = inputScaling.get().toArray(new HistogramScaler[0]);
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                int[] tileShapeXYZ = ArrayUtil.reverse(tileShape.getArrayInt(), true);
                int[] minOverlapXYZ = ArrayUtil.reverse(minOverlap.getArrayInt(), true);
                for (int i = 0; i < inputINC.length; ++i) {
                    Pair<Image[][], int[][]> res = scaleAndTileInput(inputINC[i], padding, scalers[i], tileShapeXYZ, minOverlapXYZ, scaleFrameByFrame.getSelected());
                    imINC[i] = res.key;
                    shapesIN[i] = res.value;
                }
                return new Triplet<>(imINC, shapesIN, scalers);
            }
        }
    }
    private int getOutputScalerIndex(boolean resample, int outputIdx) {
        if (!resample) {
            if (outputScaling.getChildCount()>outputIdx) {
                Parameter p = outputScaling.getChildAt(outputIdx);
                if (inputScaling.getMaxChildCount()==1) {
                    return ((BooleanParameter)p).getSelected() ? 0 : -1;
                } else return ((NumberParameter)p).getValue().intValue();
            } else return -1;
        } else {
            GroupParameter params = outputInterpAndScaling.getChildAt(outputIdx);
            if (inputInterpAndScaling.getMaxChildCount()==1) {
                return ((BooleanParameter)params.getChildAt(1)).getSelected() ? 0 : -1;
            } else return ((NumberParameter)params.getChildAt(1)).getValue().intValue();
        }
    }
    public Image[][][] processPrediction(Image[][][] predictionsONC, Triplet<Image[][][], int[][][], HistogramScaler[]> input) {
        switch (getMode()) {
            case SCALE_ONLY: {
                Image[][][] outputONC = new Image[predictionsONC.length][][];
                for (int i = 0;i<predictionsONC.length; ++i) {
                    int scalerIndex = getOutputScalerIndex(false, i);
                    if (scalerIndex>=0) outputONC[i] = scaleAndResampleReverse(input.v1[scalerIndex], predictionsONC[i], null, input.v3[scalerIndex], null, scaleFrameByFrame.getSelected());
                    else outputONC[i] = predictionsONC[i];
                }
                return outputONC;
            }
            case PAD: {
                Image[][][] outputONC = new Image[predictionsONC.length][][];
                for (int i = 0;i<predictionsONC.length; ++i) {
                    int scalerIndex = getOutputScalerIndex(false, i);
                    outputONC[i] = scaleAndPadReverse(input.v1[scalerIndex>=0?scalerIndex:0], predictionsONC[i], scalerIndex>=0 ? input.v3[scalerIndex] : null, input.v2[scalerIndex>=0?scalerIndex:0]);
                }
                return outputONC;
            }
            case RESAMPLE:
            default: {
                Image[][][] outputONC = new Image[predictionsONC.length][][];
                for (int i = 0;i<predictionsONC.length; ++i) {
                    GroupParameter params = outputInterpAndScaling.getChildAt(i);
                    InterpolatorFactory interpol = ((InterpolationParameter)params.getChildAt(0) ).getInterpolation();
                    int scalerIndex = getOutputScalerIndex(true, i);
                    outputONC[i] = scaleAndResampleReverse(input.v1[scalerIndex>=0?scalerIndex:0], predictionsONC[i],interpol, scalerIndex>=0? input.v3[scalerIndex]: null, input.v2[scalerIndex>=0?scalerIndex:0], scaleFrameByFrame.getSelected());
                }
                return outputONC;
            }
            case TILE:
                Image[][][] outputONC = new Image[predictionsONC.length][][];
                int[] minOverlapXYZ = ArrayUtil.reverse(minOverlap.getArrayInt(), true);
                for (int i = 0;i<predictionsONC.length; ++i) {
                    int scalerIndex = getOutputScalerIndex(false, i);
                    for (int n = 0; n<predictionsONC[i].length; ++n) {
                        for (int c = 0; c<predictionsONC[i][n].length; ++c) predictionsONC[i][n][c].resetOffset().translate(input.v1[scalerIndex>=0?scalerIndex:0][n][0]);
                    }
                    outputONC[i] = scaleAndTileReverse(input.v2[scalerIndex>=0?scalerIndex:0], predictionsONC[i], scalerIndex>=0 ? input.v3[scalerIndex] : null, minOverlapXYZ);
                }
                return outputONC;
        }
    }
    public static Pair<Image[][], int[][]> scaleInput(Image[][] inNC, HistogramScaler scaler, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        if (scaler==null) return new Pair(inNC, shapes);
        if (!scaleFrameByFrame) {
            List<Image> allImages = ArrayUtil.flatmap(inNC).collect(Collectors.toList());
            scaler.setHistogram(HistogramFactory.getHistogram(() -> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
        }
        scaler.transformInputImage(false); // input images are not modified
        Image[][] res = IntStream.range(0, inNC.length).parallel().mapToObj(i -> IntStream.range(0, inNC[i].length).mapToObj(j -> scaler.scale(inNC[i][j]) ).toArray(Image[]::new)).toArray(Image[][]::new);
        return new Pair(res, shapes);
    }
    public static Pair<Image[][], int[][]> scaleAndResampleInput(Image[][] inNC, InterpolatorFactory interpolation, HistogramScaler scaler, int[] targetImageShape, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        IntStream.range(0, inNC.length).parallel().forEach(i -> IntStream.range(0, inNC[i].length).forEach(j -> inNC[i][j] = TypeConverter.toFloat(inNC[i][j], null, false) ));
        if (scaler!=null) { // scale
            if (!scaleFrameByFrame) {
                List<Image> allImages = ArrayUtil.flatmap(inNC).collect(Collectors.toList());
                scaler.setHistogram(HistogramFactory.getHistogram(() -> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            }
            scaler.transformInputImage(true);
        }
        Image[][] inResampledNC = ResizeUtils.resample(inNC, interpolation, new int[][]{targetImageShape});
        if (scaler!=null) IntStream.range(0, inResampledNC.length).parallel().forEach(i -> IntStream.range(0, inResampledNC[i].length).forEach(j -> inResampledNC[i][j] = scaler.scale(inResampledNC[i][j]) ));
        return new Pair<>(inResampledNC, shapes);
    }

    public static Image[][] scaleAndResampleReverse(Image[][] inputNC, Image[][] predNC, InterpolatorFactory interpolation, HistogramScaler scaler, int[][] shapes, boolean scaleFrameByFrame) {
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
    public static Pair<Image[][], int[][]> scaleAndPadInput(Image[][] inNC, Resize.EXPAND_MODE mode, HistogramScaler scaler, int[] targetImageShape, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        IntStream.range(0, inNC.length).parallel().forEach(i -> IntStream.range(0, inNC[i].length).forEach(j -> inNC[i][j] = TypeConverter.toFloat(inNC[i][j], null, false) ));
        if (scaler!=null) { // scale
            if (!scaleFrameByFrame) {
                List<Image> allImages = ArrayUtil.flatmap(inNC).collect(Collectors.toList());
                scaler.setHistogram(HistogramFactory.getHistogram(() -> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            }
            scaler.transformInputImage(true);
        }
        Image[][] inResizedNC = ResizeUtils.pad(inNC, mode, Resize.EXPAND_POSITION.CENTER, new int[][]{targetImageShape});
        if (scaler!=null) IntStream.range(0, inResizedNC.length).parallel().forEach(i -> IntStream.range(0, inResizedNC[i].length).forEach(j -> inResizedNC[i][j] = scaler.scale(inResizedNC[i][j]) ));
        return new Pair<>(inResizedNC, shapes);
    }
    public static Image[][] scaleAndPadReverse(Image[][] inputNC, Image[][] predNC, HistogramScaler scaler, int[][] shapes) {
        if (scaler!=null){
            scaler.transformInputImage(true);
            IntStream.range(0, predNC.length).parallel().forEach(i -> IntStream.range(0, predNC[i].length).forEach(j -> predNC[i][j] = scaler.reverseScale(predNC[i][j]) ));
        }
        Image[][] predictionResizedNC = ResizeUtils.pad(predNC, Resize.EXPAND_MODE.MIRROR, Resize.EXPAND_POSITION.CENTER, shapes);
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
    public static Pair<Image[][], int[][]> scaleAndTileInput(Image[][] inNC, Resize.EXPAND_MODE padding, HistogramScaler scaler, int[] targetTileShape, int[] minOverlap, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        IntStream.range(0, inNC.length).parallel().forEach(i -> IntStream.range(0, inNC[i].length).forEach(j -> inNC[i][j] = TypeConverter.toFloat(inNC[i][j], null, false) ));
        if (scaler!=null) { // scale
            if (!scaleFrameByFrame) {
                List<Image> allImages = ArrayUtil.flatmap(inNC).collect(Collectors.toList());
                scaler.setHistogram(HistogramFactory.getHistogram(() -> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            }
            scaler.transformInputImage(true);
        }
        Image[][] tilesNtC = TileUtils.splitTiles(inNC, targetTileShape, minOverlap, padding);
        int nTiles = tilesNtC.length / inNC.length;
        if (scaler!=null) IntStream.range(0, tilesNtC.length).parallel().forEach(n -> IntStream.range(0, tilesNtC[n].length).forEach(c -> {
            tilesNtC[n][c] = scaler.scale(tilesNtC[n][c]);
            tilesNtC[n][c].translate(inNC[n/nTiles][c].getBoundingBox().reverseOffset()); // tiles offset are relative to each input image because when merging them we don't have access to input offset
        } ));
        return new Pair<>(tilesNtC, shapes);
    }

    /**
     *
     * @param targetShape images of same type as tiles, tiles will be copied on them
     * @param predNtC
     * @param scaler
     * @param minOverlap
     * @return {@param targetNC} for convinience
     */
    public static Image[][] scaleAndTileReverse(int[][] targetShape, Image[][] predNtC, HistogramScaler scaler, int[] minOverlap) {
        if (scaler!=null){
            scaler.transformInputImage(true);
            IntStream.range(0, predNtC.length).parallel().forEach(i -> IntStream.range(0, predNtC[i].length).forEach(j -> predNtC[i][j] = scaler.reverseScale(predNtC[i][j]) ));
        }
        Image[][] targetNC = IntStream.range(0, targetShape.length)
                .mapToObj(i -> IntStream.range(0, predNtC[i].length)
                        .mapToObj(c -> Image.createEmptyImage("", predNtC[i][c], new SimpleImageProperties(new SimpleBoundingBox(0, targetShape[i][0]-1, 0, targetShape[i][1]-1, 0, targetShape[i].length>2 ? targetShape[i][2]-1: 0), predNtC[i][c].getScaleXY(), predNtC[i][c].getScaleZ())
                        )).toArray(Image[]::new)
                ).toArray(Image[][]::new);
        TileUtils.mergeTiles(targetNC, predNtC, minOverlap);
        return targetNC;
    }
    @Override
    public DLResizeAndScaleParameter duplicate() {
        DLResizeAndScaleParameter res = new DLResizeAndScaleParameter(name);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
}
