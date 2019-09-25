package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;

public class ContactWithMicrochannelOpenEnd implements ObjectFeature, Hint, DevPlugin {
    RegionPopulation.ContactBorder yDown;
    BooleanParameter noContactIfOnlyOne = new BooleanParameter("Do not compute if only one object", true).setHint("If only one object is present in microchannel, returned contact is zero");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{noContactIfOnlyOne};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().size()>1) yDown = new RegionPopulation.ContactBorder(0, parent.getMask(), RegionPopulation.Border.YDown);
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        if (yDown==null) return 0;
        return yDown.getContact(region);
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
