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

import static bacmman.utils.Utils.parallele;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class HashMapGetCreate<K, V> extends HashMap<K, V> {
    Factory<K, V> factory;
    public HashMapGetCreate(Factory<K, V> factory) {
        super();
        this.factory=factory;
    }
    public HashMapGetCreate(int initialCapacity, Factory<K, V> factory) {
        super(initialCapacity);
        this.factory=factory;
    }
    /**
     * Enshure keys are present in the map
     * @param keys
     * @param parallele wheter values should be computed in parallele
     * @return the same map for convinience
     */
    public synchronized HashMapGetCreate<K, V> enshure(Set<K> keys, boolean parallele) {
        putAll(Utils.parallele(Sets.difference(keys, this.keySet()).stream(), parallele).collect(Collectors.toMap(k->k, k->factory.create(k))));
        return this;
    }
    public V getAndCreateIfNecessary(Object key) {
        V v = super.get(key);
        if (v==null) {
            v = factory.create((K)key);
            super.put((K)key, v);
        }
        return v;
    }
    public V getAndCreateIfNecessarySync(Object key) {
        V v = super.get(key);
        if (v==null) {
            synchronized(this) {
                v = super.get(key);
                if (v==null) {
                    v = factory.create((K)key);
                    super.put((K)key, v);
                }
            }
        }
        return v;
    }
    /**
     * synchronization is done on key so that if creation of V is long, the whole map is not blocked during creation of V
     * @param key
     * @return 
     */
    public V getAndCreateIfNecessarySyncOnKey(Object key) {
        V v = super.get(key);
        if (v==null) {
            synchronized(key) {
                v = super.get(key);
                if (v==null) {
                    v = factory.create((K)key);
                    synchronized(this){
                        super.put((K)key, v);
                    }
                }
            }
        }
        return v;
    }
    public static interface Factory<K, V> {
        public V create(K key);
    }
    public static class ArrayListFactory<K, V> implements Factory<K, ArrayList<V>>{
        @Override public ArrayList<V> create(K key) {
            return new ArrayList<>();
        }
    }
    public static class ListFactory<K, V> implements Factory<K, List<V>>{
        @Override public List<V> create(K key) {
            return new ArrayList<>();
        }
    }
    public static class SetFactory<K, V> implements Factory<K, Set<V>>{
        @Override public Set<V> create(K key) {
            return new HashSet<>();
        }
    }
    public static class MapFactory<K, L, V> implements Factory<K, Map<L, V>>{
        @Override public Map<L, V> create(K key) {
            return new HashMap<>();
        }
    }
    public static class HashMapGetCreateFactory<K, L, V> implements Factory<K, HashMapGetCreate<L, V>> {
        final Factory<L, V> factory;
        public HashMapGetCreateFactory(Factory<L, V> factory) {
            this.factory=factory;
        }
        @Override public HashMapGetCreate<L, V> create(K key) {
            return new HashMapGetCreate<>(factory);
        }
    }
    public enum Syncronization {NO_SYNC, SYNC_ON_KEY, SYNC_ON_MAP};
    public static <K, V> HashMapGetCreate<K, V> getRedirectedMap(Factory<K, V> factory, Syncronization sync) {
        switch(sync) {
            case NO_SYNC:
            default:
                return new HashMapGetCreateRedirected(factory);
            case SYNC_ON_KEY:
                return new HashMapGetCreateRedirectedSyncKey(factory);
            case SYNC_ON_MAP:
                return new HashMapGetCreateRedirectedSync(factory);
        }
    }
    public static <K, V> HashMapGetCreate<K, V> getRedirectedMap(int initialCapacity, Factory<K, V> factory, Syncronization sync) {
        switch(sync) {
            case NO_SYNC:
            default:
                return new HashMapGetCreateRedirected(initialCapacity, factory);
            case SYNC_ON_KEY:
                return new HashMapGetCreateRedirectedSyncKey(initialCapacity, factory);
            case SYNC_ON_MAP:
                return new HashMapGetCreateRedirectedSync(initialCapacity, factory);
        }
    }
    public static class HashMapGetCreateRedirected<K, V> extends HashMapGetCreate<K, V> {
        public HashMapGetCreateRedirected(Factory<K, V> factory) {
            super(factory);
        }
        public HashMapGetCreateRedirected(int initialCapacity, Factory<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return super.getAndCreateIfNecessary(key);
        }
    }
    public static class HashMapGetCreateRedirectedSyncKey<K, V> extends HashMapGetCreate<K, V> {
        public HashMapGetCreateRedirectedSyncKey(Factory<K, V> factory) {
            super(factory);
        }
        public HashMapGetCreateRedirectedSyncKey(int initialCapacity, Factory<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return super.getAndCreateIfNecessarySyncOnKey(key);
        }
    }
    public static class HashMapGetCreateRedirectedSync<K, V> extends HashMapGetCreate<K, V> {
        public HashMapGetCreateRedirectedSync(Factory<K, V> factory) {
            super(factory);
        }
        public HashMapGetCreateRedirectedSync(int initialCapacity, Factory<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return super.getAndCreateIfNecessarySync(key);
        }
    }
}
