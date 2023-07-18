package bacmman.configuration.parameters;

import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;

import java.util.Arrays;
import java.util.List;

public class BoundingBoxParameter extends ContainerParameterImpl<BoundingBoxParameter> {
    BoundedNumberParameter xMin = new BoundedNumberParameter("X Min", 0, 0, 0, null).setEmphasized(true);
    BoundedNumberParameter yMin = new BoundedNumberParameter("Y Min", 0, 0, 0, null).setEmphasized(true);
    BoundedNumberParameter zMin = new BoundedNumberParameter("Z Min", 0, 0, 0, null).setEmphasized(true);
    BoundedNumberParameter xLength = new BoundedNumberParameter("X Length", 0, 0, 0, null).setEmphasized(true).setHint("Length (pixel) along X axis. 0 means maximal possible length");
    BoundedNumberParameter yLength = new BoundedNumberParameter("Y Length", 0, 0, 0, null).setEmphasized(true).setHint("Length (pixel) along Y axis. 0 means maximal possible length");
    BoundedNumberParameter zLength = new BoundedNumberParameter("Z Length", 0, 0, 0, null).setEmphasized(true).setHint("Length (pixel) along Z axis. 0 means maximal possible length");
    BoundedNumberParameter fMin = new BoundedNumberParameter("F Min", 0, 0, 0, null).setEmphasized(true).setHint("Min Frame");
    BoundedNumberParameter fLength = new BoundedNumberParameter("F Length", 0, 0, 0, null).setEmphasized(true).setHint("Length of Frame interval");
    BooleanParameter frameConstraint = new BooleanParameter("Frame Constraint", false);

    ConditionalParameter<Boolean> frameConstraintCond = new ConditionalParameter<>(frameConstraint).setActionParameters(true, fMin, fLength);
    protected List<Parameter> parameters;

    public BoundingBoxParameter(String name) {
        this(name, false);
    }
    public BoundingBoxParameter(String name, boolean time) {
        super(name);
        if (time) setParameters(xMin, xLength, yMin, yLength, zMin, zLength, frameConstraintCond);
        else setParameters(xMin, xLength, yMin, yLength, zMin, zLength);
    }
    public SimpleBoundingBox getBoundingBox(BoundingBox parentBounds) {
        int xMin = this.xMin.getIntValue();
        int yMin = this.yMin.getIntValue();
        int zMin = this.zMin.getIntValue();
        int xLength = this.xLength.getIntValue();
        int yLength = this.yLength.getIntValue();
        int zLength = this.zLength.getIntValue();
        if (xLength==0) xLength = parentBounds.sizeX() - xMin;
        if (yLength==0) yLength = parentBounds.sizeY() - yMin;
        if (zLength==0) zLength = parentBounds.sizeZ() - zMin;
        return new SimpleBoundingBox(xMin, xMin+xLength-1, yMin, yMin+yLength-1, zMin, zMin+zLength-1);
    }

    public int [] getTimeWindow(int maxFrame) {
        if (parameters.contains(frameConstraintCond) && frameConstraint.getSelected()) return new int[] {fMin.getIntValue(), fLength.getIntValue()==0 ? maxFrame : fMin.getIntValue() + fLength.getIntValue() -1 };
        else return null;
    }

    public boolean withinFrameWindow(int frame) {
        if (!parameters.contains(frameConstraintCond) || !frameConstraint.getSelected()) return true;
        if (frame< fMin.getIntValue()) return false;
        return fLength.getIntValue() <= 0 || frame < fMin.getIntValue() + fLength.getIntValue();
    }
    private void setParameters(Parameter... parameters) {
        this.parameters = Arrays.asList(parameters);
        initChildList();
    }

    @Override
    protected void initChildList() {
        super.initChildren(parameters);
    }

    @Override
    public BoundingBoxParameter duplicate() {
        BoundingBoxParameter res =  new BoundingBoxParameter(name, parameters.contains(frameConstraintCond));
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
    @Override
    public String toString() {
        String res = name + ": " + "X=["+xMin.getIntValue()+"; "+ (xLength.getIntValue()==0 ? "?": xMin.getIntValue()+xLength.getIntValue()-1)+"]" + " Y=["+yMin.getIntValue()+"; "+ (yLength.getIntValue()==0 ? "?": yMin.getIntValue()+yLength.getIntValue()-1)+"]" + ( (zMin.getIntValue()==0 && zLength.getIntValue()==0)?"": " Z=["+zMin.getIntValue()+"; "+ (zLength.getIntValue()==0 ? "?": zMin.getIntValue()+zLength.getIntValue()-1)+"]" );
        if (parameters.contains(frameConstraintCond) && frameConstraint.getSelected()) {
            res += " F=["+ fMin.getIntValue()+"; "+(fLength.getIntValue()==0 ? "?" : fMin.getIntValue() + fLength.getIntValue() -1 )+"]";
        }
        return res;
    }

    @Override
    public JSONArray toJSONEntry() {
        return JSONUtils.toJSONArrayMap(parameters);
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) return;
        if (JSONUtils.isJSONArrayMap(jsonEntry)) JSONUtils.fromJSONArrayMap(parameters, (JSONArray)jsonEntry);
        else JSONUtils.fromJSON(parameters, (JSONArray)jsonEntry);
    }
}
