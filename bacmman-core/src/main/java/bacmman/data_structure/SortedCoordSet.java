package bacmman.data_structure;

import bacmman.image.Image;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.*;

import java.util.stream.Collectors;
import java.util.stream.LongStream;

public interface SortedCoordSet extends CoordCollection {
    long pollFirst();
    static SortedCoordSet create(Image sortImage, boolean reverseOrder) {
        if (sortImage.sizeZ()==1) {
            return new SortedCoordSet2D(sortImage, 0, reverseOrder);
        }
        else return new SortedCoordSet3D(sortImage, reverseOrder);
    }

    class SortedCoordSet2D extends AbstractCoordCollection2D implements SortedCoordSet {
        final IntSortedSet coords;
        public SortedCoordSet2D(Image sortImage, int z, boolean reverseOrder) {
            super(sortImage.sizeX(), sortImage.sizeY(), z);
            // comparator using the image + coherence equals
            IntComparator comparator = reverseOrder ? (i1, i2) -> {
                double d1 = getPixel(sortImage, i1);
                double d2 = getPixel(sortImage, i2);
                if (d1==d2) return Integer.compare(i1, i2);
                else if (d1 > d2) {
                    return -1;
                } else {
                    return 1;
                }
            } : (i1, i2) -> {
                double d1 = getPixel(sortImage, i1);
                double d2 = getPixel(sortImage, i2);
                if (d1==d2) return Integer.compare(i1, i2);
                else if (d1 < d2) {
                    return -1;
                } else {
                    return 1;
                }
            };
            coords = new IntRBTreeSet(comparator);
            //coords = new IntAVLTreeSet(comparator);
        }
        @Override
        public long pollFirst() {
            int f = coords.firstInt();
            coords.remove(f);
            return f;
        }
        @Override
        public boolean add(long coord) {
            return coords.add((int)coord);
        }
        public boolean add(int coord) {
            return coords.add(coord);
        }
        @Override public boolean addAll(long... coord) {
            boolean res = false;
            for (long c : coord) if (coords.add((int)c)) res = true;
            return res;
        }
        @Override public boolean addAll(CoordCollection coordCollection) {
            if (coordCollection.isEmpty()) return true;
            if (coordCollection instanceof AbstractCoordCollection2D) {
                return coords.addAll(((AbstractCoordCollection2D) coordCollection).getCoords());
            } else throw new IllegalArgumentException("Invalid coordset");
        }

        @Override
        public boolean isEmpty() {
            return coords.isEmpty();
        }
        @Override
        public boolean containsCoord(long coord) {
            return coords.contains((int)coord);
        }
        public boolean containsCoord(int coord) {
            return coords.contains(coord);
        }
        @Override
        public int size() {
            return coords.size();
        }

        @Override
        public void clear() {
            coords.clear();
        }

        @Override
        public void removeIf(LongPredicate filter) {
            coords.removeIf(filter::test);
        }

        @Override
        public LongStream stream() {
            return coords.intStream().asLongStream();
        }

        @Override
        public IntCollection getCoords() {
            return coords;
        }
        public String log(Image image) {
            return coords.intStream().limit(10).mapToObj(i -> "i="+i+ "v="+getPixel(image, i)).collect(Collectors.joining(";"));
        }
    }

    class SortedCoordSet3D extends AbstractCoordCollection3D implements SortedCoordSet {
        LongSortedSet coords;
        public SortedCoordSet3D(Image sortImage, boolean reverseOrder) {
            super(sortImage.sizeX(), sortImage.sizeY(), sortImage.sizeZ());
            // comparator using the image + coherence equals
            LongComparator comparator = reverseOrder ? (i1, i2) -> {
                double d1 = getPixel(sortImage, i1);
                double d2 = getPixel(sortImage, i2);
                if (d1 > d2) {
                    return -1;
                } else if (d1 < d2) {
                    return 1;
                } else return Long.compare(i1, i2);
            } : (i1, i2) -> {
                double d1 = getPixel(sortImage, i1);
                double d2 = getPixel(sortImage, i2);
                if (d1 < d2) {
                    return -1;
                } else if (d1 > d2) {
                    return 1;
                } else return Long.compare(i1, i2);
            };
            coords = new LongRBTreeSet(comparator);
            //coords = new LongAVLTreeSet(comparator);
        }
        @Override
        public long pollFirst() {
            long f = coords.firstLong();
            coords.remove(f);
            return f;
        }
        @Override
        public boolean add(long coord) {
            return coords.add(coord);
        }
        @Override public boolean addAll(long... coord) {
            boolean res = false;
            for (long c : coord) if (coords.add((int)c)) res = true;
            return res;
        }
        @Override public boolean addAll(CoordCollection coordCollection) {
            if (coordCollection instanceof AbstractCoordCollection3D) {
                return coords.addAll(((AbstractCoordCollection3D) coordCollection).getCoords());
            } else throw new IllegalArgumentException("Invalid coordset");
        }

        @Override
        public boolean isEmpty() {
            return coords.isEmpty();
        }
        @Override
        public boolean containsCoord(long coord) {
            return coords.contains(coord);
        }
        @Override
        public void clear() {
            coords.clear();
        }

        @Override
        public void removeIf(LongPredicate filter) {
            coords.removeIf(filter);
        }

        @Override
        public int size() {
            return coords.size();
        }

        @Override
        public LongStream stream() {
            return coords.longStream();
        }

        @Override
        public LongCollection getCoords() {
            return coords;
        }
    }

    static int indexedBinarySearch(IntList l, int key, IntComparator c) {
        int low = 0;
        int high = l.size() - 1;

        while(low <= high) {
            int mid = low + high >>> 1;
            int midVal = l.getInt(mid);
            int cmp = c.compare(midVal, key);
            if (cmp < 0) {
                low = mid + 1;
            } else {
                if (cmp <= 0) {
                    return mid;
                }

                high = mid - 1;
            }
        }

        return -(low + 1);
    }
    static int indexedBinarySearch(LongList l, long key, LongComparator c) {
        int low = 0;
        int high = l.size() - 1;

        while(low <= high) {
            int mid = low + high >>> 1;
            long midVal = l.getLong(mid);
            int cmp = c.compare(midVal, key);
            if (cmp < 0) {
                low = mid + 1;
            } else {
                if (cmp <= 0) {
                    return mid;
                }

                high = mid - 1;
            }
        }

        return -(low + 1);
    }
}
