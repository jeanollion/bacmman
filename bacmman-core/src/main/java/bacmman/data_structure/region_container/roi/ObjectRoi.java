package bacmman.data_structure.region_container.roi;

import java.awt.*;

public interface ObjectRoi<O extends ObjectRoi<O>> {
    void setColor(Color color, boolean fill);
    void setStrokeWidth(double strokeWidth);
    O duplicate();
}
