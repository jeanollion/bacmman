package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.processing.ImageOperations;
import bacmman.processing.Resample;
import bacmman.processing.ResizeUtils;
import bacmman.processing.matching.SimpleTrackGraph;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class DeltaTracker implements Tracker, TestableProcessingPlugin, Hint {
    private final static Logger logger = LoggerFactory.getLogger(DeltaTracker.class);
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(2).setOutputNumber(1)).setHint("Model that predict next cell, as in Delta <br />Input: 1) raw image at frame F with values in range [0, 1] 2) raw image at frame F+1 with values in range [0, 1] 3) binary mask of a cell at frame F 4) binary mask of all cells at frame F+1 (input can be either 1 input with the [1, 2, 3, 4]] images concatenated in the last axis or 2 inputs [1,2] and [3,4]. <br />Output: 3 channels, 2nd one contains the predicted 1rst daughter cell and 3rd one the other predicted daughter cell(s)");
    BoundedNumberParameter predictionThld = new BoundedNumberParameter("Probability Threshold", 3, 0.5, 0.001, 1 ).setHint("For each cell C at frame F, a probability map is predicted for all cells at F+1. A cell C' at frame F+1 is considered to be linked to C if the median predicted probability value within C' is greater than this threshold");
    Parameter[] parameters = new Parameter[]{dlEngine, predictionThld};

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        // input is [0] prev + current raw image [1] prev object to predict (binary mask) + all current segmented objects (binary mask)
        // output channels are [0] background [1] mother cell [2] daughter cell
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        boolean separateInputChannels = engine.getNumInputArrays() == 2;
        InputFormatter input = new InputFormatter(structureIdx, parentTrack, predictionThld.getValue().doubleValue(), new int[]{32, 256});
        SimpleTrackGraph<DefaultWeightedEdge> graph = SimpleTrackGraph.createWeightedGraph();
        parentTrack.stream().flatMap(p -> p.getChildren(structureIdx)).forEach(graph::addVertex); // populate graph vertex
        Map<Integer, List<SegmentedObject>> segObjects = new HashMapGetCreate.HashMapGetCreateRedirected<>(i -> parentTrack.get(i).getChildren(structureIdx).collect(Collectors.toList()));
        // make predictions
        int stepSize = Math.min(2048, input.length()); // in case there are lots of objects, many images could be created -> process by chunks
        for (int idx = 0; idx < input.length(); idx += stepSize) {
            logger.debug("processing batch-group {} / {}", (int)Math.ceil(idx/(double)stepSize)+1, (int)Math.ceil((input.length()-2)/(double)stepSize));
            Image[][][] inputs = input.getInput(idx, idx + stepSize, separateInputChannels);
            Image[][] outputNC = engine.process(inputs)[0];

            for (int i = 0; i < outputNC.length; ++i) { // set track links to graph
                int rIdx = idx + i;
                List<Pair<Integer, Double>> pred = input.getPredictedNextRegions(rIdx, outputNC[i]);
                if (pred.size() > 0) {
                    int sourceParentIdx = input.populationIdx(rIdx);
                    int sourceIdx = input.regionIdx(rIdx);
                    SegmentedObject prev = segObjects.get(sourceParentIdx).get(sourceIdx);
                    List<SegmentedObject> nexts = segObjects.get(sourceParentIdx + 1);
                    for (Pair<Integer, Double> idxPred : pred) graph.addWeightedEdge(prev, nexts.get(idxPred.key), idxPred.value);
                }
                if (stores!=null) {
                    SegmentedObject parent = parentTrack.get(input.populationIdx(rIdx)+1);
                    Function<Image, Image> resample = im -> (Image)Resample.resample(im, false, parent.getBounds().sizeX(), parent.getBounds().sizeY()).resetOffset().translate(parent.getBounds());
                    stores.get(parent).addIntermediateImage("MotherPredictionRegion"+input.regionIdx(rIdx), resample.apply(outputNC[i][1]));
                    stores.get(parent).addIntermediateImage("DaughterPredictionRegion"+input.regionIdx(rIdx), resample.apply(outputNC[i][2]));
                }
            }
        }
        graph.selectMaxEdges(true, false);
        graph.setTrackLinks(true, false, editor);
    }

    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }

    @Override
    public String getHintText() {
        return "Implementation of Delta, software for cell tracking in mother machine. <br />Please cite: <a href='https://www.biorxiv.org/content/10.1101/720615v1'>https://www.biorxiv.org/content/10.1101/720615v1</a>";
    }


    private static class InputFormatter {
        private final int[] regionCount;
        private final int[] popIdxCorrespondance;
        private final int[] regionInPopIdxCorrespondance;
        final RegionPopulation[] populations;
        private final Image[] regionMasksBinarized;
        private final Image[] rawResampled;
        private final double predictionThld;
        private final boolean[] noPrevParent;
        private final HashMapGetCreate.HashMapGetCreateRedirected<Integer, Image> maskBuffer;
        public InputFormatter(int objectClassIdx, List<SegmentedObject> parentTrack, double predictionThld, int[] imageDimensions) {
            maskBuffer = new HashMapGetCreate.HashMapGetCreateRedirected<>(i -> new ImageByte("", new SimpleImageProperties(imageDimensions[0], imageDimensions[1], 1, 1, 1)));
            this.predictionThld=predictionThld;

            Image[] raw = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
            // also scale by min/max
            MinMaxScaler scaler = new MinMaxScaler();
            IntStream.range(0, raw.length).parallel().forEach(i -> raw[i] = scaler.scale(raw[i])); // scale before resample so that image is converted to float
            rawResampled = ResizeUtils.resample(raw, raw, false, new int[][]{imageDimensions});
            // resample region populations + remove touching borders + binarize
            ImageInteger<? extends ImageInteger>[] regionMasks = parentTrack.parallelStream().map(p -> p.getChildRegionPopulation(objectClassIdx).getLabelMap()).toArray(ImageInteger[]::new);
            ImageInteger<? extends ImageInteger>[] regionMasksResampled = ResizeUtils.resample(regionMasks, regionMasks, true, new int[][]{imageDimensions});
            populations = Arrays.stream(regionMasksResampled).map(im -> new RegionPopulation(im.resetOffset(), true).eraseTouchingContours(false)).toArray(RegionPopulation[]::new);
            regionCount = Arrays.stream(populations).mapToInt(p->p.getRegions().size()).toArray();
            int length = IntStream.of(regionCount).sum();
            popIdxCorrespondance = new int[length];
            regionInPopIdxCorrespondance = new int[length];
            noPrevParent = new boolean[populations.length];
            int cumIdx = 0;
            for (int i = 0; i<populations.length; ++i) {
                noPrevParent[i] = i==0 || (parentTrack.get(i-1).getFrame()<parentTrack.get(i).getFrame()-1);
                for (int j = 0; j<populations[i].getRegions().size(); ++j) {
                    popIdxCorrespondance[cumIdx] = i;
                    regionInPopIdxCorrespondance[cumIdx] = j;
                    ++cumIdx;
                }
            }
            regionMasksBinarized = Arrays.stream(regionMasksResampled).parallel().map(im -> TypeConverter.toByteMask(im, null, 1)).toArray(Image[]::new);
        }
        public int length() {
            return popIdxCorrespondance.length;
        }
        public int regionIdx(int idx) {
            return regionInPopIdxCorrespondance[idx];
        }
        public int populationIdx(int idx) {
            return popIdxCorrespondance[idx];
        }

        public Image[][][] getInput(int startIncluded, int stopExcluded, boolean separateInputChannels) {
            if (!canPredictNext(stopExcluded-1)) {
                while(!canPredictNext(stopExcluded-1)) --stopExcluded;
            }
            if (separateInputChannels) {
                return new Image[][][]{IntStream.range(startIncluded, stopExcluded).parallel().mapToObj(this::getRawInput).toArray(Image[][]::new),
                        IntStream.range(startIncluded, stopExcluded).mapToObj(i->getMaskInput(i, maskBuffer.get(i-startIncluded))).toArray(Image[][]::new)};
            } else { // for compatibility with original delta network
                Image[][] in = IntStream.range(startIncluded, stopExcluded).parallel().mapToObj(i -> {
                    Image[] raw = getRawInput(i);
                    Image[] mask = getMaskInput(i, maskBuffer.get(i-startIncluded));
                    return new Image[]{raw[0], raw[1], mask[0], mask[1]};
                }).toArray(Image[][]::new);
                return new Image[][][]{in};
            }

        }
        public Image[] getRawInput(int idx) {
            return new Image[]{rawResampled[populationIdx(idx)], rawResampled[populationIdx(idx)+1]};
        }
        public Image[] getMaskInput(int idx, Image buffer) {
            return new Image[]{getObjectMask(idx, buffer), regionMasksBinarized[populationIdx(idx)+1]};
        }
        public Image getObjectMask(int idx, Image buffer) {
            Region r = populations[populationIdx(idx)].getRegions().get(regionIdx(idx));
            if (buffer==null) buffer = new ImageByte("", populations[populationIdx(idx)].getImageProperties());
            else ImageOperations.fill(buffer, 0, null);
            r.draw(buffer, 1);
            return buffer;
        }
        public boolean canPredictNext(int idx) {
            if (idx>=popIdxCorrespondance.length) return false;
            return populationIdx(idx)<populations.length-1;
        }
        /**
         *
         * @param idx index of the previous cell
         * @param predictionC predicted channels (0= background, 1 = mother, 2=daughters)
         * @return index of next mother if any followed by other daughters if any
         */
        public List<Pair<Integer, Double>> getPredictedNextRegions(int idx, Image[] predictionC) {
            if (!canPredictNext(idx)) return Collections.EMPTY_LIST;
            int nextPopIdx = populationIdx(idx)+1;
            if (noPrevParent[nextPopIdx]) return Collections.emptyList();
            RegionPopulation next = populations[nextPopIdx];
            if (next.getRegions().isEmpty()) return Collections.EMPTY_LIST;

            Image mother = (Image)predictionC[1].resetOffset().translate(next.getImageProperties());
            Image daughters = (Image)predictionC[2].resetOffset().translate(next.getImageProperties());
            double[] predictionM = next.getRegions().stream().mapToDouble(r -> BasicMeasurements.getQuantileValue(r, mother, 0.5)[0]).toArray();
            int motherIdx = ArrayUtil.max(predictionM);
            if (predictionM[motherIdx]<predictionThld) return Collections.EMPTY_LIST;
            Pair<Integer, Double> m = new Pair<>(motherIdx, predictionM[motherIdx]);
            ArrayList<Pair<Integer, Double>> res = new ArrayList<>();
            res.add(m);
            double[] predictionD = next.getRegions().stream().mapToDouble(r -> BasicMeasurements.getQuantileValue(r, daughters,0.5)[0]).toArray();
            int daugtherIdx = ArrayUtil.max(predictionD);
            if (predictionD[daugtherIdx]>=predictionThld) res.add(new Pair<>(daugtherIdx, predictionD[daugtherIdx]));
            return res;
            /* return all cells that are not the mother and that have a probability. Case of division that yield in more than 2 cells
            Stream<Pair<Integer, Double>> daugthers = IntStream.range(0, predictionD.length)
                    .filter(i->i!=motherIdx)
                    .filter(i->predictionD[i]>=predictionThld).mapToObj(i -> new Pair<>(i, predictionD[i]));
            return Stream.concat(Stream.of(m), daugthers).collect(Collectors.toList());*/
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
