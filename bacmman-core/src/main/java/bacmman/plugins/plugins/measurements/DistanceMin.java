package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.image.Offset;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.utils.geom.Point;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.utils.geom.Point.asPoint;

public class DistanceMin implements Measurement, Hint {
    @Override
    public String getHintText() {
        return "For each object of the source object class, computes the minimal distance to all the objects of the target object class. <br />" +
                "Values are in scaled units, for anisotropic images make sure that images are calibrated. <br/>" +
                "Centers can be either geometrical or mass centers, and mass centers can be computed on a user defined intensity channel<br />" +
                "The index of target object class is appended at the end of the name of the measurement <br/>" +
                "<ul><li>CENTER_CENTER: distance from centers of source object class to centers of target object class</li><li>CENTER_EDGE: distance from centers of source object class to edges of target object class</li><li>EDGE_CENTER: distance from edges of source object class to centers of target object class</li><li>EDGE_EDGE: distance from edges of source object class to edges of target object class</li></ul> ";
    }

    public enum DISTANCE_MODE {CENTER_CENTER("DistCC"), CENTER_EDGE("DistCE"), EDGE_CENTER("DistEC"), EDGE_EDGE("DistEE");
        final String name;
        DISTANCE_MODE(String name) {
            this.name = name;
        }
    }
    BooleanParameter massCenterSource = new BooleanParameter("Center (source)", "Mass", "Geometrical", false).setHint("If Mass is selected center is weighted by intensity, otherwise center is geometrical center");
    ObjectClassParameter intensitySource = new ObjectClassParameter("Intensity");
    BooleanParameter massCenterTarget = new BooleanParameter("Center (target)", "Mass", "Geometrical", false).setHint("If Mass is selected center is weighted by intensity, otherwise center is geometrical center");
    ObjectClassParameter intensityTarget = new ObjectClassParameter("Intensity");
    ObjectClassParameter ocSource = new ObjectClassParameter("Segmented Objects (source)").addListener(oc -> intensitySource.setSelectedIndex(oc.getSelectedIndex()));
    ObjectClassParameter ocTarget = new ObjectClassParameter("Segmented Objects (target)").addListener(oc -> intensityTarget.setSelectedIndex(oc.getSelectedIndex()));;
    ConditionalParameter<Boolean> massCenterSourceCond = new ConditionalParameter<>(massCenterSource).setActionParameters(true, intensitySource);
    ConditionalParameter<Boolean> massCenterTargetCond = new ConditionalParameter<>(massCenterTarget).setActionParameters(true, intensityTarget);
    MultipleEnumChoiceParameter<DISTANCE_MODE> distanceMode = new MultipleEnumChoiceParameter<>("Distance Type", DISTANCE_MODE.values(), null, true);
    Parameter[] parameters = new Parameter[]{ocSource, ocTarget, distanceMode, massCenterSourceCond, massCenterTargetCond};

    @Override
    public int getCallObjectClassIdx() {
        return ocSource.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }
    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        return distanceMode.getSelectedItems().stream()
                .map(e -> new MeasurementKeyObject(getKey(e), ocSource.getSelectedClassIdx()))
                .collect(Collectors.toList());
    }

    protected String getKey(DISTANCE_MODE mode) {
        return mode.name+"_"+ocTarget.getSelectedClassIdx();
    }

    @Override
    public void performMeasurement(SegmentedObject parent) {
        List<SegmentedObject> source = parent.getChildren(ocSource.getSelectedClassIdx()).collect(Collectors.toList());
        List<SegmentedObject> target = parent.getChildren(ocTarget.getSelectedClassIdx()).collect(Collectors.toList());
        List<DISTANCE_MODE> distanceModes = distanceMode.getSelectedItems();
        List<ToDoubleBiFunction<Region, Region>> distFuns = getDistanceFunctions(distanceModes, parent, source, target);
        for (int modeIdx = 0; modeIdx<distanceModes.size(); ++modeIdx) {
            DISTANCE_MODE mode = distanceModes.get(modeIdx);
            String key = getKey(mode);
            ToDoubleBiFunction<Region, Region> distFun = distFuns.get(modeIdx);
            source.forEach( s -> {
                double dist = target.stream().mapToDouble(t -> distFun.applyAsDouble(s.getRegion(), t.getRegion())).min().orElse(Double.NaN);
                if (!Double.isNaN(dist)) dist =Math.sqrt(dist);
                s.getMeasurements().setValue(key, dist);
            });
        }
    }

    public List<ToDoubleBiFunction<Region, Region>> getDistanceFunctions(List<DISTANCE_MODE> modes, SegmentedObject parent, List<SegmentedObject> source, List<SegmentedObject> target) {
        double scaleXY = parent.getScaleXY();
        double scaleZ = parent.getScaleZ();;
        Map<Region, Point> sourceCenters;
        if (modes.contains(DISTANCE_MODE.CENTER_CENTER) || modes.contains(DISTANCE_MODE.CENTER_EDGE)) {
            Image intensity = massCenterSource.getSelected() ? parent.getRawImage(intensitySource.getSelectedClassIdx()) : null;
            sourceCenters = source.stream().collect(Collectors.toMap(SegmentedObject::getRegion, o -> intensity==null ? o.getRegion().getGeomCenter(true) : o.getRegion().getMassCenter(intensity, true)));
        } else sourceCenters = null;
        Map<Region, Point> targetCenters;
        if (modes.contains(DISTANCE_MODE.CENTER_CENTER) || modes.contains(DISTANCE_MODE.EDGE_CENTER)) {
            Image intensity = massCenterTarget.getSelected() ? parent.getRawImage(intensityTarget.getSelectedClassIdx()) : null;
            targetCenters = target.stream().collect(Collectors.toMap(SegmentedObject::getRegion, o -> intensity==null ? o.getRegion().getGeomCenter(true) : o.getRegion().getMassCenter(intensity, true)));
        } else targetCenters = null;
        Function<DISTANCE_MODE, ToDoubleBiFunction<Region, Region>> getDistanceFunction = mode -> {
            switch (mode) {
                case CENTER_CENTER:
                default:
                    return (rSource, rTarget) -> sourceCenters.get(rSource).distSq(targetCenters.get(rTarget));
                case CENTER_EDGE:
                    return (rSource, rTarget) -> {
                        Point c = sourceCenters.get(rSource);
                        return rTarget.getContour().stream().mapToDouble(v -> c.distSq(asPoint(v, scaleXY, scaleZ))).min().orElse(Double.NaN);
                    };
                case EDGE_CENTER:
                    return (rSource, rTarget) -> {
                        Point c = targetCenters.get(rTarget);
                        return rSource.getContour().stream().mapToDouble(v -> c.distSq(asPoint(v, scaleXY, scaleZ))).min().orElse(Double.NaN);
                    };
                case EDGE_EDGE:
                    return (rSource, rTarget) -> {
                        Stream<Point> sContour = rSource.getContour().stream().map(v -> asPoint(v, scaleXY, scaleZ));
                        List<Point> tContour = rTarget.getContour().stream().map(v -> asPoint(v, scaleXY, scaleZ)).collect(Collectors.toList());
                        if (tContour.isEmpty()) return Double.NaN;
                        return sContour.mapToDouble(s -> tContour.stream().mapToDouble(t -> t.distSq(s)).min().getAsDouble()).min().orElse(Double.NaN);
                    };
            }
        };
        return modes.stream().map(getDistanceFunction).collect(Collectors.toList());
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
