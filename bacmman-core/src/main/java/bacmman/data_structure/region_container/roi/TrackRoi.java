package bacmman.data_structure.region_container.roi;

import bacmman.configuration.experiment.Structure;
import ij.gui.Overlay;
import ij.gui.Roi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class TrackRoi extends ArrayList<Roi> {
    boolean is2D;
    Structure.TRACK_DISPLAY trackType = Structure.TRACK_DISPLAY.DEFAULT;
    Map<Integer, TrackRoi> sliceDuplicates= new HashMap<>(); // if Roi from 2D ref displayed on 3D image
    Map<Roi, int[]> position = new HashMap<>();
    public boolean contained(Overlay o) {
        for (Roi r : this) if (o.contains(r)) return true;
        return false;
    }
    public TrackRoi setTrackType(Structure.TRACK_DISPLAY type) {
        this.trackType = type;
        return this;
    }
    public Structure.TRACK_DISPLAY getTrackType() {
        return trackType;
    }
    @Override
    public boolean add(Roi r) {
        position.put(r, new int[]{r.getZPosition(), r.getTPosition()});
        return super.add(r);
    }
    public TrackRoi setZToPosition() {
        for (Roi r: this) r.setPosition(position.get(r)[0]);
        return this;
    }
    public TrackRoi setTToPosition() {
        for (Roi r: this) r.setPosition(position.get(r)[1]);
        return this;
    }
    public TrackRoi setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public TrackRoi duplicateForZ(int z) {
        if (!sliceDuplicates.containsKey(z)) {
            TrackRoi res = new TrackRoi();
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

    public Map<Integer, TrackRoi> getSliceDuplicates() {
        return sliceDuplicates;
    }
}