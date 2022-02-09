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
package bacmman.utils;

import java.util.Objects;

/**
 *
 * @author Jean Ollion
 */
public class Triplet<A, B, C> {
    public A v1;
    public B v2;
    public C v3;
    public Triplet(A v1, B v2, C v3) {
        this.v1=v1;
        this.v2 = v2;
        this.v3 = v3;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 47 * hash + Objects.hashCode(this.v1);
        hash = 47 * hash + Objects.hashCode(this.v2);
        hash = 47 * hash + Objects.hashCode(this.v3);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Triplet<?, ?, ?> other = (Triplet<?, ?, ?>) obj;
        if (!Objects.equals(this.v1, other.v1)) {
            return false;
        }
        if (!Objects.equals(this.v2, other.v2)) {
            return false;
        }
        if (!Objects.equals(this.v3, other.v3)) {
            return false;
        }
        return true;
    }
    @Override 
    public String toString() {
        return "{"+(v1==null?"null":v1.toString())+";"+(v2==null?"null":v2.toString())+";"+(v3==null?"null":v3.toString())+"}";
    }
    public Pair<A, B> extractAB() {
        return new Pair<>(v1, v2);
    }
    public Pair<B, C> extractBC() {
        return new Pair<>(v2, v3);
    }
    public Pair<A, C> extractAC() {
        return new Pair<>(v1, v3);
    }
}
