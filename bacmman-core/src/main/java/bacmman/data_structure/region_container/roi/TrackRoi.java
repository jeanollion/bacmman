package bacmman.data_structure.region_container.roi;

import ij.gui.Overlay;
import ij.gui.Roi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class TrackRoi extends ArrayList<Roi> {
    boolean is2D;
    Map<Integer, TrackRoi> sliceDuplicates= new HashMap<>(); // if Roi from 2D ref displayed on 3D image
    public boolean contained(Overlay o) {
        for (Roi r : this) if (o.contains(r)) return true;
        return false;
    }
    public TrackRoi setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public TrackRoi duplicateForZ(int z) {
        if (!sliceDuplicates.containsKey(z)) {
            TrackRoi res = new TrackRoi();
            for (Roi r : this) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(z+1);
                res.add(dup);
            }
            sliceDuplicates.put(z, res);
        }
        return sliceDuplicates.get(z);
    }

    public boolean is2D() {
        return is2D;
    }

    public Map<Integer, TrackRoi> getSliceDuplicates() {
        return sliceDuplicates;
    }
}