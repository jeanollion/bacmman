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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 *
 * @author Jean Ollion
 */
public class SmallArray<T> {
    Object[] array;
    public SmallArray(){}
    public SmallArray(int bucketSize){array=new Object[bucketSize];}
    
    public List<T> asList() {
        List<T> res = new ArrayList<>(array.length);
        for (int i = 0; i<array.length; ++i) if (array[i]!=null) res.add((T)array[i]);
        return res;
    }
    public boolean has(int idx) {
        if (array==null) return false;
        else if (array.length<=idx) return false;
        else return array[idx]!=null;
    }
    public T get(int idx) {
        if (array==null) return null;
        else if (array.length<=idx) return null;
        else return (T)array[idx];
    }
    public T getOrDefault(int idx, Supplier<T> defaultValueSupplier) {
        extend(idx+1);
        if (array[idx]==null) array[idx] = defaultValueSupplier.get();
        return (T)array[idx];
    }
    public T getAndExtend(int idx) {
        if (array==null) {
            array=new Object[idx+1];
            return null;
        }
        else if (array.length<=idx) {
            Object[] newArray=new Object[idx+1];
            System.arraycopy(array, 0, newArray, 0, array.length);
            array=newArray;
            return null;
        }
        else return (T)array[idx];
    }
    public T getQuick(int idx) {return (T)array[idx];}
    public void set(T element, int idx) {
        if (array==null) array=new Object[idx+1];
        else if (array.length<=idx) {
            Object[] newArray=new Object[idx+1];
            System.arraycopy(array, 0, newArray, 0, array.length);
            array=newArray;
        }
        array[idx]=element;
    }
    public void extend(int newSize) {
        if (array==null) array=new Object[newSize];
        else if (array.length<newSize) {
             Object[] newArray=new Object[newSize];
             System.arraycopy(array, 0, newArray, 0, array.length);
             array=newArray;
        }
    }
    public void setQuick(T element, int idx) {array[idx]=element;}
    public int getBucketSize() {
        if (array==null) return 0;
        else return array.length;
    }
    public int getCount() {
        if (array==null) return 0;
        else {
            int count = 0;
            for (int i = 0; i<array.length; ++i) if (array[i]!=null) ++count;
            return count;
        }
    }
    
    public ArrayList<T> getObjects() {
        if (array==null) return new ArrayList<T>(0);
        ArrayList<T> res = new ArrayList<T>(getCount());
        for (int i = 0; i<array.length; ++i) if (array[i]!=null) res.add((T)array[i]);
        return res;
    }
    public ArrayList<T> getObjectsQuick() {
        if (array==null) return new ArrayList<T>(0);
        ArrayList<T> res = new ArrayList<T>();
        for (int i = 0; i<array.length; ++i) if (array[i]!=null) res.add((T)array[i]);
        return res;
    }
    public T getAmongNonNull(int idx) {
        for (int i = 0; i<array.length; ++i) {
            if (array[i]!=null) {
                if (idx==0) return (T)array[i];
                else --idx;
            }
        }
        return null;
    }
    public int indexOf(T object) {
        if (object==null) return -1;
        for (int i = 0; i<array.length; ++i) if (object.equals(array[i])) return i;
        return -1;
    }
    public void flush() {
        array=null;
    }
    private SmallArray(Object[] array){this.array=array;}
    public SmallArray<T> duplicate() {
        if (array==null) return new SmallArray();
        Object[] dup = Arrays.copyOf(array, array.length);
        return new SmallArray(dup);
    }
}
