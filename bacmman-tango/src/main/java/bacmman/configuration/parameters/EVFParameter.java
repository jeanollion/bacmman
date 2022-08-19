package bacmman.configuration.parameters;

import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;

public class EVFParameter extends ContainerParameterImpl<EVFParameter> {
    ObjectClassParameter objectClassRef = new ObjectClassParameter("Reference Object class(es)", -1, true, true).setHint("Object class(es) from which the EVF will be computed. The closer to one object of these object classes, the lower the EVF. <br >If no object class is set, the reference object class will be the parent object class");
    BooleanParameter negativeInside = new BooleanParameter("Compute EVF Inside and Outside Reference Object Class", true).setHint("When reference object class is not the parent object class: lowest EVF is located at the farthest point from the reference object class edges and inside the reference object class, and highest EVF is located at the farthest point from the reference object class edges and outside the reference object class. <br > if False, all points located within the reference object class have EVF of 0");
    BoundedNumberParameter erode = new BoundedNumberParameter("Erosion Distance", 3, 0, 0, null).setHint("If >0, the parent object volume will be eroded.");

    public EVFParameter(String name) {
        super(name);
        initChildList();
    }
    public Image computeEVF(SegmentedObject container) {
        int[] refClasses = getObjectClassRef();
        if (refClasses.length==0) refClasses = new int[]{container.getStructureIdx()};
        return bacmman.processing.EVF.getEVFMap(container, refClasses, getNegativeInside(), getErode());
    }

    public int[] getObjectClassRef() {
        return objectClassRef.getSelectedIndices();
    }

    public boolean getNegativeInside() {
        return negativeInside.getSelected();
    }

    public double getErode() {
        return erode.getDoubleValue();
    }

    @Override
    protected void initChildList() {
        super.initChildren(objectClassRef, negativeInside, erode);
    }

    @Override
    public JSONArray toJSONEntry() {
        return JSONUtils.toJSONArrayMap(getChildren());
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) return;
        JSONUtils.fromJSONArrayMap(getChildren(), (JSONArray)jsonEntry);
    }
}
