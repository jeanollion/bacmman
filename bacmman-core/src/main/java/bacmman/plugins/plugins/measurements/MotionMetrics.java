package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.utils.geom.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class MotionMetrics implements Measurement, Hint {
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class", -1, false, false);
    BooleanParameter massCenter = new BooleanParameter("Mass Center", false).setHint("Compute distances between mass centers. If the object class is fitted (spot / ellipse), leave false to use the fitted center");
    BooleanParameter scale = new BooleanParameter("Unit Scale", false).setHint("If false distances are in pixels");
    @Override
    public int getCallObjectClassIdx() {
        return objectClass.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return true;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>(4);
        res.add(new MeasurementKeyObject("MSD", objectClass.getSelectedIndex()));
        res.add(new MeasurementKeyObject("MJD", objectClass.getSelectedIndex()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        List<SegmentedObject> track = SegmentedObjectUtils.getTrack(object);
        List<Point> centers;
        UnaryOperator<Point> scaler = scale.getSelected() ? p -> {
            p.multiplyDim(object.getScaleXY(), 0);
            p.multiplyDim(object.getScaleXY(), 1);
            if (p.numDimensions()>2) p.multiplyDim(object.getScaleZ(), 2);
            return p;
        } :  p -> {
            if (p.numDimensions()>2) p.multiplyDim(object.getScaleZ()/object.getScaleXY(), 2);
            return p;
        };
        if (massCenter.getSelected()) centers = track.stream().map(o -> o.getRegion().getMassCenter(o.getRawImage(o.getStructureIdx()), false)).map(scaler).collect(Collectors.toList());
        else centers = track.stream().map(o -> o.getRegion().getCenterOrGeomCenter()).map(scaler).collect(Collectors.toList());
        double[] distSq = IntStream.range(1, track.size()).mapToDouble(i -> centers.get(i).distSq(centers.get(i-1))).toArray();
        double mjd = 0;
        double msd = 0;
        for (int i = 1; i<track.size(); ++i) {
            mjd = ( mjd * (i-1) + Math.sqrt(distSq[i-1]) ) / i;
            msd = ( msd * (i-1) + distSq[i-1] ) / i;
            Measurements m = track.get(i).getMeasurements();
            m.setValue("MSD", msd);
            m.setValue("MJD", mjd);
        }
        Measurements m = object.getMeasurements();
        m.setValue("MSD", track.size()==1 ? null : msd);
        m.setValue("MJD", track.size()==1 ? null : mjd);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{objectClass, massCenter, scale};
    }

    @Override
    public String getHintText() {
        return "<ul><li>MSD (Mean Squared Displacement) : average of squared displacement</li><li>MJD (Mean Jump Distance) : average of displacement</li></ul> Metrics are computed for each object, as the average from trackHead to current object. TrackHead contains the metric of the whole track";
    }
}
