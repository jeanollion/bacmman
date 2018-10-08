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
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class ReusableQueue<T> {
    //public static final org.slf4j.Logger logger = LoggerFactory.getLogger(ReusableQueue.class);
    final Queue<T> queue = new LinkedList<>();
    final Factory<T> factory;
    final Reset<T> reset;
    public ReusableQueue(Factory<T> factory, Reset<T> reset) {
        this.factory=factory;
        this.reset=reset;
    }
    public synchronized T pull() {
        T res = queue.poll();
        if (res==null) return factory.create();
        else if (reset!=null) res = reset.reset(res);
        return res;
    }
    public synchronized void push(T object) {
        queue.add(object);
        //logger.debug("queue size: {} (type: {})", queue.size(), object.getClass().getSimpleName());
    }
    public static interface Factory<T> {
        public T create();
    }
    public static interface Reset<T> {
        public T reset(T object);
    }
}
