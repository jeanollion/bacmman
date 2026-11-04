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

import static bacmman.utils.Utils.parallel;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class HashMapGetCreate<K, V> extends HashMap<K, V> {
    Function<K, V> factory;
    boolean allowNull = false;
    public HashMapGetCreate(Function<K, V> factory) {
        super();
        this.factory=factory;
    }
    public HashMapGetCreate(int initialCapacity, Function<K, V> factory) {
        super(initialCapacity);
        this.factory=factory;
    }
    public HashMapGetCreate<K, V> setAllowNullValues(boolean allow) {
        this.allowNull = allow;
        return this;
    }
    /**
     * Ensure keys are present in the map
     * @param keys
     * @param parallel whether values should be computed in parallel
     * @return the same map for convenience
     */
    public synchronized HashMapGetCreate<K, V> ensure(Set<K> keys, boolean parallel) {
        putAll(Utils.parallel(Sets.difference(keys, this.keySet()).stream(), parallel).collect(Collectors.toMap(Function.identity(), k->factory.apply(k))));
        return this;
    }
    public V getAndCreateIfNecessary(Object key) {
        if (allowNull && super.containsKey(key)) return super.get(key);
        V v = allowNull ? null : super.get(key);
        if (v==null) {
            v = factory.apply((K)key);
            super.put((K)key, v);
        }
        return v;
    }
    /**
     * Thread-safe get-or-create: all accesses to the map are performed under the lock of the map, and the value is created under this lock (the whole map is blocked during the creation of the value)
     */
    public synchronized V getAndCreateIfNecessarySync(Object key) {
        if (allowNull) {
            if (super.containsKey(key)) return super.get(key);
        } else {
            V v = super.get(key);
            if (v != null) return v;
        }
        V v = factory.apply((K) key);
        super.put((K) key, v);
        return v;
    }
    /**
     * Thread-safe get-or-create. Synchronization of the creation is done on key so that if creation of V is long, the whole map is not blocked during creation of V: values of different keys can be created in parallel.
     * All reads and writes of the map are performed under the lock of the map (a HashMap must not be read while it is modified by another thread: a read during a modification, e.g. a resize, can miss a present key, and without lock the value created by another thread is not guaranteed to be visible)
     * @param key
     * @return 
     */
    public V getAndCreateIfNecessarySyncOnKey(Object key) {
        if (key == null) return getAndCreateIfNecessarySync(null);
        V v = getIfPresentSync(key);
        if (v != null || (allowNull && containsKeySync(key))) return v;
        synchronized (key) {
            v = getIfPresentSync(key);
            if (v != null || (allowNull && containsKeySync(key))) return v;
            v = factory.apply((K) key);
            synchronized (this) {
                super.put((K) key, v);
            }
            return v;
        }
    }
    private synchronized V getIfPresentSync(Object key) {
        return super.get(key);
    }
    private synchronized boolean containsKeySync(Object key) {
        return super.containsKey(key);
    }


    public static class ArrayListFactory<K, V> implements Function<K, ArrayList<V>>{
        @Override public ArrayList<V> apply(K key) {
            return new ArrayList<>();
        }
    }
    public static class ListFactory<K, V> implements Function<K, List<V>>{
        @Override public List<V> apply(K key) {
            return new ArrayList<>();
        }
    }
    public static class SetFactory<K, V> implements Function<K, Set<V>>{
        @Override public Set<V> apply(K key) {
            return new HashSet<>();
        }
    }
    public static class MapFactory<K, L, V> implements Function<K, Map<L, V>>{
        @Override public Map<L, V> apply(K key) {
            return new HashMap<>();
        }
    }
    public static class HashMapGetCreateFactory<K, L, V> implements Function<K, HashMapGetCreate<L, V>> {
        final Function<L, V> factory;
        public HashMapGetCreateFactory(Function<L, V> factory) {
            this.factory=factory;
        }
        @Override public HashMapGetCreate<L, V> apply(K key) {
            return new HashMapGetCreate<>(factory);
        }
    }
    public enum Syncronization {NO_SYNC, SYNC_ON_KEY, SYNC_ON_MAP};
    public static <K, V> HashMapGetCreate<K, V> getRedirectedMap(Function<K, V> factory, Syncronization sync) {
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
    public static <K, V> HashMapGetCreate<K, V> getRedirectedMap(int initialCapacity, Function<K, V> factory, Syncronization sync) {
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
    public static <K, V> HashMapGetCreate<K, V> getRedirectedMap(Stream<K> keys, Function<K, V> factory, Syncronization sync) {
        Map<K, V> map = keys.collect(Collectors.toMap(Function.identity(), factory));
        HashMapGetCreate<K, V> res = getRedirectedMap(map.size(), factory, sync);
        res.putAll(map);
        return res;
    }

    public static <O, K, V> HashMapGetCreate<K, V> getRedirectedMap(Stream<O> keys, Function<O, K> keyMapper, Function<K, O> keyMapperRev,  Function<O, V> factory, Syncronization sync) {
        Map<K, V> map = keys.collect(Collectors.toMap(keyMapper, factory));
        Function<K, V> factory2 = k->factory.apply(keyMapperRev.apply(k));
        HashMapGetCreate<K, V> res = getRedirectedMap(map.size(), factory2, sync);
        res.putAll(map);
        return res;
    }

    public static class HashMapGetCreateRedirected<K, V> extends HashMapGetCreate<K, V> {
        public HashMapGetCreateRedirected(Function<K, V> factory) {
            super(factory);
        }
        public HashMapGetCreateRedirected(int initialCapacity, Function<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return super.getAndCreateIfNecessary(key);
        }
    }
    public static class HashMapGetCreateRedirectedSyncKey<K, V> extends HashMapGetCreate<K, V> {
        public HashMapGetCreateRedirectedSyncKey(Function<K, V> factory) {
            super(factory);
        }
        public HashMapGetCreateRedirectedSyncKey(int initialCapacity, Function<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return super.getAndCreateIfNecessarySyncOnKey(key);
        }
    }
    public static class HashMapGetCreateRedirectedSync<K, V> extends HashMapGetCreate<K, V> {
        public HashMapGetCreateRedirectedSync(Function<K, V> factory) {
            super(factory);
        }
        public HashMapGetCreateRedirectedSync(int initialCapacity, Function<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return super.getAndCreateIfNecessarySync(key);
        }
    }
}
