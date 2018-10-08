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
 */
public class PointContainer4<T, U, V, W> extends PointContainer3<T, U, V> {
    protected W content4;
    public PointContainer4(T o1, U o2, V o3, W o4, float... coords) {
        super(o1, o2, o3, coords);
        this.content4= o4;
    }
    public PointContainer3<T, U, V> toPointContainer3() {
        return new PointContainer3(content1, content2, content3, this.coords);
    }
    public W getContent4() {
        return content4;
    }
    public <P extends PointContainer4<T, U, V, W>> P setContent4(W o) {
        this.content4 = o;
        return (P)this;
    }
    public static <T, U, V, W>  PointContainer4<T, U, V, W> fromPoint(Point p, T o, U o2, V o3, W o4) {
        return new PointContainer4(o, o2, o3, o4, p.coords);
    }
    @Override public String toString() {
        return super.toString() + "["+content4.toString()+"]";
    }
    /*@Override public PointContainer4<T, U, V, W> duplicate() {
        return new PointContainer4(duplicateContent(content1), duplicateContent(content2), duplicateContent(content3), duplicateContent(content4), Arrays.copyOf(coords, coords.length));
    }*/
}
