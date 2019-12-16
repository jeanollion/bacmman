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
package bacmman.utils.geom;


/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public class PointContainer<T> extends Point {
    public T content1;
    public PointContainer(T o, float... coords) {
        super(coords);
        this.content1= o;
    }
    public T getContent1() {
        return content1;
    }
    public <P extends PointContainer<T>> P setContent1(T o) {
        this.content1 = o;
        return (P)this;
    }
    public static <I>  PointContainer<I> fromPoint(Point p, I o) {
        return new PointContainer(o, p.coords);
    }
    @Override public String toString() {
        return super.toString() + "["+content1.toString()+"]";
    }
    /**
     * Content will be duplicated only if it is a vector or a point, if not same instance will be set to the result
     * @return 
     */
    /*@Override public PointContainer<T> duplicate() {
        return new PointContainer(duplicateContent(content1), Arrays.copyOf(coords, coords.length));
    }
    protected static <T> T duplicateContent(T content) {
        if (content instanceof Point) return (T)((Point)content).duplicate();
        return content;
    }*/
}
