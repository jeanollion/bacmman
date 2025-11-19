package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.GraphObject;
import bacmman.data_structure.GraphObject.GraphObjectBds;
import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.utils.geom.Point;

public class MockObj implements GraphObjectBds<MockObj> {

    private final int frame;
    private final int idx;

    public MockObj(int frame, int idx) {
        this.frame = frame;
        this.idx = idx;
    }

    @Override
    public int getFrame() { return frame; }

    @Override
    public Point getCenter() { return new Point(0,0); } // dummy

    @Override
    public BoundingBox getBounds() { return new SimpleBoundingBox(0,0,0,1,1,1); }

    @Override
    public BoundingBox getParentBounds() { return new SimpleBoundingBox(0,0,0,1,1,1); }

    @Override
    public int getIdx() { return idx; }

    @Override
    public int compareTo(MockObj o) {
        int c = Integer.compare(this.frame, o.frame);
        if (c == 0) return Integer.compare(this.idx, o.idx);
        return c;
    }

    @Override
    public String toString() {
        return frame + "-" + idx;
    }
}
