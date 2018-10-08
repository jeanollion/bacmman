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

import java.util.Collection;
import java.util.Comparator;

/**
 *
 * @author Jean Ollion
 */
public abstract class InterfaceImpl<E, T extends Interface<E, T>> implements Interface<E, T> {
        protected E e1, e2;
        
        public InterfaceImpl(E e1, E e2, Comparator<? super E> elementComparator) {
            setElements(e1, e2, elementComparator);
        }
        
        private void setElements(E e1, E e2, Comparator<? super E> elementComparator) {
            if (elementComparator!=null) {
                if (elementComparator.compare(e1, e2)<=0) {
                    this.e1=e1;
                    this.e2=e2;
                } else {
                    this.e2=e1;
                    this.e1=e2;
                }
            } else {
                this.e1=e1;
                this.e2=e2;
            }
        }
        
        public E getE1() {return e1;}
        public E getE2() {return e2;}
        
        /*protected void fusionInterfaceSetElements(Interface<E, T> otherInterface, Comparator<? super E> elementComparator)  {
            E com = getCommonElement(otherInterface);
            if (com==null) throw new IllegalArgumentException("No common elements in "+this+" and "+otherInterface+" cannot merge");
            E o1 = getOther(com);
            E o2 = otherInterface.getOther(com);
            setElements(o1, o2, elementComparator);
        }*/
        
        protected int compareElements(T otherInterface, Comparator<? super E> elementComparator) {
            int c = elementComparator.compare(e1, otherInterface.getE1());
            if (c==0) return elementComparator.compare(e2, otherInterface.getE2());
            return c;
        }
        
        public void swichElements(E newE, E oldE, Comparator<? super E> elementComparator) { // need to call update sort value
            if (e1==oldE) setElements(newE, e2, elementComparator);
            else if (e2==oldE) setElements(e1, newE, elementComparator);
            else throw new IllegalArgumentException("Element: "+oldE+" not found in "+this);
        }
        
        public E getOther(E e) {
            if (e==e1) return e2;
            else if (e==e2) return e1;
            else return null;
        }
        
        public boolean isInterfaceOf(E e1, E e2) {
            return this.e1==e1 && this.e2==e2 || this.e1==e2 && this.e2==e1;
        }
        
        public boolean isInterfaceOf(E e1) {
            return this.e1==e1 || this.e2==e1;
        }
        
        public E getCommonElement(Interface<E, T> other) {
            if (e1==other.getE1() || e1==other.getE2()) return e1;
            else if (e2==other.getE1() || e2==other.getE2()) return e2;
            else return null;
        }
        
        public boolean hasOneRegionWithNoOtherInteractant(ClusterCollection<E, T> c) {
            Collection<T> l1 = c.interfaceByElement.get(e1);
            if (l1==null || l1.isEmpty() || (l1.size()==1 && l1.contains(this))) return true;
            Collection<T> l2 = c.interfaceByElement.get(e2);
            return (l2==null || l2.isEmpty() || (l2.size()==1 && l2.contains(this)) );
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 37 * hash + (this.e1 != null ? this.e1.hashCode() : 0);
            hash = 37 * hash + (this.e2 != null ? this.e2.hashCode() : 0);
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
            final InterfaceImpl other = (InterfaceImpl) obj;
            if (this.e1 != other.e1 && (this.e1 == null || !this.e1.equals(other.e1))) {
                return false;
            }
            if (this.e2 != other.e2 && (this.e2 == null || !this.e2.equals(other.e2))) {
                return false;
            }
            return true;
        }

    }
