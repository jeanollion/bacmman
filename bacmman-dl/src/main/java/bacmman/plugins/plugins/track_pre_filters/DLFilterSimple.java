package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectImageMap;
import bacmman.data_structure.dao.DiskBackedImageManager;
import bacmman.data_structure.input_image.InputImages;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.image.SimpleDiskBackedImage;
import bacmman.plugins.*;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.*;
import java.util.stream.Collectors;

public class DLFilterSimple implements TrackPreFilter, Transformation, ConfigurableTransformation, Filter, Hint, DLMetadataConfigurable { // TransformationApplyDirectly
    static Logger logger = LoggerFactory.getLogger(DLFilterSimple.class);
    PluginParameter<DLEngine> dlEngine = new PluginParameter<>("DLEngine", DLEngine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Choose a deep learning engine module");
    DLResizeAndScale dlResample = new DLResizeAndScale("ResizeAndScale").setMaxOutputNumber(1).setMaxInputNumber(1).setEmphasized(true);
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 1, 0, null).setEmphasized(true).setHint("For time-lapse dataset: defines how many frames are processed at the same time (0=all frames)");
    BooleanParameter timelapse = new BooleanParameter("Timelapse", false).setHint("If true, input image are concatenated with previous (and next) frame(s)");
    BooleanParameter next = new BooleanParameter("Next", true).setHint("If true, input image are concatenated with previous and next frame(s)");
    BoundedNumberParameter nFrames = new BoundedNumberParameter("Frame Number", 0, 1, 1, null).setHint("Defines the neighborhood size in time axis: how many previous (and next) frames are concatenated to current frame");
    public enum OOB_POLICY {MIRROR, BORDER, ZERO}
    EnumChoiceParameter<OOB_POLICY> oobPolicy = new EnumChoiceParameter<>("Out-of-bounds policy", OOB_POLICY.values(), OOB_POLICY.BORDER).setHint("How to replace an out-of-bound adjacent frame: MIRROR = use frame on the other side of the curent frame. BORDER = use closest frame. ZERO = ZERO padding. Note that if model returns same number of frame as input frame, they will be used instead of predicting edges frames using OOB.");
    ConditionalParameter<Boolean> timelapseCond = new ConditionalParameter<>(timelapse).setActionParameters(true, next, nFrames, oobPolicy);
    static boolean testNoTL = false;

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, dlResample, batchSize, timelapseCond};
    }

    @Override
    public String getHintText() {
        return "Filter an image by running a deep neural network. If network have several input channels they represent a temporal neighborhood (adjacent frames)";
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        int inputChannels = metadata.getInputs().get(0).getChannelNumber();
        if (inputChannels == 1) {
            DLEngine.setZAxis(dlEngine, DLEngine.Z_AXIS.BATCH);
            this.timelapse.setSelected(false);
        }
        else if (inputChannels>1) { // either timelapse OR ZToBatch

            this.timelapse.setSelected(true);
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

    // trackPreFilter implementation

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void filter(int structureIdx, SegmentedObjectImageMap preFilteredImages) {
        DLEngine dLengine = getDLengine();
        Map<Integer, SegmentedObject> parentTrack = preFilteredImages.streamKeys().collect(Collectors.toMap(SegmentedObject::getFrame, o->o));
        List<int[]> segments = getContiguousSegments(parentTrack);
        ImageIO imageIO = new ImageIO() {
            @Override
            public Image get(int idx) {
                return preFilteredImages.getImage(parentTrack.get(idx));
            }
            @Override
            public void set(int idx, Image img) {
                preFilteredImages.set(parentTrack.get(idx), img);
            }
        };
        logger.debug("segments: {}",Utils.toStringList(segments, s -> "["+s[0]+"; "+s[1]+")"));
        for (int[] segment : segments) {
            try {
                predict(imageIO, segment[0], segment[1], dLengine);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static List<int[]> getContiguousSegments(Map<Integer, SegmentedObject> parentTrack) {
        int[] frames = parentTrack.keySet().stream().mapToInt(i->i).sorted().toArray();
        List<int[]> splitPoints = new ArrayList<>();
        int[] curSeg = new int[2];
        curSeg[0] = frames[0];
        for (int i = 1; i<frames.length; ++i) {
            if (frames[i] > frames[i-1] + 1 ) {
                curSeg[1] = frames[i-1] + 1;
                splitPoints.add(curSeg);
                curSeg = new int[2];
                curSeg[0] = frames[i];
            }
        }
        curSeg[1] = frames[frames.length-1]+1;
        splitPoints.add(curSeg);
        return splitPoints;
    }

    // transformation implementation
    DiskBackedImageManager preProcessedImagesManager;
    Map<Integer, SimpleDiskBackedImage> preProcessedImages;
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages)  throws IOException  {
        int nFrames = inputImages.singleFrameChannel(channelIdx) ? 1 : inputImages.getFrameNumber();
        int minFrame = inputImages.getMinFrame();
        preProcessedImagesManager = Core.getDiskBackedManager(inputImages.getTmpDirectory());
        preProcessedImagesManager.startDaemon(0.6, 500);
        preProcessedImages = new HashMap<>(nFrames);
        ImageIO imageIO = new ImageIO() {
            @Override
            public Image get(int frame) throws IOException {
                return inputImages.getImage(channelIdx, frame-minFrame);
            }

            @Override
            public void set(int frame, Image img) throws IOException {
                SimpleDiskBackedImage sdbi = preProcessedImagesManager.createSimpleDiskBackedImage(img, false, false);
                preProcessedImagesManager.storeSimpleDiskBackedImage(sdbi);
                preProcessedImages.put(frame-minFrame, sdbi);
            }
        };
        predict(imageIO, minFrame, minFrame+nFrames, getDLengine());
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return preProcessedImagesManager != null;
    }

    @Override
    public boolean highMemory() {
        return true;
    }

    @Override
    public void clear() {
        preProcessedImagesManager.clear(true);
        preProcessedImagesManager = null;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        SimpleDiskBackedImage sdbi = preProcessedImages.get(timePoint);
        if (sdbi==null) throw new RuntimeException("Image not pre-processed: channel="+channelIdx+ "frame="+timePoint);
        Image res = sdbi.getImage();
        preProcessedImagesManager.detach(sdbi, true);
        return res;
    }

    // prediction functions

    private interface ImageIO {
        Image get(int idx) throws IOException;
        void set(int idx, Image img) throws IOException;
    }

    private int engineNumIn() {
        DLEngine in = dlEngine.instantiatePlugin();
        if (in==null) return 0;
        else return in.getNumInputArrays();
    }

    private DLEngine getDLengine() {
        DLEngine engine = dlEngine.instantiatePlugin();
        engine.init();
        int numInputs = engine.getNumInputArrays();
        if (numInputs!=1) throw new IllegalArgumentException("Model inputs "+numInputs+ " while 1 input is expected");
        int numOutputs = engine.getNumOutputArrays();
        if (numOutputs!=1) throw new IllegalArgumentException("Model predicts "+numOutputs+ " while 1 output is expected");
        return engine;
    }

    @FunctionalInterface
    private interface Assigner {
        void accept(int idx, Image[][][] pred) throws IOException;
    }

    private void predict(ImageIO imageIO, int idxMin, int idxMaxExcl, DLEngine engine) throws IOException {
        int nImages = idxMaxExcl - idxMin;
        int increment = batchSize.getIntValue() == 0 ? nImages : (int)Math.ceil( (double)nImages / Math.ceil( (double)nImages / batchSize.getIntValue()) );
        if (!timelapse.getSelected()) {
            for (int i = 0; i < nImages; i += increment ) {
                int batchSize = Math.min(nImages-i, increment);
                Image[][][] inputSub = new Image[1][batchSize][1];
                for (int j = 0; j<batchSize; ++j) inputSub[0][j][0] = imageIO.get(j+i+idxMin);
                Image[][][] predictionONC =dlResample.predict(engine, inputSub);
                if (predictionONC[0][0].length != 1) throw new RuntimeException("Invalid output channel number. Model should return 1 channel");
                Image[] pred = ResizeUtils.getChannel(predictionONC[0], 0);
                for (int j = 0; j<batchSize; ++j) imageIO.set(j+i+idxMin, pred[j]);
            }
        } else {
            int eLeft = nFrames.getIntValue();
            int eRight = next.getSelected() ? nFrames.getIntValue() : 0;
            Boolean[] centralOnly = new Boolean[1];
            int[] channel = new int[1];
            UnaryOperator<Image[][][]> predict = (inputSub) -> {
                Image[][][] predictionONC =dlResample.predict(engine, inputSub);
                if (centralOnly[0] == null) {
                    if (predictionONC[0][0].length == 1) centralOnly[0] = true;
                    else {
                        centralOnly[0] = false;
                        if (predictionONC[0][0].length != inputSub[0][0].length) throw new RuntimeException("Invalid output channel number. Model should return either 1 or "+inputSub[0][0].length+ " channels");
                        channel[0] = nFrames.getIntValue();
                    }
                }
                return predictionONC;
            };
            Assigner assign = (i, predictionONC) -> {
                Image[] pred = ResizeUtils.getChannel(predictionONC[0], channel[0]);
                for (int j = 0; j<pred.length; ++j) imageIO.set(j+i, pred[j]);
                // special case: edges are predicted with other channels
                if (!centralOnly[0] && i == eLeft + idxMin) { // left edges taken from channels
                    logger.debug("left edges from channels");
                    for (int j = 0; j<eLeft; ++j) imageIO.set(j+i, predictionONC[0][0][j]);
                }
                if (eRight>0 && !centralOnly[0] && i + predictionONC[0].length == idxMaxExcl - eRight) { // right edges taken from channels
                    logger.debug("right edges from channels");
                    for (int j = 0; j<eRight; ++j) imageIO.set(j+i, predictionONC[0][predictionONC[0].length-1][j + 1 + nFrames.getIntValue()]);
                }
            };
            Assigner predictAndAssign = (i, inputSub) -> {
                Image[][][] output = predict.apply(inputSub);
                assign.accept(i, output);
            };
            for (int i = eLeft + idxMin; i < idxMaxExcl - eRight; i += increment ) { // predict all but edges
                int batchSize = Math.min(idxMaxExcl - eRight - i, increment);
                //logger.debug("batch: [{}; {}) / [{}; {})", i, i+batchSize, idxMin, idxMaxExcl);
                Image[][][] inputSub = new Image[][][]{getInputs(imageIO, next.getSelected(), nFrames.getIntValue(), i, i+batchSize, idxMin, idxMaxExcl, oobPolicy.getSelectedEnum())};
                predictAndAssign.accept(i, inputSub);
            }
            if (centralOnly[0] == null && idxMin == idxMaxExcl - 1) { // case: no prediction have been made before : interval is smaller than frame range
                Image[][][] inputSub = new Image[][][]{getInputs(imageIO, next.getSelected(), nFrames.getIntValue(), idxMin, idxMaxExcl, idxMin, idxMaxExcl, oobPolicy.getSelectedEnum())};
                predictAndAssign.accept(idxMin, inputSub);
            }
            if ((centralOnly[0] == null || centralOnly[0]) && idxMin < idxMaxExcl - 1) { // central output only -> needs to compute edges
                int idxLeftRight = Math.min(idxMaxExcl, eLeft + idxMin);
                Image[][][] inputSubLeft = new Image[][][]{getInputs(imageIO, next.getSelected(), nFrames.getIntValue(), idxMin, Math.min(idxMaxExcl, eLeft + idxMin), idxMin, idxMaxExcl, oobPolicy.getSelectedEnum())};
                //logger.debug("processing left edges: [{}; {})", idxMin, Math.min(idxMaxExcl, eLeft + idxMin));
                int idxRightLeft = Math.max(idxLeftRight, idxMaxExcl - eRight);
                if (next.getSelected() && idxRightLeft < idxMaxExcl) {
                    //logger.debug("processing right edges: [{}; {})", idxRightLeft, idxMaxExcl);
                    Image[][][] inputSubRight = new Image[][][]{getInputs(imageIO, next.getSelected(), nFrames.getIntValue(), idxRightLeft, idxMaxExcl, idxMin, idxMaxExcl, oobPolicy.getSelectedEnum())};
                    if (this.batchSize.getIntValue() >= 2 * nFrames.getIntValue()) { // predict both edges together
                        //logger.debug("processing both edges together");
                        Image[][][] inputSub = new Image[1][2 * nFrames.getIntValue()][];
                        for (int i = 0; i<nFrames.getIntValue(); ++i) {
                            inputSub[0][i] = inputSubLeft[0][i];
                            inputSub[0][i+nFrames.getIntValue()] = inputSubRight[0][i];
                        }
                        Image[][][] output = predict.apply(inputSub);
                        Image[][][] outputLeft = new Image[1][nFrames.getIntValue()][];
                        Image[][][] outputRight = new Image[1][nFrames.getIntValue()][];
                        for (int i = 0; i<nFrames.getIntValue(); ++i) {
                            outputLeft[0][i] = output[0][i];
                            outputRight[0][i] = output[0][i+nFrames.getIntValue()];
                        }
                        assign.accept(idxMin, outputLeft);
                        assign.accept(idxRightLeft, outputRight);
                    } else {
                        predictAndAssign.accept(idxMin, inputSubLeft);
                        predictAndAssign.accept(idxRightLeft, inputSubRight);
                    }
                } else predictAndAssign.accept(idxMin, inputSubLeft);
            }
        }
    }
    @FunctionalInterface
    private interface NeighImageGetter {
        Image get(int curIdx, int neighIdx) throws IOException;
    }
    @FunctionalInterface
    private interface NeighImagesProvider {
        Image[] get(int idx) throws IOException;
    }
    private static Image[][] getInputs(ImageIO imageIO, boolean addNext, int nFrames, int idxMin, int idxMaxExcl, int idxLimMin, int idxLimMaxExcl, OOB_POLICY oobPolicy) throws IOException {
        NeighImageGetter[] getImage = new NeighImageGetter[1];
        getImage[0] = (cur, i) -> {
            if (i < idxLimMin) { // OOB left
                switch (oobPolicy) {
                    case ZERO: {
                        Image ref = imageIO.get(cur);
                        return Image.createEmptyImage("input frame", ref, ref);
                    }
                    case MIRROR: {
                        logger.debug("MIRROR OOB: cur {}, neigh {}, alt: {}", cur, i, cur + (cur-i));
                        i = cur + (cur-i);
                        if (i<idxLimMaxExcl) return imageIO.get(i);
                    }
                }
                return imageIO.get(idxLimMin); // border policy
            } else if (i >= idxLimMaxExcl) { // OOB right
                switch (oobPolicy) {
                    case ZERO: {
                        Image ref = imageIO.get(cur);
                        return Image.createEmptyImage("input frame", ref, ref);
                    }
                    case MIRROR: {
                        i = cur + (cur-i);
                        if (i>=idxLimMin) return imageIO.get(i);
                    }
                }
                return imageIO.get(idxLimMaxExcl-1); // border policy
            }
            return imageIO.get(i);
        };
        ToIntFunction<Integer> getNeigh = testNoTL ? n -> 0 : n -> n;

        NeighImagesProvider getImages;
        if (addNext) {
            getImages = i -> {
                Image[] res = new Image[2 * nFrames + 1];
                for (int n = -nFrames; n<=nFrames; ++n) res[n + nFrames] = getImage[0].get(i, i + getNeigh.applyAsInt(n));
                return res;
            };
        } else {
            getImages = i -> {
                Image[] res = new Image[nFrames + 1];
                for (int n = -nFrames; n<=0; ++n) res[n + nFrames] = getImage[0].get(i, i + getNeigh.applyAsInt(n));
                return res;
            };
        }
        Image[][] res = new Image[idxMaxExcl - idxMin][];
        for (int i = idxMin; i<idxMaxExcl; ++i) res[i - idxMin] = getImages.get(i);
        return res;
    }

}
