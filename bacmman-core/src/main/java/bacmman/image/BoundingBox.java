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
package bacmman.image;

import static bacmman.utils.Utils.parallele;

import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public interface BoundingBox<T> extends Offset<T> {
    public int xMax();
    public int yMax();
    public int zMax();
    public int sizeX();
    public int sizeY();
    public int sizeZ();
    public double xMean();
    public double yMean();
    public double zMean();
    public boolean contains(int x, int y, int z);
    public boolean containsWithOffset(int x, int y, int z);
    public boolean sameBounds(BoundingBox other);
    public boolean sameDimensions(BoundingBox other);
    /**
     * 
     * @param b1
     * @param b2
     * @return euclidean distance between centers of {@param b1} & {@param other}
     */
    public static double getDistance(BoundingBox b1, BoundingBox b2) {
        return Math.sqrt(Math.pow((b1.xMax()+b1.xMin()-(b2.xMin()+b2.xMax()))/2d, 2) + Math.pow((b1.yMax()+b1.yMin()-(b2.yMin()+b2.yMax()))/2d, 2) + Math.pow((b1.zMax()+b1.zMin()-(b2.zMin()-b2.zMax()))/2d, 2));
    }
    /**
     * 
     * @param b1
     * @param b2
     * @return whether {@param b1} & {@param b2} intersect or not in XY space
     */
    public static boolean intersect2D(BoundingBox b1, BoundingBox b2) {
        return Math.max(b1.xMin(), b2.xMin())<=Math.min(b1.xMax(), b2.xMax()) && Math.max(b1.yMin(), b2.yMin())<=Math.min(b1.yMax(), b2.yMax());
    }
    /**
     * 
     * @param b1
     * @param b2
     * @return whether {@param b1} & {@param b2} intersect or not in 3D space
     */
    public static boolean intersect(BoundingBox b1, BoundingBox b2) {
        return Math.max(b1.xMin(), b2.xMin())<=Math.min(b1.xMax(), b2.xMax()) && Math.max(b1.yMin(), b2.yMin())<=Math.min(b1.yMax(), b2.yMax()) && Math.max(b1.zMin(), b2.zMin())<=Math.min(b1.zMax(), b2.zMax());
    }
    /**
     * 
     * @param b1
     * @param b2
     * @param tolerance
     * @return whether {@param b1} & {@param b2} intersect or not in XY space with the tolerance {@param tolerance}
     */
    public static boolean intersect2D(BoundingBox b1, BoundingBox b2, int tolerance) {
        return Math.max(b1.xMin(), b2.xMin())<=Math.min(b1.xMax(), b2.xMax())+tolerance && Math.max(b1.yMin(), b2.yMin())<=Math.min(b1.yMax(), b2.yMax())+tolerance;
    }
    
    /**
     * 
     * @param b1
     * @param b2
     * @param tolerance
     * @return whether {@param b1} & {@param b2} intersect or not in 3D space with the tolerance {@param tolerance}
     */
    public static boolean intersect(BoundingBox b1, BoundingBox b2, int tolerance) {
        return Math.max(b1.xMin(), b2.xMin())<=Math.min(b1.xMax(), b2.xMax())+tolerance && Math.max(b1.yMin(), b2.yMin())<=Math.min(b1.yMax(), b2.yMax())+tolerance&& Math.max(b1.zMin(), b2.zMin())<=Math.min(b1.zMax(), b2.zMax())+tolerance;
    }
    
    /**
     * 
     * @param b1
     * @param b2
     * @return intersection bounding box in 3D. If the size in one direction is negative => there are no intersection in this direction.
     */
    public static SimpleBoundingBox getIntersection(BoundingBox b1, BoundingBox b2) {
        return new SimpleBoundingBox(Math.max(b1.xMin(), b2.xMin()), Math.min(b1.xMax(), b2.xMax()), Math.max(b1.yMin(), b2.yMin()), Math.min(b1.yMax(), b2.yMax()), Math.max(b1.zMin(), b2.zMin()), Math.min(b1.zMax(), b2.zMax()));
    }
    
    /**
     * 
     * @param b1
     * @param b2
     * @return intersection bounding box in XY dimensions. If the size in one direction is negative => there are no intersection in this direction. Zmin and Zmax are u {@param b1}
     */
    public static SimpleBoundingBox getIntersection2D(BoundingBox b1, BoundingBox b2) {
        return new SimpleBoundingBox(Math.max(b1.xMin(), b2.xMin()), Math.min(b1.xMax(), b2.xMax()), Math.max(b1.yMin(), b2.yMin()), Math.min(b1.yMax(), b2.yMax()), b1.zMin(), b1.zMax());
    }
    
    /**
     * Test inclusion in 3D
     * @param contained element that could be contained in {@param contained}
     * @param container element that could contain {@param contained}
     * @return whether {@param contained} is included or not in {@param container}
     */
    public static boolean isIncluded(BoundingBox contained, BoundingBox container) {
        return contained.xMin()>=container.xMin() && contained.xMax()<=container.xMax() && contained.yMin()>=container.yMin() && contained.yMax()<=container.yMax() && contained.zMin()>=container.zMin() && contained.zMax()<=container.zMax();
    }
    /**
     * Test inclusion in XY dimensions
     * @param contained element that could be contained in {@param contained}
     * @param container element that could contain {@param contained}
     * @return whether {@param contained} is included or not in {@param container} only taking into acount x & y dimensions
     */
    public static boolean isIncluded2D(BoundingBox contained, BoundingBox container) {
        return contained.xMin()>=container.xMin() && contained.xMax()<=container.xMax() && contained.yMin()>=container.yMin() && contained.yMax()<=container.yMax();
    }
    
    public static MutableBoundingBox getMergedBoundingBox(Stream<BoundingBox> bounds) {
        MutableBoundingBox res = new MutableBoundingBox();
        bounds.forEach(b->res.union(b));
        return res;
    }
    public static void loop(BoundingBox bb, LoopFunction function, LoopPredicate predicate, boolean parallele) {
        if (!parallele) {
            loop(bb, function, predicate);
            return;
        }
        if (predicate==null) {
            loop(bb, function, parallele);
            return;
        }
        int sXY = bb.sizeX() * bb.sizeY();
        int sX = bb.sizeX();
        IntStream.range(0, sXY*bb.sizeZ()).parallel().forEach(i-> {
            int xy = i%sXY;
            int z = i/sXY;
            int x = xy%sX;
            int y = xy/sX;
            if (predicate.test(x, y, z)) function.loop(x, y, z);
        });
    }
    public static void loop(BoundingBox bb, LoopFunction function, boolean parallele) {
        if (!parallele) {
            loop(bb, function);
            return;
        }
        int sXY = bb.sizeX() * bb.sizeY();
        int sX = bb.sizeX();
        parallele(IntStream.range(0, sXY*bb.sizeZ()), parallele).forEach(i-> {
            int xy = i%sXY;
            function.loop(xy%sX, xy/sX, i/sXY);
        });
    }
    
    public static void loop(BoundingBox bb, LoopFunction function) {
        for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
            for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                    function.loop(x, y, z);
                }
            }
        }
    }
    public static void loop(BoundingBox bb, LoopFunction function, LoopPredicate predicate) {
        if (predicate==null) {
            loop(bb, function);
            return;
        }
        for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
            for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                    if (predicate.test(x, y, z)) function.loop(x, y, z);
                }
            }
        }
    }
    
    public static interface LoopFunction {
        public void loop(int x, int y, int z);
    }
    public static interface LoopPredicate {
        public boolean test(int x, int y, int z);
    }
    public static LoopPredicate and(LoopPredicate... predicates) {
        switch (predicates.length) {
            case 0:
                return (x, y, z)->true;
            case 1:
                return predicates[0];
            case 2:
                return (x, y, z) -> predicates[0].test(x, y, z) && predicates[1].test(x, y, z);
            case 3:
                return (x, y, z) -> predicates[0].test(x, y, z) && predicates[1].test(x, y, z) && predicates[2].test(x, y, z);
            default:
                return (x, y, z) -> {
                    for (LoopPredicate p : predicates) if (!p.test(x, y, z)) return false;
                    return true;
                };
        }
    }
    public static LoopPredicate or(LoopPredicate... predicates) {
        switch (predicates.length) {
            case 0:
                return (x, y, z)->true;
            case 1:
                return predicates[0];
            case 2:
                return (x, y, z) -> predicates[0].test(x, y, z) || predicates[1].test(x, y, z);
            case 3:
                return (x, y, z) -> predicates[0].test(x, y, z) || predicates[1].test(x, y, z) || predicates[2].test(x, y, z);
            default:
                return (x, y, z) -> {
                    for (LoopPredicate p : predicates) if (p.test(x, y, z)) return true;
                    return false;
                };
        }
    }
}
