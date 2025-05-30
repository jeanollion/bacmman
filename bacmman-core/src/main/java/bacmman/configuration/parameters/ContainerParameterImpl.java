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
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.utils.Utils;
import org.json.simple.JSONObject;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 *
 * @author Jean Ollion
 * @param <P>
 */

public abstract class ContainerParameterImpl<P extends ContainerParameterImpl<P>> implements ContainerParameter<Parameter, P>, InvisibleNode, PythonConfiguration {
    protected String name;
    protected MutableTreeNode parent;
    protected List<Parameter> children;
    protected Boolean isEmphasized;
    protected Predicate<P> additionalValidation = p->true;
    
    public ContainerParameterImpl(String name) {
        this.name=name;
    }
    protected String toolTipText, toolTipTextSimple;
    @Override
    public String getHintText() {
        return toolTipText;
    }
    @Override
    public P setHint(String tip) {
        this.toolTipText= tip;
        return (P)this;
    }
    @Override
    public String getSimpleHintText() {
        return toolTipTextSimple;
    }
    @Override
    public P setSimpleHint(String tip) {
        this.toolTipTextSimple= tip;
        return (P)this;
    }
    @Override
    public boolean isEmphasized() {
        if (isEmphasized!=null) return isEmphasized;
        return false;
        //return getChildren().stream().anyMatch((child) -> (child.isEmphasized()));
    }
    @Override
    public P setEmphasized(boolean isEmphasized) {
        //this.getChildren().stream().filter(p->!p.isEmphasized()).forEach(p -> p.setEmphasized(isEmphasized));
        this.isEmphasized = isEmphasized;
        return (P) this;
    }
    protected void initChildren(List<Parameter> parameters) {
        if (parameters==null) {
            children = new ArrayList<>(0);
        } else {
            children=parameters;
            int idx = 0;
            for (Parameter p : parameters) {
                if (p!=null) p.setParent(this);
                else logger.warn("SCP: {} initChildren error: param null: {}, name: {}, type: {}", this.hashCode(), idx, name, getClass().getSimpleName());
                //if (p instanceof SimpleContainerParameter) ((SimpleContainerParameter)p).initChildList(); -> cf postLoad
                idx++;
            }
        }
    }
    
    protected void initChildren(Parameter... parameters) {
        if (parameters==null || parameters.length==0) {
            children = new ArrayList<>(0);
        } else {
            initChildren(new ArrayList<>(Arrays.asList(parameters)));
        }
    }
    
    protected abstract void initChildList();
    
    @Override
    public P addValidationFunction(Predicate<P> isValid) {
        additionalValidation = additionalValidation.and(isValid);
        return (P)this;
    }
    
    @Override
    public boolean isValid() {
        if (getChildren().stream().anyMatch((child) -> (!child.isValid()))) return false;
        return additionalValidation.test((P)this);
    }
    
    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof ContainerParameterImpl) {
            bypassListeners=true;
            ContainerParameterImpl otherP = (ContainerParameterImpl) other;
            if (!ParameterUtils.setContent(getChildren(), otherP.getChildren())) logger.warn("SCP: {}({}): different parameter length, they might not be well set: c:{}/src:{}", name, this.getClass().getSimpleName(), children.size(), otherP.children.size());
            transferStateArguments((ContainerParameterImpl) other, this);
            bypassListeners=false;
            if (this instanceof ConfigIDAware && other instanceof ConfigIDAware) {
                ((ConfigIDAware)this).setConfigID(((ConfigIDAware)other).getConfigID());
                ConfigIDAware.setAutoUpdate((ConfigIDAware)other, (ConfigIDAware)this);
            }
            if (this instanceof Deactivable && other instanceof Deactivable) ((Deactivable)this).setActivated(((Deactivable)other).isActivated());
        } else {
            //throw new IllegalArgumentException("wrong parameter type");
        }

    }
    public static void transferStateArguments(ContainerParameterImpl source, ContainerParameterImpl dest) {
        dest.setListeners(source.listeners);
        dest.setHint(source.toolTipText);
        dest.setSimpleHint(source.toolTipTextSimple);
        dest.addValidationFunction(source.additionalValidation);
        if (source.isEmphasized!=null) dest.setEmphasized(source.isEmphasized);
        if (source instanceof Deactivable && dest instanceof Deactivable) ((Deactivable)dest).setActivated(((Deactivable)source).isActivated());
    }
    @Override
    public P duplicate() {
        try {
            P p = (P) this.getClass().getDeclaredConstructor(String.class).newInstance(name);
            p.setContentFrom(this);
            transferStateArguments(this, p);
            return p;
        } catch (NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            logger.error("duplicate error:", ex);
        }
        return null;
    }
    
    @Override
    public String getName(){
        return name;
    }
    
    @Override
    public P setName(String name) {
        this.name=name;
        return (P)this;
    }
    
    @Override
    public ArrayList<Parameter> getParameterPath() {
        return ParameterImpl.getPath(this);
    }
    
    @Override
    public boolean sameContent(Parameter other) {
        if (this instanceof Deactivable && other instanceof Deactivable) {
            if (((Deactivable)this).isActivated() != ((Deactivable)other).isActivated()) return false;
        }
        if (other instanceof ContainerParameter) {
            ContainerParameter otherLP = (ContainerParameter)other;
            if (otherLP.getChildCount()==this.getChildCount()) {
                for (int i = 0; i<getChildCount(); i++) {
                    if (!(this.getChildAt(i)).sameContent((Parameter)otherLP.getChildAt(i))) {
                        logger.trace("{}!={} class {}, children differ at {} ({} != {})", name, other.getName(), getClass().getSimpleName(), i, getChildAt(i).toString(), ((Parameter)otherLP.getChildAt(i)).toString());
                        return false;
                    }
                }
                return true;
            } else {
                logger.trace("{} != {} class {}, child number: {} vs {}", name, other.getName(), getClass().getSimpleName(), getChildCount(), other.getChildCount());
                return false;
            }
        } else return false;
    }

    @Override
    public void insert(MutableTreeNode child, int index) {}
    
    @Override
    public void remove(int index) {}

    @Override
    public void remove(MutableTreeNode node) {}

    @Override public void setUserObject(Object object) {this.name=object.toString();}

    @Override
    public void removeFromParent() {
        //logger.info("(container) removing node from parent:"+((Parameter)this).toString() +" total number: "+children.size());
        parent.remove(this);
    }

    @Override
    public void setParent(MutableTreeNode newParent) {
        if (newParent==null) parent = null;
        else parent=newParent;
    }

    @Override
    public ContainerParameter getParent() {
        return (ContainerParameter)parent;
    }

    @Override
    public boolean getAllowsChildren() {
        return true;
    }

    @Override
    public boolean isLeaf() {
        return false;
    }
    
    @Override
    public String toString() {return getName();}
    
    @Override
    public String toStringFull() {return getName()+":"+Utils.toStringList(children, p->p.toStringFull());}

    @Override
    public Parameter getChildAt(int childIndex) {
        return getChildren().get(childIndex);
    }

    @Override
    public int getChildCount() {
        return getChildren().size();
    }

    @Override
    public int getIndex(TreeNode node) {
        return getChildren().indexOf(node);
    }

    @Override
    public Enumeration children() {
        return Collections.enumeration(getChildren());
    }

    @Override
    public List<Parameter> getChildren() {
        if (children==null) this.initChildList();
        return children;
    }
    
    // listenable
    protected List<Consumer<P>> listeners;
    boolean bypassListeners;
    public P addListener(Consumer<P> listener) {
        if (listeners == null) listeners = new ArrayList<>();
        listeners.add(listener);
        return (P)this;
    }
    public void removeListener(Consumer<P> listener) {
        if (listeners != null) listeners.remove(listener);
    }
    public void fireListeners() {
        if (listeners != null && !bypassListeners) for (Consumer<P> pl : listeners) pl.accept((P)this);
    }
    public void setListeners(List<Consumer<P>> listeners) {
        if (listeners==null) this.listeners=null;
        else this.listeners=new ArrayList<>(listeners);
    }

    // invisible node implementation : allows hiding some parameters in beginners mode
    @Override
    public Parameter getChildAt(int index, boolean filterIsActive) {
        if (!filterIsActive) {
            return getChildAt(index);
        }
        if (children == null) {
            throw new ArrayIndexOutOfBoundsException("node has no children");
        }
        int realIndex = -1;
        int visibleIndex = -1;
        for (Parameter p : children) {
            if (p.isEmphasized()) visibleIndex++;
            realIndex++;
            if (visibleIndex == index) {
                return children.get(realIndex);
            }
        }
        throw new ArrayIndexOutOfBoundsException("index unmatched");
    }
    @Override
    public int getChildCount(boolean filterIsActive) {
        if (!filterIsActive) return getChildCount();
        if (children == null) return 0;
        return (int)children.stream().filter(p->p.isEmphasized()).count();
    }

    // python configuration
    @Override
    public Object getPythonConfiguration() {
        JSONObject json = new JSONObject();
        PythonConfiguration.putParameters(getChildren(), json);
        return json;
    }

    @Override
    public String getPythonConfigurationKey() {
        return PythonConfiguration.toSnakeCase(this.getName());
    }
}
