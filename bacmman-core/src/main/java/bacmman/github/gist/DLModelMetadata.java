package bacmman.github.gist;

import bacmman.configuration.parameters.*;
import bacmman.plugins.HistogramScaler;
import org.json.simple.JSONObject;

import java.util.List;

public class DLModelMetadata extends ContainerParameterImpl<DLModelMetadata>  {
    SimpleListParameter<DLModelInputParameter> inputs = new SimpleListParameter<>("Input layers", 0, new DLModelInputParameter("input")).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1).setHint("Description of input tensor(s)");
    SimpleListParameter<DLModelOutputParameter> outputs = new SimpleListParameter<>("Output layers", 0, new DLModelOutputParameter("output")).setNewInstanceNameFunction((s, i)->"output #"+i).setChildrenNumber(1).setHint("Description of output tensor(s)");;
    ArrayNumberParameter contraction = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{8, 8}, null).setEmphasized(true).setName("Contraction Factor").setHint("Size ratio between the smallest tensor in the network and the input tensor. <br />For a network that performs 3 contractions with each contraction dividing the image by two, enter 8 on each axis").addValidationFunction(a -> inputs.getChildren().stream().mapToInt(c-> c.is3D()?3:2).max().orElse(2) == a.getChildCount());
    TextParameter exportLibrary = new TextParameter("Export Library", "", true).setHint("DL Library the model was exported with");
    SimpleListParameter<CustomParameter<Parameter>> miscParameters = new SimpleListParameter<>("Other Parameters", -1, new CustomParameter<>("Parameter", Parameter.class, ObjectClassOrChannelParameter.class::isAssignableFrom));

    public DLModelMetadata() {
        super("Metadata");

    }
    @Override
    public String getHintText() {
        return "Metadata associated to the model, and will be used for configuration";
    }
    @Override
    protected void initChildList() {
        super.initChildren(inputs, outputs, contraction, exportLibrary, miscParameters);
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("inputs", inputs.toJSONEntry());
        res.put("outputs", outputs.toJSONEntry());
        res.put("contraction", contraction.toJSONEntry());
        res.put("library", exportLibrary.toJSONEntry());
        res.put("otherParameters", miscParameters.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) throw new IllegalArgumentException("Cannot init xp with null content!");
        JSONObject jsonO = (JSONObject)jsonEntry;
        inputs.initFromJSONEntry(jsonO.get("inputs"));
        outputs.initFromJSONEntry(jsonO.get("outputs"));
        exportLibrary.initFromJSONEntry(jsonO.get("library"));
        if (jsonO.containsKey("contraction")) contraction.initFromJSONEntry(jsonO.get("contraction"));
        if (jsonO.containsKey("otherParameters")) miscParameters.initFromJSONEntry(jsonO.get("otherParameters"));
    }

    public List<DLModelInputParameter> getInputs() {
        return inputs.getChildren();
    }

    public List<DLModelOutputParameter> getOutputs() {
        return outputs.getChildren();
    }

    public int[] getContraction() {
        return contraction.getArrayInt();
    }

    public <P extends Parameter> P getOtherParameter(String key, Class<P> valueClass) {
        if (key==null || valueClass==null) throw new IllegalArgumentException("Key and Value must not be null");
        P res = (P)miscParameters.getChildren().stream()
                .filter(p -> key.equals(p.getKey()))
                .filter(p -> valueClass.getSimpleName().equals(p.getParameterClassName()))
                .map(p -> p.getCurrentParameter(true)).findFirst().orElse(null);
        if (res==null) res = (P)miscParameters.getChildren().stream()
                .filter(p -> key.toLowerCase().equals(p.getKey().toLowerCase()))
                .filter(p -> valueClass.getSimpleName().equals(p.getParameterClassName()))
                .map(p -> p.getCurrentParameter(true)).findFirst().orElse(null);
        return res;
    }

    public class DLModelInputParameter extends ContainerParameterImpl<DLModelInputParameter> {
        PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Intensity Scaling", HistogramScaler.class, true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction");
        BoundedNumberParameter chanelNumber = new BoundedNumberParameter("Channel Number", 0, 1, 1, null).setEmphasized(true).setHint("Number of channel of input tensor");
        BooleanParameter fixedSize = new BooleanParameter("Fixed Size").setEmphasized(true).setHint("Whether the input must a a pre-defined size or not");
        ArrayNumberParameter shape = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0,0}, null).setEmphasized(true).setName("Shape").setHint("Tensor shape. 0 means no constraint on axis (None)");
        ConditionalParameter<Boolean> sizeCond = new ConditionalParameter<>(fixedSize).setActionParameters(true, shape);
        BooleanParameter is3D = new BooleanParameter("3D tensor", false).setEmphasized(true).setHint("Whether the input has 3 spatial dimensions");

        public DLModelInputParameter(String name) {
            super(name);
            is3D.addListener(p-> {
                int dim = p.getSelected() ? 3 : 2;
                shape.setChildrenNumber(dim);
            });
        }
        public PluginParameter<HistogramScaler> getScaling() {return scaler;}
        public int getChannelNumber() {return chanelNumber.getValue().intValue();}
        public boolean fixedSize() {return fixedSize.getSelected();}
        public int[] getShape() {return shape.getArrayInt();}
        public boolean is3D() {return is3D.getSelected();}

        @Override
        protected void initChildList() {
            super.initChildren(scaler, is3D, sizeCond, chanelNumber);
        }

        @Override
        public Object toJSONEntry() {
            JSONObject res = new JSONObject();
            res.put("scaling", scaler.toJSONEntry());
            res.put("channelNumber", chanelNumber.toJSONEntry());
            res.put("fixedSize", fixedSize.toJSONEntry());
            res.put("shape", shape.toJSONEntry());
            res.put("is3D", is3D.toJSONEntry());
            return res;
        }

        @Override
        public void initFromJSONEntry(Object jsonEntry) {
            if (jsonEntry instanceof JSONObject) {
                JSONObject jsonO = (JSONObject) jsonEntry;
                if (jsonO.containsKey("scaling")) scaler.initFromJSONEntry(jsonO.get("scaling"));
                if (jsonO.containsKey("channelNumber")) chanelNumber.initFromJSONEntry(jsonO.get("channelNumber"));
                if (jsonO.containsKey("fixedSize")) fixedSize.initFromJSONEntry(jsonO.get("fixedSize"));
                if (jsonO.containsKey("shape")) shape.initFromJSONEntry(jsonO.get("shape"));
                if (jsonO.containsKey("is3D")) is3D.initFromJSONEntry(jsonO.get("is3D"));
            }
        }

        @Override
        public DLModelInputParameter duplicate() {
            DLModelInputParameter p = new DLModelInputParameter("output");
            p.setContentFrom(this);
            transferStateArguments(this, p);
            return p;
        }
    }

    public class DLModelOutputParameter extends ContainerParameterImpl<DLModelOutputParameter> {
        BoundedNumberParameter scalerIndex = new BoundedNumberParameter("Scaler index", 0, -1, -1, null).setEmphasized(true).setHint("Index of input scaler used to rescale back the image intensity. -1 no reverse scaling");

        public DLModelOutputParameter(String name) {
            super(name);
            scalerIndex.addValidationFunction(s -> s.getIntValue() < inputs.getChildCount());
        }

        public int getReverseScalingIndex() {return scalerIndex.getIntValue();}

        @Override
        protected void initChildList() {
            super.initChildren(scalerIndex);
        }

        @Override
        public Object toJSONEntry() {
            JSONObject res = new JSONObject();
            res.put("scalerIndex", scalerIndex.toJSONEntry());
            return res;
        }

        @Override
        public void initFromJSONEntry(Object jsonEntry) {
            if (jsonEntry instanceof JSONObject) {
                JSONObject jsonO = (JSONObject) jsonEntry;
                if (jsonO.containsKey("scalerIndex")) scalerIndex.initFromJSONEntry(jsonO.get("scalerIndex"));
            }
        }

        @Override
        public DLModelOutputParameter duplicate() {
            DLModelOutputParameter p = new DLModelOutputParameter("output");
            p.setContentFrom(this);
            transferStateArguments(this, p);
            return p;
        }
    }
}
