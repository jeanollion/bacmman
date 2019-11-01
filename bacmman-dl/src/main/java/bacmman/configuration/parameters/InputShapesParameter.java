package bacmman.configuration.parameters;

import java.util.Arrays;

public class InputShapesParameter extends SimpleListParameter<ArrayNumberParameter> {

    public InputShapesParameter(String name, int unMutableIndex) {
        super(name, unMutableIndex, getInputShapeParameter());
        setAllowDeactivable(false);
    }
    public static ArrayNumberParameter getInputShapeParameter() {
        ArrayNumberParameter res = new ArrayNumberParameter("Input shape", 2, new BoundedNumberParameter("", 0, 0, 1, null))
            .setMaxChildCount(4)
            .setNewInstanceNameFunction((l,i) -> {
                if (l.getChildCount()<=3 && i<3) return "CYX".substring(i, i+1);
                else {
                    if (i>3) i = 3;
                    return "CZYX".substring(i, i+1);
                }
            }).setValue(2, 256, 32).setAllowMoveChildren(false)
            .addValidationFunction(l -> Arrays.stream(l.getArrayInt()).allMatch(i->i>0));
        res.addListener(l -> l.resetName(null));
        return res;
    }
    public int[][] getInputShapes() {
        return getChildren().stream().map(a -> a.getArrayInt()).toArray(int[][]::new);
    }
    public InputShapesParameter setInputShapes(int[][] inputShapes) {
        for (int i = 0; i<inputShapes.length; ++i) setInputShape(i, inputShapes[i]);
        return this;
    }
    public InputShapesParameter setInputShape(int inputIndex, int... dimensions) {
        if (getChildCount()<=inputIndex) setChildrenNumber(inputIndex+1);
        getChildAt(inputIndex).setValue(dimensions);
        return this;
    }
}
