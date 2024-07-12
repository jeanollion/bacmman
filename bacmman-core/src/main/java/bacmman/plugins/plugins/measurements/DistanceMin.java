package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.image.Image;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.utils.Pair;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
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
    public enum CENTER {MASS, GEOMETRICAL, FROM_SEGMENTATION}
    EnumChoiceParameter<CENTER> sourceCenterMode = new EnumChoiceParameter<>("Center (source)", CENTER.values(), CENTER.FROM_SEGMENTATION).setHint("If Mass is selected center is weighted by intensity, otherwise center is geometrical center");
    ObjectClassParameter intensitySource = new ObjectClassParameter("Intensity");
    EnumChoiceParameter<CENTER> targetCenterMode = new EnumChoiceParameter<>("Center (target)", CENTER.values(), CENTER.FROM_SEGMENTATION).setHint("If Mass is selected center is weighted by intensity, otherwise center is geometrical center");
    ObjectClassParameter intensityTarget = new ObjectClassParameter("Intensity");
    ObjectClassParameter ocSource = new ObjectClassParameter("Segmented Objects (source)").addListener(oc -> intensitySource.setSelectedIndex(oc.getSelectedIndex()));
    ObjectClassParameter ocTarget = new ObjectClassParameter("Segmented Objects (target)").addListener(oc -> intensityTarget.setSelectedIndex(oc.getSelectedIndex()));;
    ConditionalParameter<CENTER> massCenterSourceCond = new ConditionalParameter<>(sourceCenterMode).setActionParameters(CENTER.MASS, intensitySource);
    ConditionalParameter<CENTER> massCenterTargetCond = new ConditionalParameter<>(targetCenterMode).setActionParameters(CENTER.MASS, intensityTarget);
    MultipleEnumChoiceParameter<DISTANCE_MODE> distanceMode = new MultipleEnumChoiceParameter<>("Distance Type", DISTANCE_MODE.values(), null, true);
    BooleanParameter scaled = new BooleanParameter("Scaled", true).setHint("If false, distance is expressed in pixels");
    BooleanParameter returnVector = new BooleanParameter("Return Distance Vector", false).setHint("If true, distance vector towards the closest object will be returned. <br>Note that if dZ is returned, and <em>Scaled</em> is set to false and image is anisotropic, dZ is not expressed in plane number but in XY pixels size");
    BooleanParameter returnDZ = new BooleanParameter("Include Z-axis", true);
    BooleanParameter returnIndices = new BooleanParameter("Return Indices", true).setHint("If true, returns the indices of the closest object");
    ConditionalParameter<Boolean> includeVectorCond = new ConditionalParameter<>(returnVector).setActionParameters(true, returnDZ);
    Parameter[] parameters = new Parameter[]{ocSource, ocTarget, distanceMode, massCenterSourceCond, massCenterTargetCond, scaled, includeVectorCond, returnIndices};

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
        List<MeasurementKey> keys = distanceMode.getSelectedItems().stream()
                .map(e -> new MeasurementKeyObject(getKey(e), ocSource.getSelectedClassIdx()))
                .collect(Collectors.toList());
        if (returnVector.getSelected()) {
            distanceMode.getSelectedItems().stream().map(e -> new MeasurementKeyObject(getKey(e)+"_dX", ocSource.getSelectedClassIdx())).forEach(keys::add);
            distanceMode.getSelectedItems().stream().map(e -> new MeasurementKeyObject(getKey(e)+"_dY", ocSource.getSelectedClassIdx())).forEach(keys::add);
            if (returnDZ.getSelected()) distanceMode.getSelectedItems().stream().map(e -> new MeasurementKeyObject(getKey(e)+"_dZ", ocSource.getSelectedClassIdx())).forEach(keys::add);
            if (returnIndices.getSelected()) distanceMode.getSelectedItems().stream().map(e -> new MeasurementKeyObject(getKey(e)+"_Indices", ocSource.getSelectedClassIdx())).forEach(keys::add);

        }
        return keys;
    }

    protected String getKey(DISTANCE_MODE mode) {
        return mode.name+"_oc"+ocTarget.getSelectedClassIdx();
    }

    @Override
    public void performMeasurement(SegmentedObject parent) {
        List<SegmentedObject> source = parent.getChildren(ocSource.getSelectedClassIdx()).collect(Collectors.toList());
        List<SegmentedObject> target = parent.getChildren(ocTarget.getSelectedClassIdx()).collect(Collectors.toList());
        List<DISTANCE_MODE> distanceModes = distanceMode.getSelectedItems();
        List<BiFunction<Region, Region, Vector>> distFuns = getDistanceFunctions(distanceModes, parent, source, target);
        boolean sourceIsTarget = ocSource.getSelectedClassIdx() == ocTarget.getSelectedClassIdx();
        double scaleXY = scaled.getSelected() ? parent.getScaleXY() : 1;
        double scaleZ = scaled.getSelected() ? parent.getScaleZ() : 1;
        double[] scaleA= new double[]{scaleXY, scaleXY, scaled.getSelected() ? parent.getScaleZ() : parent.getScaleZ()/parent.getScaleXY()};
        for (int modeIdx = 0; modeIdx<distanceModes.size(); ++modeIdx) {
            DISTANCE_MODE mode = distanceModes.get(modeIdx);
            String key = getKey(mode);
            BiFunction<Region, Region, Vector> distFun = distFuns.get(modeIdx);
            source.forEach( s -> {
                Predicate<SegmentedObject> excludeSame = sourceIsTarget ? t -> !t.equals(s) : t -> true;
                Pair<Vector, SegmentedObject> closest = target.stream().filter(excludeSame).map(t -> new Pair<>(distFun.apply(s.getRegion(), t.getRegion()), t)).min(Comparator.comparingDouble(p -> p.key.normSq())).orElse(null);
                if (closest!=null && closest.key!=null) {
                    s.getMeasurements().setValue(key, closest.key.norm(scaleA));
                    if (returnVector.getSelected()) {
                        s.getMeasurements().setValue(key+"_dX", closest.key.get(0) * scaleXY);
                        s.getMeasurements().setValue(key+"_dY", closest.key.get(1) * scaleXY);
                        if (returnDZ.getSelected() && closest.key.numDimensions()>2) s.getMeasurements().setValue(key+"_dZ", closest.key.get(2) * scaleZ);
                        if (returnIndices.getSelected()) s.getMeasurements().setStringValue(key+"_Indices", Selection.indicesString(closest.value));
                    }
                } else {
                    s.getMeasurements().setValue(key, null);
                    if (returnVector.getSelected()) {
                        s.getMeasurements().setValue(key+"_dX", null);
                        s.getMeasurements().setValue(key+"_dY", null);
                        if (returnDZ.getSelected()) s.getMeasurements().setValue(key+"_dZ", null);
                        if (returnIndices.getSelected()) s.getMeasurements().setStringValue(key+"_Indices", null);
                    }
                }

            });
        }
    }

    public List<BiFunction<Region, Region, Vector>> getDistanceFunctions(List<DISTANCE_MODE> modes, SegmentedObject parent, List<SegmentedObject> source, List<SegmentedObject> target) {
        double scaleXY = scaled.getSelected() ? parent.getScaleXY() : 1;
        double scaleZ = scaled.getSelected() ? parent.getScaleZ() : parent.getScaleZ()/parent.getScaleXY();

        Comparator<Vector> comp = Vector.comparator(scaleXY, scaleZ);
        Map<Region, Point> sourceCenters;
        if (modes.contains(DISTANCE_MODE.CENTER_CENTER) || modes.contains(DISTANCE_MODE.CENTER_EDGE)) {
            Image intensity = sourceCenterMode.getSelectedEnum().equals(CENTER.MASS) ? parent.getRawImage(intensitySource.getSelectedClassIdx()) : null;
            sourceCenters = source.stream().collect(Collectors.toMap(SegmentedObject::getRegion,  getCenterFun(sourceCenterMode.getSelectedEnum(), intensity) ));
        } else sourceCenters = null;
        Map<Region, Point> targetCenters;
        if (modes.contains(DISTANCE_MODE.CENTER_CENTER) || modes.contains(DISTANCE_MODE.EDGE_CENTER)) {
            Image intensity = targetCenterMode.getSelectedEnum().equals(CENTER.MASS) ? parent.getRawImage(intensityTarget.getSelectedClassIdx()) : null;
            targetCenters = target.stream().collect(Collectors.toMap(SegmentedObject::getRegion, getCenterFun(targetCenterMode.getSelectedEnum(), intensity) ));
        } else targetCenters = null;
        Function<DISTANCE_MODE, BiFunction<Region, Region, Vector>> getDistanceFunction = mode -> {
            switch (mode) {
                case CENTER_CENTER:
                default:
                    return (rSource, rTarget) -> Vector.vector(sourceCenters.get(rSource), targetCenters.get(rTarget));
                case CENTER_EDGE:
                    return (rSource, rTarget) -> {
                        Point c = sourceCenters.get(rSource);
                        return rTarget.getContour().stream().map(v -> Vector.vector(c, asPoint((RealLocalizable)v))).min(comp).orElse(null);
                    };
                case EDGE_CENTER:
                    return (rSource, rTarget) -> {
                        Point c = targetCenters.get(rTarget);
                        return rSource.getContour().stream().map(v -> Vector.vector(asPoint((RealLocalizable)v), c)).min(comp).orElse(null);
                    };
                case EDGE_EDGE:
                    return (rSource, rTarget) -> {
                        Stream<Point> sContour = rSource.getContour().stream().map(v -> asPoint((RealLocalizable)v));
                        List<Point> tContour = rTarget.getContour().stream().map(v -> asPoint((RealLocalizable)v)).collect(Collectors.toList());
                        if (tContour.isEmpty()) return null;
                        return sContour.map(s -> tContour.stream().map(t -> Vector.vector(s, t)).min(comp).get()).min(comp).orElse(null);
                    };
            }
        };
        return modes.stream().map(getDistanceFunction).collect(Collectors.toList());
    }

    private static Function<SegmentedObject, Point> getCenterFun(CENTER mode, Image intensity) {
        switch (mode) {
            case GEOMETRICAL: {
                return o -> o.getRegion().getGeomCenter(false);
            }
            case MASS: {
                return o -> o.getRegion().getMassCenter(intensity, false);
            }
            case FROM_SEGMENTATION:
            default : {
                return o ->  o.getRegion().getCenterOrGeomCenter();
            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
