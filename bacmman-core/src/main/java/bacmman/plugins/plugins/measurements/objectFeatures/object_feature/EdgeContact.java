package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.image.BlankMask;
import bacmman.image.ImageMask;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.GeometricalFeature;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;

public class EdgeContact implements GeometricalFeature, Hint {
    BooleanParameter xLbool = new BooleanParameter("X left", true).setEmphasized(true).setHint("Counts contact with left part of the image (X==0)");
    BooleanParameter xHbool = new BooleanParameter("X right", true).setEmphasized(true).setHint("Counts contact with right part of the image (X==Xmax)");
    BooleanParameter yLbool = new BooleanParameter("Y up", true).setEmphasized(true).setHint("Counts contact with upper part of the image (Y==0)");
    BooleanParameter yHbool = new BooleanParameter("Y down", true).setEmphasized(true).setHint("Counts contact with lower part of the image (Y==Ymax)");
    BooleanParameter zLbool = new BooleanParameter("Z bottom", false).setEmphasized(true).setHint("Counts contact with first slice (Z==0)");
    BooleanParameter zHbool = new BooleanParameter("Z top", false).setEmphasized(true).setHint("Counts contact with last slice (Z==Zmax)");
    enum NORMALIZATION {NONE, FERET_DIAMETER, THICKNESS, DIAMETER}
    EnumChoiceParameter<NORMALIZATION> normalization = new EnumChoiceParameter<>("Normalization", NORMALIZATION.values(), NORMALIZATION.NONE).setHint("Edge contact normalization. NONE: absolute contact (in pixel), FERET_DIAMETER: absolut contact value/ feret diameter, THICKNESS: absolut contact value / thickness (estimated with local max of EDM), DIAMETER: absolut contact value / diameter (estimated with size)");
    BooleanParameter useMask = new BooleanParameter("Contact with mask", false).setEmphasized(true).setHint("If true, counts contact with parent mask edges, otherwise counts contact with image edges.");
    RegionPopulation.ContactBorder contact;

    public EdgeContact set(boolean xStart, boolean xEnd, boolean yStart, boolean yEnd, boolean zStart, boolean zEnd) {
        this.xLbool.setSelected(xStart);
        this.xHbool.setSelected(xEnd);
        this.yLbool.setSelected(yStart);
        this.yHbool.setSelected(yEnd);
        this.zLbool.setSelected(zStart);
        this.zHbool.setSelected(zEnd);
        return this;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{xLbool, xHbool, yLbool, yHbool, zLbool, zHbool, normalization, useMask};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        RegionPopulation.Border border = new RegionPopulation.Border(xLbool.getSelected(), xHbool.getSelected(), yLbool.getSelected(), yHbool.getSelected(), zLbool.getSelected(), zHbool.getSelected());
        ImageMask mask = parent.getMask();
        if (parent.isRoot() && (zLbool.getSelected() || zHbool.getSelected())) { // viewfield is 2D.
            int sZ = parent.getExperimentStructure().sizeZ(parent.getPositionName(), parent.getExperimentStructure().getChannelIdx(childStructureIdx));
            mask = new BlankMask(mask.sizeX(), mask.sizeY(), sZ, mask.xMin(), mask.yMin(), 0, mask.getScaleXY(), mask.getScaleZ());
        }
        contact = useMask.getSelected() ? new RegionPopulation.ContactBorderMask(0, mask, border) : new RegionPopulation.ContactBorder(0, mask, border);
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        if (contact==null) return Double.NaN;
        double contact = this.contact.getContact(region);
        switch (normalization.getSelectedEnum()) {
            case FERET_DIAMETER:
                double feret = GeometricalMeasurements.getFeretMax(region);
                contact/=feret;
                break;
            case THICKNESS:
                double thickness = GeometricalMeasurements.getThickness(region);
                contact/=thickness;
                break;
            case DIAMETER:
                double radius;
                if (region instanceof Spot) radius = ((Spot)region).getRadius();
                else if (region.is2D()) radius = Math.sqrt(region.size()/Math.PI);
                else radius = Math.pow(3 * region.size()/(4 * Math.PI), 1d/3);
                contact/=(2*radius);
        }
        return contact;
    }

    @Override
    public String getDefaultName() {
        return "EdgeContact";
    }

    @Override
    public String getHintText() {
        return "Computes the number of pixel of the object in contact with the edges of the image";
    }
}
