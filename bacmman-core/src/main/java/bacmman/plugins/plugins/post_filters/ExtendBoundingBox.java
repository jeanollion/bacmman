package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Analytical;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBox;
import bacmman.image.ImageInteger;
import bacmman.image.MutableBoundingBox;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;

public class ExtendBoundingBox implements PostFilter, Hint {
    BoundedNumberParameter radX = new BoundedNumberParameter("Radius X", 0, 1, 0, null).setEmphasized(true).setHint("size (in pixels) to add to each side of the object along X axis");
    BoundedNumberParameter radY = new BoundedNumberParameter("Radius Y", 0, 1, 0, null).setEmphasized(true).setHint("size (in pixels) to add to each side of the object along Y axis");
    BoundedNumberParameter radZ = new BoundedNumberParameter("Radius Z", 0, 0, 0, null).setEmphasized(true).setHint("size (in pixels) to add to each side of the object along Z axis");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{radX, radY, radZ};
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        BoundingBox<? extends BoundingBox<?>> bounds = parent.getBounds().duplicate();
        bounds.resetOffset(); // object bounds are relative to parent
        int radX = this.radX.getValue().intValue();
        int radY = this.radY.getValue().intValue();
        int radZ = this.radZ.getValue().intValue();
        boolean allowOutOfBoundsZ  =  (parent.is2D() && childPopulation.getRegions().stream().anyMatch(r -> !r.is2D()));
        childPopulation.getRegions().forEach(r -> {
            BoundingBox bds = r.getBounds();
            MutableBoundingBox ext = new MutableBoundingBox(- Math.min(radX, bds.xMin()), Math.min(radX, bounds.xMax()-bds.xMax()), - Math.min(radY, bds.yMin()), Math.min(radY, bounds.yMax()-bds.yMax()), 0, 0 );
            if (!r.is2D()) {
                if (allowOutOfBoundsZ) ext.setzMin(-radZ).setzMax(radZ);
                else ext.setzMin(-Math.min(radZ, bds.zMin())).setzMax(Math.min(radZ, bounds.zMax() - bds.zMax()));
            }
            if (r instanceof Analytical) {
                // TODO only update bounds for analytical regions
            } else {
                r.ensureMaskIsImageInteger();
                ImageInteger m = (ImageInteger) r.getMask();
                m = (ImageInteger) m.extend(ext);
                r.setMask(m);
            }
        });

        return childPopulation;
    }

    @Override
    public String getHintText() {
        return "This post-filter extends the bounding box of the segmented regions, but do not modify the region. <br />It is useful when the region is the segmentation parent of another object class: it allows to extend the cropped area in which the child object class will be segmented<br />If the region is analytical (spot/ellipse) this post-filter will have no effect";
    }
}
