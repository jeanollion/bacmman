package bacmman.configuration.parameters;

import bacmman.plugins.Hint;
import org.json.simple.JSONObject;

import java.util.Arrays;

public class IlluminationParameter extends ConditionalParameterAbstract<Boolean, IlluminationParameter> implements PythonConfiguration, Hint {
    IntervalParameter gaussianBlur = new IntervalParameter("Gaussian Blur Range", 1, 0, null, 1,2).setHint("A random sigma value is drawn in this interval, and gaussian blur is applied before adding noise");
    BoundedNumberParameter noiseIntensity = new BoundedNumberParameter("Noise Intensity", 5, 0.2, 0, null).setHint("Intensity of random noise (relative to scaled image values).");
    BooleanParameter gaussianNoise = new BooleanParameter("Gaussian Noise", true).setHint("Additive gaussian noise");
    BooleanParameter poissonNoise = new BooleanParameter("Poisson Noise", true).setHint("Gaussian-poisson noise");
    BooleanParameter speckleNoise = new BooleanParameter("Speckle Noise", false).setHint("Multiplicative gaussian noise");

    BoundedNumberParameter histoElasticDeformNPoints = new BoundedNumberParameter("Histogram Elasticdeform Point Number", 0, 5, 0, null).setHint("Number of points for elastic deformation of histogram");
    BoundedNumberParameter histoElasticDeformIntensity = new BoundedNumberParameter("Histogram Elasticdeform Intensity", 5, 0.5, 0, 1).setHint("Intensity of histogram elastic deformation");

    ArrayNumberParameter illuVariationNPoints = InputShapesParameter.getInputShapeParameter(false, true, new int[]{8, 8}, null)
            .setMaxChildCount(2)
            .setName("Illumination Variation Point Number").setHint("Number of points in each axis (Y, X) for an illumination transformation that simulates variation of illumination along each axis (Y, X). <br/>Adapted from delta software: https://gitlab.com/dunloplab/delta/blob/master/data.py");
    BoundedNumberParameter illuVariationIntensity = new BoundedNumberParameter("Illumination Variation Intensity", 5, 0.5, 0, 1).setHint("Intensity of histogram elastic deformation");
    BooleanParameter illumVariation2D = new BooleanParameter("Illumination Variation 2D", false).setHint("Whether illumination variation is performed on a 2D grid or 2 x 1D grids.");

    public IlluminationParameter(String name) {
        this(name, false);
    }

    public IlluminationParameter(String name, boolean perform) {
        super(new BooleanParameter(name, perform));
        this.setActionParameters(true, gaussianBlur, noiseIntensity, gaussianNoise, poissonNoise, speckleNoise, histoElasticDeformIntensity, histoElasticDeformNPoints, illuVariationIntensity, illuVariationNPoints, illumVariation2D);
        initChildList();
    }

    @Override
    public String getHintText() {
        return "A set of illumination transformations (applied on image intensity)";
    }
    @Override
    public IlluminationParameter duplicate() {
        IlluminationParameter res = new IlluminationParameter(name, getActionValue());
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }

    @Override
    public JSONObject getPythonConfiguration() {
        if (this.getActionValue()) {
            JSONObject res = (JSONObject) super.getPythonConfiguration();
            res.put("histogram_elasticdeform_n_points", res.remove(PythonConfiguration.toSnakeCase(histoElasticDeformNPoints.getName())));
            res.put("illumination_variation_n_points", res.remove(PythonConfiguration.toSnakeCase(illuVariationNPoints.getName())));
            return res;
        } else return null;
    }
    @Override
    public String getPythonConfigurationKey() {return "illumination_parameters";}
}
