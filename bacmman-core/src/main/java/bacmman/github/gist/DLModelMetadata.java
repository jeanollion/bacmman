package bacmman.github.gist;

import bacmman.configuration.parameters.*;
import bacmman.core.DockerGateway;
import bacmman.plugins.DockerDLTrainer;
import bacmman.plugins.HistogramScaler;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DLModelMetadata extends ContainerParameterImpl<DLModelMetadata>  {
    SimpleListParameter<DLModelInputParameter> inputs = new SimpleListParameter<>("Input layers", new DLModelInputParameter("input")).setMinChildCount(1)
            .setNewInstanceNameFunction((s, i)->"input #"+i)
            .addchildrenPropertyValidation(DLModelInputParameter::is3D, true)
            .setChildrenNumber(1).setHint("Description of input tensor(s)");
    SimpleListParameter<DLModelOutputParameter> outputs = new SimpleListParameter<>("Output layers", new DLModelOutputParameter("output")).setMinChildCount(1).setNewInstanceNameFunction((s, i)->"output #"+i)
            .setChildrenNumber(1).setHint("Description of output tensor(s)")
            .addNewInstanceConfiguration(o -> o.scalerIndex.addValidationFunction(s -> s.getIntValue() < inputs.getChildCount()));
    ArrayNumberParameter contraction = InputShapesParameter.getInputShapeParameter(false, true,  new int[]{8, 8}, null).setEmphasized(true).setName("Contraction Factor").setHint("Size ratio between the smallest tensor in the network and the input tensor. <br />For a network that performs 3 contractions with each contraction dividing the image by two, enter 8 on each axis").addValidationFunction(a -> inputs.getChildren().stream().mapToInt(c-> c.is3D()?3:2).max().orElse(2) == a.getChildCount());
    TextParameter exportLibrary = new TextParameter("Export Library", "", true).setHint("DL Library the model was exported with");
    SimpleListParameter<CustomParameter<Parameter>> miscParameters = new SimpleListParameter<>("Other Parameters", new CustomParameter<>("Parameter", Parameter.class, ObjectClassOrChannelParameter.class::isAssignableFrom));
    PluginParameter<DockerDLTrainer> dockerTrainer = new PluginParameter<>("Docker Training Configuration", DockerDLTrainer.class, true);
    public DLModelMetadata() {
        super("Metadata");
    }
    @Override
    public String getHintText() {
        return "Metadata associated to the model, and will be used for configuration";
    }
    @Override
    protected void initChildList() {
        super.initChildren(inputs, outputs, contraction, exportLibrary, miscParameters, dockerTrainer);
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("inputs", inputs.toJSONEntry());
        res.put("outputs", outputs.toJSONEntry());
        res.put("contraction", contraction.toJSONEntry());
        res.put("library", exportLibrary.toJSONEntry());
        res.put("otherParameters", miscParameters.toJSONEntry());
        res.put("dockerDLTrainer", dockerTrainer.toJSONEntry());
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
        if (jsonO.containsKey("dockerDLTrainer")) dockerTrainer.initFromJSONEntry(jsonO.get("dockerDLTrainer"));
    }
    public DLModelMetadata setInputs(DLModelInputParameter... inputs) {
        this.inputs.removeAllElements();
        return addInputs(inputs);
    }
    public DLModelMetadata addInputs(DLModelInputParameter... inputs) {
        this.inputs.insert(inputs);
        return this;
    }
    public DLModelMetadata setOutputs(DLModelOutputParameter... outputs) {
        this.outputs.removeAllElements();
        return addOutputs(outputs);
    }
    public DLModelMetadata addOutputs(DLModelOutputParameter... outputs) {
        this.outputs.insert(outputs);
        return this;
    }
    public DLModelMetadata setContraction(int... contraction) {
        if (contraction.length == 1) {
            int c = contraction[0];
            contraction = new int[inputs.isEmpty() ? 2 : (inputs.getChildAt(0).is3D() ? 3 : 2)];
            for (int i = 0; i<contraction.length; ++i) contraction[i] = c;
        }
        this.contraction.setValue(contraction);
        return this;
    }
    public DLModelMetadata setExportLibrary(String lib) {
        this.exportLibrary.setValue(lib);
        return this;
    }
    public String getExportLibrary() {
        return this.exportLibrary.getValue();
    }

    public DLModelMetadata addMiscParameters(CustomParameter<Parameter>... parameters) {
        this.miscParameters.insert(parameters);
        return this;
    }
    public DLModelMetadata addMiscParameters(Parameter... parameters) {
        for (Parameter p : parameters) {
            CustomParameter cp = new CustomParameter<>(p);
            this.miscParameters.insert(cp);
        }
        return this;
    }

    public DLModelMetadata setDockerDLTrainer(DockerDLTrainer trainer) {
        this.dockerTrainer.setPlugin(trainer);
        DockerGateway.DockerImage im = trainer.getConfiguration().getTrainingParameters().getDockerImage(true);
        if (im!=null) this.exportLibrary.setValue(im.getVersion());
        return this;
    }

    public DockerDLTrainer getDockerDLTrainer() {
        return this.dockerTrainer.instantiatePlugin();
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

    public <P extends Parameter> P getOtherParameter(Class<P> valueClass, String... keyCandidates) {
        for (String k : keyCandidates) {
            P p = getOtherParameter(k, valueClass);
            if (p != null) return p;
        }
        if (keyCandidates == null || keyCandidates.length == 0) {
            List<P> res = miscParameters.getChildren().stream()
                    .filter(p -> valueClass.getSimpleName().equals(p.getParameterClassName()))
                    .map(p -> p.getCurrentParameter(true)).map(p -> (P)p).collect(Collectors.toList());
            if (res.size() == 1) return res.get(0);
        }
        return null;
    }

    public static class DLModelInputParameter extends ContainerParameterImpl<DLModelInputParameter> {
        PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Intensity Scaling", HistogramScaler.class, true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction");
        BoundedNumberParameter chanelNumber = new BoundedNumberParameter("Channel Number", 0, 1, 1, null).setEmphasized(true).setHint("Number of channel of input tensor");
        BoundedNumberParameter frameNumber = new BoundedNumberParameter("Frame Number", 0, 1, 1, null).setEmphasized(true).setHint("Number of frame (time points) of input tensor");
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
        public DLModelInputParameter setScaling(HistogramScaler scaler) {
            this.scaler.setPlugin(scaler);
            return this;
        }
        public DLModelInputParameter setChannelNumber(int channelNumber) {
            this.chanelNumber.setValue(channelNumber);
            return this;
        }

        public DLModelInputParameter setIs3D(boolean is3D) {
            this.is3D.setSelected(is3D);
            return this;
        }

        public DLModelInputParameter setShape(int... shape) {
            boolean fixedSize = shape!=null && shape.length > 0;
            if (fixedSize) {
                int[] distinct = IntStream.of(shape).distinct().toArray();
                fixedSize = !(distinct.length == 1 && distinct[0]<=0);
            }
            if (!fixedSize) {
                this.fixedSize.setSelected(false);
                if (is3D.getSelected()) this.shape.setValue(0, 0, 0);
                else this.shape.setValue(0,0);
            } else {
                if (shape.length == 1) {
                    int s = shape[0];
                    shape = new int[is3D.getSelected()?3:2];
                    Arrays.fill(shape, s);
                }
                this.fixedSize.setSelected(true);
                this.shape.setValue(shape);
                this.is3D.setSelected(shape.length == 3);
            }
            return this;
        }

        public PluginParameter<HistogramScaler> getScaling() {return scaler;}
        public int getChannelNumber() {return chanelNumber.getValue().intValue();}
        public int getFrameNumber() {return frameNumber.getValue().intValue();}
        public boolean fixedSize() {return fixedSize.getSelected();}
        public int[] getShape() {return shape.getArrayInt();}
        public boolean is3D() {return is3D.getSelected();}

        @Override
        protected void initChildList() {
            super.initChildren(scaler, is3D, sizeCond, chanelNumber, frameNumber);
        }

        @Override
        public Object toJSONEntry() {
            JSONObject res = new JSONObject();
            res.put("scaling", scaler.toJSONEntry());
            res.put("channelNumber", chanelNumber.toJSONEntry());
            res.put("frameNumber", frameNumber.toJSONEntry());
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
                if (jsonO.containsKey("frameNumber")) frameNumber.initFromJSONEntry(jsonO.get("frameNumber"));
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

    public static class DLModelOutputParameter extends ContainerParameterImpl<DLModelOutputParameter> {
        BoundedNumberParameter scalerIndex = new BoundedNumberParameter("Scaler index", 0, -1, -1, null).setEmphasized(true).setHint("Index of input scaler used to rescale back the image intensity. -1 no reverse scaling");

        public DLModelOutputParameter(String name) {
            super(name);
        }

        public DLModelOutputParameter setReverseScalingIndex(int i) {
            this.scalerIndex.setValue(i);
            return this;
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
