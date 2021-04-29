package bacmman.configuration.parameters;

import java.util.Arrays;
import java.util.function.Predicate;

public class InputShapesParameter extends SimpleListParameter<ArrayNumberParameter> {
    public InputShapesParameter(String name, int unMutableIndex, boolean includeChannel) {
        this(name, unMutableIndex, includeChannel, false, includeChannel ? new int[]{2, 256, 32} : new int[]{256, 32}, null);
    }
    public InputShapesParameter(String name, int unMutableIndex, boolean includeChannel, boolean allowNoneShape, int[] defValues, Integer upperBound) {
        super(name, unMutableIndex, getInputShapeParameter(includeChannel, allowNoneShape, defValues, upperBound));
        setAllowDeactivable(false);
        this.addValidationFunctionToChildren(sameRankValidation()); // all shapes must have same number of axis
    }
    public static Predicate<ArrayNumberParameter> sameRankValidation() {
        return v->v.getParent().getChildren().stream().filter(c->c instanceof ArrayNumberParameter).mapToInt(c->((ArrayNumberParameter)c).getChildCount()).allMatch(c->c==v.getChildCount());
    }
    @Override
    public InputShapesParameter setHint(String hint) {
        super.setHint(hint);
        return this;
    }
    public static ArrayNumberParameter getInputShapeParameter(boolean includeChannel, boolean allowNoneShape) {
        int[] defValues = includeChannel ? new int[]{2, 256, 32} : new int[]{256, 32};
        return getInputShapeParameter(includeChannel, allowNoneShape, defValues, null);
    }
    public static ArrayNumberParameter getInputShapeParameter(boolean includeChannel, boolean allowNoneShape, int[] defValues, Integer upperBound) {
        int max = includeChannel ? 4 : 3;
        String yx = includeChannel ? "CYX" : "YX";
        String zyx = includeChannel ? "CZYX" : "ZYX";
        //
        ArrayNumberParameter res = new ArrayNumberParameter("Input shape", includeChannel?2:1, new BoundedNumberParameter("", 0, 0, allowNoneShape ? 0 : 1, upperBound))
            .setMaxChildCount(max)
            .setNewInstanceNameFunction((l,i) -> {
                if (l.getChildCount()<=max-1 && i<max-1) return yx.substring(i, i+1);
                else {
                    if (i>=max) i = max-1;
                    return zyx.substring(i, i+1);
                }
            }).setValue(defValues).setAllowMoveChildren(false)
            .addValidationFunction(l -> Arrays.stream(l.getArrayInt()).allMatch(allowNoneShape? i->i>=0 : i->i>0));
        res.addListener(l -> l.resetName(null));
        return res;
    }
    public int[][] getInputShapes() {
        return getChildren().stream().map(ArrayNumberParameter::getArrayInt).toArray(int[][]::new);
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
