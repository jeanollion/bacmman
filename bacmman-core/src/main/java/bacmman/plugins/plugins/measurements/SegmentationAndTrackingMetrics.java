package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.processing.matching.TrackMateInterface;
import fiji.plugin.trackmate.Spot;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SegmentationAndTrackingMetrics implements Measurement, Hint, DevPlugin {
    ObjectClassParameter groundTruth = new ObjectClassParameter("Ground truth", -1, false, false);
    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false);
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix to add to measurement keys");
    SimpleListParameter<PluginParameter<FeatureFilter>> removeObjects = new SimpleListParameter<>("Object Filters", new PluginParameter<>("Filter", FeatureFilter.class, false));

    @Override
    public String getHintText() {
        return "Computes metrics to evaluate segmentation precision of an object class ( S = ∪(Sᵢ) ) relatively to a ground truth object class ( G = ∪(Gᵢ) )." +
                "<ol>" +
                "<li>Per pixel segmentation metric: <b>Dice Index</b> (D). This module computes the total volume of ground truth : |G|, of object class S: |S| and their pixel-wise intersection: |G∩S|. Final index can be computed as: D = Σ|G∩S| / (Σ|G| + Σ|S|) (Σ over the whole test dataset)</li>" +
                "<li>Per object segmentation metric: <b>Aggregated Jaccard Index</b> (AJI, as in Kumar et al. IEEE Transactions on Medical Imaging, 2017 ). This module computes the object-wise intersection OI = Σ|Gᵢ∩Sₖ|, union OU = Σ|Gᵢ∪Sₖ| and false positive volume Sբ. Sₖ being the object from S that maximizes tje Jaccard Index with Gᵢ (Σ = sum over all objects Gᵢ of G). Sբ is the sum of the volume of all object from S that are not included in OI and UI. Final index can be computed as: AJI = ΣOI / (ΣOU + ΣSբ) (Σ over the whole test dataset)</li>" +
                //"<li>Tracking metric for links with previous/next objects: Gₗ = total number of links with previous/next objects, Sₗ = total number of links with previous/next objects, Gₗ∩Sₗ total number of identical links, i-e if an object Gᵢ is linked to Gᵢ', the object Sₖ that intersect most with Gᵢ has to be linked to Sₖ' that intersect most with Gᵢ'</li>" +
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
        int structureIdx = getCallObjectClassIdx();
        String prefix = this.prefix.getValue();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix+"G_Vol", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"S_Vol", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"S_FP_Vol", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"G_Inter_S", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"G_ObjectInter_S", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"G_ObjectUnion_S", structureIdx));
        /*res.add(new MeasurementKeyObject(prefix+"G_Prev", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"S_Prev", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"G_Inter_S_Prev", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"G_Next", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"S_Next", structureIdx));
        res.add(new MeasurementKeyObject(prefix+"G_Inter_S_Next", structureIdx));*/
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        int gtIdx = groundTruth.getSelectedClassIdx();
        int sIdx = objectClass.getSelectedClassIdx();
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Map<Integer, List<SegmentedObject>> GbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), gtIdx));
        Map<Integer, List<SegmentedObject>> SbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), sIdx));

        if (removeObjects.getActivatedChildCount()>0) {
            PostFilterSequence filters = new PostFilterSequence("").add(removeObjects.getActivatedChildren().stream().map(pp -> pp.instanciatePlugin()).collect(Collectors.toList()));
            // apply features to remove objects from Gt & S
            parentTrack.parallelStream().forEach(parent -> {
                filterObjects(parent, gtIdx, GbyF, filters);
                filterObjects(parent, sIdx, SbyF, filters);
            });
        }
        // compute all overlaps between regions and put non null overlap in a map
        Map<Integer, Set<Region>> falsePositive = new ConcurrentHashMap<>();
        Map<Integer, Map<Region, Overlap>> overlapMap = parentTrack.parallelStream().filter(p -> !GbyF.get(p).isEmpty() && !SbyF.get(p).isEmpty()).collect(Collectors.toMap(p->p.getFrame(), p -> {
            List<SegmentedObject> G = GbyF.get(p);
            Set<Region> S = SbyF.get(p).stream().map(o->o.getRegion()).collect(Collectors.toSet());
            Map<Region, Overlap> map = G.stream().collect(Collectors.toMap(g->g.getRegion(), g -> {
                Overlap ol = S.stream().map(s -> {
                    double overlap = g.getRegion().getOverlapArea(s);
                    if (overlap==0) return null;
                    return new Overlap(g.getRegion(), s, overlap);
                }).filter(o->o!=null).max(Comparator.comparingDouble(o->o.jacardIndex())).orElse(null);
                S.remove(ol.s);
                return ol;
            }));
            falsePositive.put(p.getFrame(), S);
            return map;
        }));


    }
    static class Overlap{
        final Region s;
        final double overlap, gVol, sVol;

        Overlap(Region g, Region s, double overlap) {
            this.s = s;
            this.overlap = overlap;
            this.gVol = g.size();
            this.sVol = s.size();
        }
        double jacardIndex() {
            return overlap/(gVol + sVol - overlap);
        }
    }
    static class RegionOverlapDistance extends Spot {
        final Region region;
        final int frame;
        public RegionOverlapDistance(Region region, int frame) {
            super(region.getBounds().getCenter(), 1, 1);
            this.region = region;
            this.frame = frame;
        }
        @Override
        public double squareDistanceTo(Spot other) {
            RegionOverlapDistance otherR = (RegionOverlapDistance)other;
            if (otherR.frame!=otherR.frame) return Double.POSITIVE_INFINITY;
            double overlap = otherR.region.getOverlapArea(region);
            return 1/overlap;
        }
    }
    private static void filterObjects(SegmentedObject parent, int objectClassIdx, Map<Integer, List<SegmentedObject>> objects, PostFilterSequence filters) {
        RegionPopulation pop = parent.getChildRegionPopulation(objectClassIdx);
        if (pop.getRegions().isEmpty()) return;
        pop = filters.filter(pop, objectClassIdx, parent);
        if (pop.getRegions().isEmpty()) objects.get(parent.getFrame()).clear();
        else {
            Set<Region> remainingObjects = new HashSet<>(pop.getRegions());
            objects.get(parent.getFrame()).removeIf(o -> !remainingObjects.contains(o.getRegion()));
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{groundTruth, objectClass, prefix};
    }
}
