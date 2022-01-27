package bacmman.processing.bacteria_spine;

import bacmman.core.Core;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.Offset;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

public interface SpineOverlayDrawer {

    Object drawLine(Point start, Vector dir, String color, double width);

    Object drawArrow(Point start, Vector dir, String color, double width);
    Object drawPoint(Point point, String color, double width, int size, int type);
    Object getSpineOverlay(BacteriaSpineFactory.SpineResult s, Offset offset, String color, String contourColor, double width);
    void display(String title, ImageMask image, Object overlay);
    void display(String title, Image image, Object overlay);
    BacteriaSpineFactory.SpineResult trimSpine(BacteriaSpineFactory.SpineResult spine, double keepProp);

    void drawArrow(Object overlay, Offset offset, Point p1, Point p2, String color, double width);

    void drawPoint(Object overlay, Offset offset, Point p, String color, double width);


    static SpineOverlayDrawer get() {
        List<Class<SpineOverlayDrawer>> imp = Core.findImplementation("bacmman.processing.bacteria_spine", SpineOverlayDrawer.class);
        if (!imp.isEmpty()) {
            Class<SpineOverlayDrawer> clazz = imp.get(0);
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (NoClassDefFoundError | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            }
        }
        return null;
    }
}
