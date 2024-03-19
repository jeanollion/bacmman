package bacmman.configuration.parameters;

import org.json.simple.JSONObject;

import java.util.Arrays;

public class Swim1DParameter extends ConditionalParameterAbstract<Boolean, Swim1DParameter> implements PythonConfiguration {

    BoundedNumberParameter distance = new BoundedNumberParameter("Distance", 0, 50, 2, null).setHint("Swim distance in pixel is a random number between min gap parameter and this value");
    BoundedNumberParameter minGap = new BoundedNumberParameter("Minimum Gap", 0, 3, 1, null).setHint("Minimum gap between two cells so that a gap can be randomly added in between (in pixels)");

    BooleanParameter closedEnd = new BooleanParameter("Closed End", true).setHint("Whether microchannels have one closed end or two open ends");
    public Swim1DParameter(String name) {
        this(name, false);
    }

    public Swim1DParameter(String name, boolean perform) {
        super(new BooleanParameter(name, perform));
        this.setActionParameters(true, distance, minGap, closedEnd);
        initChildList();
    }

    @Override
    public String getHintText() {
        return "For cells growing in microchannels: simulate random swimming along the axis of the microchannel";
    }

    @Override
    public Swim1DParameter duplicate() {
        Swim1DParameter res = new Swim1DParameter(name, getActionValue());
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
    @Override
    public JSONObject getPythonConfiguration() {
        if (this.getActionValue()) {
            JSONObject res = new JSONObject();
            res.put("distance", distance.getIntValue());
            res.put("min_gap", minGap.getIntValue());
            res.put("closed_end", closedEnd.toJSONEntry());
            return res;
        } else return null;
    }
    @Override
    public String getPythonConfigurationKey() {return "swim1d_parameters";}
}
