package bacmman.configuration.parameters;

import bacmman.plugins.Hint;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static bacmman.configuration.parameters.PythonConfiguration.toSnakeCase;

public class HardSampleMiningParameter extends ConditionalParameterAbstract<Boolean, HardSampleMiningParameter> implements PythonConfiguration, Hint {

    IntervalParameter quantiles = new IntervalParameter("Metrics Percentiles", 5, 0, 100, 1, 99)
            .addRightBound(0, 49).addLeftBound(1, 51).setHint("Metrics min and max quantiles. <br/>Must verify: percentile_min / 100 < 1 / enrich factor").addValidationFunction(q -> {
                BoundedNumberParameter ef = ParameterUtils.getParameterFromSiblings(BoundedNumberParameter.class, q, null);
                return isValid(ef.getDoubleValue(), q.getValuesAsDouble()[1]/100.);
            });
    BoundedNumberParameter enrichFactor = new BoundedNumberParameter("Enrich Factor", 5, 20, 1, null)
            .addValidationFunction(ef -> {
                IntervalParameter q = ParameterUtils.getParameterFromSiblings(IntervalParameter.class, ef, null);
                return isValid(ef.getDoubleValue(), q.getValuesAsDouble()[0]/100.);
            })
            .setHint("Sample with Metrics value greater than maximum quantile will have a probability to be drawn multiplied by this factor. Samples with metric located in the interval [quantile min, quantile max] will have an enriched probability that depend on the metric value. <br/>Must verify: percentile_min / 100 < 1 / enrich factor");
    IntegerParameter period = new IntegerParameter("Period", 100).setLowerBound(1).setHint("Sample probability will be updated every N epochs. This operation can be time consuming especially on large datasets as it needs a prediction on each sample of the dataset");
    IntegerParameter startEpoch = new IntegerParameter("Start From Epoch", 100).setLowerBound(0).setHint("Hard Sample Mining is performed only after this epoch");

    List<Parameter> additionalParameters;
    private static boolean isValid(double enrichFactor, double quantile) {
        return quantile < 1./enrichFactor;
    }

    public HardSampleMiningParameter(String name, Parameter... additionalParameters) {
        this(name, false, additionalParameters);
    }

    public HardSampleMiningParameter(String name, boolean perform, Parameter... additionalParameters) {
        super(new BooleanParameter(name, perform));
        this.additionalParameters = Arrays.asList(additionalParameters);
        List<Parameter> params = new ArrayList<>(3 + this.additionalParameters.size());
        params.add(period);
        params.add(quantiles);
        params.add(enrichFactor);
        params.add(startEpoch);
        params.addAll(this.additionalParameters);
        this.setActionParameters(true, params);
        initChildList();
    }

    @Override
    public String getHintText() {
        return "Enrich hard samples / Drop easy samples";
    }
    @Override
    public HardSampleMiningParameter duplicate() {
        HardSampleMiningParameter res = new HardSampleMiningParameter(name, getActionValue());
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }

    public double getMinQuantile() {
        return quantiles.getValuesAsDouble()[0]/100;
    }

    @Override
    public JSONObject getPythonConfiguration() {
        if (this.getActionValue()) {
            JSONObject json = new JSONObject();
            double[] quantiles = this.quantiles.getValuesAsDouble();
            json.put("quantile_min", quantiles[0]/100.);
            json.put("quantile_max", quantiles[1]/100.);
            json.put("enrich_factor", enrichFactor.getDoubleValue());
            json.put("period", period.getIntValue());
            json.put("start_from_epoch", startEpoch.getIntValue());
            for (Parameter p : additionalParameters) json.put(toSnakeCase(p.getName()), p.toJSONEntry());
            return json;
        } else return null;
    }
    @Override
    public String getPythonConfigurationKey() {return "hard_sample_mining";}
}
