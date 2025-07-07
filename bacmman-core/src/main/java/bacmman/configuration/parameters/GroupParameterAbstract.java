package bacmman.configuration.parameters;

import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public abstract class GroupParameterAbstract<T extends GroupParameterAbstract<T>> extends ContainerParameterImpl<T> {
    public GroupParameterAbstract(String name, Parameter... parameters) {
        super(name);
        this.children = Arrays.asList(parameters);
        initChildList();
    }
    public GroupParameterAbstract(String name, Collection<Parameter> parameters) {
        super(name);
        this.children = new ArrayList<>(parameters);
        initChildList();
    }

    protected T setChildren(Parameter... parameters) {
        this.children = Arrays.asList(parameters);
        initChildList();
        return (T)this;
    }

    protected T setChildren(Collection<Parameter> parameters) {
        this.children = new ArrayList<>(parameters);
        initChildList();
        return (T)this;
    }

    protected GroupParameterAbstract(String name) {
        super(name);
    }

    @Override
    protected void initChildList() {
        super.initChildren(children);
    }
    @Override
    public abstract T duplicate();

    @Override
    public JSONArray toJSONEntry() {
        return JSONUtils.toJSONArrayMap(children);
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry==null) return;
        boolean init = JSONUtils.isJSONArrayMap(jsonEntry) ? JSONUtils.fromJSONArrayMap(children, (JSONArray)jsonEntry) :
            JSONUtils.fromJSON(children, (JSONArray)jsonEntry);
        if (!init)  throw new IllegalArgumentException("Error initializing group parameter");
    }

}