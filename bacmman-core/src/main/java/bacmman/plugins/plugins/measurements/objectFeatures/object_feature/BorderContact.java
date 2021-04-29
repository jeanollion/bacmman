package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;

public class BorderContact implements ObjectFeature, Hint {
    BooleanParameter xLbool = new BooleanParameter("X left", true).setHint("Counts contact with left part of the image (X==0)");
    BooleanParameter xHbool = new BooleanParameter("X right", true).setHint("Counts contact with right part of the image (X==Xmax)");
    BooleanParameter yLbool = new BooleanParameter("Y up", true).setHint("Counts contact with upper part of the image (Y==0)");
    BooleanParameter yHbool = new BooleanParameter("Y down", true).setHint("Counts contact with lower part of the image (Y==Ymax)");
    BooleanParameter zLbool = new BooleanParameter("Z bottom", false).setHint("Counts contact with first");
    BooleanParameter zHbool = new BooleanParameter("Z top", false).setHint("Counts contact with last slice");
    RegionPopulation.ContactBorder contact;

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{xLbool, xHbool, yLbool, yHbool, zLbool, zHbool};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        contact = new RegionPopulation.ContactBorder(0, parent.getMask(), new RegionPopulation.Border(xLbool.getSelected(), xHbool.getSelected(), yLbool.getSelected(), yHbool.getSelected(), zLbool.getSelected(), zHbool.getSelected()));
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        if (contact==null) return Double.NaN;
        double contact = this.contact.getContact(region);
        return contact;
    }

    @Override
    public String getDefaultName() {
        return "BorderContact";
    }

    @Override
    public String getHintText() {
        return "Computes the number of pixel of the object in contact with the edges of the image";
    }
}
