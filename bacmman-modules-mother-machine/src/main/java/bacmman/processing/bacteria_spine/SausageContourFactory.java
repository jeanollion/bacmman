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

import bacmman.utils.ArrayUtil;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.PointContainer2;
import bacmman.utils.geom.Vector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SausageContourFactory {
    public final static Logger logger = LoggerFactory.getLogger(SausageContourFactory.class);
    /**
     * Modifies the spine so that it has a sausage shape: width is median width, ends are circles of radius width/2
     * @param spine that will be modified
     */
    public static void toSausage(BacteriaSpineFactory.SpineResult spine, double resampleContour) {
        if (spine.spine.length<=3) return;
        // get median width
        double width = ArrayUtil.median(Arrays.stream(spine.spine, 1, spine.spine.length-1).mapToDouble(s->s.getContent1().norm()).toArray());
        double length = spine.spine[spine.spine.length-1].getContent2();
        // adjust all length
        DoubleUnaryOperator getSausageWidthEnd = curvDistToEnd -> 2 * Math.sqrt(width*curvDistToEnd - curvDistToEnd*curvDistToEnd); // ends are circular + application of pythagorean theorem
        DoubleUnaryOperator getSausageWidth = curvCoord -> {
            if (curvCoord<width/2) return getSausageWidthEnd.applyAsDouble(curvCoord);
            else if (length - curvCoord < width/2) return getSausageWidthEnd.applyAsDouble(length - curvCoord);
            else return width;
        };
        // modifies the width of each spine vector
        Arrays.stream(spine.spine).forEach(v -> v.getContent1().normalize().multiply(getSausageWidth.applyAsDouble(v.getContent2())));
        // re-create a contour from spine vector ends
        // we start by generating two streams of points one from top to bottom on the left side and of from bottom to top on the other side
        BiFunction<PointContainer2<Vector, ?>, Boolean, Point> getSausageContourPoint = (s, left) -> s.duplicate().translate(s.getContent1().duplicate().multiply(left?0.5:-0.5));
        Stream<Point> left = Arrays.stream(spine.spine).map(s -> getSausageContourPoint.apply(s, true));
        Stream<Point> right = IntStream.range(1, spine.spine.length-1).map(i->spine.spine.length-1-i).mapToObj(i->getSausageContourPoint.apply(spine.spine[i], false)); // ends are not taken into acount because they are of length 0. 
        List<Point> all = Stream.concat(left, right).collect(Collectors.toList());
        CircularNode<Point> circContour = CircularNode.toCircularContour(all);
        if (resampleContour>0) {
            circContour=CircularContourFactory.resampleContour(circContour, resampleContour);
            spine.setContour(CircularContourFactory.getSet(circContour));
        } else spine.setContour(new HashSet<>(all));
        spine.setCircContour(circContour);
    }
}
