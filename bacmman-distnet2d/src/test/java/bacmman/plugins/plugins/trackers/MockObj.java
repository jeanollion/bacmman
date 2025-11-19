package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.GraphObject;
import bacmman.data_structure.GraphObject.GraphObjectBds;
import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.utils.geom.Point;

public class MockObj implements GraphObjectBds<MockObj> {

    private final int frame;
    private final int idx;
    private final Point center;

    public MockObj(int frame, int idx) {
        this(frame, idx, new Point(0, 0));
    }

    public MockObj(int frame, int idx, Point center) {
        this.frame = frame;
        this.idx = idx;
        this.center = center;
    }

    @Override
    public boolean contains(Point p) {
        return getBounds().contains(p);
    }

    @Override
    public int getFrame() { return frame; }

    @Override
    public Point getCenter() { return center; } // dummy

    @Override
    public BoundingBox getBounds() { return new SimpleBoundingBox((int)(center.get(0)-1),(int)Math.ceil((center.get(0)+1)),(int)(center.get(1)-1),(int)Math.ceil((center.get(1)+1)),(int)(center.getWithDimCheck(2)-1),(int)Math.ceil((center.getWithDimCheck(2)+1))); }

    @Override
    public BoundingBox getParentBounds() { return new SimpleBoundingBox(0,0,0,100,100,100); }

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
