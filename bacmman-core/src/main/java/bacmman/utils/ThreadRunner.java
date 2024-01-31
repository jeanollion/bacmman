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

import bacmman.data_structure.Processor;
import bacmman.core.ProgressCallback;
import net.imagej.ops.Ops;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
/**
Copyright (C) Jean Ollion

License:
This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

public class ThreadRunner {
    public final static Logger logger = LoggerFactory.getLogger(ThreadRunner.class);
    /** Start all given threads and wait on each of them until all are done.
     * From Stephan Preibisch's Multithreading.java class. See:
     * http://repo.or.cz/w/trakem2.git?a=blob;f=mpi/fruitfly/general/MultiThreading.java;hb=HEAD
     */

    /* To initiate Threads:
    final ThreadRunner tr = new ThreadRunner(0, sizeZ, multithread?0:1);
    for (int i = 0; i<tr.threads.length; i++) {
        tr.threads[i] = new Thread(
            new Runnable() {
                public void run() {
                    for (int idx = tr.ai.getAndIncrement(); idx<tr.end; idx = tr.ai.getAndIncrement()) {

                    }
                }
            }
        );
    } 
    tr.startAndJoin();

     * 
     */
    public final int start, end;
    public final Thread[] threads;
    public final AtomicInteger ai;
    public final List<Pair<String, Throwable>> errors = new ArrayList<>();
    public ThreadRunner(int start, int end) {
        this(start, end, 0);
    }
    
    public int size() {
        return this.threads.length;
    }
    public static boolean leaveOneCPUFree = true;
    /**
     * 
     * @param start inclusive
     * @param end exclusive 
     * @param cpulimit 
     */
    public ThreadRunner(int start, int end, int cpulimit) {
        this.start=start;
        this.end=end;
        this.ai= new AtomicInteger(this.start);
        int nb = getNbCpus();
        if (cpulimit>0 && nb>cpulimit) {
            nb=cpulimit;
            if (leaveOneCPUFree && nb==getNbCpus() && nb>1) nb--;
        }
       
        this.threads = new Thread[nb];
        
    }

    public void startAndJoin() {
        Map<Integer, Throwable> err = startAndJoin(threads);
        for (Entry<Integer, Throwable> er : err.entrySet()) errors.add(new Pair<>("Thread #"+er.getKey(), er.getValue() instanceof Exception ? (Exception)er.getValue() : new RuntimeException(er.getValue())));
    }
    
    public void throwErrorIfNecessary(String message) throws Exception {
        if (!errors.isEmpty()) {
            throw new MultipleException(errors);
            //throw new RuntimeException(message +"Errors in #"+ errors.size()+" threads. Throwing one error", errors.iterator().next().value);
        }
    }
    
    protected static Map<Integer, Throwable> startAndJoin(Thread[] threads) {
        Map<Integer, Throwable> res = new HashMap<>();
        Thread.UncaughtExceptionHandler h = (Thread th, Throwable ex) -> {
            res.put(Arrays.asList(threads).indexOf(th), ex);
        };
        for (int ithread = 0; ithread < threads.length; ++ithread) {
            threads[ithread].setPriority(Thread.NORM_PRIORITY);
            threads[ithread].setUncaughtExceptionHandler(h);
            threads[ithread].start();
        }

        try {
            for (int ithread = 0; ithread < threads.length; ++ithread) {
                threads[ithread].join();
            }
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
        return res;
    }
    

    public void resetAi(){
        ai.set(start);
    }
    
    private int getNbCpus() {
        return Math.max(1, Math.min(getMaxCPUs(), end-start));
    }
    
    public static int getMaxCPUs() {
        return Runtime.getRuntime().availableProcessors();
    }
    public static <T> void execute(final T[] array, final boolean setToNull, final ThreadAction<T> action) {
        execute(array, setToNull, action, null, null);
    }
    public static <T> void execute(final T[] array, final boolean setToNull, final ThreadAction<T> action, ProgressCallback pcb) {
        execute(array, setToNull, action, null, pcb);
    }
    
    public static <T> void execute(T[] array, final boolean setToNull, final ThreadAction<T> action, ExecutorService executor, ProgressCallback pcb) {
        if (array==null) return;
        if (array.length==0) return;
        if (array.length==1) {
            action.run(array[0], 0);
            return;
        }
        if (executor==null) executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        CompletionService<Pair<String, Throwable>> completion = new ExecutorCompletionService<>(executor);
        final List<Pair<String, Throwable>> errors = new ArrayList<>();
        int idx=0;
        for (T e : array) {
            final int i = idx;
            completion.submit(()->{
                try {
                    action.run(e, i);
                } catch (Throwable ex) {
                    return new Pair(e.toString(), ex);
                }
                return null;
            });
            if (setToNull) array[idx]=null;
            ++idx;
        }
        if (pcb!=null) pcb.setSubtaskNumber(idx);
        for (int i = 0; i<idx; ++i) {
            try {
                Pair<String, Throwable> e = completion.take().get();
                if (e!=null) {
                    if (e.value instanceof MultipleException) errors.addAll(((MultipleException)e.value).getExceptions());
                    else errors.add(e);
                }
            } catch (InterruptedException|ExecutionException ex) {
                errors.add(new Pair("Execution exception: "+array[i], ex));
            } finally {
                if (pcb!=null) pcb.incrementSubTask();
            }

        }
        if (!errors.isEmpty()) throw new MultipleException(errors);
    }
    public static <T> void execute(Collection<T> array, boolean removeElements, final ThreadAction<T> action) {
        execute(array, removeElements, action, null, null);
    }
    public static <T> void execute(Collection<T> array, boolean removeElements, final ThreadAction<T> action, ProgressCallback pcb) {
        execute(array, removeElements, action, null, pcb);
    }
    public static <T> void execute(Collection<T> array, boolean removeElements, final ThreadAction<T> action,  ExecutorService executor, ProgressCallback pcb) {
        if (array==null) return;
        if (array.isEmpty()) return;
        if (array.size()==1) {
            action.run(array.iterator().next(), 0);
            return;
        }
        if (executor==null) executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        CompletionService<Pair<String, Throwable>> completion = new ExecutorCompletionService<>(executor);
        final List<Pair<String, Throwable>> errors = new ArrayList<>();
        int count=0;
        Iterator<T> it = array.iterator();
        while(it.hasNext()) {
            T e = it.next();
            final int i = count;
            completion.submit(()->{
                try {
                    //if (pcb!=null) pcb.log("will run process: "+i+" -> "+e);
                    action.run(e, i);
                    //if (pcb!=null) pcb.log("has run process: "+i+" -> "+e);
                } catch (Throwable ex) {
                    //logger.debug("error on: "+e, ex);
                    return new Pair(e.toString(), ex);
                } 
                return null;
            });
            if (removeElements) it.remove();
            ++count;
        }
        if (pcb!=null) pcb.incrementTaskNumber(count);
        for (int i = 0; i<count; ++i) {
            try {
                Pair<String, Throwable> e = completion.take().get();
                if (e!=null) {
                    if (e.value instanceof MultipleException) errors.addAll(((MultipleException)e.value).getExceptions());
                    else errors.add(e);
                }
            } catch (InterruptedException|ExecutionException ex) {
                errors.add(new Pair("Execution exception:"+Utils.getElementAt(array, i), ex));
            }
            if (pcb!=null) pcb.incrementProgress();
        }
        if (!errors.isEmpty()) {
            //throw new Error(errors.get(0).value);
            Processor.logger.error("MultipleException: "+errors.get(0).key, errors.get(0).value);
            throw new MultipleException(errors);
        }
    }
    public static <T> void executeAndThrowErrors(Stream<T> stream, Consumer<T> action) {
        MultipleException e = new MultipleException();
        stream.forEach(t -> {
            try {
                action.accept(t);
            } catch (MultipleException me) {
                synchronized(e) {e.addExceptions(me.getExceptions());}
            } catch(Throwable ex) {
                synchronized(e) {e.addExceptions(new Pair<>(toString(t), ex));}
            }
        });
        if (!e.isEmpty()) throw e;
    }
    public static <K, V> Stream<V> safeMap(Stream<K> stream, Function<K, V> mapper) {
        Function<K, V> mapper2 = t -> {
            try {
                return mapper.apply(t);
            } catch(Exception ex) {
                throw new TR_RuntimeException(ex);
            }
        };
        return stream.map(mapper2);
    }
    public static class TR_RuntimeException extends RuntimeException {
        public TR_RuntimeException(Exception ex) {
            super(ex);
        }
    }
    private static <T> String toString(T t) {
        if (t instanceof Collection) return ((Collection)t).iterator().next().toString(); // only one element
        else if (t instanceof Object[]) return ((Object[])t)[0].toString();
        else return t.toString();
    }
    public static interface ThreadAction<T> {
        public void run(T object, int idx);
    }
    
    public static ProgressCallback loggerProgressCallback(final org.slf4j.Logger logger) {
        return new ProgressCallback() {
            int subTask = 0;
            int taskCount = 0;
            double subTaskNumber = 0;
            double subTaskCount = 0;
            @Override
            public synchronized void incrementTaskNumber(int subtask) {
                this.subTask+=subtask;
            }

            @Override
            public void setSubtaskNumber(int number) {
                subTaskNumber = number;
                subTaskCount = 0;
            }

            @Override
            public synchronized void incrementSubTask() {
                ++subTaskCount;
                logger.debug("Current: {}/{}, subtask: {}/{}", taskCount, subTask, subTaskCount, subTaskNumber);
            }

            @Override
            public void incrementProgress() {
                subTaskCount = 0;
                logger.debug("Current: {}/{}", ++taskCount, subTask);
            }

            @Override
            public void log(String message) {
                logger.debug(message);
            }
        };
    }
    public static PriorityThreadFactory priorityThreadFactory(int priority) {return new PriorityThreadFactory(priority);}
    public static class PriorityThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final int priority;
        public PriorityThreadFactory(int priority) {
            if (priority<Thread.MIN_PRIORITY) priority=Thread.MIN_PRIORITY;
            if (priority>Thread.MAX_PRIORITY) priority=Thread.MAX_PRIORITY;
            this.priority=priority;
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() :
                                  Thread.currentThread().getThreadGroup();
            namePrefix = "pool-" +
                          poolNumber.getAndIncrement() +
                         "-thread-";
        }
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r,
                                  namePrefix + threadNumber.getAndIncrement(),
                                  0);
            if (t.isDaemon())
                t.setDaemon(false);
            if (t.getPriority() != priority)
                t.setPriority(priority);
            return t;
        }
    }
    
    public static void executeUntilFreeMemory(Runnable r) {
        executeUntilFreeMemory(r, 10);
    }
    public static void executeUntilFreeMemory(Runnable r, int maxTryouts) {
        int idx = 0;
        OutOfMemoryError outOfMemoryError=null;
        while(idx++<maxTryouts) {
            try {
                r.run();
                if (idx>1) Processor.logger.debug("ok");
                return;
            } catch (OutOfMemoryError e) {
                outOfMemoryError=e;
                try {
                    Processor.logger.debug("R: sleeping until free memory available... Free: {}/{} GB", (Runtime.getRuntime().freeMemory()/1000000)/1000d, (Runtime.getRuntime().totalMemory()/1000000)/1000d);
                    System.gc();
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        }
        throw outOfMemoryError;
    }
    public static <T> T executeUntilFreeMemory(Supplier<T> s) {
        return executeUntilFreeMemory(s, 10);
    }
    public static <T> T executeUntilFreeMemory(Supplier<T> r, int maxTryouts) {
        int idx = 0;
        OutOfMemoryError outOfMemoryError=null;
        while(idx++<maxTryouts) {
            try {
                T t = r.get();
                if (idx>1) Processor.logger.debug("ok");
                return t;
            } catch (OutOfMemoryError e) {
                outOfMemoryError=e;
                try {
                    Processor.logger.debug("S: sleeping until free memory available... Free: {}/{} GB", Runtime.getRuntime().freeMemory()/1000000d, Runtime.getRuntime().totalMemory()/1000000d);
                    System.gc();
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    return null;
                }
            }
        }
        throw outOfMemoryError;
    }
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            
        }
    }

    public static void parallelExecutionBySegments(IntConsumer action, int minIdx, int maxIdxExcl, int window, IntConsumer segmentEnd) {
        int n = (maxIdxExcl - minIdx) / window;
        double r = (maxIdxExcl - minIdx) % window;
        if (r>=window/2. || n==0) ++n;
        for (int s = 0;s<n; ++s) {
            int min = s*window + minIdx;
            int max = s==n-1 ? maxIdxExcl :  (s+1) * window + minIdx;
            logger.debug("parallel ex by segment: [{}; {}) / [{}; {})", min, max, minIdx, maxIdxExcl);
            IntStream.range(min, max).parallel().forEach(action);
            if (segmentEnd!=null) segmentEnd.accept(s);
        }
    }
    public static void parallelExecutionBySegments(IntConsumer action, List<Integer> indices, int window, IntConsumer segmentEnd) {
        int n = indices.size() / window;
        double r = indices.size() % window;
        if (r>=window/2. || n==0) ++n;
        for (int s = 0;s<n; ++s) {
            int min = s*window;
            int max = s==n-1 ? indices.size() :  (s+1) * window;
            logger.debug("parallel ex by segment: [{}; {}) / [{}; {})", min, max, 0, indices.size());
            IntStream.range(min, max).parallel().mapToObj(indices::get).mapToInt(i->i).forEach(action);
            if (segmentEnd!=null) segmentEnd.accept(s);
        }
    }
    public static <T> List<T> parallelExecutionBySegmentsFunction(Function<Integer, T> action, int minIdx, int maxIdxExcl, int window, boolean multithreadPerSegment) {
        return parallelExecutionBySegmentsFunction(action, IntStream.range(minIdx, maxIdxExcl).boxed().collect(Collectors.toList()), window, multithreadPerSegment);
    }

    public static <T> List<T> parallelExecutionBySegmentsFunction(Function<Integer, T> action, Collection<Integer> indices, int window, boolean multithreadPerSegment) {
        int n = indices.size() / window;
        double r = indices.size() % window;
        if (r>=window/2. || n==0) ++n;
        List<Integer> indiceList = indices instanceof List ? (List<Integer>)indices : new ArrayList<>(indices);
        if (multithreadPerSegment) {
            List<T> res = new ArrayList<>();
            for (int s = 0;s<n; ++s) {
                int min = s*window;
                int max = s==n-1 ? indiceList.size() :  (s+1) * window;
                logger.debug("parallel ex by segment: [{}; {}) / [{}; {})", min, max, 0, indiceList.size());
                res.addAll(IntStream.range(min, max).parallel().mapToObj(indiceList::get).map(action).collect(Collectors.toList()));
            }
            return res;
        } else {
            List<T>[] allLists = new List[n];
            IntStream.range(0, n).boxed().parallel().forEach(s -> {
                int min = s*window;
                int max = s==allLists.length-1 ? indiceList.size() :  (s+1) * window;
                logger.debug("parallel ex by segment: [{}; {}) / [{}; {})", min, max, 0, indiceList.size());
                allLists[s] = IntStream.range(min, max).mapToObj(indiceList::get).map(action).collect(Collectors.toList());
            });
            return Arrays.stream(allLists).flatMap(Collection::stream).collect(Collectors.toList());
        }
    }

    public static <T> Stream<T> parallelStreamBySegment(IntFunction<T> mapper, int minIdx, int maxIdxExcl, int window, IntConsumer segmentEnd) {
        int n = (maxIdxExcl - minIdx) / window;
        double r = (maxIdxExcl - minIdx) % window;
        if (r>=window/2. || n==0) ++n;
        int n_ = n;
        return IntStream.range(0, n).boxed().flatMap( s -> {
            int min = s*window + minIdx;
            int max = s==n_-1 ? maxIdxExcl :  (s+1) * window + minIdx;
            return IntStream.range(min, max).parallel().mapToObj(mapper).onClose(()->segmentEnd.accept(s));
        });
    }
}
