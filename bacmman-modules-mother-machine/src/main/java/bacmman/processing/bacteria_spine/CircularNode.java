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

import bacmman.utils.geom.GeomUtils;
import bacmman.utils.geom.Point;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import net.imglib2.RealLocalizable;

/**
 *
 * @author Jean Ollion
 */
public class CircularNode<T> implements Comparable<CircularNode> {

    
    CircularNode<T> prev, next;
    T element;
    public CircularNode(T element) {
        this.element = element;
    }

    public T getElement() {
        return element;
    }

    public void setElement(T element) {
        this.element = element;
    }
    
    public void setPrev(CircularNode<T> prev) {
        this.prev = prev;
        prev.next= this;
    }
    public void setNext(CircularNode<T> next) {
        this.next = next;
        next.prev= this;
    }
    public CircularNode<T> setPrev(T element) {
        this.prev = new CircularNode(element);
        this.prev.next = this;
        return this.prev;
    }
    public CircularNode<T> insertPrev(T element) {
        CircularNode<T> old = prev;
        CircularNode<T> newN = setPrev(element);
        newN.setPrev(old);
        return newN;
    }
    public CircularNode<T> setNext(T element) {
        this.next = new CircularNode(element);
        this.next.prev = this;
        return this.next;
    }
    public CircularNode<T> insertNext(T element) {
        CircularNode<T> old = next;
        CircularNode<T> newN = setNext(element);
        newN.setNext(old);
        return newN;
    }
    public CircularNode<T> next() {
        return next;
    }
    public CircularNode<T> prev() {
        return prev;
    }
    public CircularNode<T> getFollowing(boolean next) {
        return next ? this.next : prev;
    }
    public CircularNode<T> getInFollowing(T element, boolean next) {
        return next ? getInNext(element) : getInPrev(element);
    }
    public CircularNode<T> getInNext(T element) {
        if (element==null) return null;
        if (element.equals(this.element)) return this;
        CircularNode<T> search = this.next;
        while(!search.equals(this)) {
            if (element.equals(search.element)) return search;
            search = search.next;
        }
        return null;
    }
    /**
     * Idem as getFollowing but searching in other direction
     * @param element
     * @return 
     */
    public CircularNode<T> getInPrev(T element) {
        if (element==null) return null;
        if (element.equals(this.element)) return this;
        CircularNode<T> search = this.prev;
        while(!search.equals(this)) {
            if (element.equals(search.element)) return search;
            search = search.prev;
        }
        return null;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.element);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final CircularNode<?> other = (CircularNode<?>) obj;
        if (!Objects.equals(this.element, other.element)) {
            return false;
        }
        return true;
    }

    @Override
    public int compareTo(CircularNode o) {
        if (this.equals(o)) return 0;
        if (this.prev.equals(o)) return 1;
        if (this.next.equals(o)) return -1;
        CircularNode p = o.prev;
        CircularNode n = o.next;
        while(!p.equals(n) || this.equals(p)) { // second condition if searched value is at the exaxt opposite of the contour
            if (this.equals(p)) return -1;
            if (this.equals(n)) return 1;
            p = p.prev;
            n = n.next;
        }
        throw new IllegalArgumentException("Circular Node not from the same list");
    }
    @Override
    public String toString() {
        return element.toString();
    }
    // HELPER METHOD
    public static <T> void apply(CircularNode<T> circContour, Consumer<CircularNode<T>> func, boolean next) {
        func.accept(circContour);
        if (next) {
            CircularNode<T> n = circContour.next;
            while(circContour!=n) {
                func.accept(n);
                n = n.next;
                if (n==null) return;
            }
        } else {
            CircularNode<T> p = circContour.prev;
            while(circContour!=p) {
                func.accept(p);
                p = p.prev;
                if (p==null) return;
            }
        }
    }
    public static <U, V> CircularNode<V> map(CircularNode<U> source, Function<U, V> mapper) {
        CircularNode<V> firstDest = new CircularNode(mapper.apply(source.element));
        CircularNode<U> currentSource = source.next();
        CircularNode<V> currentDest=firstDest;
        while(!currentSource.equals(source)) {
            currentDest = currentDest.setNext(mapper.apply(currentSource.getElement()));
            currentSource = currentSource.next();
        }
        currentDest.setNext(firstDest); // closes circular contour
        return firstDest;
    }
    public static <T> CircularNode<T> toCircularContour(List<T> orderedList) {
        CircularNode<T> first = new CircularNode(orderedList.get(0));
        CircularNode<T> previous=first;
        for (int i = 0; i<orderedList.size();++i) previous = previous.setNext(orderedList.get(i));
        previous.setNext(first); // closes contour
        return first;
    }
    // HELPER METHOD WITH LOCALIZABLE ELEMENTS
    /**
     * Local min distance search from {@param ref} starting from {@param firstSearchPoint}
     * @param ref
     * @param firstSearchPoint
     * @param bucket receive 2 closest point or only one if the 2 other neighbors have same distance
     */
    static <T extends RealLocalizable> void addTwoLocalNearestPoints(Point ref, CircularNode<T> firstSearchPoint, List<CircularNode<T>> bucket) {
        bucket.clear();
        Function<RealLocalizable, Double> dist = (RealLocalizable v) -> ref.distSq(v);
        double dMin = dist.apply(firstSearchPoint.element);
        CircularNode<T> p = firstSearchPoint.prev();
        CircularNode<T> n = firstSearchPoint.next();
        double dMinP = dist.apply(p.element);
        double dMinN = dist.apply(n.element);
        // if both are inferior -> put both points.
        if (dMinP < dMin && dMinN < dMin) {
            bucket.add(p);
            bucket.add(n);
        } else if (dMinP < dMin) {
            // search in prev direction
            while (dMinP < dMin) {
                dMin = dMinP;
                p = p.prev();
                dMinP = dist.apply(p.element);
            }
            p = p.next(); // local min
            bucket.add(p);
            dMinN = dist.apply(p.next().element);
            if (dMinN < dMinP) {
                bucket.add(p.next());
            } else if (dMinP < dMinN) {
                bucket.add(p.prev());
            }
        } else if (dMinN < dMin) {
            // search in next direction
            while (dMinN < dMin) {
                dMin = dMinN;
                n = n.next();
                dMinN = dist.apply(n.element);
            }
            n = n.prev(); // local min
            bucket.add(n);
            dMinP = dist.apply(n.prev().element);
            if (dMinP < dMinN) {
                bucket.add(n.prev());
            } else if (dMinN < dMinP) {
                bucket.add(n.next());
            }
        } else {
            bucket.add(firstSearchPoint);
            if (dMinP < dMinN) {
                bucket.add(p);
            } else if (dMinP > dMinN) {
                bucket.add(n);
            }
        }
    }
    public static <T extends RealLocalizable> CircularNode<T> searchForFirstCloseElement(RealLocalizable ref, double distanceSqThld, CircularNode<T> start, boolean searchInNext, boolean searchInPrev) {
        if (!searchInPrev && !searchInNext) throw new IllegalArgumentException("Search either in next or in prev");
        ToDoubleFunction<T> dist = (T elem) -> GeomUtils.distSq(ref, elem);
        if (dist.applyAsDouble(start.element)<distanceSqThld) return start;
        if (searchInPrev && searchInNext) {
            CircularNode<T> n = start.next;
            CircularNode<T> p = start.prev;
            while(n!=start) {
                if (dist.applyAsDouble(n.element)<distanceSqThld) return n;
                if (dist.applyAsDouble(p.element)<distanceSqThld) return p;
                n = n.next;
                p = p.prev;
            }
        } else if (searchInNext) {
            CircularNode<T> n = start.next;
            while(n!=start) {
                if (dist.applyAsDouble(n.element)<distanceSqThld) return n;
                n = n.next;
            }
        } else if (searchInPrev) {
            CircularNode<T> p = start.prev;
            while(p!=start) {
                if (dist.applyAsDouble(p.element)<distanceSqThld) return p;
                p = p.prev;
            }
        }
        return null; // not found
    }
    public static <T> CircularNode<T> getMiddlePoint(CircularNode<T> p1, CircularNode<T> p2, boolean firstNext) {
        if (!firstNext) return getMiddlePoint(p2, p1, true);
        while(!p1.equals(p2)) {
            p1 = p1.next();
            if (p1.equals(p2)) return p1;
            p2 = p2.prev();
        }
        return p1;
    }
}
