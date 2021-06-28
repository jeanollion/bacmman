package bacmman.github.gist;

import bacmman.configuration.parameters.*;
import org.json.simple.JSONObject;

import java.util.function.Function;
import java.util.stream.Stream;

public class DLModelMetadata extends ContainerParameterImpl<DLModelMetadata> {
    TextParameter inputLayerName = new TextParameter("layer name", "input", true, false).setHint("Input tensor name, as exported in the model graph");
    TextParameter outputLayerName = new TextParameter("layer name", "output", true, false).setHint("Output tensor name, as exported in the model graph");
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(true, true, new int[]{0,0,0}, null).setHint("Input tensor shape. 0 means no constraint on axis (None)");
    ArrayNumberParameter outputShape = InputShapesParameter.getInputShapeParameter(true, true, new int[]{0,0,0}, null).setHint("Output tensor shape. 0 means no constraint on axis (None)");;
    GroupParameter inputLayer = new GroupParameter("input layer", inputLayerName, inputShape);
    GroupParameter outputLayer = new GroupParameter("output layer", outputLayerName, outputShape);

    Function<GroupParameter, String> getName = grp -> ((TextParameter)grp.getChildAt(0)).getValue();
    Function<GroupParameter, Stream<String>> getOtherNames = grp -> ((SimpleListParameter<GroupParameter>)grp.getParent()).getActivatedChildren().stream().filter(c->!c.equals(grp)).map(getName);
    SimpleListParameter<GroupParameter> inputs = new SimpleListParameter<>("Input layers", 0,inputLayer).setNewInstanceNameFunction((s, i)->"input #"+i).setChildrenNumber(1).addValidationFunctionToChildren(t->getOtherNames.apply(t).noneMatch(v-> v.equals(getName.apply(t)))).setHint("Description of input tensor(s)");
    SimpleListParameter<GroupParameter> outputs = new SimpleListParameter<>("Output layers", 0,outputLayer).setNewInstanceNameFunction((s, i)->"output #"+i).setChildrenNumber(1).addValidationFunctionToChildren(t->getOtherNames.apply(t).noneMatch(v-> v.equals(getName.apply(t)))).setHint("Description of output tensor(s)");;
    TextParameter exportLibrary = new TextParameter("Export Library", "tensorflow 2.4.1", true).setHint("DL Library the model was exported with");

    public DLModelMetadata() {
        super("Metadata");
    }

    @Override
    protected void initChildList() {
        super.initChildren(inputs, outputs, exportLibrary);
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("inputs", inputs.toJSONEntry());
        res.put("outputs", outputs.toJSONEntry());
        res.put("library", exportLibrary.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) throw new IllegalArgumentException("Cannot init xp with null content!");
        JSONObject jsonO = (JSONObject)jsonEntry;
        inputs.initFromJSONEntry(jsonO.get("inputs"));
        outputs.initFromJSONEntry(jsonO.get("outputs"));
        exportLibrary.initFromJSONEntry(jsonO.get("library"));
    }

}
