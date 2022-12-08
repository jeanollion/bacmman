package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
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
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class MotionMetrics implements Measurement, Hint {
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class", -1, false, false);
    BoundedNumberParameter msdScales = new BoundedNumberParameter("Number Of Time Lags", 0, 1, 1, null).setHint("MSD is computed for intervals of 1 to n frames.");
    BooleanParameter massCenter = new BooleanParameter("Mass Center", false).setHint("Compute distances between mass centers. If the object class is fitted (spot / ellipse), leave false to use the fitted center");
    BooleanParameter scale = new BooleanParameter("Unit Scale", false).setHint("If false distances are in pixels");
    BoundedNumberParameter trim = new BoundedNumberParameter("Trim Track", 0, 0, 0, null).setHint("Trim tracks to this number of intervals frame. 0 = no trimming");
    TextParameter prefix = new TextParameter("Prefix", "", false, true);
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
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix.getValue()+"MJD", objectClass.getSelectedIndex()));
        for (int i = 1; i<= msdScales.getIntValue(); ++i) {
            res.add(new MeasurementKeyObject(prefix.getValue()+"MSD_"+i, objectClass.getSelectedIndex()));
            res.add(new MeasurementKeyObject(prefix.getValue()+"IntervalCount_"+i, objectClass.getSelectedIndex()));
        }
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        List<SegmentedObject> t = SegmentedObjectUtils.getTrack(object);
        List<SegmentedObject> track = trim.getIntValue()>0 && trim.getIntValue() + 1 < t.size() ? t.subList(0, trim.getIntValue()+1) : t;
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
        int[] frames = track.stream().mapToInt(SegmentedObject::getFrame).toArray();
        Measurements m = object.getMeasurements();
        for (int i = 1; i<=msdScales.getIntValue(); ++i) {
            double[] msd = getMeanAndCount(centers, frames, i, true);
            m.setValue(prefix.getValue()+"MSD_"+i, track.size()==1 ? null : msd[0]);
            m.setValue(prefix.getValue()+"IntervalCount_"+i, track.size()==1 ? null : msd[1]);
            if (i==1) {
                double[] mjd = getMeanAndCount(centers, frames, i, false);
                m.setValue(prefix.getValue()+"MJD", track.size()==1 ? null : mjd[0]);
            }
        }
    }
    public static double[] getMeanAndCount(List<Point> centers, int[] frames, int delta, boolean msd) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i<centers.size()-1; ++i) {
            int j = i+1;
            while(j<frames.length-1 && frames[j]-frames[i]<delta) ++j;
            if (frames[j]-frames[i]==delta) {
                double d = centers.get(j).distSq(centers.get(i));
                if (!msd) d = Math.sqrt(d);
                sum+=d;
                ++count;
            }
        }
        return new double[]{sum/count, count};
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{objectClass, massCenter, scale, msdScales, trim, prefix};
    }

    @Override
    public String getHintText() {
        return "<ul><li>MSD (Mean Squared Displacement) : average of squared displacement</li><li>MJD (Mean Jump Distance) : average of displacement</li></ul> Metrics are only assigned to the TrackHead. ";
    }
}
