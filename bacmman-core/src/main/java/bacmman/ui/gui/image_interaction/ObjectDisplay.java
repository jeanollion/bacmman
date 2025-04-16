package bacmman.ui.gui.image_interaction;

import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBox;
import bacmman.image.Offset;
import bacmman.image.SimpleBoundingBox;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ObjectDisplay implements Comparable<ObjectDisplay> {
    static final Logger logger = LoggerFactory.getLogger(ObjectDisplay.class);
    public final SegmentedObject object;
    public final BoundingBox offset;
    public final int sliceIdx;

    public ObjectDisplay(SegmentedObject object, Offset offset, int sliceIdx) {
        if (object==null) throw new IllegalArgumentException("Object cannot be null");
        if (offset==null) {
            logger.error("Null offset for {} at slice: {}", object, sliceIdx);
            throw new IllegalArgumentException("Offset cannot be null");
        }
        this.object = object;
        this.offset = new SimpleBoundingBox(object.getBounds()).resetOffset().translate(offset);
        this.sliceIdx = sliceIdx;
    }

    public static Stream<SegmentedObject> getObjectStream(Collection<ObjectDisplay> od) {
        return od.stream().map(o->o.object);
    }
    public static List<SegmentedObject> getObjectList(Collection<ObjectDisplay> od) {
        return od.stream().map(o->o.object).collect(Collectors.toList());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ObjectDisplay)) return false;
        ObjectDisplay that = (ObjectDisplay) o;
        return sliceIdx == that.sliceIdx && Objects.equals(object, that.object) && offset.sameBounds(((ObjectDisplay) o).offset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(object, offset.xMin(), offset.xMax(), offset.yMin(), offset.yMax(), offset.zMin(), offset.zMax(), sliceIdx);
    }

    @Override
    public int compareTo(ObjectDisplay o) {
        int c = Integer.compare(sliceIdx, o.sliceIdx);
        if (c == 0) return object.compareTo(o.object);
        else return c;
    }

    @Override
    public String toString() {
        return object.toStringShort() + "|offset="+offset+"|slice="+sliceIdx;
    }
}
