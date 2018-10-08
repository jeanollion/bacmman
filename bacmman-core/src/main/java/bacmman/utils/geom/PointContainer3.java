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
public class PointContainer3<T, U, V> extends PointContainer2<T, U> {
    protected V content3;
    public PointContainer3(T o1, U o2, V o3, float... coords) {
        super(o1, o2, coords);
        this.content3= o3;
    }
    public PointContainer2<T, U> toPointContainer2() {
        return new PointContainer2(content1, content2, this.coords);
    }
    public V getContent3() {
        return content3;
    }
    public <P extends PointContainer3<T, U, V>> P setContent3(V o) {
        this.content3 = o;
        return (P)this;
    }
    public static <T, U, V>  PointContainer3<T, U, V> fromPoint(Point p, T o, U o2, V o3) {
        return new PointContainer3(o, o2, o3, p.coords);
    }
    @Override public String toString() {
        return super.toString() + "["+content3.toString()+"]";
    }
    /*@Override public PointContainer3<T, U, V> duplicate() {
        return new PointContainer3(duplicateContent(content1), duplicateContent(content2), duplicateContent(content3), Arrays.copyOf(coords, coords.length));
    }*/
}
