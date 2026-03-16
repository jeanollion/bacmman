package bacmman.ui.gui.image_interaction;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.ObjectSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public class SliceSplitter  implements ObjectSplitter {
    final int z;
    final boolean up;
    final ToIntFunction<Region> offset;
    public SliceSplitter(int z, boolean up, ToIntFunction<Region> offset) {
        this.z=z;
        this.up = up;
        this.offset=offset;
    }

    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
        List<Region> res= new ArrayList<>(2);
        int currentZ = z + offset.applyAsInt(object);
        logger.debug("slice splitter: obj: {} z: {} currentZ: {}", object.getBounds(), z, currentZ);
        if (up) {
            Region upper = currentZ+1 <= object.getBounds().zMax() ? object.intersectWithZPlanes(currentZ+1, object.getBounds().zMax(), false, false) : null;
            Region lower = object.getBounds().zMin() <= currentZ ? object.intersectWithZPlanes(object.getBounds().zMin(), currentZ, false, false) : null;
            if (lower != null) res.add(lower);
            if (upper != null) res.add(upper);
        } else {
            Region upper = currentZ <= object.getBounds().zMax() ? object.intersectWithZPlanes(currentZ, object.getBounds().zMax(), false, false) : null;
            Region lower = currentZ-1>=object.getBounds().zMin() ? object.intersectWithZPlanes(object.getBounds().zMin(), currentZ-1, false, false) : null;
            if (upper != null) res.add(upper);
            if (lower != null) res.add(lower);
        }
        return new RegionPopulation(res,  parent.getMask());
    }

    @Override
    public void setSplitVerboseMode(boolean verbose) {

    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }
}
