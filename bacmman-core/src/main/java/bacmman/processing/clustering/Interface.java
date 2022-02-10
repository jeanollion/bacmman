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
package bacmman.processing.clustering;

import java.util.Comparator;

/**
 *
 * @author Jean Ollion
 * @param <E> type of elements
 * @param <T> type of interface
 */
public interface Interface<E, T extends Interface<E, T>> extends Comparable<T> {
    E getE1();
    E getE2();
    boolean isInterfaceOf(E e1, E e2);
    boolean isInterfaceOf(E e1);
    E getOther(E e);
    
    void swichElements(E newE, E oldE, Comparator<? super E> elementComparator);
    void performFusion();
    boolean checkFusion();
    void fusionInterface(T otherInterface, Comparator<? super E> elementComparator);
    void updateInterface();
}
