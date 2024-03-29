package bacmman.data_structure.region_container.roi;

import bacmman.configuration.experiment.Structure;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Palette;
import bacmman.utils.StreamConcatenation;
import ij.gui.Roi;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static bacmman.data_structure.region_container.roi.IJRoi3D.logger;
import static bacmman.data_structure.region_container.roi.IJRoi3D.setRoiColor;

public class IJTrackRoi implements TrackRoi {
    boolean is2D;
    Structure.TRACK_DISPLAY trackType = Structure.TRACK_DISPLAY.DEFAULT;
    Map<Integer, IJTrackRoi> sliceDuplicates= new HashMap<>(); // if Roi from 2D ref displayed on 3D image
    Map<Roi, int[]> positionZT = new HashMap<>();

    List<Roi> objects=new ArrayList<>();
    List<Roi> links = new ArrayList<>();
    List<Roi> flags = new ArrayList<>();
    Map<Roi, Supplier<Color>> flagColorSupplier = new HashMap<>();
    public static final Supplier<Color> SAME_COLOR = () -> null;
    public IJTrackRoi setTrackType(Structure.TRACK_DISPLAY type) {
        this.trackType = type;
        return this;
    }
    public Stream<Roi> getObjects() {
        return objects.stream();
    }
    public Stream<Roi> getLinks() {
        return links.stream();
    }
    public Stream<Roi> getFags() {
        return flags.stream();
    }
    public Stream<Roi> getRois() {
        return StreamConcatenation.concat(getObjects(), getLinks(), getFags());
    }
    public IJTrackRoi mergeWith(IJTrackRoi other) {
        objects.addAll(other.objects);
        links.addAll(other.links);
        flags.addAll(other.flags);
        positionZT.putAll(other.positionZT);
        sliceDuplicates.clear();
        return this;
    }
    @Override
    public Structure.TRACK_DISPLAY getDisplayType() {
        return trackType;
    }

    @Override
    public void setColor(Color color, double edgeOpacity, double fillOpacity, double arrowOpacity) {
        for (Roi r: objects) {
            if (r.getFillColor()!=null) setRoiColor(r, Palette.setOpacity(color, (int)Math.round(255*fillOpacity)), true);
            else setRoiColor(r, Palette.setOpacity(color, (int)Math.round(255*edgeOpacity)), false);
        }
        for (Roi r: links) {
            Color c = Palette.setOpacity(color, (int)Math.round(255*arrowOpacity));
            if (r.getStrokeColor()!=null) r.setStrokeColor(c);
            if (r.getFillColor()!=null) r.setFillColor(c);
        }
        for (Roi r: flags) {
            Supplier<Color> colorSupplier = flagColorSupplier.get(r);
            if (colorSupplier == null) continue;
            Color c = colorSupplier.get();
            if (c==null) c = color;
            if (r.getStrokeColor()!=null) r.setStrokeColor(c);
            if (r.getFillColor()!=null) r.setFillColor(c);
        }
    }

    public boolean addLink(Roi r) {
        positionZT.put(r, new int[]{r.getZPosition(), r.getTPosition()});
        return links.add(r);
    }
    public boolean addObject(Roi r) {
        positionZT.put(r, new int[]{r.getZPosition(), r.getTPosition()});
        return objects.add(r);
    }

    public boolean addFlag(Roi r, Supplier<Color> setColor) {
        positionZT.put(r, new int[]{r.getZPosition(), r.getTPosition()});
        if (setColor != null) flagColorSupplier.put(r, setColor);
        return flags.add(r);
    }

    public IJTrackRoi setZToPosition() {
        for (Roi r: links) r.setPosition(positionZT.get(r)[0]);
        for (Roi r: objects) r.setPosition(positionZT.get(r)[0]);
        for (Roi r: flags) r.setPosition(positionZT.get(r)[0]);
        return this;
    }
    public IJTrackRoi setTToPosition() {
        for (Roi r: links) r.setPosition(positionZT.get(r)[1]);
        for (Roi r: objects) r.setPosition(positionZT.get(r)[1]);
        for (Roi r: flags) r.setPosition(positionZT.get(r)[1]);
        return this;
    }
    public IJTrackRoi setHyperstackPosition() {
        for (Roi r: links) {
            int[] pZT = positionZT.get(r);
            r.setPosition(0, pZT[0], pZT[1]);
        }
        for (Roi r: objects) {
            int[] pZT = positionZT.get(r);
            r.setPosition(0, pZT[0], pZT[1]);
        }
        for (Roi r: flags) {
            int[] pZT = positionZT.get(r);
            r.setPosition(0, pZT[0], pZT[1]);
        }
        return this;
    }
    public IJTrackRoi setIs2D(boolean is2D) {this.is2D=is2D; return this;}
    public IJTrackRoi duplicateForZ(int z) {
        if (!sliceDuplicates.containsKey(z)) {
            IJTrackRoi res = new IJTrackRoi();
            for (Roi r : objects) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
                res.addObject(dup);
            }
            for (Roi r : links) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
                res.addLink(dup);
            }
            for (Roi r : flags) {
                Roi dup = (Roi)r.clone();
                dup.setPosition(r.getCPosition(), z+1, r.getTPosition());
                res.addFlag(dup, flagColorSupplier.get(r));
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