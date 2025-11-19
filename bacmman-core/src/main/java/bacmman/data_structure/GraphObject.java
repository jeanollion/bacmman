package bacmman.data_structure;

import bacmman.image.BoundingBox;
import bacmman.utils.geom.Point;

public interface GraphObject<S> extends Comparable<S> {
    int getFrame();

    interface GraphObjectBds<S> extends GraphObject<S> {
        Point getCenter();
        BoundingBox getBounds();
        BoundingBox getParentBounds();
        int getIdx();
    }
}
