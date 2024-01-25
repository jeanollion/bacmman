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
public class UnaryPair<E> extends Pair<E, E> {

    public UnaryPair(E e1, E e2) {
        super(e1, e2);
    }

    public UnaryPair<E> reverse() {
        return new UnaryPair<>(value, key);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Pair) {
            final Pair<?, ?> other = (Pair<?, ?>) obj;
            if (key!=null && value!=null) return key.equals(other.key) && value.equals(other.value);
            else if (key==null && value!=null) return other.key==null && value.equals(other.value);
            else if (key!=null) return other.key==null && key.equals(other.value);
            else return other.key==null && other.value==null;
        } else return false;
    }

    public boolean equalsSymmetrical(Object obj) {
        if (obj instanceof Pair) {
            final Pair<?, ?> other = (Pair<?, ?>) obj;
            if (key!=null && value!=null) return key.equals(other.key) && value.equals(other.value) || key.equals(other.value) && value.equals(other.key);
            else if (key==null && value!=null) return other.key==null && value.equals(other.value) || other.value==null && value.equals(other.key);
            else if (key!=null) return other.key==null && key.equals(other.value) || other.value==null && key.equals(other.key);
            else return other.key==null && other.value==null;
        } else return false;
    }
}
