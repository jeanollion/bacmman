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

import bacmman.processing.ImageDerivatives;
import bacmman.utils.ArrayUtil;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public interface BoundingBox<T extends BoundingBox<T>> extends Offset<T> {
    Logger logger = LoggerFactory.getLogger(BoundingBox.class);
    int xMax();
    int yMax();
    int zMax();
    int getMax(int dim);
    int getMin(int dim);
    int sizeX();
    int sizeY();
    int sizeZ();
    int size(int dim);
    default int[] dimensions() {
        if (sizeZ() == 1 && sizeY() == 1) return new int[]{sizeX()}; // 1D case
        return sizeZ()>1 ? new int[]{sizeX(), sizeY(), sizeZ()}:new int[]{sizeX(), sizeY()};
    }
    int volume();
    double xMean();
    double yMean();
    double zMean();
    boolean contains(int x, int y, int z);
    boolean contains(Point point);
    boolean containsWithOffset(int x, int y, int z);
    boolean containsWithOffset(Point point);
    boolean sameBounds(BoundingBox other);
    boolean sameBounds2D(BoundingBox other);
    boolean sameDimensions(BoundingBox other);
    @Override BoundingBox<T> duplicate();
    @Override BoundingBox<T> translate(Offset other);
    @Override BoundingBox<T> translate(int dX, int dY, int dZ);
    Point getCenter();

    boolean isValid();
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
        if (!b1.isValid() || !b2.isValid()) return false;
        return Math.max(b1.xMin(), b2.xMin())<=Math.min(b1.xMax(), b2.xMax()) && Math.max(b1.yMin(), b2.yMin())<=Math.min(b1.yMax(), b2.yMax());
    }
    /**
     * 
     * @param b1
     * @param b2
     * @return whether {@param b1} & {@param b2} intersect or not in 3D space
     */
    public static boolean intersect(BoundingBox b1, BoundingBox b2) {
        if (!b1.isValid() || !b2.isValid()) return false;
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
        if (!b1.isValid() || !b2.isValid()) return false;
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
        if (!b1.isValid() || !b2.isValid()) return false;
        return Math.max(b1.xMin(), b2.xMin())<=Math.min(b1.xMax(), b2.xMax())+tolerance && Math.max(b1.yMin(), b2.yMin())<=Math.min(b1.yMax(), b2.yMax())+tolerance&& Math.max(b1.zMin(), b2.zMin())<=Math.min(b1.zMax(), b2.zMax())+tolerance;
    }
    
    /**
     * 
     * @param b1
     * @param b2
     * @return intersection bounding box in 3D. If the size in one direction is negative => there are no intersection in this direction.
     */
    public static SimpleBoundingBox getIntersection(BoundingBox b1, BoundingBox b2) {
        if (!b1.isValid() || !b2.isValid()) return new SimpleBoundingBox(1, -1, 1, -1, 1, -1);
        return new SimpleBoundingBox(Math.max(b1.xMin(), b2.xMin()), Math.min(b1.xMax(), b2.xMax()), Math.max(b1.yMin(), b2.yMin()), Math.min(b1.yMax(), b2.yMax()), Math.max(b1.zMin(), b2.zMin()), Math.min(b1.zMax(), b2.zMax()));
    }
    
    /**
     * 
     * @param b1
     * @param b2
     * @return intersection bounding box in XY dimensions. If the size in one direction is negative => there are no intersection in this direction. Zmin and Zmax are u {@param b1}
     */
    public static SimpleBoundingBox getIntersection2D(BoundingBox b1, BoundingBox b2) {
        if (!b1.isValid() || !b2.isValid()) return new SimpleBoundingBox(1, -1, 1, -1, 0, 0);
        return new SimpleBoundingBox(Math.max(b1.xMin(), b2.xMin()), Math.min(b1.xMax(), b2.xMax()), Math.max(b1.yMin(), b2.yMin()), Math.min(b1.yMax(), b2.yMax()), b1.zMin(), b1.zMax());
    }

    /**
     * Test inclusion in 3D
     * @param contained element that could be contained in {@param contained}
     * @param container element that could contain {@param contained}
     * @return whether {@param contained} is included or not in {@param container}
     */
    public static boolean isIncluded(BoundingBox contained, BoundingBox container) {
        if (!contained.isValid() || !container.isValid()) return false;
        return contained.xMin()>=container.xMin() && contained.xMax()<=container.xMax() && contained.yMin()>=container.yMin() && contained.yMax()<=container.yMax() && contained.zMin()>=container.zMin() && contained.zMax()<=container.zMax();
    }
    /**
     * Test inclusion in XY dimensions
     * @param contained element that could be contained in {@param contained}
     * @param container element that could contain {@param contained}
     * @return whether {@param contained} is included or not in {@param container} only taking into acount x & y dimensions
     */
    static boolean isIncluded2D(BoundingBox contained, BoundingBox container) {
        if (!contained.isValid() || !container.isValid()) return false;
        return contained.xMin()>=container.xMin() && contained.xMax()<=container.xMax() && contained.yMin()>=container.yMin() && contained.yMax()<=container.yMax();
    }

    static boolean isIncluded2D(BoundingBox contained, BoundingBox container, int tolerance) {
        if (!contained.isValid() || !container.isValid()) return false;
        return contained.xMin()+tolerance>=container.xMin() && contained.xMax()-tolerance<=container.xMax() && contained.yMin()+tolerance>=container.yMin() && contained.yMax()-tolerance<=container.yMax();
    }

    static boolean isIncluded(Point point, BoundingBox container) {
        return point.get(0)>=container.xMin() && point.get(0)<=container.xMax() && point.get(1)>=container.yMin() && point.get(1)<=container.yMax() && point.get(2)>=container.zMin() && point.get(2)<=container.zMax();
    }

    static boolean isIncluded2D(Point point, BoundingBox container) {
        return point.get(0)>=container.xMin() && point.get(0)<=container.xMax() && point.get(1)>=container.yMin() && point.get(1)<=container.yMax();
    }

    static boolean isIncluded2D(Point point, BoundingBox container, int tolerance) {
        return point.get(0)+tolerance>=container.xMin() && point.get(0)-tolerance<=container.xMax() && point.get(1)+tolerance>=container.yMin() && point.get(1)-tolerance<=container.yMax();
    }

    static boolean containsZ(BoundingBox bds, int z) {
        return bds.zMin()<=z && bds.zMax()>=z;
    }

    static double outterDistanceSq2D(Point point, BoundingBox bds) {
        return Math.pow(outterDistance1D(point.get(0), bds, 0), 2) + Math.pow(outterDistance1D(point.get(1), bds, 1), 2);
    }

    static double outterDistanceSq(Point point, BoundingBox bds) {
        return Math.pow(outterDistance1D(point.get(0), bds, 0), 2) + Math.pow(outterDistance1D(point.get(1), bds, 1), 2)  + Math.pow(outterDistance1D(point.get(2), bds, 2), 2);
    }

    // returns zero when inside
    static double outterDistance1D(double coord, BoundingBox bds, int dim) {
        int min = bds.getMin(dim);
        if (coord < min) return min - coord;
        int max = bds.getMax(dim);
        if (coord > max) return coord - max;
        return 0;
    }

    static double innerDistance1D(double coord, BoundingBox bds, int dim) {
        int min = bds.getMin(dim);
        if (coord < min) return 0;
        int max = bds.getMax(dim);
        if (coord > max) return 0;
        return Math.min(coord - min, max - coord);
    }
    
    public static MutableBoundingBox getMergedBoundingBox(Stream<BoundingBox> bounds) {
        return bounds.reduce(new MutableBoundingBox(), MutableBoundingBox::union, MutableBoundingBox::union);
    }

    static boolean[] getTouchingEdges(BoundingBox container, BoundingBox other) {
        boolean[] res = new boolean[6];
        res[0] = other.xMin()<=container.xMin();
        res[1] = other.xMax()>=container.xMax();
        res[2] = other.yMin()<=container.yMin();
        res[3] = other.yMax()>=container.yMax();
        res[4] = other.zMin()<=container.zMin();
        res[5] = other.zMax()>=container.zMax();
        return res;
    }

    static boolean[] getTouchingEdges2D(BoundingBox container, BoundingBox other) {
        boolean[] res = new boolean[4];
        res[0] = other.xMin()<=container.xMin();
        res[1] = other.xMax()>=container.xMax();
        res[2] = other.yMin()<=container.yMin();
        res[3] = other.yMax()>=container.yMax();
        return res;
    }
    static boolean touchEdges2D(BoundingBox container, BoundingBox other) {
        for (boolean b: getTouchingEdges2D(container, other)) if (b) return true;
        return false;
    }

    static boolean touchEdges(BoundingBox container, BoundingBox other) {
        for (boolean b: getTouchingEdges(container, other)) if (b) return true;
        return false;
    }

    /**
     *
     * @param bb area to loop over
     * @param function : function to run on each pixel
     * @param predicate : tested on all pixels, function is executed only if predicate is true.
     * @param parallel run function on multiple threads
     */
    static void loop(BoundingBox bb, LoopFunction function, LoopPredicate predicate, boolean parallel) {
        if (!parallel) {
            loop(bb, function, predicate);
            return;
        }
        if (predicate==null) {
            loop(bb, function, parallel);
            return;
        }
        int parallelAxis = chooseParallelAxis(bb);
        if (parallelAxis == 2) {
            IntStream.rangeClosed(bb.zMin(), bb.zMax()).parallel().forEach(z -> {
                for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                    for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                        if (predicate.test(x, y, z)) function.loop(x, y, z);
                    }
                }
            });
        } else if (parallelAxis == 1) {
            IntStream.rangeClosed(bb.yMin(), bb.yMax()).parallel().forEach(y -> {
                for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                    for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                        if (predicate.test(x, y, z)) function.loop(x, y, z);
                    }
                }
            });
        } else {
            IntStream.rangeClosed(bb.xMin(), bb.xMax()).parallel().forEach(x -> {
                for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                    for (int y=bb.yMin(); y<=bb.yMax(); ++y) {
                        if (predicate.test(x, y, z)) function.loop(x, y, z);
                    }
                }
            });
        }
    }

    /**
     *
     * @param bb area to loop over
     * @param function : function to run on each pixel
     * @param parallel run function on multiple threads
     */
    static void loop(BoundingBox bb, LoopFunction function, boolean parallel) {
        if (!parallel) {
            loop(bb, function);
            return;
        }
        int parallelAxis = chooseParallelAxis(bb);
        if (parallelAxis == 2) {
            IntStream.rangeClosed(bb.zMin(), bb.zMax()).parallel().forEach(z -> {
                for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                    for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                        function.loop(x, y, z);
                    }
                }
            });
        } else if (parallelAxis == 1) {
            IntStream.rangeClosed(bb.yMin(), bb.yMax()).parallel().forEach(y -> {
                for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                    for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                        function.loop(x, y, z);
                    }
                }
            });
        } else {
            IntStream.rangeClosed(bb.xMin(), bb.xMax()).parallel().forEach(x -> {
                for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                    for (int y=bb.yMin(); y<=bb.yMax(); ++y) {
                        function.loop(x, y, z);
                    }
                }
            });
        }
    }

    /**
     *
     * @param bb area to loop over
     * @param function : Each thread will call the function supplier only once and use only the supplied function.
     * @param parallel run function on multiple threads
     */
    static void loop(BoundingBox bb, Supplier<LoopFunction> function, boolean parallel) {
        if (!parallel) {
            loop(bb, function.get());
            return;
        }
        int parallelAxis = chooseParallelAxis(bb);
        if (parallelAxis == 2) {
            ThreadRunner<Void> tr = new ThreadRunner<>( () ->  {
                LoopFunction fun = function.get();
                return z -> {
                    for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                        for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                            fun.loop(x, y, z);
                        }
                    }
                    return null;
                };
            }, bb.zMin(), bb.zMax() + 1);
            try {
                tr.setCollectValues(false).startAndJoin();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else if (parallelAxis == 1) {
            ThreadRunner<Void> tr = new ThreadRunner<>( () ->  {
                LoopFunction fun = function.get();
                return y -> {
                    for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                        for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                            fun.loop(x, y, z);
                        }
                    }
                    return null;
                };
            }, bb.yMin(), bb.yMax() + 1);
            try {
                tr.setCollectValues(false).startAndJoin();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            ThreadRunner<Void> tr = new ThreadRunner<>( () ->  {
                LoopFunction fun = function.get();
                return x -> {
                    for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                        for (int y=bb.yMin(); y<=bb.yMax(); ++y) {
                            fun.loop(x, y, z);
                        }
                    }
                    return null;
                };
            }, bb.xMin(), bb.xMax() + 1);
            try {
                tr.setCollectValues(false).startAndJoin();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     *
     * @param bb area to loop over
     * @param function : Each thread will call the function supplier only once and use only the supplied function.
     * @param predicate: Predicate tested at each pixel, function is only executed if true. Supplier is only called once per thread and resulting predicate used only by the calling thread.
     * @param parallel run function on multiple threads
     */
    static void loop(BoundingBox bb, Supplier<LoopFunction> function, Supplier<LoopPredicate> predicate, boolean parallel) {
        if (!parallel) {
            loop(bb, function.get());
            return;
        }
        int parallelAxis = chooseParallelAxis(bb);
        if (parallelAxis == 2) {
            ThreadRunner<Void> tr = new ThreadRunner<>( () ->  {
                LoopFunction fun = function.get();
                LoopPredicate test = predicate.get();
                return z -> {
                    for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                        for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                            if (test.test(x, y, z)) fun.loop(x, y, z);
                        }
                    }
                    return null;
                };
            }, bb.zMin(), bb.zMax() + 1);
            try {
                tr.setCollectValues(false).startAndJoin();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else if (parallelAxis == 1) {
            ThreadRunner<Void> tr = new ThreadRunner<>( () ->  {
                LoopFunction fun = function.get();
                LoopPredicate test = predicate.get();
                return y -> {
                    for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                        for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                            if (test.test(x, y, z)) fun.loop(x, y, z);
                        }
                    }
                    return null;
                };
            }, bb.yMin(), bb.yMax() + 1);
            try {
                tr.setCollectValues(false).startAndJoin();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            ThreadRunner<Void> tr = new ThreadRunner<>( () ->  {
                LoopFunction fun = function.get();
                LoopPredicate test = predicate.get();
                return x -> {
                    for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
                        for (int y=bb.yMin(); y<=bb.yMax(); ++y) {
                            if (test.test(x, y, z)) fun.loop(x, y, z);
                        }
                    }
                    return null;
                };
            }, bb.xMin(), bb.xMax() + 1);
            try {
                tr.setCollectValues(false).startAndJoin();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static int chooseParallelAxis(BoundingBox bb) {
        int[] dimensions = new int[]{bb.sizeX(), bb.sizeY(), bb.sizeZ()};
        int maxDim = ArrayUtil.max(dimensions); // avoid looping on that one
        int minDim = ArrayUtil.min(dimensions); // avoid looping on that one if too small
        int middleDim = IntStream.range(0, 3).filter(a -> a != maxDim && a != minDim).findFirst().getAsInt();
        if (dimensions[minDim] == 1) {
            if (dimensions[middleDim] == 1) return maxDim; // otherDims are 1
            else return middleDim;
        } else {
            int nCPUs = Runtime.getRuntime().availableProcessors();
            if (dimensions[minDim] < nCPUs) return middleDim;
            else return minDim;
        }
    }
    
    static void loop(BoundingBox bb, LoopFunction function) {
        for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
            for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                    function.loop(x, y, z);
                }
            }
        }
    }

    static void loop(BoundingBox bb, LoopFunction function, LoopPredicate predicate) {
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

    static void loop(BoundingBox bb, LoopFunction function, LoopPredicate predicate, LoopPredicate exitPredicate) {
        if (predicate==null && exitPredicate == null) {
            loop(bb, function);
            return;
        } else if (exitPredicate == null) {
            loop(bb, function, predicate);
            return;
        } else if (predicate == null) {
            predicate = (x, y, z) -> true;
        }
        for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
            for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                    if (predicate.test(x, y, z)) function.loop(x, y, z);
                    if (exitPredicate.test(x, y, z)) return;
                }
            }
        }
    }

    static boolean test(BoundingBox bb, LoopPredicate predicate) {
        for (int z = bb.zMin(); z<=bb.zMax(); ++z) {
            for (int y = bb.yMin(); y<=bb.yMax(); ++y) {
                for (int x=bb.xMin(); x<=bb.xMax(); ++x) {
                    if (predicate.test(x, y, z)) return true;
                }
            }
        }
        return false;
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
