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
package bacmman.configuration.parameters;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Function;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import org.json.simple.JSONArray;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import bacmman.utils.Utils;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 
 * @author Jean Ollion
 * @param <T>
 * @param <L>
 */

public abstract class ListParameterImpl<T extends Parameter, L extends ListParameterImpl<T, L>> implements ListParameter<T,L>, Listenable<L> {

    protected String name;
    protected List<T> children;
    protected int unMutableIndex;
    protected Class<T> childClass;
    protected String childClassName;
    protected T childInstance;
    protected Boolean isEmphasized;
    protected ContainerParameter parent;
    protected Function<Integer, String> newInstanceNameFunction;
    boolean allowMoveChildren = true;
    protected Predicate<L> additionalValidation = l -> true;
    protected Predicate<T> childrenValidation = l -> true;
    
    @Override
    public L addValidationFunction(Predicate<L> isValid) {
        additionalValidation = additionalValidation.and(isValid);
        return (L)this;
    }
    
    public L addValidationFunctionToChildren(Predicate<T> isValid) {
        childrenValidation = childrenValidation.and(isValid);
        children.forEach( t -> t.addValidationFunction(isValid)); // add only the new validation function because previous ones were added before
        return (L)this;
    }
    
    public L setAllowMoveChildren(boolean allow) {
        this.allowMoveChildren=allow;
        return (L)this;
    }
    @Override 
    public boolean allowMoveChildren() {
        return allowMoveChildren;
    }
    @Override
    public JSONAware toJSONEntry() {
        JSONObject res= new JSONObject();
        JSONArray list= new JSONArray();
        for (T p : children) list.add(p.toJSONEntry());
        res.put("list", list);
        return res;
    }

    @Override
    public void initFromJSONEntry(Object json) {
        synchronized(this) {
            this.bypassListeners = true;
            removeAllElements();
            if (json instanceof JSONObject && ((JSONObject)json).containsKey("list")) {
                JSONObject jsonO = (JSONObject)json;
                JSONArray list = (JSONArray)jsonO.get("list");
                for (Object o : list) {
                    T newI = createChildInstance();
                    newI.setParent(this);
                    newI.initFromJSONEntry(o);
                    insert(newI);
                }
            } else { // try to init with one single element (if element was replaced by list)
                T newI = createChildInstance();
                newI.setParent(this);
                newI.initFromJSONEntry(json);
                insert(newI);
            }
            this.bypassListeners=false;
        }
    }
    
    protected String toolTipText, simpleToolTipText;
    @Override
    public String getHintText() {
        return toolTipText;
    }
    @Override
    public L setHint(String tip) {
        this.toolTipText= tip;
        return (L)this;
    }
    @Override
    public String getSimpleHintText() {
        return simpleToolTipText;
    }
    @Override
    public L setSimpleHint(String tip) {
        this.simpleToolTipText= tip;
        return (L)this;
    }
    
    /**
     * 
     * @param name : name of the parameter
     * @param unMutableIndex : index of the last object that cannot be modified
     */
    public ListParameterImpl(String name, int unMutableIndex, Class<T> childClass) {
        this.name = name;
        children = new ArrayList<T>(10);
        this.unMutableIndex=unMutableIndex;
        this.childClass = childClass;
        this.childClassName=childClass.getName();
    }
    /**
     * 
     * @param name : name of the parameter
     */
    public ListParameterImpl(String name, Class<T> childClass) {
        //this.childrenClass=childrenClass;
        this.name = name;
        children = new ArrayList<T>(10);
        this.unMutableIndex=-1;
        this.childClass = childClass;
        this.childClassName=childClass.getName();
    }
    
    public ListParameterImpl(String name, int unMutableIndex, T childInstance) {
        this.childInstance=childInstance;
        this.name = name;
        children = new ArrayList<T>(10);
        this.unMutableIndex=unMutableIndex;
        this.childClass=(Class<T>)childInstance.getClass();
    }
    
    public ListParameterImpl(String name, T childInstance) {
        this.childInstance=childInstance;
        this.name = name;
        children = new ArrayList<T>(10);
        this.unMutableIndex=-1;
        this.childClass=(Class<T>)childInstance.getClass();
    }
    
    public boolean containsElement(String name) {
        return getChildren().stream().anyMatch(p->p.getName().equals(name));
    }

    @Override public Class<T> getChildClass() {
        if (childClass==null) {
            if (this.childInstance!=null) this.childClass=(Class<T>)childInstance.getClass();
            else try {
                childClass = (Class<T>) Class.forName(childClassName);
            } catch (ClassNotFoundException ex) {
                logger.error("childClass search error", ex);
            }
        }
        return childClass;
    }
    
    @Override
    public T createChildInstance() {
        T res = null;
        if (childInstance == null && getChildClass() != null) {
            try {
                res = childClass.getDeclaredConstructor(String.class).newInstance(newInstanceNameFunction!=null ? newInstanceNameFunction.apply(getChildCount()) : "new "+childClass.getSimpleName());
                //if (isEmphasized!=null) res.setEmphasized(isEmphasized);
            } catch (NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                logger.error("duplicate error", ex);
            }
        } else if (childInstance != null) {
            res =  (T)childInstance.duplicate();
            if (newInstanceNameFunction!=null) res.setName(newInstanceNameFunction.apply(getChildCount()));
            if (childInstance.isEmphasized()) res.setEmphasized(true); // || Boolean.FALSE.equals(isEmphasized)
        }
        if (res!=null) {
            res.addValidationFunction(childrenValidation); // validation should not be present in child instance...
            for (Consumer<T> conf : this.configs) conf.accept(res);
        }
        return res;
    }
    
    @Override
    public boolean isDeactivatable() {
        return Deactivatable.class.isAssignableFrom(this.getChildClass());
    }
    
    @Override
    public void setActivatedAll(boolean activated) {
        if (isDeactivatable()) {
            for (Parameter p: getChildren()) ((Deactivatable)p).setActivated(activated);
        }
    }
    
    @Override
    public List<T> getActivatedChildren() {
        if (!isDeactivatable()) return getChildren();
        else {
            List<T> res = new ArrayList<T>(this.getChildCount());
            for (T p: getChildren()) if (((Deactivatable)p).isActivated()) res.add(p);
            return res;
        }
    }
    
    public T createChildInstance(String name) {
        T instance = createChildInstance();
        instance.setName(name);
        return instance;
    }

    
    
    public String[] getChildrenString() {
        String[] res = new String[getChildren().size()];
        int i=0;
        for (Parameter s : children) res[i++] = s.getName();
        return res;
    }
    
    @Override
    public List<T> getChildren() {
        //postLoad();
        return children;
    }
    
    @Override
    public String getName(){
        return name;
    }
    
    @Override
    public L setName(String name) {
        this.name=name;
        return (L)this;
    }
    
    @Override
    public ArrayList<Parameter> getParameterPath(){
        return ParameterImpl.getPath(this);
    }
    
    @Override
    public boolean isValid() {
        if (unMutableIndex>=this.getChildCount()) return false;
        if (!getActivatedChildren().stream().noneMatch((child) -> (!child.isValid()))) return false;
        return this.additionalValidation.test((L)this);
    }
    
    @Override
    public boolean isEmphasized() {
        //return isEmphasized;
        if(isEmphasized!=null) return isEmphasized;
        return false;
        //return getActivatedChildren().stream().anyMatch((child) -> (child.isEmphasized()));
    }
    @Override
    public L setEmphasized(boolean isEmphasized) {
        //this.getChildren().stream().filter(p->!p.isEmphasized()).forEach(p -> p.setEmphasized(isEmphasized));
        this.isEmphasized = isEmphasized;
        return (L)this;
    }
    
    @Override
    public boolean sameContent(Parameter other) { // ne check pas le nom ni l'index unMutable..
        if (other instanceof ListParameter) {
            ListParameter otherLP = (ListParameter)other;
            if (otherLP.getChildCount()==this.getChildCount()) {
                for (int i = 0; i<getChildCount(); i++) {
                    if (!(this.getChildAt(i)).sameContent((Parameter)otherLP.getChildAt(i))) {
                        logger.trace("{}!={} class {}, children differ at {} ({} != {})", name, other.getName(), getClass().getSimpleName(), i, getChildAt(i).toString(), (otherLP.getChildAt(i)).toString());
                        return false;
                    }
                }
                return true;
            } else {
                logger.trace("{}!={} class {}, child number: {} vs {}", name, other.getName(), getClass().getSimpleName(), getChildCount(), other.getChildCount());
                return false;
            }
        } else return false;
    }

    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof ListParameter) {
            ListParameter<? extends Parameter, ? extends ListParameter> otherLP = (ListParameter)other;
            if (otherLP.getChildClass()!=this.getChildClass()) throw new IllegalArgumentException("setContentFrom: wrong parameter type : child class is:"+getChildClass() + " but should be: "+otherLP.getChildClass());
            else {
                //this.unMutableIndex = otherLP.getUnMutableIndex();
                //this.name=otherLP.getName();
                if (children==null) children = new ArrayList<>();
                this.children.clear();
                bypassListeners=true;
                for (Parameter p : otherLP.getChildren()) {
                    T newP = createChildInstance(p.getName());
                    newP.setContentFrom(p);
                    insert(newP);
                }
                if (other instanceof ListParameterImpl && other.getClass() == this.getClass()) {
                    ListParameterImpl<T, L> otherLPI = (ListParameterImpl)other;
                    setListeners(otherLPI.listeners);
                    addValidationFunction(otherLPI.additionalValidation);
                    addValidationFunctionToChildren(otherLPI.childrenValidation);
                    for (Consumer<T> conf : configs) otherLPI.addNewInstanceConfiguration(conf);
                }
                bypassListeners=false;
            }
            
        } else throw new IllegalArgumentException("wrong parameter type");
    }
    
    protected void setChildrenNumber(int number) {
        if (this.getChildCount()>number) {
            int c = this.getChildCount();
            for (int i = c; i>number; --i) remove(i-1);
        } else if (this.getChildCount()<number) {
            int c = this.getChildCount();
            for (int i = c; i<number; ++i) this.insert(createChildInstance());
        }
    }
    
    public void setUnmutableIndex(int unMutableIndex) {
        this.unMutableIndex=unMutableIndex;
    }
    @Override
    public int getUnMutableIndex() {
        return unMutableIndex;
    }
    public L setNewInstanceNameFunction(Function<Integer, String> nameFunction) {
        this.newInstanceNameFunction = nameFunction;
        return (L)this;
    }
    public void resetName(Function<Integer, String> nameFunction) {
        if (nameFunction==null && this.newInstanceNameFunction==null) return;
        if (nameFunction!=null) this.newInstanceNameFunction=nameFunction;
        int count = 0;
        for (T t : this.children) t.setName(newInstanceNameFunction.apply(count++));
    }
    
    @Override
    public String toString() {return name;}
    
    @Override
    public String toStringFull() {return name+":"+Utils.toStringList(children, p->p.toStringFull());}
    
    @Override
    public void insert(MutableTreeNode child, int index) {
        if (index>=getChildren().size()) children.add((T)child);
        else children.add(index, (T)child);
        child.setParent(this);
        fireListeners();
    }

    @Override
    public void insert(T... child) {
        for (T c : child) {
            getChildren().add(c);
            c.setParent(this);
        }
        fireListeners();
        if (!bypassListeners) Arrays.stream(child).filter(c-> c instanceof Listenable).forEach(c->((Listenable)c).fireListeners());
    }

    @Override
    public void remove(int index) {
        T e = getChildren().remove(index);
        if (e!=null) {
            e.setParent(null);
            fireListeners();
        }
        
    }

    @Override
    public void remove(MutableTreeNode node) {
        //System.out.println("removing node:"+((Parameter)node).toString() +" total number: "+children.size());
        logger.info("(list) removing node:"+((Parameter)node).toString() +" total number: "+children.size());
        boolean rem =  getChildren().remove((T)node);
        if (rem) {
            node.setParent(null);
            fireListeners();
        }
        
    }

    @Override
    public void setUserObject(Object object) {
        this.name=object.toString();
    }

    @Override
    public void removeFromParent() {
        logger.info("(list) removing node from parent:"+((Parameter)this).toString() +" total number: "+children.size());
        if (parent!=null) {
            this.parent.remove(this);
            parent = null;
        }
    }
    
    @Override 
    public void removeAllElements() {
        children.clear();
        fireListeners();
        //if (this.unMutableIndex<0) children=new ArrayList<>(children.size());
        //else for (int i = getChildren().size()-1;i>unMutableIndex;--i) children.remove(i);
    }

    @Override
    public void setParent(MutableTreeNode newParent) {
        if (newParent==null) parent = null;
        else parent=(ContainerParameter)newParent;
    }
    
    @Override
    public T getChildByName(String name) { // returns the first occurence..
        if (name==null) return null;
        if (children==null) {
            logger.error("no children for list: {}( child type:{})", name, this.childClassName);
        }
        for (T child : getChildren()) {
            if (name.equals(child.getName())) return child;
        }
        return null;
    }
    public int getIndex(String name) {
        T child = getChildByName(name);
        if (child==null) return -1;
        else return getIndex(child);
    }
    @Override
    public T getChildAt(int childIndex) {
        return getChildren().get(childIndex);
    }

    @Override
    public int getChildCount() {
        return getChildren().size();
    }

    @Override
    public ContainerParameter getParent() {
        return parent;
    }

    @Override
    public int getIndex(TreeNode node) {
        return children.indexOf(node);
    }

    @Override
    public boolean getAllowsChildren() {
        return true;
    }

    @Override
    public boolean isLeaf() {
        return getChildren().isEmpty();
    }

    @Override
    public Enumeration children() {
        return Collections.enumeration(getChildren());
    }

    List<Consumer<L>> listeners;
    boolean bypassListeners;
    @Override
    public void addListener(Consumer<L> listener) {
        if (listeners==null) listeners = new ArrayList<>();
        listeners.add(listener);
    }

    @Override
    public void removeListener(Consumer<L> listener) {
        if (listeners==null) return;
    }

    @Override
    public void fireListeners() {
        if (listeners==null || bypassListeners) return;
        for (Consumer<L> l : listeners) l.accept((L)this);
    }
    public void fireChildrenListeners() {
        if (bypassListeners) return;
        for (T p : children) if (p instanceof Listenable) ((Listenable)p).fireListeners();
    }
    public void setListeners(List<Consumer<L>> listeners) {
        if (listeners==null) this.listeners=null;
        else this.listeners=new ArrayList<>(listeners);
    }
    List<Consumer<T>> configs = new ArrayList<>();
    public void addNewInstanceConfiguration(Consumer<T> configuration) {
        configs.add(configuration);
        // also run for existing children
        for (T c : getChildren()) configuration.accept(c);
    }
}
