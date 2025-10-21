package bacmman.configuration.parameters;

import org.json.simple.JSONObject;

public class Swim1DParameter extends ConditionalParameterAbstract<Boolean, Swim1DParameter> implements PythonConfiguration {

    BoundedNumberParameter distance = new BoundedNumberParameter("Distance", 0, 50, 2, null).setHint("Swim distance in pixel is a random number between min gap parameter and this value");
    BoundedNumberParameter minGap = new BoundedNumberParameter("Minimum Gap", 0, 3, 1, null).setHint("Minimum gap between two cells so that a gap can be randomly added in between (in pixels)");
    BooleanParameter closedEnd = new BooleanParameter("Closed End", true).setHint("Whether microchannels have one closed end or two open ends");
    IntegerParameter refMaskIdx = new IntegerParameter("Reference Mask Idx", -1).setLowerBound(-1).setHint("Index of mask used to determine gaps between objects. -1 = target object to predict, &gt;=0 index of additional input label");

    public Swim1DParameter(String name) {
        this(name, false);
    }

    public Swim1DParameter(String name, boolean perform) {
        super(new BooleanParameter(name, perform));
        this.setActionParameters(true, distance, minGap, closedEnd, refMaskIdx);
        initChildList();
        refMaskIdx.addValidationFunction(i -> {
            TrainingConfigurationParameter.DatasetParameter ds = ParameterUtils.getFirstParameterFromParents(TrainingConfigurationParameter.DatasetParameter.class, i, false);
            if (ds!=null) return i.getIntValue() < ds.getLabelNumber();
            else return true;
        });
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
            res.put("ref_mask_idx", refMaskIdx.getIntValue()+1); // +1 in script 0 = index of target label
            return res;
        } else return null;
    }
    @Override
    public String getPythonConfigurationKey() {return "swim1d_parameters";}
}
