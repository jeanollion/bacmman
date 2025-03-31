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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;

/**
 *
 * @author Jean Ollion
 */
public class MultipleException extends RuntimeException {
    public final static Logger logger = LoggerFactory.getLogger(MultipleException.class);
    final private List<Pair<String, Throwable>> exceptions; // localized execption: string = internal source of exception (position, structure object etc...)
    public MultipleException(List<Pair<String, Throwable>> exceptions) {
        this.exceptions= new ArrayList<>();
        addExceptions(exceptions);
    }
    public MultipleException() {
        this.exceptions=new ArrayList<>();
    }
    public void addExceptions(Pair<String, Throwable>... ex) {
        if (ex==null || ex.length==0) return;
        if (ex.length == 1) addException(ex[0]);
        else addExceptions(Arrays.asList(ex));
    }
    public void addExceptions(Collection<Pair<String, Throwable>> ex) {
        ex.forEach(p->addException(p));
    }
    
    private void addException(Pair<String, Throwable> ex) {
        if (ex==null || ex.value==null || ex.key==null) return;
        boolean[] added = new boolean[1];
        exceptions.stream().filter(p->thowableEqual.test(p.value, ex.value)).findAny().ifPresent(p->{
            p.key+=";"+ex.key;
            added[0] = true;
        });
        if (!added[0]) exceptions.add(ex);
        //logger.debug("add ex: {}, total: {}", added[0], exceptions.size());
        //logger.debug(ex.key, ex.value);
    }

    public void unroll() {
        // check for multiple exceptions and unroll them
        List<Pair<String, Throwable>> errorsToAdd = new ArrayList<>();
        Iterator<Pair<String, Throwable>> it = exceptions.iterator();
        while(it.hasNext()) {
            Pair<String, ? extends Throwable> e = it.next();
            if (e.value instanceof MultipleException) {
                it.remove();
                ((MultipleException)e.value).unroll();
                errorsToAdd.addAll(((MultipleException)e.value).getExceptions());
            }
        }
        exceptions.addAll(errorsToAdd);
    }

    
    private final static BiPredicate<Throwable, Throwable> tEq = (t1, t2) -> {
       return  t1==null ? t2==null : 
               (t2==null ? false : 
               (t1.getMessage()==null ? t2.getMessage()==null : 
               (t2.getMessage()==null ? false : t1.getMessage().equals(t2.getMessage())))
                    && Arrays.equals(t1.getStackTrace(), t2.getStackTrace()));
    };
    public final static BiPredicate<Throwable, Throwable> thowableEqual = (t1, t2) -> tEq.test(t1, t2) && tEq.test(t1.getCause(), t2.getCause());
    
    public List<Pair<String, Throwable>> getExceptions() {
        return exceptions;
    }
    public boolean isEmpty() {
        return exceptions.isEmpty();
    }
}
