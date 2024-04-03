package bacmman.configuration.parameters;

import bacmman.plugins.Hint;
import org.json.simple.JSONObject;

public class ActiveLearningParameter extends ConditionalParameterAbstract<Boolean, ActiveLearningParameter> implements PythonConfiguration, Hint {

    IntervalParameter quantiles = new IntervalParameter("Loss Percentiles", 5, 0, 100, 0.1, 99.9)
            .addRightBound(0, 49).addLeftBound(1, 51).setHint("Loss min and max quantiles. <br/>Must verify: 1 - percentile_max / 100 > 1 / enrich factor").addValidationFunction(q -> {
                BoundedNumberParameter ef = ParameterUtils.getParameterFromSiblings(BoundedNumberParameter.class, q, null);
                return isValid(ef.getDoubleValue(), q.getValuesAsDouble()[1]/100.);
            });
    BoundedNumberParameter enrichFactor = new BoundedNumberParameter("Enrich Factor", 5, 100, 1, null)
            .addValidationFunction(ef -> {
                IntervalParameter q = ParameterUtils.getParameterFromSiblings(IntervalParameter.class, ef, null);
                return isValid(ef.getDoubleValue(), q.getValuesAsDouble()[1]/100.);
            })
            .setHint("Sample with loss value greater than maximum quantile will have a probability to be drawn multiplied by this factor. Samples with loss located in the interval [quantile min, quantile max] will have an enriched probability that depend on the loss value. <br/>Must verify: 1 - percentile_max / 100 > 1 / enrich factor");
    IntegerParameter period = new IntegerParameter("Period", 100).setLowerBound(1).setHint("Sample probability will be updated every N epochs. This operation can be time consuming especially on large datasets as it needs a prediction on each sample of the dataset");

    private static boolean isValid(double enrichFactor, double quantile) {
        return 1 - quantile < 1./enrichFactor;
    }

    public ActiveLearningParameter(String name) {
        this(name, false);
    }

    public ActiveLearningParameter(String name, boolean perform) {
        super(new BooleanParameter(name, perform));
        this.setActionParameters(true, period, quantiles, enrichFactor);
        initChildList();
    }

    @Override
    public String getHintText() {
        return "Enrich hard samples / Drop easy samples";
    }
    @Override
    public ActiveLearningParameter duplicate() {
        ActiveLearningParameter res = new ActiveLearningParameter(name, getActionValue());
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
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
            return json;
        } else return null;
    }
    @Override
    public String getPythonConfigurationKey() {return "active_learning";}
}
