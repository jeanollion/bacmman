package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.input_image.InputImages;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.plugins.*;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.*;
import java.util.stream.IntStream;

public class DLFilterSimple implements TrackPreFilter, ConfigurableTransformation, Filter, Hint, DLMetadataConfigurable { // TransformationApplyDirectly
    static Logger logger = LoggerFactory.getLogger(DLFilterSimple.class);
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("DLEngine", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Choose a deep learning engine module");
    DLResizeAndScale dlResample = new DLResizeAndScale("ResizeAndScale").setMaxOutputNumber(1).setMaxInputNumber(1).setEmphasized(true);
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 1, 0, null).setEmphasized(true).setHint("For time-lapse dataset: defines how many frames are processed at the same time (0=all frames)");
    BooleanParameter timelapse = new BooleanParameter("Timelapse", false).setHint("If true, input image are concatenated with previous (and next) frame(s)");
    BooleanParameter next = new BooleanParameter("Next", true).setHint("If true, input image are concatenated with previous and next frame(s)");
    BoundedNumberParameter nFrames = new BoundedNumberParameter("Frame Number", 0, 1, 1, null).setHint("Defines the neighborhood size in time axis: how many previous (and next) frames are concatenated to current frame");
    //BooleanParameter averagePredictions = new BooleanParameter("Average Prediction", false).setHint("If true, prediction are averaged with predictions from neighboring frames");
    ArrayNumberParameter frameSubsampling = new ArrayNumberParameter("Frame sub-sampling average", -1, new BoundedNumberParameter("Frame interval", 0, 2, 2, null)).setDistinct(true).setSorted(true).addValidationFunctionToChildren(n -> n.getIntValue() > 1);
    BoundedNumberParameter channel = new BoundedNumberParameter("Channel", 0, 0, 0, null).setHint("In case the model predicts several channel, set here the channel to be used");
    BooleanParameter inputFrameIndex = new BooleanParameter("Input Frame Index", false)
            .setHint("Set true to provide frame index to the dl model as second input (dl model must support this feature)");
    public enum OOB_POLICY {MIRROR, BORDER, ZERO}
    EnumChoiceParameter<OOB_POLICY> oobPolicy = new EnumChoiceParameter<>("Out-of-bounds policy", OOB_POLICY.values(), OOB_POLICY.MIRROR).setHint("How to replace an out-of-bound adjacent frame: MIRROR = use frame on the other side of the curent frame. BORDER = use closest frame. ZERO = ZERO padding");
    ConditionalParameter<Boolean> timelapseCond = new ConditionalParameter<>(timelapse).setActionParameters(true, next, nFrames, oobPolicy, frameSubsampling);
    static boolean testNoFrame = false;
    static boolean testNoTL = false;
    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }
    private int engineNumIn() {
        DLengine in = dlEngine.instantiatePlugin();
        if (in==null) return 0;
        else return in.getNumInputArrays();
    }
    private Image[] predict(Image[][][] inputINC) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        int numInputs = engine.getNumInputArrays();
        if (numInputs!=1) throw new IllegalArgumentException("Model inputs "+numInputs+ " while 1 input is expected");
        int numOutputs = engine.getNumOutputArrays();
        if (numOutputs!=1) throw new IllegalArgumentException("Model predicts "+numOutputs+ " while 1 output is expected");

        double nImages = inputINC[0].length;
        int increment = batchSize.getIntValue() == 0 ? inputINC[0].length : (int)Math.ceil( nImages / Math.ceil( nImages / batchSize.getIntValue()) );
        Image[] res = new Image[inputINC[0].length];
        for (int i = 0; i < nImages; i += increment ) {
            int nFrames = (int)Math.min(nImages-i, increment);
            Image[][][] inputSub = new Image[1][nFrames][1];
            for (int j = 0; j<nFrames; ++j) inputSub[0][j][0] = inputINC[0][j+i][0];
            Image[][][] predictionONC =dlResample.predict(engine, inputSub);
            Image[] pred = ResizeUtils.getChannel(predictionONC[0], channel.getIntValue());
            System.arraycopy(pred, 0, res, i, pred.length);
        }
        return res;
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Image[] out;
        boolean inputFrameIndex = this.inputFrameIndex.getSelected();
        if (!this.timelapse.getSelected()) {
            Image[][][] in = new Image[inputFrameIndex ? 2 : 1][][];
            in[0] = preFilteredImages.values().stream().map(im -> new Image[]{im}).toArray(Image[][]::new);
            if (inputFrameIndex) in[1] = preFilteredImages.keySet().stream().map(p -> new Image[]{asImage(p.getFrame())}).toArray(Image[][]::new);
            out = predict(in);
        } else {
            boolean[] noPrevParent = new boolean[preFilteredImages.size()];
            noPrevParent[0] = true;
            List<SegmentedObject> parentTrack = new ArrayList<>(preFilteredImages.keySet());
            for (int i = 1; i < noPrevParent.length; ++i) if (parentTrack.get(i - 1).getFrame() < parentTrack.get(i).getFrame() - 1) noPrevParent[i] = true;
            ToIntFunction<SegmentedObject> getFrame = testNoFrame ? p->0 : SegmentedObject::getFrame;
            Image[] frames = inputFrameIndex ? preFilteredImages.keySet().stream().map(p -> asImage(getFrame.applyAsInt(p))).toArray(Image[]::new) : null;
            out = predictTimelapse(preFilteredImages.values().toArray(new Image[0]), frames, noPrevParent);
        }
        int idx = 0;
        for (Map.Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) e.setValue(out[idx++]);
    }


    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, inputFrameIndex, dlResample, batchSize, channel, timelapseCond};
    }

    @Override
    public String getHintText() {
        return "Filter an image by running a deep neural network";
    }

    // transformation implementation
    Image[] processedFrames;
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages)  throws IOException  {
        int nFrames = inputImages.singleFrameChannel(channelIdx) ? 1 : inputImages.getFrameNumber();
        int minFrame = inputImages.getMinFrame();
        boolean inputFrameIndex = this.inputFrameIndex.getSelected();
        if (!this.timelapse.getSelected()) {
            Image[][][] input = new Image[inputFrameIndex? 2 : 1][nFrames][1];
            for (int t = 0; t < nFrames; ++t) {
                input[0][t][0] = inputImages.getImage(channelIdx, t);
                if (inputFrameIndex) input[1][t][0] = asImage(t+minFrame);
            }
            processedFrames = predict(input);
        } else {
            boolean[] noPrevParent = new boolean[inputImages.getFrameNumber()];
            noPrevParent[0] = true;
            Image[] input = new Image[inputImages.getFrameNumber()];
            for (int i = 0; i<input.length; ++i) {
                input[i] = inputImages.getImage(channelIdx, i);
            }
            ToIntFunction<Integer> getFrame = testNoFrame ? f->0 : f -> f+minFrame;
            Image[] frames = inputFrameIndex ? IntStream.range(0, inputImages.getFrameNumber()).mapToObj(f -> asImage(getFrame.applyAsInt(f))).toArray(Image[]::new) : null;
            processedFrames = predictTimelapse(input, frames, noPrevParent);
        }
    }

    private static Image asImage(int frameIndex) {
        ImageFloat res = new ImageFloat("index", 1, 1, 1);
        res.setPixel(0, 0, 0, frameIndex);
        return res;
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
      return processedFrames!=null;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        /*Image[][][] input = new Image[][][]{{{image}}};
        Image[] out = predict(input);
        return out[0];*/
        return processedFrames.length>1 ? processedFrames[timePoint] : processedFrames[0];
    }

    @Override
    public boolean highMemory() {
        return true;
    }

    protected static Image[][] getInputs(Image[] images, boolean[] noPrevParent, boolean addNext, int nFrames, int idxMin, int idxMaxExcl, int frameInterval, OOB_POLICY oobPolicy) {
        BiFunction<Integer, Integer, Image>[] getImage = new BiFunction[1];
        getImage[0] = (cur, i) -> {
            if (i < 0) { // OOB left
                switch (oobPolicy) {
                    case ZERO: {
                        return Image.createEmptyImage("input frame", images[0], images[0]);
                    }
                    case MIRROR: {
                        logger.debug("MIRROR OOB: cur {}, neigh {}, alt: {}", cur, i, cur + (cur-i));
                        i = cur + (cur-i);
                        if (i<images.length) return images[i];
                    }
                }
                return images[0];
            } else if (i >= images.length) { // OOB right
                switch (oobPolicy) {
                    case ZERO: {
                        return Image.createEmptyImage("input frame", images[0], images[0]);
                    }
                    case MIRROR: {
                        i = cur + (cur-i);
                        if (i>=0) return images[i];
                    }
                }
                return images[images.length - 1];
            }
            if (i < cur) {
                if (noPrevParent[i + 1]) return getImage[0].apply(cur, i + 1);
            } else if (i > cur) {
                if (noPrevParent[i]) return getImage[0].apply(cur, i - 1);
            }
            return images[i];
        };
        ToIntFunction<Integer> getNeigh = testNoTL ? n -> 0 : n -> n * frameInterval;
        IntFunction<Image[]> getImages = addNext ? i -> IntStream.rangeClosed(- nFrames, nFrames).mapToObj(n->getImage[0].apply(i, i + getNeigh.applyAsInt(n))).toArray(Image[]::new) : i -> IntStream.rangeClosed(- nFrames, 0).mapToObj(n->getImage[0].apply(i, i + getNeigh.applyAsInt(n))).toArray(Image[]::new);
        return IntStream.range(idxMin, idxMaxExcl).mapToObj(getImages).toArray(Image[][]::new);
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        IntegerParameter channel = metadata.getOtherParameter("Channel", IntegerParameter.class);
        if (channel!=null) this.channel.setValue(channel.getIntValue());
        int inputChannels = metadata.getInputs().get(0).getChannelNumber();
        if (inputChannels == 1) this.timelapse.setSelected(false);
        else if (inputChannels>1) {
            BooleanParameter nextP = metadata.getOtherParameter("Next", BooleanParameter.class);
            if (nextP==null) {
                if (inputChannels%2 == 0) next.setSelected(false);
            } else next.setSelected(nextP.getSelected());
            if (next.getSelected() && inputChannels%2==0) {
                logger.error("Error while configuring parameter: next is selected thus number of input channels should be uneven");
            } else {
                int nFrames = next.getSelected() ? (inputChannels - 1) / 2 : inputChannels;
                this.nFrames.setValue(nFrames);
            }
        }
    }

    private class PredictedChannels {
        Image[] filtered, filteredP, filteredN;
        final boolean next;
        boolean avg;
        final int nFrames;
        PredictedChannels(boolean avg, boolean next, int nFrames) {
            this.avg = avg;
            this.next = next;
            this.nFrames = nFrames;
        }

        void init(int n) {
            filtered = new Image[n];
            if (avg && next) {
                filteredP = new Image[n];
                if (next) {
                    filteredN = new Image[n];
                }
            }
        }

        void predict(DLengine engine, Image[] images, Image[] frames, boolean[] noPrev, int frameInterval) {
            int idxLimMin = frameInterval > 1 ? frameInterval : 0;
            int idxLimMax = frameInterval > 1 ? next ? images.length - frameInterval : images.length : images.length;
            init(idxLimMax - idxLimMin);
            double interval = idxLimMax - idxLimMin;
            int increment = (int)Math.ceil( interval / Math.ceil( interval / batchSize.getIntValue()) );
            for (int i = idxLimMin; i < idxLimMax; i += increment ) {
                int idxMax = Math.min(i + batchSize.getIntValue(), idxLimMax);
                Image[][] input = getInputs(images, noPrev, next, nFrames, i, idxMax, frameInterval, oobPolicy.getSelectedEnum());
                Image[][] inputF = frames!=null ? getInputs(frames, noPrev, next, nFrames, i, idxMax, frameInterval, oobPolicy.getSelectedEnum()) : null;
                logger.debug("input: [{}; {}) / [{}; {})", i, idxMax, idxLimMin, idxLimMax);
                Image[][][] predictions = inputF == null ? dlResample.predict(engine, input) : dlResample.predict(engine, input, inputF);
                appendPrediction(predictions, i - idxLimMin);
            }
        }

        void appendPrediction(Image[][][] predictions, int idx) {
            int n = predictions[0].length;
            if (predictions[0][0].length==1) {
                System.arraycopy(ResizeUtils.getChannel(predictions[0], 0), 0, this.filtered, idx, n);
                filteredP = null;
                filteredN=null;
                avg = false;
            } else {
                System.arraycopy(ResizeUtils.getChannel(predictions[0], nFrames), 0, this.filtered, idx, n);
                if (avg) {
                    System.arraycopy(ResizeUtils.getChannel(predictions[0], nFrames - 1), 0, this.filteredP, idx, n);
                    if (next) {
                        System.arraycopy(ResizeUtils.getChannel(predictions[0], nFrames + 1), 0, this.filteredN, idx, n);
                    }
                }
            }
        }

        void averagePredictions(boolean[] noPrevParent, Image prev) {
            if (avg) {
                if (next) {
                    BiFunction<Image[][], Image, Image[]> average3 = (pcn, prevN) -> {
                        Image[] prevI = pcn[0];
                        Image[] curI = pcn[1];
                        Image[] nextI = pcn[2];
                        int last = curI.length - 1;
                        if (prevI.length > 1 && !noPrevParent[1]) {
                            if (prevN != null) ImageOperations.average(curI[0], curI[0], prevI[1], prevN);
                            else ImageOperations.average(curI[0], curI[0], prevI[1]);
                        }
                        for (int i = 1; i < last; ++i) {
                            if (!noPrevParent[i + 1] && !noPrevParent[i]) {
                                ImageOperations.average(curI[i], curI[i], prevI[i + 1], nextI[i - 1]);
                            } else if (!noPrevParent[i + 1]) {
                                ImageOperations.average(curI[i], curI[i], prevI[i + 1]);
                            } else if (!noPrevParent[i]) {
                                ImageOperations.average(curI[i], curI[i], nextI[i - 1]);
                            }
                        }
                        if (!noPrevParent[last]) ImageOperations.average(curI[last], curI[last], nextI[last - 1]);
                        return curI;
                    };
                    Image[][] pcn = new Image[][]{filteredP, filtered, filteredN};
                    filtered = average3.apply(pcn, prev);
                    filteredP = null;
                    filteredN = null;

                } else {
                    Function<Image[][], Image[]> average = (pc) -> {
                        Image[] p = pc[0];
                        Image[] cur = pc[1];
                        for (int i = 0; i < cur.length - 1; ++i) {
                            if (!noPrevParent[i + 1]) ImageOperations.average(cur[i], cur[i], p[i + 1]);
                        }
                        return cur;
                    };
                    Image[][] pn = new Image[][]{filteredP, filtered};
                    filtered = average.apply(pn);
                    filteredP = null;
                }
                System.gc();
            }
        }
    }

    private Image[] predictTimelapse(Image[] input, Image[] frames, boolean[] noPrev) {
        boolean next = this.next.getSelected();
        long t0 = System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1 = System.currentTimeMillis();
        logger.info("engine instantiated in {}ms, class: {}", t1 - t0, engine.getClass());
        boolean avg = false; //this.averagePredictions.getSelected()
        PredictedChannels pred = new PredictedChannels(avg, this.next.getSelected(), this.nFrames.getIntValue());
        pred.predict(engine, input, frames, noPrev, 1);
        long t2 = System.currentTimeMillis();

        logger.info("{} predictions made in {}ms", input.length, t2 - t1);



        pred.averagePredictions(noPrev, null);
        // average with prediction with user-defined frame intervals

        if (frameSubsampling.getChildCount() > 0) {
            System.gc();
            int size = input.length;
            IntPredicate filter = next ? frameInterval -> 2 * frameInterval < size : frameInterval -> frameInterval < size;
            int[] frameSubsampling = IntStream.of(this.frameSubsampling.getArrayInt()).filter(filter).toArray();
            ToIntFunction<Integer> getNSubSampling = next ? frame -> (int) IntStream.of(frameSubsampling).filter(fi -> frame >= fi && frame < size - fi).count() : frame -> (int) IntStream.of(frameSubsampling).filter(fi -> frame >= fi).count();
            if (frameSubsampling.length > 0) {
                for (int frame = 1; frame < pred.filtered.length; frame++) { // half of the final value is edm without frame subsampling
                    if (getNSubSampling.applyAsInt(frame) > 0) {
                        ImageOperations.affineOperation(pred.filtered[frame], pred.filtered[frame], 0.5, 0);
                    }
                }
                for (int frameInterval : frameSubsampling) {
                    logger.debug("averaging with frame subsampled: {}", frameInterval);
                    PredictedChannels pred2 = new PredictedChannels(false, this.next.getSelected(), pred.nFrames);
                    pred2.predict(engine, input, frames, noPrev, frameInterval);
                    for (int frame = frameInterval; frame < pred2.filtered.length + frameInterval; ++frame) { // rest of half of the value is filtered with frame subsampling
                        double n = getNSubSampling.applyAsInt(frame);
                        ImageOperations.weightedSum(pred.filtered[frame], new double[]{1, 0.5 / n}, pred.filtered[frame], pred2.filtered[frame - frameInterval]);
                    }
                }
            }
        }
        return pred.filtered;
    }

}
