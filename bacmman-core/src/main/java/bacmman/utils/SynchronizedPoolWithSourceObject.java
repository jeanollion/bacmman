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

import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 *
 * @author Jean Ollion
 */
public class SynchronizedPoolWithSourceObject<T, S> {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(SynchronizedPoolWithSourceObject.class);
    protected final Queue<Pair<S, T>> queue = new LinkedList<>();
    protected final Function<S, T> factory;
    protected final BiFunction<S, T, T> reset;
    protected boolean search;
    public SynchronizedPoolWithSourceObject(Function<S, T> factory, BiFunction<S, T, T> reset, boolean search) {
        this.factory=factory;
        this.reset=reset;
        this.search=search;
    }
    public synchronized T poll(S sourceObject) {
        Pair<S, T> res = search&&sourceObject!=null ? searchInQueue(sourceObject) : null;
        if (res==null) res = queue.poll();
        if (res==null) return factory.apply(sourceObject);
        else if (reset!=null) return reset.apply(sourceObject, res.value);
        return res.value;
    }
    public synchronized void push(T object, S source) {
        queue.add(new Pair(source, object));
        //logger.debug("queue size: {} (type: {})", queue.size(), object.getClass().getSimpleName());
    }
    protected Pair<S, T> searchInQueue(S source) {
        Iterator<Pair<S, T>> it = queue.iterator();
        while(it.hasNext()) {
            Pair<S, T> p = it.next();
            if (source.equals(p.key)) {
                it.remove();
                //logger.debug("Queue search: found {} for {}, queue size: {} (type: {})", p.value, source, queue.size(), p.value.getClass().getSimpleName());
                return p;
            }
        }
        return null;
    }
}
