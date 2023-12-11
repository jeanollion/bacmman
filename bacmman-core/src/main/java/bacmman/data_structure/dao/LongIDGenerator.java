package bacmman.data_structure.dao;

import bacmman.utils.HashMapGetCreate;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;
import java.util.stream.*;

public class LongIDGenerator implements Function<Integer, Long> {
    static Logger logger = LoggerFactory.getLogger(LongIDGenerator.class);
    Map<Integer, Counter> frameCounter = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(f -> new Counter(f, new int[0]));
    public LongIDGenerator() {

    }

    public LongIDGenerator(LongStream existingIds) {
        Supplier<Map<Integer, IntArrayList>> supplier = HashMap::new;
        ObjLongConsumer<Map<Integer, IntArrayList>> accumulator = (m, id) -> {
            int frame = (int)(id >> 32);
            IntArrayList l = m.get(frame);
            if (l == null) {
                l = new IntArrayList();
                m.put(frame, l);
            }
            l.add((int)id);
        };
        BiConsumer<Map<Integer, IntArrayList>, Map<Integer, IntArrayList>> combiner = (m1, m2) -> {
            m2.forEach((f, ids) -> {
                if (m1.containsKey(f)) m1.get(f).addAll(ids);
                else m1.put(f, ids);
            });
        };
        Map<Integer, IntArrayList> idsByFrame = existingIds.collect(supplier, accumulator, combiner);
        idsByFrame.forEach( (f, ids) -> {
            int[] idArray = ids.toIntArray();
            IntArrays.unstableSort(idArray);
            frameCounter.put(f, new Counter(f, idArray));
        });
    }

    @Override
    public Long apply(Integer frame) {
        return (long)frame << 32 | frameCounter.get(frame).next() & 0xFFFFFFFFL;
    }

    public void reset() {
        for (Counter c : frameCounter.values()) c.reset();
    }

    public static String toString(long id) {
        return (int)(id >> 32) + "-" + (int)id;
    }
    public static long toLong(int frame, int idx) {
        return (long)frame << 32 | idx & 0xFFFFFFFFL;
    }

    public static int getId(long id) {
        return (int)id;
    }

    public static int getFrame(long id) {
        return (int)(id >> 32);
    }

    static class Counter {
        int cursor, lastValue;
        final int frame;
        int[] existingIds;
        public Counter(int frame, int[] existingIds) {
            this.frame=frame;
            this.existingIds=existingIds;
            if (existingIds.length == 0)  {
                if (frame == 0) lastValue = 0; // 0 not allowed
                else lastValue = -1;
            } else {
                if (frame == 0) {
                    if (existingIds[0]>1) {
                        lastValue = 0;
                        cursor = -1;
                    } else lastValue = existingIds[0];
                } else {
                    if (existingIds[0]>0) {
                        lastValue = -1;
                        cursor = -1;
                    } else lastValue = existingIds[0];
                }
            }
        }
        public void reset() {
            if (frame == 0) lastValue = 0; // 0 not allowed
            else lastValue = -1;
        }
        public synchronized int next() {
            return asyncNext();
        }
        protected int asyncNext() {
            if (existingIds.length==0) return ++lastValue;
            if (cursor == -1) {
                if (lastValue<existingIds[0]-1) return ++lastValue;
                else {
                    lastValue = existingIds[++cursor];
                    return asyncNext();
                }
            } else if (cursor == existingIds.length-1) return ++lastValue;
            else if (existingIds[cursor] + 1 == existingIds[cursor+1]){
                while (cursor < existingIds.length-1 && existingIds[cursor]+1 == existingIds[cursor+1]) ++cursor;
                return lastValue = existingIds[cursor]+1;
            } else { // gap
                if (existingIds[cursor+1] > lastValue + 1) return ++lastValue;
                else {
                    lastValue = existingIds[++cursor];
                    return asyncNext();
                }
            }
        }
    }

   /* public static void main(String[] args) {
        int[] frames = new int[]{0, 0, 0, 0, 1, 1, 1};
        int[] ids = new int[] {2, 2, 5, 6, 5, 6, 7};
        LongStream s = IntStream.range(0, frames.length).mapToLong(i -> toLong(frames[i], ids[i]));
        LongIDGenerator gen = new LongIDGenerator(s);
        int[] id0F = new int[10];
        int[] id0ID = new int[10];
        int[] id1F = new int[10];
        int[] id1ID = new int[10];
        int[] id2F = new int[10];
        int[] id2ID = new int[10];
        long[] id0 = new long[10];
        long[] id1 = new long[10];
        long[] id2 = new long[10];
        for (int i = 0; i<10; ++i) {
            Long l0 = gen.apply(0);
            id0[i] = l0;
            id0F[i] = getFrame(l0);
            id0ID[i] = getId(l0);
            Long l1 = gen.apply(1);
            id1[i] = l1;
            id1F[i] = getFrame(l1);
            id1ID[i] = getId(l1);
            Long l2 = gen.apply(2);
            id2[i] = l2;
            id2F[i] = getFrame(l2);
            id2ID[i] = getId(l2);
        }
        logger.debug("frame {} ids {}", frames, ids);
        logger.debug("frame 0: frames: {} ids: {}, long id {}", id0F, id0ID, id0);
        logger.debug("frame 1: frames: {} ids: {}, long id {}", id1F, id1ID, id1);
        logger.debug("frame 2: frames: {} ids: {}, long id: {}", id2F, id2ID, id2);
    }*/
}
