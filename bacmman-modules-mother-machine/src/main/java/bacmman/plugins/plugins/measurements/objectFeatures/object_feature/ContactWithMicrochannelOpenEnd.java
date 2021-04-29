package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;

public class ContactWithMicrochannelOpenEnd implements ObjectFeature, Hint {
    RegionPopulation.ContactBorder yDown;
    BooleanParameter noContactIfOnlyOne = new BooleanParameter("Do not compute if only one object", true).setHint("If only one object is present in microchannel, returned contact is zero");
    BooleanParameter normalize = new BooleanParameter("Divide by bacteria width?", true).setHint("If true, contact value will be divided by the median bacteria width");
    BoundedNumberParameter tolerance = new BoundedNumberParameter("Tolerance", 0, 0, 0, null).setHint("if >0, a contact is considered if the distance from the contour of the object to the open-end of the microchannel is inferior to this parameter");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{normalize, noContactIfOnlyOne, tolerance};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (!noContactIfOnlyOne.getSelected() || childPopulation.getRegions().size()>1) {
            yDown = new RegionPopulation.ContactBorder(0, parent.getMask(), RegionPopulation.Border.YDown)
                    .setTolerance(tolerance.getValue().intValue());
        }
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        if (yDown==null) return 0;
        double contact = yDown.getContact(region);
        if (normalize.getSelected()) {
            return contact / GeometricalMeasurements.medianThicknessX(region);
        } else return contact;
    }

    @Override
    public String getDefaultName() {
        return "OpenEndOfChannelContact";
    }

    @Override
    public String getHintText() {
        return "Computes the number of pixel of the object in contact with the lower end of the microchannel (parent object)";
    }
}
