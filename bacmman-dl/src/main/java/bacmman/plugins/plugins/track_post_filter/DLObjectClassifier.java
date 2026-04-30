package bacmman.plugins.plugins.track_post_filter;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.*;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DLObjectClassifier implements TrackPostFilter, Hint, MultiThreaded {
    protected ChannelImageParameter channels = new ChannelImageParameter("Channels", true, true)
            .setHint("Channels images that will be fed to the neural network. If no channel is selected, the channel of the <em>Objects</em> parameter will be used");
    BooleanParameter proba = new BooleanParameter("Export All Probabilities", false).setHint("If true, probabilities for each class are returned");
    protected BoundedNumberParameter classNumber = new BoundedNumberParameter("Class number", 0, -1, 0, null).setHint("Number of predicted classes");
    ConditionalParameter<Boolean> probaCond = new ConditionalParameter<>(proba).setActionParameters(true, classNumber);
    PluginParameter<DLEngine> dlEngine = new PluginParameter<>("DLEngine", DLEngine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3)).setHint("Deep learning engine used to run the DNN.");
    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Input Size And Intensity Scaling", true, true, false)
            .setMinInputNumber(1).setMaxOutputNumber(1).setMinOutputNumber(1).setOutputNumber(1)
            .setMode(DLResizeAndScale.MODE.PAD).setDefaultContraction(8, 8);
    BooleanParameter eraseTouchingContours = new BooleanParameter("Erase Touching Contours", false).setHint("If true, draws a black line to split touching objects");

    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix for measurement names");
    enum STAT {MEAN, MEDIAN}
    EnumChoiceParameter<STAT> stat =  new EnumChoiceParameter<>("Quantification", STAT.values(), STAT.MEDIAN).setHint("Operation to reduce estimated probability in each segmented object");
    IntegerParameter frameWindow = new IntegerParameter("FrameWindow", 0).setHint("Define the size of the input frame window. Set 0 if prediction is performed on single frames");

    public DLObjectClassifier() {
        channels.addValidationFunction(chs -> dlResizeAndScale.getInputNumber() == chs.getSelectedIndices().length);
        dlResizeAndScale.addInputNumberValidation( () -> channels.getSelectedIndices().length );
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return frameWindow.getIntValue() == 0 ? ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS : ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL;
    }

    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        //dlResizeAndScale.setScaleLogger(Core::userLog);
        Map<SegmentedObject, List<SegmentedObject>> parentMapChildren = parentTrack.stream().collect(Collectors.toMap(i->i, i->i.getChildren(structureIdx).collect(Collectors.toList())));
        parentMapChildren.entrySet().removeIf(e -> e.getValue().isEmpty()); // do not predict when no objects
        SegmentedObject[] parentArray = parentMapChildren.keySet().toArray(new SegmentedObject[0]);
        // prepare inputs: EDM/CDM + channels
        Map<SegmentedObject, Region> regions = new HashMapGetCreate.HashMapGetCreateRedirected<>(SegmentedObject::getRegion);
        Function<SegmentedObject, RegionPopulation> createPop = p -> {
            RegionPopulation res = p.getChildRegionPopulation(structureIdx);
            if (eraseTouchingContours.getSelected()) {
                res = res.duplicate();
                List<SegmentedObject> so = parentMapChildren.get(p);
                for (int i = 0; i<res.getRegions().size(); ++i) regions.put(so.get(i), res.getRegions().get(i));
            }
            return res;
        };
        int[] channels = this.channels.getSelectedIndices().length==0 ? new int[]{parentTrack.get(0).getExperimentStructure().getChannelIdx(structureIdx)} : this.channels.getSelectedIndices();
        Supplier<IntStream> chanStream = ()->IntStream.concat(IntStream.of(channels), IntStream.of(-1, -2));
        // TODO : add timelapse mode.
        Image[][][] chans = chanStream.get()
                .mapToObj(i -> parentMapChildren.keySet().stream()
                        .map(p->i>=0 ? p.getRawImageByChannel(i) : (i==-1 ? createPop.apply(p).getEDM(true, true) : createPop.apply(p).getGCDM(true) ) )
                        .map(im -> new Image[]{im})// per channel per object
                        .toArray(Image[][]::new)) // per channel
                .toArray(Image[][][]::new);
        DLEngine engine = dlEngine.instantiatePlugin();
        engine.init();
        //dlResizeAndScale.setScaleLogger(Core::userLog);
        Image[][] predNC = getDlResizeAndScale(channels.length).predict(engine, chans)[0];
        boolean allProba = this.proba.getSelected();
        if (allProba && predNC[0].length!=classNumber.getIntValue()) throw new RuntimeException("ClassNumber parameter differs from number of predicted classes: "+predNC[0].length);
        BiFunction<Region, Image[], double[]> reduction;
        switch (stat.getSelectedEnum()) {
            case MEDIAN:
            default:
                reduction = (r, predC) -> Arrays.stream(predC).mapToDouble(i -> BasicMeasurements.getQuantileValue(r, i, 0.5)[0]).toArray();
                break;
            case MEAN:
                reduction = (r, predC) -> Arrays.stream(predC).mapToDouble(i -> BasicMeasurements.getMeanValue(r, i)).toArray();
                break;
        }
        for (int i = 0; i<predNC.length; ++i) {
            Image[] predC = predNC[i];
            parentMapChildren.get(parentArray[i]).forEach(o -> {
                double[] probas = reduction.apply(regions.get(o), predC);
                int idxMax = ArrayUtil.max(probas);
                o.setAttribute(prefix.getValue()+"ClassIdx", idxMax);
                o.setAttribute(prefix.getValue()+"Proba", probas[idxMax]);
                if (allProba) {
                    for (int c = 0; c<probas.length; ++c) o.setAttribute(prefix.getValue() + "ProbaClass_" + c, probas[c]);
                }
            });
        }
    }



    @Override
    public String getHintText() {
        return "Runs a DL model fed with one or several channels as well as EDM/CDM of segmented objects (in this order, one input for EDM and one input for each channel) ; it predicts a category for each object";
    }

    protected DLResizeAndScale getDlResizeAndScale(int nChannels) {
        // add scaling & interpolation for EDM and GCDM for each label
        DLResizeAndScale res = dlResizeAndScale.duplicate().setScaleLogger(dlResizeAndScale.getScaleLogger());
        // channel, then EDM then CDM
        res.setInputNumber( nChannels + 2 );
        int[] labelIdx = IntStream.range(nChannels, nChannels + 2).toArray();
        for (int i : labelIdx) res.setScaler(i, null); // no intensity scaling for EDM and CDM
        if (res.getMode().equals(DLResizeAndScale.MODE.RESAMPLE)) { // set interpolation : identical to 1st channel (EDM & CDM are floating point maps)
            res.setInterpolationForInput(res.getInputInterpolation(0), labelIdx);
        }
        return res;

    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{channels, probaCond, dlEngine, dlResizeAndScale, stat, eraseTouchingContours, prefix};
    }
    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }
}
