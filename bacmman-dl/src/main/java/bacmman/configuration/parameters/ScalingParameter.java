package bacmman.configuration.parameters;

import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.scalers.ModePercentileScaler;
import bacmman.plugins.plugins.scalers.ModeSdScaler;
import bacmman.plugins.plugins.scalers.PercentileScaler;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

public class ScalingParameter extends ConditionalParameter<ScalingParameter.MODE> implements PythonConfiguration, Hint {


    enum MODE {RANDOM_CENTILES, RANDOM_MIN_MAX, TRANSMITTED_LIGHT, FLUORESCENCE} // FLUORESCENCE, TRANSMITTED_LIGHT
    IntervalParameter minCentileRange = new IntervalParameter("Min Centile Range", 6, 0, 100, 0.1, 5.).setHint("Zero (min value) of scaled image will correspond to a random centile drawn in this interval");
    IntervalParameter maxCentileRange = new IntervalParameter("Max Centile Range", 6, 0, 100, 95., 99.9).setHint("One (max value) of scaled image will correspond to a random centile drawn in this interval");
    BooleanParameter saturate = new BooleanParameter("Saturate", true).setHint("whether values lower than min centile and greater than max centile are saturated");
    BoundedNumberParameter minRange = new BoundedNumberParameter("Min Range", 5, 0.1, 1e-5, 1).setHint("Image will be scaled to a random range [min, max] drawn in [0, 1] so that max - min >= this value");
    BoundedNumberParameter tlSdFactor = new BoundedNumberParameter("Sd Factor", 5, 3, 0, null);
    IntervalParameter fluoScaleRange = new IntervalParameter("Scale Centile Range", 6, 0, 100, 75., 99.9).setHint("Interval [pMin, pMax] is computed on the whole dataset at user-defined percentiles values, scale_range = [pMin - M, pMax - M] with M = modal value");
    IntervalParameter fluoCenterRange = new IntervalParameter("Center Centile Range", 6, -100, 100, -20., 30.).addRightBound(0, 0).setHint("let P be the percentile of the modal value M, center_range is the interval at percentile values [P+p1, P+p2] with p1, p2 values of this parameter");

    public ScalingParameter(String name) {
        super(new EnumChoiceParameter<>(name, ScalingParameter.MODE.values(), ScalingParameter.MODE.RANDOM_CENTILES));
        BooleanSupplier centileRangeIsValid = () -> minCentileRange.getValuesAsDouble()[0] < maxCentileRange.getValuesAsDouble()[1];
        minCentileRange.addValidationFunction(i -> centileRangeIsValid.getAsBoolean());
        maxCentileRange.addValidationFunction(i -> centileRangeIsValid.getAsBoolean());
        this.setActionParameters(MODE.RANDOM_CENTILES, minCentileRange, maxCentileRange, saturate);
        this.setActionParameters(MODE.RANDOM_MIN_MAX, minRange);
        this.setActionParameters(MODE.TRANSMITTED_LIGHT, tlSdFactor);
        this.setActionParameters(MODE.FLUORESCENCE, fluoCenterRange, fluoScaleRange);
    }
    @Override
    public String getHintText() {
        return "Scaling modes. Note that RANDOM_CENTILES is valid for all types of images, FLUORESCENCE is valid when modal value always corresponds to background value. <br/>" +
                "<ul><li>RANDOM_CENTILES: (cmin, cmax) are drawn in user-defined centiles intervals, and interval [cmin, cmax] is mapped to [0, 1] </li>" +
                "<li>RANDOM_MIN_MAX: Image is scaled to a random range [min, max] drawn in [0, 1] so that max - min >= <em>Min Range</em> parameter</li>" +
                "<li>TRANSMITTED_LIGHT: Image is scaled with the formula I -> (I - center) / scale, with: center = random value in [mean(I) -/+ sd(I) * sdf] and scale = random value in [sd(I) / sdf, sd(I) * sdf], sd(I) = standard deviation of I, sdf = <em>Sd Factor</em> parameter</li>" +
                "<li>FLUORESCENCE: center_range and scale_range are computed on the whole dataset, then each image I is scaled with the formula I -> (I - center) / scale with center and scale random values drawn in center_range, scale_range. See computation details in sub-parameter hints</li></ul>";
    }

    public HistogramScaler getScaler() {
        ToDoubleFunction<double[]> getMid = a -> 0.5 * (a[0]+a[1]);
        switch (this.getActionValue()) {
            case RANDOM_CENTILES:
            default: {
                return new PercentileScaler().setPercentiles(new double[]{getMid.applyAsDouble(minCentileRange.getValuesAsDouble()) / 100., getMid.applyAsDouble(maxCentileRange.getValuesAsDouble()) / 100.});
            }
            case RANDOM_MIN_MAX: {
                return new MinMaxScaler();
            }
            case TRANSMITTED_LIGHT: {
                return new ModeSdScaler();
            }
            case FLUORESCENCE: {
                return new ModePercentileScaler().setPercentile(getMid.applyAsDouble(fluoScaleRange.getValuesAsDouble()) / 100.);
            }
        }
    }

    @Override
    public Object getPythonConfiguration() {
        JSONObject json = new JSONObject();
        json.put("mode", this.action.getValue().toString());
        for (Parameter p : getCurrentParameters()) {
            if (p.equals(tlSdFactor)) {
                json.put("tl_sd_factor", tlSdFactor.toJSONEntry());
            } else if (p.equals(fluoScaleRange)) {
                json.put("fluo_scale_centile_range", fluoScaleRange.toJSONEntry());
            } else if (p.equals(fluoCenterRange)) {
                double[] range = fluoCenterRange.getValuesAsDouble();
                JSONArray list= new JSONArray();
                list.add(-range[0]);
                list.add(range[1]);
                json.put("fluo_center_centile_extent", list);
            } else if (p instanceof PythonConfiguration) {
                PythonConfiguration pp = (PythonConfiguration) p;
                json.put(pp.getPythonConfigurationKey(), pp.getPythonConfiguration());
            } else json.put(PythonConfiguration.toSnakeCase(p.getName()), p.toJSONEntry());
        }
        return json;
    }

    @Override
    public String getPythonConfigurationKey() {
        return "scaling_parameters";
    }
}
