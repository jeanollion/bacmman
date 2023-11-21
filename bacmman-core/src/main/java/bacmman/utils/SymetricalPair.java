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

/**
 *
 * @author Jean Ollion
 */
public class SymetricalPair<E> extends Pair<E, E> {

    public SymetricalPair(E e1, E e2) {
        super(e1, e2);
    }

    public SymetricalPair<E> reverse() {
        return new SymetricalPair<>(value, key);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Pair) {
            final Pair<?, ?> other = (Pair<?, ?>) obj;
            if (key!=null && value!=null) return key.equals(other.key) && value.equals(other.value) || key.equals(other.value) && value.equals(other.key);
            else if (key==null && value!=null) return other.key==null && value.equals(other.value) || other.value==null && value.equals(other.key);
            else if (value==null && key!=null) return other.key==null && key.equals(other.value) || other.value==null && key.equals(other.key);
            else return other.key==null && other.value==null;
        } else return false;
    }

    @Override
    public int hashCode() {
        return (this.key != null ? this.key.hashCode() : 0) ^ (this.value != null ? this.value.hashCode() : 0);
    }
}
