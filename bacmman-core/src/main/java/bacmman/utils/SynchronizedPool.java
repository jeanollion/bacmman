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

import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class SynchronizedPool<T> {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(SynchronizedPool.class);
    final Queue<T> queue = new LinkedList<>();
    final Supplier<T> factory;
    final UnaryOperator<T> reset;
    public SynchronizedPool(Supplier<T> factory) {
        this(factory, (UnaryOperator<T>) null);
    }
    public SynchronizedPool(Supplier<T> factory, UnaryOperator<T> reset) {
        this.factory=factory;
        this.reset=reset;
    }
    public SynchronizedPool(Supplier<T> factory, Consumer<T> reset) {
        this.factory=factory;
        this.reset=t -> {reset.accept(t);return t;};
    }
    public synchronized T pull() {
        T res = queue.poll();
        if (res==null) return factory.get();
        else if (reset!=null) res = reset.apply(res);
        return res;
    }
    public synchronized void push(T object) {
        queue.add(object);
        //logger.debug("queue size: {} (type: {})", queue.size(), object.getClass().getSimpleName());
    }
    public <U> U apply(Function<T, U> function) {
        T buffer = this.pull();
        U res = function.apply(buffer);
        this.push(buffer);
        return res;
    }

    public <U, E extends Throwable> U applyThrows(Utils.CheckedFunction<T, U, E> function) throws E {
        T buffer = this.pull();
        U res = function.apply(buffer);
        this.push(buffer);
        return res;
    }

    public void flush() {
        queue.clear();
    }
}


