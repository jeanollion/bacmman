/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.processing.bacteria_spine;

import bacmman.ui.gui.image_interaction.IJImageDisplayer;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.Offset;
import bacmman.image.TypeConverter;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.PointContainer2;
import bacmman.utils.geom.Vector;
import ij.ImagePlus;
import ij.gui.Arrow;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import java.awt.Color;

/**
 *
 * @author Jean Ollion
 */
public class SpineOverlayDrawer {
    public static Line drawLine(Point start, Vector dir, Color color, double width) {
        Line l = new Line(start.get(0), start.get(1), start.get(0)+dir.get(0), start.get(1)+dir.get(1));
        l.setStrokeColor(color);
        l.setStrokeWidth(width);
        return l;
    }
    public static Arrow drawArrow(Point start, Vector dir, Color color, double width) {
        Arrow l = new Arrow(start.get(0), start.get(1), start.get(0)+dir.get(0), start.get(1)+dir.get(1));
        l.setStrokeColor(color);
        l.setStrokeWidth(width);
        l.setHeadSize(width*3);
        return l;
    }
    public static PointRoi drawPoint(Point point, Color color, double width, int size, int type) {
        PointRoi p = new PointRoi(new float[]{point.get(0)}, new float[]{point.get(1)});
        p.setStrokeColor(color);
        p.setStrokeWidth(width);
        p.setPointType(type);
        p.setSize(size);
        return p;
    }
    public static Overlay getSpineOverlay(BacteriaSpineFactory.SpineResult s, Offset offset, Color color, Color contourColor, double width) {
        Overlay res = new Overlay();
        // draw contour
        if (contourColor!=null) {
            CircularNode.apply(s.circContour, n -> {
                Point cur = Point.asPoint2D(n.element).translateRev(offset);
                Vector dir = Vector.vector2D(n.element, n.next.element);
                res.add(drawLine(cur, dir, contourColor, width));
            }, true);
        }
        // draw lateral direction
        for (PointContainer2<Vector, Double> p : s.spine) {
            if (p.getContent1() == null || p.getContent1().isNull()) continue;
            double norm = p.getContent1().norm();
            int vectSize= (int) (norm/2.0+0.5);
            Vector dir = p.getContent1().duplicate().normalize();
            Point cur = p.duplicate().translateRev(dir.duplicate().multiply(norm/4d)).translateRev(offset);
            dir.multiply(vectSize);
            res.add(drawLine(cur, dir, color, width));
        }
        // draw central line
        for (int i = 1; i<s.spine.length; ++i) { 
            PointContainer2<Vector, Double> p = s.spine[i-1];
            PointContainer2<Vector, Double> p2 = s.spine[i];
            res.add(drawLine(p.duplicate().translateRev(offset), Vector.vector2D(p, p2), color, width));
        }
        return res;
    }
    static final IJImageDisplayer DISP = new IJImageDisplayer();
    public static void display(String title, ImageMask image, Overlay overlay) {
        Image im = TypeConverter.toByteMask(image, null, 1).setName(title);
        ImagePlus ip = DISP.showImage(im);
        ip.setOverlay(overlay);
    }
    public static void display(String title, Image image, Overlay overlay) {
        image.setName(title);
        ImagePlus ip = DISP.showImage(image);
        ip.setOverlay(overlay);
    }
    public static BacteriaSpineFactory.SpineResult trimSpine(BacteriaSpineFactory.SpineResult spine, double keepProp) {
        int finalSize = (int)Math.max(3, (int)spine.spine.length * keepProp);
        PointContainer2<Vector, Double>[] newSpine = new PointContainer2[finalSize];
        for (int i = 0; i<finalSize; ++i) newSpine[i] = spine.spine[(int) (i* (spine.spine.length-1)/(double)(finalSize-1)+0.5)];
        return new BacteriaSpineFactory.SpineResult().setSpine(newSpine).setCircContour(spine.circContour);
    }

    public static void drawArrow(Overlay overlay, Offset offset, Point p1, Point p2, Color color, double width) {
        if (p1==null || p2==null) return;
        overlay.add(SpineOverlayDrawer.drawArrow(p1.duplicate().translateRev(offset), Vector.vector(p1, p2), color, width));
    }

    public static void drawPoint(Overlay overlay, Offset offset, Point p, Color color, double width) {
        if (p==null) return;
        overlay.add(SpineOverlayDrawer.drawPoint(p.duplicate().translateRev(offset), color, width, 1, 2));
    }

}
