package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.processing.gaussian_fit.GaussianFit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GaussianFitFluoQuantification implements Measurement, Hint {

    protected ObjectClassParameter structure = new ObjectClassParameter("Objects", -1, false, false);
    protected Parameter[] parameters = new Parameter[]{structure};

    @Override
    public int getCallObjectClassIdx() {
        return structure.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        int oIdx = structure.getSelectedClassIdx();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject("GF_Sigma", oIdx));
        res.add(new MeasurementKeyObject("GF_N", oIdx));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parent) {
        int oIdx = structure.getSelectedClassIdx();
        Map<Region, double[]> parameters = GaussianFit.run(parent.getRawImage(oIdx), parent.getChildRegionPopulation(oIdx).getRegions(), 4, 300, 0.001, 0.1);
        Map<Region, SegmentedObject> rSMap = parent.getChildren(oIdx).collect(Collectors.toMap(o->o.getRegion(), o->o));
        parameters.forEach((r, p) -> {
            SegmentedObject so = rSMap.get(r);
            so.setAttribute("GF_Sigma", p[p.length-2]);
            so.setAttribute("GF_N", p[p.length-3]);
        });
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
