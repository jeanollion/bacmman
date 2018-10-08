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
public class PointContainer2<T, U> extends PointContainer<T> {
    protected U content2;
    public PointContainer2(T o1, U o2, float... coords) {
        super(o1, coords);
        this.content2= o2;
    }
    public PointContainer<T> toPointContainer() {
        return new PointContainer(content1, this.coords);
    }
    public U getContent2() {
        return content2;
    }
    public <P extends PointContainer2<T, U>> P setContent2(U o) {
        this.content2 = o;
        return (P)this;
    }
    public static <T, U>  PointContainer2<T, U> fromPoint(Point p, T o, U o2) {
        return new PointContainer2(o, o2, p.coords);
    }
    @Override public String toString() {
        return super.toString() + "["+content2.toString()+"]";
    }
    /*@Override public PointContainer2<T, U> duplicate() {
        return new PointContainer2(duplicateContent(content1), duplicateContent(content2), Arrays.copyOf(coords, coords.length));
    }*/
}
