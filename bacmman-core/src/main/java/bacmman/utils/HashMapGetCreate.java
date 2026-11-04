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

import com.google.common.collect.Sets;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Map that creates values on demand with a factory ({@link #getAndCreateIfNecessary}, or {@link #get} for the "redirected" versions).
 * Implementations:
 * <ul>
 *     <li>{@link HashMapGetCreate}, {@link HashMapGetCreateRedirected} (NO_SYNC): values are stored in a {@link HashMap}: not thread-safe (except the explicitly synchronized methods {@link #getAndCreateIfNecessarySync} / {@link #getAndCreateIfNecessarySyncOnKey}, which lock the map)</li>
 *     <li>{@link HashMapGetCreateRedirectedSyncKey} (SYNC_ON_KEY): values are stored in a {@link ConcurrentHashMap}: reads are lock-free, each value is created only once, outside of any lock, so that values of different keys can be created in parallel</li>
 *     <li>{@link HashMapGetCreateRedirectedSync} (SYNC_ON_MAP): values are stored in a {@link ConcurrentHashMap}: reads are lock-free, values are created one at a time (the factory is never called concurrently)</li>
 * </ul>
 * Null values behave the same in all versions: if null values are not allowed (default), a factory returning null is called again at the next access, and putting a null value removes the key; if they are allowed ({@link #setAllowNullValues}), they are stored. In synchronized versions, null values are stored internally as a placeholder, which is never visible from outside (get, views, iteration...).
 * Synchronized versions do not accept null keys ({@link NullPointerException}), as {@link ConcurrentHashMap}.
 * @author Jean Ollion
 */
public class HashMapGetCreate<K, V> implements Map<K, V> {
    protected final Function<K, V> factory;
    protected final Map<K, V> map;
    protected boolean allowNull = false;
    public HashMapGetCreate(Function<K, V> factory) {
        this(new HashMap<>(), factory);
    }
    public HashMapGetCreate(int initialCapacity, Function<K, V> factory) {
        this(new HashMap<>(Math.max(1, initialCapacity)), factory);
    }
    protected HashMapGetCreate(Map<K, V> map, Function<K, V> factory) {
        this.map = map;
        this.factory = factory;
    }
    /**
     * @param allow if true, null values (e.g. returned by the factory) are stored, otherwise the factory is called again at the next access and putting a null value removes the key
     */
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
    /**
     * Get-or-create. Not thread-safe in this implementation, thread-safe in synchronized implementations
     */
    public V getAndCreateIfNecessary(Object key) {
        if (allowNull && map.containsKey(key)) return map.get(key);
        V v = allowNull ? null : map.get(key);
        if (v==null) {
            v = factory.apply((K)key);
            map.put((K)key, v);
        }
        return v;
    }
    /**
     * Thread-safe get-or-create, values are created one at a time. In this implementation the whole method is performed under the lock of the map (other accesses to the map are not synchronized)
     */
    public V getAndCreateIfNecessarySync(Object key) {
        synchronized (this) {
            return getAndCreateIfNecessary(key);
        }
    }
    /**
     * Thread-safe get-or-create. In this implementation: same as {@link #getAndCreateIfNecessarySync}. In {@link HashMapGetCreateRedirectedSyncKey}: values of different keys are created in parallel
     */
    public V getAndCreateIfNecessarySyncOnKey(Object key) {
        return getAndCreateIfNecessarySync(key);
    }

    // Map methods: forwarded to the internal map
    @Override public int size() { return map.size(); }
    @Override public boolean isEmpty() { return map.isEmpty(); }
    @Override public boolean containsKey(Object key) { return map.containsKey(key); }
    @Override public boolean containsValue(Object value) { return map.containsValue(value); }
    @Override public V get(Object key) { return map.get(key); }
    @Override public V put(K key, V value) { return map.put(key, value); }
    @Override public V remove(Object key) { return map.remove(key); }
    @Override public void putAll(Map<? extends K, ? extends V> m) { map.putAll(m); }
    @Override public void clear() { map.clear(); }
    @Override public Set<K> keySet() { return map.keySet(); }
    @Override public Collection<V> values() { return map.values(); }
    @Override public Set<Entry<K, V>> entrySet() { return map.entrySet(); }
    @Override public V getOrDefault(Object key, V defaultValue) { return map.getOrDefault(key, defaultValue); }
    @Override public void forEach(BiConsumer<? super K, ? super V> action) { map.forEach(action); }
    @Override public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) { map.replaceAll(function); }
    @Override public V putIfAbsent(K key, V value) { return map.putIfAbsent(key, value); }
    @Override public boolean remove(Object key, Object value) { return map.remove(key, value); }
    @Override public boolean replace(K key, V oldValue, V newValue) { return map.replace(key, oldValue, newValue); }
    @Override public V replace(K key, V value) { return map.replace(key, value); }
    @Override public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) { return map.computeIfAbsent(key, mappingFunction); }
    @Override public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) { return map.computeIfPresent(key, remappingFunction); }
    @Override public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) { return map.compute(key, remappingFunction); }
    @Override public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) { return map.merge(key, value, remappingFunction); }
    @Override public boolean equals(Object o) { return o == this || map.equals(o); }
    @Override public int hashCode() { return map.hashCode(); }
    @Override public String toString() { return map.toString(); }

    /**
     * Base of synchronized implementations: values are stored in a {@link ConcurrentHashMap} (reads are lock-free). Null values are stored as a placeholder (allowed null values) or not stored, null keys are rejected.
     */
    public static abstract class ConcurrentHashMapGetCreate<K, V> extends HashMapGetCreate<K, V> {
        private static final Object NULL_VALUE = new Object(); // placeholder for null values
        protected ConcurrentHashMapGetCreate(int initialCapacity, Function<K, V> factory) {
            super(new ConcurrentHashMap<>(Math.max(1, initialCapacity)), factory);
        }
        @SuppressWarnings("unchecked")
        protected V mask(V v) { return v == null ? (V) NULL_VALUE : v; }
        protected V unmask(V v) { return v == NULL_VALUE ? null : v; }
        /** @return value to store for {@param v}, or null if nothing should be stored */
        protected V toStore(V v) { return v != null ? v : (allowNull ? mask(null) : null); }
        /** thread-safe get-or-create, specific to each implementation */
        protected abstract V create(Object key);
        @Override public V getAndCreateIfNecessary(Object key) { return create(key); }
        @Override public V getAndCreateIfNecessarySync(Object key) { return create(key); }
        @Override public V getAndCreateIfNecessarySyncOnKey(Object key) { return create(key); }
        @Override public HashMapGetCreate<K, V> ensure(Set<K> keys, boolean parallel) {
            Utils.parallel(keys.stream(), parallel).forEach(this::create);
            return this;
        }

        @Override public boolean containsValue(Object value) { return map.containsValue(value == null ? NULL_VALUE : value); }
        @Override public V get(Object key) { return unmask(map.get(key)); }
        @Override public V put(K key, V value) {
            V st = toStore(value);
            return unmask(st == null ? map.remove(key) : map.put(key, st));
        }
        @Override public V remove(Object key) { return unmask(map.remove(key)); }
        @Override public void putAll(Map<? extends K, ? extends V> m) { m.forEach(this::put); }
        @Override public Collection<V> values() {
            Collection<V> values = map.values();
            return new AbstractCollection<V>() {
                @Override public Iterator<V> iterator() {
                    Iterator<V> it = values.iterator();
                    return new Iterator<V>() {
                        @Override public boolean hasNext() { return it.hasNext(); }
                        @Override public V next() { return unmask(it.next()); }
                        @Override public void remove() { it.remove(); }
                    };
                }
                @Override public int size() { return values.size(); }
                @Override public boolean contains(Object o) { return ConcurrentHashMapGetCreate.this.containsValue(o); }
                @Override public void clear() { values.clear(); }
            };
        }
        @Override public Set<Entry<K, V>> entrySet() {
            Set<Entry<K, V>> entries = map.entrySet();
            return new AbstractSet<Entry<K, V>>() {
                @Override public Iterator<Entry<K, V>> iterator() {
                    Iterator<Entry<K, V>> it = entries.iterator();
                    return new Iterator<Entry<K, V>>() {
                        @Override public boolean hasNext() { return it.hasNext(); }
                        @Override public Entry<K, V> next() {
                            Entry<K, V> e = it.next();
                            return new Entry<K, V>() {
                                @Override public K getKey() { return e.getKey(); }
                                @Override public V getValue() { return unmask(e.getValue()); }
                                @Override public V setValue(V value) {
                                    V st = toStore(value);
                                    if (st == null) throw new NullPointerException("Null values are not allowed");
                                    return unmask(e.setValue(st));
                                }
                                @Override public boolean equals(Object o) {
                                    if (!(o instanceof Entry)) return false;
                                    Entry<?, ?> oe = (Entry<?, ?>) o;
                                    return Objects.equals(getKey(), oe.getKey()) && Objects.equals(getValue(), oe.getValue());
                                }
                                @Override public int hashCode() { return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue()); }
                                @Override public String toString() { return getKey() + "=" + getValue(); }
                            };
                        }
                        @Override public void remove() { it.remove(); }
                    };
                }
                @Override public int size() { return entries.size(); }
                @Override public void clear() { entries.clear(); }
            };
        }
        @Override public V getOrDefault(Object key, V defaultValue) {
            V v = map.get(key);
            return v == null ? defaultValue : unmask(v);
        }
        @Override public void forEach(BiConsumer<? super K, ? super V> action) { map.forEach((k, v) -> action.accept(k, unmask(v))); }
        @Override public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            map.replaceAll((k, v) -> {
                V st = toStore(function.apply(k, unmask(v)));
                if (st == null) throw new NullPointerException("Null values are not allowed");
                return st;
            });
        }
        @Override public V putIfAbsent(K key, V value) {
            V st = toStore(value);
            if (st == null) return get(key);
            return unmask(map.putIfAbsent(key, st));
        }
        @Override public boolean remove(Object key, Object value) { return map.remove(key, value == null ? NULL_VALUE : value); }
        @Override public boolean replace(K key, V oldValue, V newValue) {
            V st = toStore(newValue);
            if (st == null) return false;
            return map.replace(key, mask(oldValue), st);
        }
        @Override public V replace(K key, V value) {
            V st = toStore(value);
            if (st == null) return containsKey(key) ? remove(key) : null;
            return unmask(map.replace(key, st));
        }
        // compute methods: as in the Map contract, a null result removes the key
        @Override public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) { return unmask(map.computeIfAbsent(key, mappingFunction)); }
        @Override public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) { return unmask(map.computeIfPresent(key, (k, v) -> remappingFunction.apply(k, unmask(v)))); }
        @Override public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) { return unmask(map.compute(key, (k, v) -> remappingFunction.apply(k, unmask(v)))); }
        @Override public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) { return unmask(map.merge(key, value, (v1, v2) -> remappingFunction.apply(unmask(v1), v2))); }
        @Override public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof Map)) return false;
            return entrySet().equals(((Map<?, ?>) o).entrySet());
        }
        @Override public int hashCode() { return entrySet().hashCode(); }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("{");
            Iterator<Entry<K, V>> it = entrySet().iterator();
            while (it.hasNext()) { sb.append(it.next()); if (it.hasNext()) sb.append(", "); }
            return sb.append('}').toString();
        }
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
            return getAndCreateIfNecessary(key);
        }
    }
    /**
     * Synchronized implementation, synchronization on key: reads are lock-free, each value is created once (other threads requesting the same key wait for it), outside of any lock of the map, so that values of different keys can be created in parallel
     */
    public static class HashMapGetCreateRedirectedSyncKey<K, V> extends ConcurrentHashMapGetCreate<K, V> {
        private final Map<Object, FutureTask<V>> pending = new ConcurrentHashMap<>(); // values being created (one task per key)
        public HashMapGetCreateRedirectedSyncKey(Function<K, V> factory) {
            super(16, factory);
        }
        public HashMapGetCreateRedirectedSyncKey(int initialCapacity, Function<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return create(key);
        }
        @Override
        protected V create(Object key) {
            Objects.requireNonNull(key, "Synchronized map does not accept null keys");
            V v = map.get(key);
            if (v != null) return unmask(v);
            FutureTask<V> task = new FutureTask<>(() -> { // result: value as stored in the map (placeholder for an allowed null value), or null if nothing is stored
                V existing = map.get(key); // may have been created by a task that completed meanwhile
                return existing != null ? existing : toStore(factory.apply((K) key));
            });
            FutureTask<V> other = pending.putIfAbsent(key, task);
            if (other != null) return unmask(result(other));
            try {
                task.run();
                v = result(task);
                if (v != null) map.put((K) key, v);
                return unmask(v);
            } finally {
                pending.remove(key, task);
            }
        }
        private static <V> V result(FutureTask<V> task) {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        return task.get();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException e) {
                        Throwable c = e.getCause();
                        if (c instanceof RuntimeException) throw (RuntimeException) c;
                        if (c instanceof Error) throw (Error) c;
                        throw new RuntimeException(c);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
    /**
     * Synchronized implementation, synchronization on map: reads are lock-free, values are created one at a time (the factory is never called concurrently)
     */
    public static class HashMapGetCreateRedirectedSync<K, V> extends ConcurrentHashMapGetCreate<K, V> {
        private final Object creationLock = new Object();
        public HashMapGetCreateRedirectedSync(Function<K, V> factory) {
            super(16, factory);
        }
        public HashMapGetCreateRedirectedSync(int initialCapacity, Function<K, V> factory) {
            super(initialCapacity, factory);
        }
        @Override
        public V get(Object key) {
            return create(key);
        }
        @Override
        protected V create(Object key) {
            Objects.requireNonNull(key, "Synchronized map does not accept null keys");
            V v = map.get(key);
            if (v != null) return unmask(v);
            synchronized (creationLock) {
                v = map.get(key);
                if (v != null) return unmask(v);
                v = factory.apply((K) key);
                V st = toStore(v);
                if (st != null) map.put((K) key, st);
                return v;
            }
        }
    }
}
