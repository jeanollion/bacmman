package bacmman.utils;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

/**
 * Behavior of {@link HashMapGetCreate}: non-synchronized version, and thread-safety of the synchronized versions
 */
public class HashMapGetCreateTest {
    static final int THREADS = 8;

    static void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t<threads; ++t) futures.add(ex.submit(() -> { start.await(); task.run(); return null; }));
        start.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        ex.shutdown();
    }

    @Test
    public void testNoSync() {
        AtomicInteger calls = new AtomicInteger();
        HashMapGetCreate<Integer, Integer> m = new HashMapGetCreate<>(k -> { calls.incrementAndGet(); return k * 2; });
        assertNull("plain get does not create", m.get(1));
        assertEquals(Integer.valueOf(2), m.getAndCreateIfNecessary(1));
        assertEquals(Integer.valueOf(2), m.getAndCreateIfNecessary(1));
        assertEquals(1, calls.get());
        assertTrue(m.containsKey(1));
        Map<Integer, Integer> r = new HashMapGetCreate.HashMapGetCreateRedirected<>(k -> k + 1);
        assertEquals(Integer.valueOf(4), r.get(3));
        assertEquals(1, r.size());
        // null values: allowed and stored only if requested
        AtomicInteger nullCalls = new AtomicInteger();
        HashMapGetCreate<Integer, Integer> n = new HashMapGetCreate<Integer, Integer>(k -> { nullCalls.incrementAndGet(); return null; }).setAllowNullValues(true);
        assertNull(n.getAndCreateIfNecessary(1));
        assertNull(n.getAndCreateIfNecessary(1));
        assertEquals("null value stored", 1, nullCalls.get());
        assertTrue(n.containsKey(1));
    }

    @Test
    public void testSyncNullValuesAndKeys() {
        for (HashMapGetCreate.Syncronization sync : new HashMapGetCreate.Syncronization[]{HashMapGetCreate.Syncronization.SYNC_ON_KEY, HashMapGetCreate.Syncronization.SYNC_ON_MAP}) {
            AtomicInteger calls = new AtomicInteger();
            HashMapGetCreate<Integer, Integer> m = HashMapGetCreate.getRedirectedMap((Integer k) -> { calls.incrementAndGet(); return null; }, sync);
            assertNull(m.get(1));
            assertNull(m.get(1));
            assertEquals(sync + ": null value not stored: factory called again", 2, calls.get());
            assertFalse(m.containsKey(1));
            assertTrue(m.isEmpty());
            m.put(2, 4);
            assertEquals(Integer.valueOf(4), m.get(2));
            m.put(2, null);
            assertFalse(sync + ": putting null removes the key", m.containsKey(2));
            for (Runnable r : new Runnable[]{() -> m.get(null), () -> m.getAndCreateIfNecessary(null), () -> m.put(null, 1), () -> m.containsKey(null)}) {
                try {
                    r.run();
                    fail(sync + ": null keys must be rejected");
                } catch (NullPointerException e) { }
            }
            // allowed null values: stored once, visible as null everywhere
            AtomicInteger nullCalls = new AtomicInteger();
            HashMapGetCreate<Integer, Integer> n = HashMapGetCreate.getRedirectedMap((Integer k) -> { nullCalls.incrementAndGet(); return k==1 ? null : k; }, sync).setAllowNullValues(true);
            assertNull(n.get(1));
            assertNull(n.get(1));
            assertEquals(sync + ": allowed null value stored: factory called once", 1, nullCalls.get());
            assertTrue(n.containsKey(1));
            assertTrue(n.containsValue(null));
            assertEquals(Integer.valueOf(2), n.get(2));
            assertTrue(sync + ": values() shows null", n.values().contains(null) && n.values().stream().allMatch(v -> v==null || v==2));
            for (Map.Entry<Integer, Integer> e : n.entrySet()) assertEquals(e.getKey()==1 ? null : e.getKey(), e.getValue());
            n.forEach((k, v) -> assertEquals(k==1 ? null : k, v));
            Map<Integer, Integer> ref = new HashMap<>(); ref.put(1, null); ref.put(2, 2);
            assertEquals(sync + ": equals a HashMap with the same content", ref, n);
            assertEquals(ref.hashCode(), n.hashCode());
            assertNull(n.put(3, null));
            assertTrue(n.containsKey(3));
            assertNull(n.remove(3));
            assertFalse(n.containsKey(3));
        }
    }

    /** SYNC_ON_KEY: each value is created once, and values of different keys are created in parallel */
    @Test
    public void testSyncOnKey() throws Exception {
        int nKeys = 200;
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        Map<Integer, Object> m = HashMapGetCreate.getRedirectedMap((Integer k) -> { calls.computeIfAbsent(k, kk -> new AtomicInteger()).incrementAndGet(); return new Object(); }, HashMapGetCreate.Syncronization.SYNC_ON_KEY);
        Map<Integer, Set<Object>> seen = new ConcurrentHashMap<>();
        runConcurrently(THREADS, () -> {
            for (int rep = 0; rep<20; ++rep) for (int k = 0; k<nKeys; ++k) seen.computeIfAbsent(k, kk -> ConcurrentHashMap.newKeySet()).add(m.get(k));
        });
        assertEquals(nKeys, m.size());
        for (int k = 0; k<nKeys; ++k) {
            assertEquals("value created once for key "+k, 1, calls.get(k).get());
            assertEquals("all threads got the same value for key "+k, 1, seen.get(k).size());
        }
        // parallel creation of slow values
        int slowKeys = THREADS, sleep = 200;
        Map<Integer, Integer> slow = HashMapGetCreate.getRedirectedMap((Integer k) -> { try { Thread.sleep(sleep); } catch (InterruptedException e) { } return k; }, HashMapGetCreate.Syncronization.SYNC_ON_KEY);
        AtomicInteger next = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        runConcurrently(THREADS, () -> slow.get(next.getAndIncrement()));
        long time = System.currentTimeMillis() - t0;
        assertEquals(slowKeys, slow.size());
        assertTrue("values of different keys should be created in parallel: "+time+" ms for "+slowKeys+" x "+sleep+" ms", time < slowKeys * sleep / 2);
    }

    /** SYNC_ON_MAP: each value is created once, and the factory is never called concurrently */
    @Test
    public void testSyncOnMap() throws Exception {
        int nKeys = 100;
        AtomicInteger running = new AtomicInteger(), maxRunning = new AtomicInteger();
        Map<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();
        Function<Integer, Object> factory = k -> {
            int r = running.incrementAndGet();
            maxRunning.accumulateAndGet(r, Math::max);
            calls.computeIfAbsent(k, kk -> new AtomicInteger()).incrementAndGet();
            try { Thread.sleep(1); } catch (InterruptedException e) { }
            running.decrementAndGet();
            return new Object();
        };
        Map<Integer, Object> m = HashMapGetCreate.getRedirectedMap(factory, HashMapGetCreate.Syncronization.SYNC_ON_MAP);
        runConcurrently(THREADS, () -> { for (int k = 0; k<nKeys; ++k) m.get(k); });
        assertEquals(nKeys, m.size());
        assertEquals("factory must not be called concurrently", 1, maxRunning.get());
        for (int k = 0; k<nKeys; ++k) assertEquals(1, calls.get(k).get());
    }

    /** synchronized versions: iteration while other threads create values does not fail, and direct calls to getAndCreateIfNecessary are thread-safe */
    @Test
    public void testIterationDuringCreation() throws Exception {
        HashMapGetCreate<Integer, Integer> m = HashMapGetCreate.getRedirectedMap((Integer k) -> k, HashMapGetCreate.Syncronization.SYNC_ON_KEY);
        AtomicInteger next = new AtomicInteger();
        runConcurrently(THREADS, () -> {
            for (int i = 0; i<2000; ++i) {
                m.getAndCreateIfNecessary(next.getAndIncrement());
                int sum = 0;
                for (Integer v : m.values()) sum += v;
                assertTrue(sum>=0);
            }
        });
        assertEquals(THREADS * 2000, m.size());
        assertTrue(IntStream.range(0, THREADS * 2000).allMatch(m::containsKey));
    }
}
