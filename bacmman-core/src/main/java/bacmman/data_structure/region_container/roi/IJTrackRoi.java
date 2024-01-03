package bacmman.data_structure.region_container.roi;

import bacmman.configuration.experiment.Structure;
import ij.gui.Overlay;
import ij.gui.Roi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class IJTrackRoi extends ArrayList<Roi> implements TrackRoi {
    boolean is2D;
    Structure.TRACK_DISPLAY trackType = Structure.TRACK_DISPLAY.DEFAULT;
    Map<Integer, IJTrackRoi> sliceDuplicates= new HashMap<>(); // if Roi from 2D ref displayed on 3D image
    Map<Roi, int[]> position = new HashMap<>();

    public boolean contained(Overlay o) {
        for (Roi r : this) if (o.contains(r)) return true;
        return false;
    }
    public IJTrackRoi setTrackType(Structure.TRACK_DISPLAY type) {
        this.trackType = type;
        return this;
    }
    public IJTrackRoi mergeWith(IJTrackRoi other) {
        addAll(other);
        sliceDuplicates.clear();
        return this;
    }
    @Override
    public Structure.TRACK_DISPLAY getDisplayType() {
        return trackType;
    }
    @Override
    public boolean add(Roi r) {
        position.put(r, new int[]{r.getZPosition(), r.getTPosition()});
        return super.add(r);
    }
    public IJTrackRoi setZToPosition() {
        for (Roi r: this) r.setPosition(position.get(r)[0]);
        return this;
    }
    public IJTrackRoi setTToPosition() {
        for (Roi r: this) r.setPosition(position.get(r)[1]);
        return this;
    }
    public IJTrackRoi setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public IJTrackRoi duplicateForZ(int z) {
        if (!sliceDuplicates.containsKey(z)) {
            IJTrackRoi res = new IJTrackRoi();
            for (Roi r : this) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
                res.add(dup);
            }
            sliceDuplicates.put(z, res);
        }
        return sliceDuplicates.get(z);
    }

    public boolean is2D() {
        return is2D;
    }

    public Map<Integer, IJTrackRoi> getSliceDuplicates() {
        return sliceDuplicates;
    }

    @Override
    public boolean equals(Object obj) {
        return (this == obj);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}