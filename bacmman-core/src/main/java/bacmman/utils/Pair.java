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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author Jean Ollion
 */
public class Pair<K, V> {
    public K key;
    public V value;
    public Pair(K key, V value) {
        this.key=key;
        this.value=value;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 29 * hash + (this.key != null ? this.key.hashCode() : 0);
        hash = 29 * hash + (this.value != null ? this.value.hashCode() : 0);
        return hash;
    }
    private static boolean objectEquals(Object o1, Object o2) {
        if (o1.getClass()!=o2.getClass()) return false;
        if (o1.getClass().isArray()) {
            if (o1 instanceof int[]) return Arrays.equals((int[])o1, (int[])o2);
            else if (o1 instanceof long[]) return Arrays.equals((long[])o1, (long[])o2);
            else if (o1 instanceof char[]) return Arrays.equals((char[])o1, (char[])o2);
            else if (o1 instanceof double[]) return Arrays.equals((double[])o1, (double[])o2);
            else if (o1 instanceof float[]) return Arrays.equals((float[])o1, (float[])o2);
            else if (o1 instanceof short[]) return Arrays.equals((short[])o1, (short[])o2);
            else if (o1 instanceof byte[]) return Arrays.equals((byte[])o1, (byte[])o2);
            else return Arrays.equals((Object[])o1, (Object[])o2);
        } else return o1.equals(o2);
    }
    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (getClass() == obj.getClass()) {
            final Pair<?, ?> other = (Pair<?, ?>) obj;
            if (key!=null && other.key!=null && !objectEquals(key, other.key)) return false;
            return !(value!=null && other.value!=null && !objectEquals(value, other.value));
        } else return false;
    }
    @Override 
    public String toString() {
        return "{"+(key==null?"null":key.toString())+"->"+(value==null?"null":value.toString())+"}";
    }
    public static <K, V> List<V> unpairValues(Collection<? extends Pair<K, V>> list) {
        if (list == null) {
            return null;
        }
        List<V> res = new ArrayList<>(list.size());
        for (Pair<K, V> p : list) {
            if (p.value != null) {
                res.add(p.value);
            }
        }
        return res;
    }

    public static <K, V> List<K> unpairKeys(Collection<? extends Pair<K, V>> list) {
        if (list == null) {
            return null;
        }
        List<K> res = new ArrayList<K>(list.size());
        for (Pair<K, V> p : list) {
            if (p.key != null) {
                res.add(p.key);
            }
        }
        return res;
    }
    public static <K> Collection<K> flatten(Collection<? extends Pair<K, K>> list, Collection<K> output) {
        if (list == null) return null;
        if (output==null) output= new HashSet<K>(list.size());
        for (Pair<K, K> p : list) {
            if (p.key != null) output.add(p.key);
            if (p.value!=null) output.add(p.value);
        }
        return output;
    }
    public static <K> List<Pair<K, ?>> pairAsKeys(Collection<K> list) {
        if (list == null) {
            return null;
        }
        List<Pair<K, ?>> res = new ArrayList<Pair<K, ?>>(list.size());
        for (K k : list) res.add(new Pair(k, null));
        return res;
    }
    public static <V> List<Pair<?, V>> pairAsValues(Collection<V> list) {
        if (list == null) {
            return null;
        }
        List<Pair<?, V>> res = new ArrayList<Pair<?, V>>(list.size());
        for (V v : list) res.add(new Pair(null, v));
        return res;
    }
    public static <K, V> Map<K, Set<V>> toMap(Collection<Pair<K, V>> pairs) {
        HashMapGetCreate<K, Set<V>> res = new HashMapGetCreate(new HashMapGetCreate.SetFactory<>());
        for (Pair<K, V> p : pairs) res.getAndCreateIfNecessary(p.key).add(p.value);
        return res;
    }
    public static <K> Map<K, Set<K>> toMapSym(Collection<? extends Pair<K, K>> pairs) {
        HashMapGetCreate<K, Set<K>> res = new HashMapGetCreate(new HashMapGetCreate.SetFactory<>());
        for (Pair<K, K> p : pairs) {
            res.getAndCreateIfNecessary(p.key).add(p.value);
            res.getAndCreateIfNecessary(p.value).add(p.key);
        }
        return res;
    }
    public static <T> T getOther(Pair<T, T> pair, T value) {
        if (pair.key.equals(value)) return pair.value;
        else return pair.key;
    }
}
