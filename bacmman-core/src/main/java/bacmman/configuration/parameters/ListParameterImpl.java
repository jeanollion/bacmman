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
import java.util.function.*;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;

import bacmman.utils.JSONSerializable;
import org.json.simple.JSONArray;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 
 * @author Jean Ollion
 * @param <T>
 * @param <L>
 */

public abstract class ListParameterImpl<T extends Parameter, L extends ListParameterImpl<T, L>> implements ListParameter<T,L>, Listenable<L>, PythonConfiguration, ParameterWithLegacyInitialization<L, List<T>>, JSONSerializable.PartialInit {
    final Logger logger = LoggerFactory.getLogger(ListParameterImpl.class);
    protected String name;
    protected ContainerParameter parent;

    protected List<T> children;
    protected Class<T> childClass;
    protected String childClassName;
    protected T childInstance;

    protected int unMutableIndex, maxChildCount, minChildCount;
    protected Boolean isEmphasized;
    protected BiFunction<L, Integer, String> newInstanceNameFunction;
    protected Utils.TriConsumer<L, Integer, T> newInstanceConfiguration;
    boolean allowMoveChildren = true;
    boolean allowModifications=true;
    protected Predicate<L> additionalValidation = l -> true;
    protected Predicate<T> childrenValidation = l -> true;
    protected boolean allowDeactivate = true;

    static void transferStateArguments(ListParameterImpl source, ListParameterImpl dest) {
        dest.addValidationFunction(source.additionalValidation);
        dest.addValidationFunctionToChildren(source.childrenValidation);
        if (source.isEmphasized!=null) dest.setEmphasized(source.isEmphasized);
        dest.setAllowDeactivable(source.allowDeactivate);
        dest.setAllowMoveChildren(source.allowMoveChildren);
        dest.setAllowMoveChildren(source.allowModifications);
        dest.setUnmutableIndex(source.unMutableIndex);
        dest.setMaxChildCount(source.maxChildCount);
        dest.setMinChildCount(source.minChildCount);
        dest.setNewInstanceNameFunction(source.newInstanceNameFunction);
        dest.setNewInstanceConfigurationFunction(source.newInstanceConfiguration);
        dest.setHint(source.toolTipText);
        dest.setSimpleHint(source.toolTipTextSimple);
        dest.setListeners(source.listeners);
        if (source instanceof Deactivable && dest instanceof Deactivable) {
            ((Deactivable)dest).setActivated(((Deactivable)source).isActivated());
        }
    }
    @Override public boolean isEmpty() {return getChildren().isEmpty();}
    @Override
    public L addValidationFunction(Predicate<L> isValid) {
        additionalValidation = additionalValidation.and(isValid);
        return (L)this;
    }

    public L unique(Function<T, Object> mapper) {
        return addchildrenPropertyValidation(mapper, false);
    }
    public L addchildrenPropertyValidation(Function<T, Object> mapper, boolean equals) {
        return addValidationFunctionToChildren( c -> {
            Stream<Object> otherProp = ((L)c.getParent()).getActivatedChildren().stream().filter(cc -> !cc.equals(c)).map(mapper);
            if (equals) return otherProp.allMatch(o -> o.equals(mapper.apply(c)));
            else return otherProp.noneMatch(o -> o.equals(mapper.apply(c)));
        } );
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

    public L setAllowModifications(boolean allow) {
        this.allowModifications=allow;
        return (L)this;
    }

    @Override
    public int getMaxChildCount() {
        return this.maxChildCount;
    }
    public L setMaxChildCount(int maxChildCount) {
        this.maxChildCount=maxChildCount;
        return (L)this;
    }
    public L setMinChildCount(int minChildCount) {
        this.minChildCount=minChildCount;
        return (L)this;
    }
    public int getMinChildCount() {
        return this.minChildCount;
    }
    @Override 
    public boolean allowMoveChildren() {
        return allowMoveChildren;
    }

    @Override
    public boolean allowModifications() {
        return allowModifications;
    }

    @Override
    public JSONAware toJSONEntry() {
        JSONArray list= new JSONArray();
        for (T p : children) list.add(p.toJSONEntry());
        return list;
    }

    @Override
    public void initFromJSONEntry(Object json) {
        this.initFromJSONEntry(json, false);
    }

    @Override
    public void initFromJSONEntry(Object json, boolean partialInit) {
        synchronized(this) {
            this.bypassListeners = true;
            removeAllElements();
            Throwable el=null;
            if (json instanceof JSONArray || (json instanceof JSONObject && ((JSONObject)json).containsKey("list") )) {
                try {
                    JSONArray list = json instanceof JSONArray ? (JSONArray)json : (JSONArray)((JSONObject)json).get("list");
                    for (Object o : list) {
                        T newI = createChildInstance();
                        insert(newI); // may be necessary for initFromJSONEntry
                        if (newI instanceof PartialInit) ((PartialInit)newI).initFromJSONEntry(o, partialInit);
                        else newI.initFromJSONEntry(o);
                    }
                    this.bypassListeners=false;
                    return;
                } catch (Throwable e) {
                    remove(getChildCount()-1); // error init last children
                    if (!isEmpty()) { // some children have already been inserted
                        this.bypassListeners=false;
                        throw e;
                    } else el = e; // error at first children -> check if element was replace by list
                }
            }

            // try to init with one single element (if element was replaced by list)
            try {
                T newI = createChildInstance();
                insert(newI);
                if (newI instanceof PartialInit) ((PartialInit)newI).initFromJSONEntry(json, partialInit);
                else newI.initFromJSONEntry(json);
                this.bypassListeners = false;
            } catch (Throwable e) {
                remove(0);
                this.bypassListeners = false;
                if (el != null) throw new RuntimeException(el);
                else throw e;
            }
        }
    }
    
    protected String toolTipText, toolTipTextSimple;
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
        return toolTipTextSimple;
    }
    @Override
    public L setSimpleHint(String tip) {
        this.toolTipTextSimple = tip;
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
        this(name, -1, childClass);
    }
    
    public ListParameterImpl(String name, int unMutableIndex, T childInstance) {
        this(name, unMutableIndex, (Class<T>)childInstance.getClass());
        this.childInstance=childInstance;
    }
    
    public ListParameterImpl(String name, T childInstance) {
        this(name, -1, childInstance);
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
                res = childClass.getDeclaredConstructor(String.class).newInstance(newInstanceNameFunction!=null ? newInstanceNameFunction.apply((L)this, getChildCount()) : "new "+childClass.getSimpleName());
                if (newInstanceConfiguration!=null) newInstanceConfiguration.accept((L)this, getChildCount(), res);
                //if (isEmphasized!=null) res.setEmphasized(isEmphasized);
            } catch (NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                logger.error("duplicate error", ex);
            }
        } else if (childInstance != null) {
            res =  (T)childInstance.duplicate();
            if (newInstanceNameFunction!=null) res.setName(newInstanceNameFunction.apply((L)this, getChildCount()));
            if (newInstanceConfiguration!=null) newInstanceConfiguration.accept((L)this, getChildCount(), res);
            if (childInstance.isEmphasized()) res.setEmphasized(true); // || Boolean.FALSE.equals(isEmphasized)
        }
        if (res!=null) {
            res.setParent(this);
            res.addValidationFunction(childrenValidation); // validation should not be present in child instance...
            for (Consumer<T> conf : this.configs) conf.accept(res);
        }
        return res;
    }
    public L setAllowDeactivable(boolean allow) {
        allowDeactivate = allow;
        return (L)this;
    }
    @Override
    public boolean isDeactivatable() {
        if (!allowDeactivate) return false;
        return Deactivable.class.isAssignableFrom(this.getChildClass());
    }
    @Override
    public boolean allowDeactivate() {
        return allowDeactivate;
    }
    
    @Override
    public void setActivatedAll(boolean activated) {
        if (isDeactivatable()) {
            for (Parameter p: getChildren()) ((Deactivable)p).setActivated(activated);
        }
    }
    
    @Override
    public List<T> getActivatedChildren() {
        if (!isDeactivatable()) return getChildren();
        else return getChildren().stream().filter(p->((Deactivable)p).isActivated()).collect(Collectors.toList());
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
        if (maxChildCount>0 && maxChildCount<this.getChildCount()) return false;
        if (minChildCount>0 && minChildCount>this.getChildCount()) return false;
        if (getActivatedChildren().stream().anyMatch((child) -> (!child.isValid()))) return false;
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
                    if (isDeactivatable() && ((Deactivable)this.getChildAt(i)).isActivated() != ((Deactivable)otherLP.getChildAt(i)).isActivated() ) return false;
                    if (!(this.getChildAt(i)).sameContent((Parameter)otherLP.getChildAt(i))) {
                        //logger.trace("{}!={} class {}, children differ at {} ({} != {})", name, other.getName(), getClass().getSimpleName(), i, getChildAt(i).toString(), (otherLP.getChildAt(i)).toString());
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
            if (otherLP.getChildClass()==this.getChildClass()) {
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
            } //else throw new IllegalArgumentException("setContentFrom: wrong parameter type : child class is:"+getChildClass() + " but should be: "+otherLP.getChildClass());
        } //else throw new IllegalArgumentException("wrong parameter type");
    }
    
    public L setChildrenNumber(int number) {
        if (this.maxChildCount>0 && maxChildCount<number) throw new IllegalArgumentException("requested child number ("+number+") is greater than maximum child number ("+maxChildCount+")");
        if (this.unMutableIndex>=0 && number<unMutableIndex+1) throw new IllegalArgumentException("requested child number ("+number+") is lower than minimum child number ("+(unMutableIndex+1)+")");
        if (this.getChildCount()>number) {
            int c = this.getChildCount();
            for (int i = c; i>number; --i) remove(i-1);
        } else if (this.getChildCount()<number) {
            int c = this.getChildCount();
            for (int i = c; i<number; ++i) this.insert(createChildInstance());
        }
        resetName(null);
        return (L)this;
    }
    
    public L setUnmutableIndex(int unMutableIndex) {
        this.unMutableIndex=unMutableIndex;
        return (L)this;
    }
    @Override
    public int getUnMutableIndex() {
        return unMutableIndex;
    }
    public L setNewInstanceNameFunction(BiFunction<L, Integer, String> nameFunction) {
        this.newInstanceNameFunction = nameFunction;
        return (L)this;
    }
    public L setNewInstanceConfigurationFunction(Utils.TriConsumer<L, Integer, T> function) {
        this.newInstanceConfiguration = function;
        return (L)this;
    }
    public void resetName(BiFunction<L, Integer, String> nameFunction) {
        if (nameFunction==null && this.newInstanceNameFunction==null) return;
        if (nameFunction!=null) this.newInstanceNameFunction=nameFunction;
        int count = 0;
        for (T t : this.children) t.setName(newInstanceNameFunction.apply((L)this, count++));
    }
    
    @Override
    public String toString() {return getName();}
    
    @Override
    public String toStringFull() {return getName()+":"+Utils.toStringList(children, p->p.toStringFull());}
    
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
        //logger.info("removing node:"+((Parameter)node).toString() +" total number: "+children.size());
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
    public int getActivatedChildCount() {
        if (!isDeactivatable()) return getChildCount();
        return (int)getChildren().stream().filter(c->(((Deactivable)c).isActivated())).count();
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
    public L addListener(Consumer<L> listener) {
        if (listeners==null) listeners = new ArrayList<>();
        listeners.add(listener);
        return (L)this;
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
    public L addNewInstanceConfiguration(Consumer<T> configuration) {
        configs.add(configuration);
        // also run for existing children
        for (T c : getChildren()) configuration.accept(c);
        return (L)this;
    }
    public boolean removeNewInstanceConfiguration(Consumer<T> configuration) {
        return configs.remove(configuration);
    }

    // legacy init

    /**
     * When parameter cannot be initialized, this value is used as default. Useful when parametrization of a module has changed.
     * @param value default value
     * @return this parameter for convenience
     */
    @Override
    public L setLegacyInitializationValue(List<T> value) {
        this.legacyInitItems = value;
        return (L)this;
    }

    /**
     * This method is run when a parameter cannot be initialized, meaning that the parametrization of the module has changed.
     */
    @Override
    public void legacyInit() {
        if (legacyInitItems != null) {
            this.getChildren().clear();
            legacyInitItems.forEach(this::insert);
        }
        if (legacyParameter!=null && setValue!=null) setValue.accept(legacyParameter, (L)this);
    }
    List<T> legacyInitItems;
    Parameter[] legacyParameter;
    BiConsumer<Parameter[], L> setValue;

    /**
     * When a parameter A of a module has been replaced by B, this methods allows to initialize B using the former value of A
     * @param p
     * @param setValue
     * @return
     */
    @Override
    public L setLegacyParameter(BiConsumer<Parameter[], L> setValue, Parameter... p) {
        this.legacyParameter = p;
        this.setValue = setValue;
        return (L)this;
    }
    @Override
    public Parameter[] getLegacyParameters() {
        return legacyParameter;
    }


    // python configuration
    @Override
    public Object getPythonConfiguration() {
        JSONArray json = new JSONArray();
        for (T p : getChildren()) {
            if (p instanceof PythonConfiguration) json.add(((PythonConfiguration) p).getPythonConfiguration());
            else json.add(p.toJSONEntry());
        }
        return json;
    }

    @Override
    public String getPythonConfigurationKey() {
        return PythonConfiguration.toSnakeCase(this.getName());
    }
}
