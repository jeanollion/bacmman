package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
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
    protected BooleanParameter forceCompute = new BooleanParameter("Force computation", false).setHint("If set to false and selected segmented objects are already gaussian-fitted spots, instead of performing a gaussian fit on centers, the radius and intensity of spots will be extracted");
    protected BooleanParameter fitBackground = new BooleanParameter("Fit Background", false).setHint("If true a gaussian with an additive constant will be fit, as well as the constant. If false, the constant will not be fit and set to the minimal value in the surroundings of the object");
    protected BooleanParameter fitCenter = new BooleanParameter("Fit Center", false).setHint("If false, a gaussian centered at the center of the object will be fit. If true the center of the gaussian will be fit");
    protected BooleanParameter fitEllipse = new BooleanParameter("Fit Ellipse", false).setHint("If true, an elliptic gaussian gaussian will be fit. Only available for 2D images.");

    NumberParameter typicalSigma = new BoundedNumberParameter("Typical sigma", 1, 2, 1, null).setHint("Typical sigma of spot when fitted by a gaussian. Gaussian fit will be performed on an area of span 2 * σ +1 around the center. When two (or more) spot have spans that overlap, they are fitted together");

    protected Parameter[] parameters = new Parameter[]{structure, forceCompute, fitEllipse, typicalSigma, fitCenter, fitBackground};

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
        if (!fitEllipse.getSelected()) res.add(new MeasurementKeyObject("GF_Radius", oIdx));
        else {
            res.add(new MeasurementKeyObject("GF_Major", oIdx));
            res.add(new MeasurementKeyObject("GF_Minor", oIdx));
        }
        res.add(new MeasurementKeyObject("GF_N", oIdx));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parent) {
        int oIdx = structure.getSelectedClassIdx();
        if (!forceCompute.getSelected() && !fitEllipse.getSelected() && parent.getChildRegionPopulation(oIdx).getRegions().stream().allMatch(r->r instanceof Spot)) { // simply use spot parameters
            parent.getChildren(oIdx).forEach(so -> {
                so.setAttribute("GF_Radius", ((Spot)so.getRegion()).getRadius());
                so.setAttribute("GF_N", ((Spot)so.getRegion()).getIntensity());
            });
        } else {
            GaussianFit.GaussianFitConfig config = new GaussianFit.GaussianFitConfig(typicalSigma.getValue().doubleValue(), false, fitBackground.getSelected())
                    .setMaxCenterDisplacement(Math.max(1, typicalSigma.getValue().doubleValue()))
                    .setCoFitDistance(4*typicalSigma.getValue().doubleValue()+1).setFitCenter(fitCenter.getSelected());
            Map<Region, double[]> parameters = GaussianFit.runOnRegions(parent.getRawImage(oIdx), parent.getChildRegionPopulation(oIdx).getRegions(), config, true , false);
            Map<Region, SegmentedObject> rSMap = parent.getChildren(oIdx).collect(Collectors.toMap(SegmentedObject::getRegion, o -> o));
            parameters.forEach((r, p) -> {
                SegmentedObject so = rSMap.get(r);
                GaussianFit.FitParameter fp = new GaussianFit.FitParameter(p, parent.getRawImage(oIdx).sizeZ()>1 ? 3:2, fitEllipse.getSelected(), false);
                if (fitEllipse.getSelected()) {
                    so.setAttribute("GF_Major", fp.getAxis(true));
                    so.setAttribute("GF_Minor", fp.getAxis(false));
                } else so.setAttribute("GF_Radius", fp.getRadius());
                so.setAttribute("GF_N", fp.getIntegratedIntensity());
            });
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "Estimation of spot fluorescence by gaussian fit. <br />Fit formula: I(xᵢ) = C + A * exp (- 1/(2*σ) * ∑ (xᵢ - x₀ᵢ)² ). Fit starts at center of segmented objects. <br />Measured parameters: <ul><li>GF_Sigma = σ</li><li>GF_N = 2 * pi * σ * A, estimation of number of fluorescent molecules within spot</li></ul>";
    }
}
