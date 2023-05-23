package bacmman.ui.gui.image_interaction;

import bacmman.data_structure.Region;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;

import java.awt.*;

public interface OverlayDisplayer {
    void displayContours(Region r, int frame, double size, Color color, boolean dashed);
    void displayRegion(Region r, int frame, Color color);
    void displayArrow(Point start, Vector direction, int frame, boolean arrowStart, boolean arrowEnd, double size, Color color);
    void updateDisplay();
}
