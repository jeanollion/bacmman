package bacmman.configuration.parameters;

import bacmman.dl.TileUtils;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.*;
import bacmman.plugins.DLengine;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.plugins.scalers.IQRScaler;
import bacmman.processing.Resize;
import bacmman.processing.ResizeUtils;
import bacmman.utils.*;
import net.imglib2.interpolation.InterpolatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class DLResizeAndScale extends ConditionalParameterAbstract<DLResizeAndScale.MODE, DLResizeAndScale> implements DLMetadataConfigurable {
    static Logger logger = LoggerFactory.getLogger(DLResizeAndScale.class);

    public enum MODE {SCALE_ONLY, RESAMPLE, PAD, TILE}
    ArrayNumberParameter targetShape = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{0, 0}, null).setEmphasized(true).setName("Resize Shape").setHint("Input shape expected by the DNN. If the DNN has no pre-defined shape for an axis, set 0, and define contraction number for the axis.");
    ArrayNumberParameter contraction = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{8, 8}, null).setEmphasized(true).setName("Total Shape Contraction").setHint("Size ratio between the smallest tensor in the network and the input tensor. Only used when shape is set to zero for the axis: ensures that resized shape on this axis can be divided by the contraction. <br />For a network that performs 3 contractions with each contraction dividing the image by two, enter 8 on each axis");

    ArrayNumberParameter tileShape = InputShapesParameter.getInputShapeParameter(false, false,  new int[]{128, 128}, null).setEmphasized(true).setName("Tile Shape");
    ArrayNumberParameter minOverlap = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{16, 16}, null).setEmphasized(true).setName("Min Overlap").setHint("Minimum tile overlap");
    EnumChoiceParameter<Resize.EXPAND_MODE> paddingMode = new EnumChoiceParameter<>("Padding Mode", Resize.EXPAND_MODE.values(), Resize.EXPAND_MODE.MIRROR);
    ArrayNumberParameter minPad = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{0, 0}, null).setEmphasized(true).setName("Minimum Padding").setHint("Minimum Padding added on each side of the image");
    BooleanParameter padTiles = new BooleanParameter("Pad border tiles", false).setHint("If true, border tiles will be padded by minimum overlap");
    ConditionalParameter<Boolean> padTilesCond = new ConditionalParameter<>(padTiles).setActionParameters(true, paddingMode);

    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS).setEmphasized(true).setHint("Interpolation used for resizing. Use Nearest Neighbor for label images");
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scaler", HistogramScaler.class, true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction");
    PairParameter<InterpolationParameter, PluginParameter<HistogramScaler>> grp = new PairParameter<>("Input", interpolation, scaler).setEmphasized(true);
    SimpleListParameter<PairParameter<InterpolationParameter, PluginParameter<HistogramScaler>>> inputInterpAndScaling = new SimpleListParameter<>("Input Interpolation/Scaling", grp).setNewInstanceNameFunction((s, i)->"Input #"+i).setEmphasized(true).setHint("Define here Interpolation mode and scaling mode for each input. All channels of each input will be processed together");
    SimplePluginParameterList<HistogramScaler> inputScaling = new SimplePluginParameterList<>("Input Scaling", "Scaler", HistogramScaler.class, new IQRScaler(), true).setNewInstanceNameFunction((s, i)->"Scaler for input #"+i).setEmphasized(true).setHint("Define here histogram scaling mode for each input. All channels of each input will be processed together");
    BooleanParameter scaleImageByImage = new BooleanParameter("Scale Image by Image", false).setHint("If true, scaling factors are computed on the histogram of the whole batch, if false scale is computed for each image. If image has several channels (sometimes corresponding to different frames) they will be scaled together");

    BoundedNumberParameter outputScalerIndex = new BoundedNumberParameter("Output scaler index", 0, 0, -1, null).setEmphasized(true).setHint("Index of input scaler used to rescale back the image. -1 no reverse scaling");
    BooleanParameter reverseScaling = new BooleanParameter("Reverse scaling", true).setEmphasized(true).setHint("Whether scale the output using the scaling parameters of the corresponding input");
    GroupParameter outputGrp;
    SimpleListParameter<? extends Parameter> outputInterpAndScaling;
    SimpleListParameter<? extends Parameter> outputScaling;
    boolean noReverseScaling, singleOutput;
    Consumer<String> scaleLogger;
    private void initOutput(boolean singleOutput, boolean noReverseScaling) {
        this.noReverseScaling=noReverseScaling;
        this.singleOutput = singleOutput;
        outputGrp = new GroupParameter("Output", interpolation, inputInterpAndScaling.getMaxChildCount()==1 ? reverseScaling : outputScalerIndex).setEmphasized(true);
        if (noReverseScaling) {
            outputInterpAndScaling = new SimpleListParameter<>("Output Interpolation", interpolation).setEmphasized(true).setHint("For each output, set the interpolation mode");
        } else {
            outputInterpAndScaling = new SimpleListParameter<>("Output Interpolation/Scaling", outputGrp).setEmphasized(true).setHint("For each output, set the interpolation mode and the index of the input scaler used to reverse histogram scaling");
            if (inputInterpAndScaling.getMaxChildCount()>1) {
                outputInterpAndScaling.addValidationFunctionToChildren(grp -> ((BoundedNumberParameter)grp.getChildAt(1)).getValue().intValue()<inputInterpAndScaling.getChildCount());
            }
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
    public DLResizeAndScale(String name) {
        this(name, false, false);
    }
    public DLResizeAndScale(String name, boolean singleOutput, boolean noReverseScaling) {
        super(new EnumChoiceParameter<>(name, MODE.values(), MODE.PAD));
        targetShape.addValidationFunction(InputShapesParameter.sameRankValidation());
        contraction.addValidationFunction(InputShapesParameter.sameRankValidation());
        tileShape.addValidationFunction(InputShapesParameter.sameRankValidation());
        minOverlap.addValidationFunction(InputShapesParameter.sameRankValidation());
        minPad.addValidationFunction(InputShapesParameter.sameRankValidation());
        setMinInputNumber(1);
        initOutput(singleOutput, noReverseScaling);
        setConditionalParameter();
        setHint("Prepares input images for Deep Neural Network processing: resize & scale images <br /><ul><li>SCALE_ONLY: Only performs intensity scaling (no resizing is performed). Shape of all input image provided must be equal to be processed by the dl engine.</li><li>RESAMPLE: Resize all images to a fixed size that must be compatible with the network input requirements. Choose this option to make a prediction on the whole image.</li><li>PAD: expand image either to a fixed user-defined size, or to a size compatible with the contraction level of the network</li><li>TILE: image is split into tiles on which predictions are made. Tiles are re-assembled by averaging the overlapping part. To limit border effects, border defined by the <em>min overlap</em> parameter are removed before assembling tiles.</li></ul>");
    }
    public DLResizeAndScale setScaleLogger(Consumer<String> scaleLogger) {
        this.scaleLogger = scaleLogger;
        return this;
    }
    public DLResizeAndScale addInputNumberValidation(IntSupplier inputNumber) {
        inputInterpAndScaling.addValidationFunction(list -> list.getChildCount()==inputNumber.getAsInt());
        inputScaling.addValidationFunction(list -> list.getChildCount()==inputNumber.getAsInt());
        return this;
    }
    public DLResizeAndScale addOutputNumberValidation(IntSupplier outputNumber) {
        outputInterpAndScaling.addValidationFunction(list -> list.getChildCount()==outputNumber.getAsInt());
        outputScaling.addValidationFunction(list -> list.getChildCount()==outputNumber.getAsInt());
        return this;
    }
    public DLResizeAndScale setInterpolationForOutput(InterpolationParameter interpolation, int... outputIdx) {
        if (noReverseScaling) {
            for (int idx : outputIdx) outputInterpAndScaling.getChildAt(idx).setContentFrom(interpolation);
        } else {
            for (int idx : outputIdx) ((GroupParameter)outputInterpAndScaling.getChildAt(idx)).getChildAt(0).setContentFrom(interpolation);
        }
        return this;
    }
    public DLResizeAndScale setMinInputNumber(int min) {
        inputInterpAndScaling.setUnmutableIndex(min-1);
        if (inputInterpAndScaling.getChildCount()<min) inputInterpAndScaling.setChildrenNumber(min);
        inputScaling.setUnmutableIndex(min-1);
        if (inputScaling.getChildCount()<min) inputScaling.setChildrenNumber(min);
        return this;
    }

    public DLResizeAndScale setMaxInputNumber(int max) {
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
        initOutput(this.singleOutput, this.noReverseScaling);
        setConditionalParameter();
        return this;
    }

    public DLResizeAndScale setMinOutputNumber(int min) {
        outputInterpAndScaling.setUnmutableIndex(min-1);
        if (outputInterpAndScaling.getChildCount()<min) outputInterpAndScaling.setChildrenNumber(min);
        outputScaling.setUnmutableIndex(min-1);
        if (outputScaling.getChildCount()<min) outputScaling.setChildrenNumber(min);
        return this;
    }

    public DLResizeAndScale setMaxOutputNumber(int max) {
        initOutput(max==1, this.noReverseScaling);
        outputInterpAndScaling.setMaxChildCount(max);
        if (outputInterpAndScaling.getChildCount()>max) outputInterpAndScaling.setChildrenNumber(max);
        outputScaling.setMaxChildCount(max);
        if (outputScaling.getChildCount()>max) outputScaling.setChildrenNumber(max);
        setConditionalParameter();
        return this;
    }

    public DLResizeAndScale setOutputNumber(int n) {
        if (outputInterpAndScaling.getUnMutableIndex()>=n-1) setMinOutputNumber(n);
        outputInterpAndScaling.setChildrenNumber(n);
        outputScaling.setChildrenNumber(n);
        return this;
    }
    public DLResizeAndScale setScaler(int inputIdx, HistogramScaler scaler) {
        if (inputScaling.getChildCount()<=inputIdx) inputScaling.setChildrenNumber(inputIdx+1);
        inputScaling.getChildAt(inputIdx).setPlugin(scaler);
        if (inputInterpAndScaling.getChildCount()<=inputIdx) inputInterpAndScaling.setChildrenNumber(inputIdx+1);
        inputInterpAndScaling.getChildAt(inputIdx).getParam2().setPlugin(scaler);
        return this;
    }

    public DLResizeAndScale setMode(MODE mode) {
        this.getActionableParameter().setValue(mode);
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
        if (noReverseScaling) {
            this.setActionParameters(MODE.SCALE_ONLY, iS, scaleImageByImage);
            this.setActionParameters(MODE.PAD, targetShape, contraction, paddingMode, minPad, iS, scaleImageByImage);
            this.setActionParameters(MODE.TILE, tileShape, minOverlap, padTilesCond, iS, scaleImageByImage);
        } else {
            this.setActionParameters(MODE.SCALE_ONLY, iS, oS, scaleImageByImage);
            this.setActionParameters(MODE.PAD, targetShape, contraction, paddingMode, minPad, iS, oS, scaleImageByImage);
            this.setActionParameters(MODE.TILE, tileShape, minOverlap, padTilesCond, iS, oS, scaleImageByImage);
        }
        this.setActionParameters(MODE.RESAMPLE, targetShape, contraction, iIS, oIS , scaleImageByImage);
        initChildList();
    }
    public DLResizeAndScale setEmphasized(boolean emp) {
        super.setEmphasized(emp);
        return this;
    }

    public DLResizeAndScale setDefaultTargetShape(int... targetShape) {
        this.targetShape.setValue(targetShape);
        return this;
    }
    public DLResizeAndScale setDefaultContraction(int... contraction) {
        this.contraction.setValue(contraction);
        return this;
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        logger.debug("configuring DLResizeAndScaleParameter from metadata");
        List<DLModelMetadata.DLModelInputParameter> inputs = metadata.getInputs();
        List<DLModelMetadata.DLModelOutputParameter> outputs = metadata.getOutputs();
        contraction.setValue(metadata.getContraction());
        if (inputs.get(0).fixedSize()) {
            if (getActionableParameter().getValue().equals(MODE.SCALE_ONLY)) getActionableParameter().setValue(MODE.TILE);
            targetShape.setValue(inputs.get(0).getShape());
            tileShape.setValue(inputs.get(0).getShape());
        } else {
            targetShape.setValue(inputs.get(0).is3D() ? new int[]{0,0,0}: new int[]{0,0});
        }
        inputInterpAndScaling.setChildrenNumber(inputs.size());
        inputScaling.setChildrenNumber(inputs.size());
        outputInterpAndScaling.setChildrenNumber(outputs.size());
        outputScaling.setChildrenNumber(outputs.size());
        for (int i = 0; i<inputs.size(); ++i) {
            PluginParameter<HistogramScaler> scaler = inputs.get(i).getScaling();
            inputInterpAndScaling.getChildAt(i).getChildAt(1).setContentFrom(scaler);
            inputScaling.getChildAt(i).setContentFrom(scaler);
            if (i==0) scaler.setContentFrom(scaler);
        }
        for (int i = 0; i<outputs.size(); ++i) {
            int scalerIndex = outputs.get(i).getReverseScalingIndex();
            if (!this.noReverseScaling) {
                Parameter scaling = ((GroupParameter)outputInterpAndScaling.getChildAt(i)).getChildAt(1);
                if (scaling instanceof BooleanParameter) ((BooleanParameter)scaling).setSelected(scalerIndex>=0);
                else ((NumberParameter)scaling).setValue(scalerIndex);
            }
            Parameter scaling2 = outputScaling.getChildAt(i);
            if (scaling2 instanceof BooleanParameter) ((BooleanParameter)scaling2).setSelected(scalerIndex>=0);
            else ((NumberParameter)scaling2).setValue(scalerIndex);
        }
        if (outputs.size()==1) {
            reverseScaling.setSelected(outputs.get(0).getReverseScalingIndex()==0);
            outputScalerIndex.setValue(outputs.get(0).getReverseScalingIndex());
        }
    }

    public MODE getMode() {
        return ((EnumChoiceParameter<MODE>)action).getSelectedEnum();
    }
    private static int getSize(double[] sizes, int div, boolean pad, int minPad) {
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
            if (div<=1) return cand;
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
        int[] totalContraction = ArrayUtil.reverse(contraction.getArrayInt(), true);
        int[] minPadA = ArrayUtil.reverse(minPad.getArrayInt(), true);
        for (int i = 0; i<shape.length; ++i) {
            int dim = i;
            if (shape[i]==0) {
                double[] sizes = Arrays.stream(imageNC).mapToDouble(a -> a[0].size(dim)).toArray();
                // get modal shape
                shape[i] = getSize(sizes, totalContraction[i], pad, minPadA[i]);
            } else if (pad) { // check that size is smaller than target size
                int minTargetShape = Arrays.stream(imageNC).mapToInt(a -> a[0].size(dim)).max().getAsInt() + 2 * minPadA[i];
                if (shape[i]<minTargetShape) throw new RuntimeException("Error while resizing in padding mode on axis="+i+":  max image size (with min pad)="+minTargetShape+" > target size="+shape[i]);
            }
        }
        logger.debug("Target shape: {}", Utils.toStringArray(shape));
        return shape;
    }
    public Image[][][] predict(DLengine engine, Image[][]... inputINC) {
        Triplet<Image[][][], int[][][], Map<Integer, HistogramScaler>[]> in = getNetworkInput(inputINC);
        Image[][][] out = engine.process(in.v1);
        return processPrediction(out, in);
    }
    private Supplier<HistogramScaler> setScaleLogger(Supplier<HistogramScaler> supplier) {
        if (scaleLogger!=null) return () -> {
            HistogramScaler scaler = supplier.get();
            if (scaler!=null) scaler.setScaleLogger(scaleLogger);
            return scaler;
        };
        else return supplier;
    }
    public Triplet<Image[][][], int[][][], Map<Integer, HistogramScaler>[]> getNetworkInput(Image[][][] inputINC) {
        switch (getMode()) {
            case SCALE_ONLY: {
                int nInputs = this.inputScaling.getActivatedChildCount();
                if (inputINC.length < nInputs) throw new IllegalArgumentException("Wrong input number: expected="+inputScaling.getActivatedChildren()+" provided="+inputINC.length);
                Map<Integer, HistogramScaler>[] scalersI = new Map[inputINC.length];
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    if (i<nInputs) {
                        int ii = i;
                        Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> res = scaleInput(inputINC[i], setScaleLogger(() -> inputScaling.getChildAt(ii).instantiatePlugin()), scaleImageByImage.getSelected());
                        imINC[i] = res.v1;
                        shapesIN[i] = res.v2;
                        scalersI[i] = res.v3;
                    } else imINC[i] = inputINC[i];
                }
                return new Triplet<>(imINC, shapesIN, scalersI);
            }
            case RESAMPLE:
            default: {
                int nInputs = this.inputInterpAndScaling.getActivatedChildCount();
                if (nInputs > inputINC.length) throw new IllegalArgumentException("Wrong input number: expected="+inputInterpAndScaling.getActivatedChildren()+" provided="+inputINC.length);
                Map<Integer, HistogramScaler>[] scalersI = new Map[inputINC.length];
                Supplier<HistogramScaler>[] scalerSuppliersI = new Supplier[inputINC.length];
                InterpolatorFactory[] interpolsI = new InterpolatorFactory[inputINC.length];
                for (int i = 0; i < nInputs; ++i) {
                    PairParameter<InterpolationParameter, PluginParameter<HistogramScaler>> params = inputInterpAndScaling.getChildAt(i);
                    interpolsI[i] = params.getParam1().getInterpolation();
                    scalerSuppliersI[i] = setScaleLogger(() -> params.getParam2().instantiatePlugin());
                }
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    if (i<nInputs) {
                        Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> res = scaleAndResampleInput(inputINC[i], interpolsI[i], scalerSuppliersI[i], getTargetImageShape(inputINC[i], false), scaleImageByImage.getSelected());
                        imINC[i] = res.v1;
                        shapesIN[i] = res.v2;
                        scalersI[i] = res.v3;
                    } else {
                        imINC[i] = inputINC[i];
                    }
                }
                return new Triplet<>(imINC, shapesIN, scalersI);
            }
            case PAD: {
                int nInputs = this.inputScaling.getActivatedChildCount();
                if (inputINC.length < nInputs) throw new IllegalArgumentException("Wrong input number: expected="+inputScaling.getActivatedChildren()+" provided="+inputINC.length);
                Map<Integer, HistogramScaler>[] scalersI = new Map[inputINC.length];
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                for (int i = 0; i < inputINC.length; ++i) {
                    if (i<nInputs) {
                        int ii = i;
                        Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> res = scaleAndPadInput(inputINC[i], paddingMode.getSelectedEnum(), setScaleLogger(() -> inputScaling.getChildAt(ii).instantiatePlugin()), getTargetImageShape(inputINC[i], true), scaleImageByImage.getSelected());
                        imINC[i] = res.v1;
                        shapesIN[i] = res.v2;
                        scalersI[i] = res.v3;
                    } else imINC[i] = inputINC[i];
                }
                return new Triplet<>(imINC, shapesIN, scalersI);
            }
            case TILE: {
                int nInputs = this.inputScaling.getActivatedChildCount();
                if (inputINC.length < nInputs) throw new IllegalArgumentException("Wrong input number: expected="+inputScaling.getActivatedChildren()+" provided="+inputINC.length);
                Resize.EXPAND_MODE padding = padTiles.getSelected() ? paddingMode.getSelectedEnum() : null;
                Map<Integer, HistogramScaler>[] scalersI = new Map[inputINC.length];
                Image[][][] imINC = new Image[inputINC.length][][];
                int[][][] shapesIN = new int[inputINC.length][][];
                int[] tileShapeXYZ = ArrayUtil.reverse(tileShape.getArrayInt(), true);
                int[] minOverlapXYZ = ArrayUtil.reverse(minOverlap.getArrayInt(), true);
                for (int i = 0; i < inputINC.length; ++i) {
                    if (i<nInputs) {
                        int ii = i;
                        Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> res = scaleAndTileInput(inputINC[i], padding, setScaleLogger(() -> inputScaling.getChildAt(ii).instantiatePlugin()), tileShapeXYZ, minOverlapXYZ, scaleImageByImage.getSelected());
                        imINC[i] = res.v1;
                        shapesIN[i] = res.v2;
                        scalersI[i] = res.v3;
                    } else imINC[i] = inputINC[i];
                }
                return new Triplet<>(imINC, shapesIN, scalersI);
            }
        }
    }
    private int getOutputScalerIndex(boolean resample, int outputIdx) {
        if (noReverseScaling) return -1;
        if (!resample) {
            if (outputScaling.getChildCount()>outputIdx) {
                Parameter p = outputScaling.getChildAt(outputIdx);
                if (inputScaling.getMaxChildCount()==1) {
                    return ((BooleanParameter)p).getSelected() ? 0 : -1;
                } else return ((NumberParameter)p).getValue().intValue();
            } else return -1;
        } else {
            GroupParameter params = (GroupParameter) outputInterpAndScaling.getChildAt(outputIdx);
            if (inputInterpAndScaling.getMaxChildCount()==1) {
                return ((BooleanParameter)params.getChildAt(1)).getSelected() ? 0 : -1;
            } else return ((NumberParameter)params.getChildAt(1)).getValue().intValue();
        }
    }
    public Image[][][] processPrediction(Image[][][] predictionsONC, Triplet<Image[][][], int[][][], Map<Integer, HistogramScaler>[]> input) {
        switch (getMode()) {
            case SCALE_ONLY: {
                Image[][][] outputONC = new Image[predictionsONC.length][][];
                for (int i = 0;i<predictionsONC.length; ++i) {
                    int scalerIndex = getOutputScalerIndex(false, i);
                    if (scalerIndex>=0) outputONC[i] = scaleAndResampleReverse(input.v1[scalerIndex], predictionsONC[i], null, input.v3[scalerIndex], null, scaleImageByImage.getSelected());
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
                    InterpolatorFactory interpol;
                    if (noReverseScaling) {
                        interpol = ((InterpolationParameter)outputInterpAndScaling.getChildAt(0) ).getInterpolation();
                    } else {
                        GroupParameter params = (GroupParameter)outputInterpAndScaling.getChildAt(i);
                        interpol = ((InterpolationParameter)params.getChildAt(0) ).getInterpolation();
                    }
                    int scalerIndex = getOutputScalerIndex(true, i);
                    outputONC[i] = scaleAndResampleReverse(input.v1[scalerIndex>=0?scalerIndex:0], predictionsONC[i],interpol, scalerIndex>=0? input.v3[scalerIndex]: null, input.v2[scalerIndex>=0?scalerIndex:0], scaleImageByImage.getSelected());
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
                    outputONC[i] = scaleAndTileReverse(input.v2[scalerIndex>=0?scalerIndex:0], predictionsONC[i], scalerIndex>=0 ? input.v3[scalerIndex] : null, minOverlapXYZ, padTiles.getSelected());
                }
                return outputONC;
        }
    }
    protected static Map<Integer, HistogramScaler> getScalerMap(Image[][] inNC, Supplier<HistogramScaler> scalerSupplier, boolean frameByFrame, boolean allowTransformInputImages) {
        if (scalerSupplier==null) return null;
        Map<Integer, HistogramScaler> scalerMap;
        if (!frameByFrame) {
            List<Image> allImages = ArrayUtil.flatmap(inNC).collect(Collectors.toList());
            HistogramScaler scaler = scalerSupplier.get();
            if (scaler!=null) {
                scaler.setHistogram(HistogramFactory.getHistogram(() -> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
                scaler.transformInputImage(allowTransformInputImages); // input images are not modified
            }
            scalerMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(i -> scaler==null ? HistogramScaler.noScaling() : scaler);
        } else {
            scalerMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(i -> {
                HistogramScaler scaler = scalerSupplier.get();
                List<Image> allImages = Arrays.asList(inNC[i]);
                if (scaler!=null) {
                    scaler.setHistogram(HistogramFactory.getHistogram(() -> Image.stream(allImages), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
                    scaler.transformInputImage(allowTransformInputImages); // input images are not modified
                }
                return scaler==null ? HistogramScaler.noScaling() : scaler;
            });
        }
        return scalerMap;
    }
    public static Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> scaleInput(Image[][] inNC, Supplier<HistogramScaler> scalerSupplier, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        if (scalerSupplier==null) return new Triplet(inNC, shapes, null);
        Map<Integer, HistogramScaler> scalerMap = getScalerMap(inNC, scalerSupplier, scaleFrameByFrame, false);
        Image[][] res = IntStream.range(0, inNC.length).parallel().mapToObj(i -> Arrays.stream(inNC[i]).map(image -> scalerMap.get(i).scale(image)).toArray(Image[]::new)).toArray(Image[][]::new);
        return new Triplet<>(res, shapes, scalerMap);
    }
    public static Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> scaleAndResampleInput(Image[][] inNC, InterpolatorFactory interpolation, Supplier<HistogramScaler> scalerSupplier, int[] targetImageShape, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        IntStream.range(0, inNC.length).parallel().forEach(i -> IntStream.range(0, inNC[i].length).forEach(j -> inNC[i][j] = TypeConverter.toFloatingPoint(inNC[i][j], false, false) ));
        Map<Integer, HistogramScaler> scalerMap = getScalerMap(inNC, scalerSupplier, scaleFrameByFrame, true);
        Image[][] inResampledNC = ResizeUtils.resample(inNC, interpolation, new int[][]{targetImageShape});
        if (scalerMap!=null) IntStream.range(0, inResampledNC.length).parallel().forEach(i -> IntStream.range(0, inResampledNC[i].length).forEach(j -> inResampledNC[i][j] = scalerMap.get(i).scale(inResampledNC[i][j]) ));
        return new Triplet<>(inResampledNC, shapes, scalerMap);
    }

    public static Image[][] scaleAndResampleReverse(Image[][] inputNC, Image[][] predNC, InterpolatorFactory interpolation, Map<Integer, HistogramScaler> scalerN, int[][] shapes, boolean scaleFrameByFrame) {
        if (scalerN!=null){
            scalerN.forEach((i,s) -> s.transformInputImage(true));
            IntStream.range(0, predNC.length).parallel().forEach(i -> IntStream.range(0, predNC[i].length).forEach(j -> predNC[i][j] = scalerN.get(i).reverseScale(predNC[i][j]) ));
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
    public static Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> scaleAndPadInput(Image[][] inNC, Resize.EXPAND_MODE mode, Supplier<HistogramScaler> scalerSupplier, int[] targetImageShape, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        IntStream.range(0, inNC.length).parallel().forEach(i -> IntStream.range(0, inNC[i].length).forEach(j -> inNC[i][j] = TypeConverter.toFloatingPoint(inNC[i][j], false, false) ));
        Map<Integer, HistogramScaler> scalerMap = getScalerMap(inNC, scalerSupplier, scaleFrameByFrame, true);
        Image[][] inResizedNC = ResizeUtils.pad(inNC, mode, Resize.EXPAND_POSITION.CENTER, new int[][]{targetImageShape});
        if (scalerMap!=null) IntStream.range(0, inResizedNC.length).parallel().forEach(i -> IntStream.range(0, inResizedNC[i].length).forEach(j -> inResizedNC[i][j] = scalerMap.get(i).scale(inResizedNC[i][j]) ));
        return new Triplet<>(inResizedNC, shapes, scalerMap);
    }
    public static Image[][] scaleAndPadReverse(Image[][] inputNC, Image[][] predNC, Map<Integer, HistogramScaler> scalerN, int[][] shapes) {
        if (scalerN!=null){
            scalerN.forEach((i,s) -> s.transformInputImage(true));
            IntStream.range(0, predNC.length).parallel().forEach(i -> IntStream.range(0, predNC[i].length).forEach(j -> predNC[i][j] = scalerN.get(i).reverseScale(predNC[i][j]) ));
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
    public static Triplet<Image[][], int[][], Map<Integer, HistogramScaler>> scaleAndTileInput(Image[][] inNC, Resize.EXPAND_MODE padding, Supplier<HistogramScaler> scalerSupplier, int[] targetTileShape, int[] minOverlap, boolean scaleFrameByFrame) {
        int[][] shapes = ResizeUtils.getShapes(inNC, false);
        IntStream.range(0, inNC.length).parallel().forEach(i -> IntStream.range(0, inNC[i].length).forEach(j -> inNC[i][j] = TypeConverter.toFloatingPoint(inNC[i][j], false, false) ));
        Map<Integer, HistogramScaler> scalerMap = getScalerMap(inNC, scalerSupplier, scaleFrameByFrame, true);
        Image[][] tilesNtC = TileUtils.splitTiles(inNC, targetTileShape, minOverlap, padding);
        int nTiles = tilesNtC.length / inNC.length;
        if (scalerMap!=null) IntStream.range(0, tilesNtC.length).parallel().forEach(n -> IntStream.range(0, tilesNtC[n].length).forEach(c -> {
            int i = n/nTiles;
            tilesNtC[n][c] = scalerMap.get(i).scale(tilesNtC[n][c]);
            tilesNtC[n][c].translate(inNC[i][c].getBoundingBox().reverseOffset()); // tiles offset are relative to each input image because when merging them we don't have access to input offset
        } ));
        return new Triplet<>(tilesNtC, shapes, scalerMap);
    }

    /**
     *
     * @param targetShape shape of destination tiles
     * @param predNtC
     * @param scalerN
     * @param minOverlap
     * @return scaled tiles
     */
    public static Image[][] scaleAndTileReverse(int[][] targetShape, Image[][] predNtC, Map<Integer, HistogramScaler> scalerN, int[] minOverlap, boolean padding) {
        Boolean[] target2DC = IntStream.range(0, predNtC[0].length).mapToObj(c -> Arrays.stream(predNtC).map(a -> a[c]).filter(i->i.sizeZ()>1).findAny().orElse(null) == null).toArray(Boolean[]::new); // special case: when input is 3D and prediction is 2D.
        Image[][] targetNC = IntStream.range(0, targetShape.length)
                .mapToObj(i -> IntStream.range(0, predNtC[i].length)
                        .mapToObj(c -> Image.createEmptyImage("", predNtC[i][c], new SimpleImageProperties(new SimpleBoundingBox(0, targetShape[i][0]-1, 0, targetShape[i][1]-1, 0, targetShape[i].length>2 ? (target2DC[c]?0:targetShape[i][2]-1): 0), predNtC[i][c].getScaleXY(), predNtC[i][c].getScaleZ())
                        )).toArray(Image[]::new)
                ).toArray(Image[][]::new);
        TileUtils.mergeTiles(targetNC, predNtC, minOverlap, padding);
        if (scalerN!=null){
            scalerN.forEach((i,s) -> s.transformInputImage(true));
            IntStream.range(0, targetNC.length).parallel().forEach(i -> IntStream.range(0, targetNC[i].length).forEach(j -> targetNC[i][j] = scalerN.get(i).reverseScale(targetNC[i][j]) ));
        }
        return targetNC;
    }
    @Override
    public DLResizeAndScale duplicate() {
        DLResizeAndScale res = new DLResizeAndScale(name);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
    public BoundingBox getOptimalPredictionBoundingBox(BoundingBox minimalBouningBox, BoundingBox globalBoundingBox) {
        MutableBoundingBox res = new MutableBoundingBox(minimalBouningBox);
        switch(getMode()) {
            case TILE:
                int[] tileShape = ArrayUtil.reverse(this.tileShape.getArrayInt(), true);
                if (res.sizeX()<tileShape[0]) res.setSizeX(tileShape[0], MutableBoundingBox.DIRECTION.CENTER);
                if (res.sizeY()<tileShape[1]) res.setSizeY(tileShape[1], MutableBoundingBox.DIRECTION.CENTER);
                if (tileShape.length==3 && res.sizeZ()<tileShape[2]) res.setSizeZ(tileShape[2], MutableBoundingBox.DIRECTION.CENTER);
                res.translateInto(globalBoundingBox);
                return res;
            case PAD: {
                int[] targetShape = ArrayUtil.reverse(this.targetShape.getArrayInt(), true);
                int[] contraction = ArrayUtil.reverse(this.contraction.getArrayInt(), true);
                int[] padding = ArrayUtil.reverse(this.minPad.getArrayInt(), true);
                for (int i = 0; i<targetShape.length; ++i) {
                    if (targetShape[i] == 0) targetShape[i] = closestNumber(minimalBouningBox.size(i), contraction[i], true) - padding[i]*2;
                    if (targetShape[i]>minimalBouningBox.size(i)) res.setSize(targetShape[i], MutableBoundingBox.DIRECTION.CENTER, i );
                }
            }
            case RESAMPLE: {
                // either honor contraction or target shape
                int[] targetShape = ArrayUtil.reverse(this.targetShape.getArrayInt(), true);
                int[] contraction = ArrayUtil.reverse(this.contraction.getArrayInt(), true);
                for (int i = 0; i<targetShape.length; ++i) {
                    if (targetShape[i] == 0) targetShape[i] = closestNumber(minimalBouningBox.size(i), contraction[i], true);
                    if (targetShape[i]>minimalBouningBox.size(i)) res.setSize(targetShape[i], MutableBoundingBox.DIRECTION.CENTER, i );
                }

            }
            default: {
                return minimalBouningBox;
            }
        }
    }
}
