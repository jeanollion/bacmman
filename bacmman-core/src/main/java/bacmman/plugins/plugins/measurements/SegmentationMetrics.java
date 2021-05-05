package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.PostFilterFeature;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.processing.matching.MaxOverlapMatcher;
import bacmman.processing.matching.SimpleTrackGraph;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import fiji.plugin.trackmate.Spot;
import org.eclipse.collections.impl.factory.Sets;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

public class SegmentationMetrics implements Measurement, Hint {
    ObjectClassParameter groundTruth = new ObjectClassParameter("Ground truth", -1, false, false).setHint("Reference object class");
    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false).setHint("Object class to compare to the ground truth");
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix to add to measurement keys");

    @Override
    public String getHintText() {
        return "Computes metrics to evaluate segmentation precision of a two object classes. The following metrics are computed for each object class relatively to the other:" +
                "<ol>" +
                "<li>OverlapMax: overlap with the maximum overlapping object (pixels)</li>" +
                "<li>OverlapSum: sum of overlap with all overlapping objects (pixels)</li>" +
                "<li>MatchIndices: indices of the maximum overlapping object</li>" +
                "<li>Size: size of the object in pixels</li>" +
                "</ol>";
    }

    @Override
    public int getCallObjectClassIdx() {
        return groundTruth.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return true;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        int gClass = groundTruth.getSelectedClassIdx();
        int sClass = objectClass.getSelectedClassIdx();
        String prefix = this.prefix.getValue();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix+"OverlapMax", sClass));
        res.add(new MeasurementKeyObject(prefix+"OverlapSum", sClass));
        res.add(new MeasurementKeyObject(prefix+"Size", sClass));
        res.add(new MeasurementKeyObject(prefix+"MatchIndices", sClass));
        res.add(new MeasurementKeyObject(prefix+"MatchIndices", gClass));
        res.add(new MeasurementKeyObject(prefix+"OverlapMax", gClass));
        res.add(new MeasurementKeyObject(prefix+"OverlapSum", gClass));
        res.add(new MeasurementKeyObject(prefix+"Size", gClass));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        performMeasurement(parentTrackHead, true);
        performMeasurement(parentTrackHead, false);
    }

    private void performMeasurement(SegmentedObject parentTrackHead, boolean invert) {
        int gtIdx = !invert ? groundTruth.getSelectedClassIdx() : objectClass.getSelectedClassIdx();
        int sIdx = !invert ? objectClass.getSelectedClassIdx() : groundTruth.getSelectedClassIdx();
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Map<Integer, List<SegmentedObject>> GbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), gtIdx));
        Map<Integer, List<SegmentedObject>> SbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), sIdx));

        // compute all overlaps between regions and put non null overlap in a map
        MaxOverlapMatcher<SegmentedObject> matcher = new MaxOverlapMatcher<>(MaxOverlapMatcher.segmentedObjectOverlap());
        SimpleWeightedGraph<SegmentedObject, DefaultWeightedEdge> matchG2S = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        parentTrack.forEach(p -> {
            List<SegmentedObject> G = GbyF.get(p.getFrame());
            List<SegmentedObject> S = SbyF.get(p.getFrame());
            if (G!=null) G.forEach(matchG2S::addVertex);
            if (S!=null) S.forEach(matchG2S::addVertex);
            matcher.match(G, S, matchG2S);
        });
        Function<SegmentedObject, Set<SegmentedObject>> getAllMatchingS = g -> matchG2S.outgoingEdgesOf(g).stream().map(matchG2S::getEdgeTarget).collect(Collectors.toSet());
        Function<SegmentedObject, Set<SegmentedObject>> getAllMatchingG = s -> matchG2S.incomingEdgesOf(s).stream().map(matchG2S::getEdgeSource).collect(Collectors.toSet());
        ToDoubleBiFunction<SegmentedObject, SegmentedObject> getOverlap = (g, s) -> {
            DefaultWeightedEdge e = matchG2S.getEdge(g, s);
            if (e==null) return 0;
            return matchG2S.getEdgeWeight(e);
        };
        // map with S that match exactly one G
        Map<SegmentedObject, SegmentedObject> matching1to1S2G = Utils.toMapWithNullValues(SbyF.values().stream().flatMap(Collection::stream), Function.identity(), s -> {
            Set<SegmentedObject> matchingG = getAllMatchingG.apply(s);
            if (matchingG.size()!=1) return null;
            SegmentedObject g = matchingG.iterator().next();
            Set<SegmentedObject> matchingS = getAllMatchingS.apply(g);
            if (matchingS.size()==1) return g;
            else return null;
        }, false, null);

        // set measurement to objects
        String prefix = this.prefix.getValue();
        parentTrack.forEach(p -> {
            List<SegmentedObject> S = SbyF.get(p.getFrame());
            if (S==null) S = Collections.emptyList();
            Set<SegmentedObject> seenS = new HashSet<>();
            for (SegmentedObject s : S) {
                if (seenS.contains(s)) continue;
                SegmentedObject g = matching1to1S2G.get(s);
                if (g!=null) { // 1 to 1 match
                    double intersection = getOverlap.applyAsDouble(g, s);
                    s.getMeasurements().setValue(prefix + "OverlapMax", intersection);
                    s.getMeasurements().setValue(prefix + "OverlapSum", intersection);
                    s.getMeasurements().setValue(prefix + "Size", s.getRegion().size());
                    s.getMeasurements().setStringValue(prefix + "MatchIndices", Selection.indicesString(g));
                    seenS.add(s);
                } else { // false positive or several objects match s or s matches several objects
                    Set<SegmentedObject>  matchingG = getAllMatchingG.apply(s);
                    if (matchingG.isEmpty()) { // false positive
                        s.getMeasurements().setValue(prefix + "OverlapMax", 0);
                        s.getMeasurements().setValue(prefix + "OverlapSum", 0);
                        s.getMeasurements().setValue(prefix + "Size", s.getRegion().size());
                        s.getMeasurements().setStringValue(prefix + "MatchIndices", null);
                        seenS.add(s);
                    } else if (matchingG.size()==1) { // several s match one g
                        g = matchingG.iterator().next();
                        Set<SegmentedObject> matchingS = getAllMatchingS.apply(g);
                        for (SegmentedObject ss : matchingS) {
                            double intersection = getOverlap.applyAsDouble(g, ss);
                            ss.getMeasurements().setValue(prefix + "OverlapMax", intersection);
                            ss.getMeasurements().setValue(prefix + "OverlapSum", intersection);
                            ss.getMeasurements().setValue(prefix + "Size", ss.getRegion().size());
                            ss.getMeasurements().setStringValue(prefix + "MatchIndices", Selection.indicesString(g));
                        }
                        seenS.addAll(matchingS);
                    } else { // several g match one s
                        double overlapSum = matchingG.stream().mapToDouble(o->getOverlap.applyAsDouble(o, s)).sum();
                        SegmentedObject maxG = matchingG.stream().sorted(Comparator.comparingDouble(o->-getOverlap.applyAsDouble(o, s))).limit(1).collect(Collectors.toList()).get(0);
                        s.getMeasurements().setValue(prefix + "OverlapMax", getOverlap.applyAsDouble(maxG, s));
                        s.getMeasurements().setValue(prefix + "OverlapSum", overlapSum);
                        s.getMeasurements().setValue(prefix + "Size", s.getRegion().size());
                        s.getMeasurements().setStringValue(prefix + "MatchIndices",  Selection.indicesString(maxG));
                        seenS.add(s);
                    }
                }
            }
        });
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{groundTruth, objectClass, prefix};
    }
}
