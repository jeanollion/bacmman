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
package bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class FrameRangeLock {
    public final static Logger logger = LoggerFactory.getLogger(FrameRangeLock.class);
    private final SortedSet<FrameRange> locked = new TreeSet<>();
    private final Object lock = new Object();
    private final Map<FrameRange, Thread> blockingThreads = new HashMap<>();
    public Unlocker lock(FrameRange range)  {
        synchronized (lock) {
            if (locked.contains(range) && blockingThreads.get(range)==Thread.currentThread()) return () -> { }; // same thread already locked, it will unlock after
            while (!available(range)) {
                try {
                    lock.wait();
                } catch (InterruptedException ex) {
                    logger.error("lock error", ex);
                }
            }
            locked.add(range);
            blockingThreads.put(range, Thread.currentThread());
            return () -> {
                synchronized (lock) {
                    locked.remove(range);
                    blockingThreads.remove(range);
                    lock.notifyAll();
                }
            };
        }
    }

    private boolean available(FrameRange range) {
        SortedSet<FrameRange> tailSet = locked.tailSet(range);
        SortedSet<FrameRange> headSet = locked.headSet(range);
        Thread curThread= Thread.currentThread();
        return (tailSet.isEmpty() || ( blockingThreads.get(tailSet.first())==curThread || !tailSet.first().overlap(range) ))  
                && (headSet.isEmpty() || ( blockingThreads.get(headSet.last())==curThread || !headSet.last().overlap(range)));
    }

    public interface Unlocker {
        void unlock();
    }
}
