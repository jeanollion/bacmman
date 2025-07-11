package bacmman.configuration.parameters;

import bacmman.core.Core;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CustomParameter<P extends Parameter> extends ContainerParameterImpl<CustomParameter<P>> implements  ChoosableParameter<CustomParameter<P>>{
    public static final List<Class<Parameter>> allParameters = Core.findImplementation("bacmman.configuration.parameters", Parameter.class);

    public static final Logger logger = LoggerFactory.getLogger(CustomParameter.class);
    final Map<String, Class<P>> parameterChoice;
    final List<String> parameterNames;
    final Map<String, Integer> parameterIndex;
    TextParameter key = new TextParameter("Key", "", true, false);
    String selectedParameter;
    P currentParameter;
    Class<P> clazz;
    Predicate<Class<P>> excludeClasses;

    public CustomParameter(P parameter) {
        this(parameter, (Class<P>)parameter.getClass());
    }
    public CustomParameter(P parameter, Class<P> clazz) {
        this(parameter.getName(), clazz, c -> false);
        this.key.setValue(parameter.getName());
        this.currentParameter = parameter;
        this.selectedParameter = parameterChoice.entrySet().stream().filter(e -> e.getValue().equals(clazz)).map(Map.Entry::getKey).findFirst().orElse(parameter.getClass().getName());
    }
    public CustomParameter(String name, Class<P> clazz) {
        this(name, clazz, c -> false);
    }
    public CustomParameter(String name, Class<P> clazz, Predicate<Class<P>> excludeClasses) {
        super(name);
        this.clazz = clazz;
        this.excludeClasses=excludeClasses;
        Predicate<Class<?>> hasStringConstructor = c -> {
            try {
                c.getDeclaredConstructor(String.class);
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        };
        parameterChoice = new TreeMap<>(allParameters.stream().filter(clazz::isAssignableFrom)
                .filter(hasStringConstructor)
                .filter(c -> !excludeClasses.test((Class<P>)c))
                .collect(Collectors.toMap(Class::getSimpleName, c -> (Class<P>) c)));
        parameterNames = new ArrayList<>(parameterChoice.keySet());
        parameterIndex = IntStream.range(0, parameterNames.size()).boxed().collect(Collectors.toMap(parameterNames::get, i->i));
        key.addListener(p -> setName(p.getValue()));
    }

    @Override
    public String toString() {
        String k = key.getValue().isEmpty() ? "Key" : key.getValue();
        String v = currentParameter==null ? "" : currentParameter.toString().replace("Value", "");
        return k+v;
    }

    @Override
    public CustomParameter<P> duplicate() {
        CustomParameter<P> other = new CustomParameter<>(name, clazz, excludeClasses);
        other.setSelectedItem(selectedParameter);
        other.setContentFrom(this);
        transferStateArguments(this, other);
        return other;
    }
    @Override
    public void setSelectedItem(String item) {
        if (item!=null && !item.equals(selectedParameter) && parameterChoice.containsKey(item)) {
            try {
                P p = parameterChoice.get(item).getDeclaredConstructor(String.class).newInstance("Value");
                setParameter(p);
                this.selectedParameter = item;
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                logger.debug("could not instantiate parameter: ", e);
                selectedParameter = null;
                setParameter(null);
            }
        } else if (item==null) {
            this.selectedParameter = null;
            setParameter(null);
        }

    }
    public void setParameter(P p) {
        currentParameter = p;
        initChildList();
    }
    public P getCurrentParameter(boolean duplicate) {
        if (duplicate) return (P)currentParameter.duplicate();
        else return currentParameter;
    }
    public String getKey() {
        return key.getValue();
    }

    public String getParameterClassName() {return selectedParameter;}
    @Override
    public String[] getChoiceList() {
        return parameterChoice.keySet().toArray(new String[0]);
    }

    @Override
    public int getSelectedIndex() {
        if (selectedParameter==null || !parameterIndex.containsKey(selectedParameter)) return -1;
        return parameterIndex.get(selectedParameter);
    }

    @Override
    public boolean isAllowNoSelection() {
        return false;
    }

    @Override
    public String getNoSelectionString() {
        return "No Parameter Selected";
    }

    @Override
    protected void initChildList() {
        if (currentParameter==null) super.initChildren(key);
        else super.initChildren(key, currentParameter);
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("key", key.toJSONEntry());
        if (selectedParameter != null) res.put("parameterClass", selectedParameter);
        if (currentParameter!=null) res.put("parameters", currentParameter.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry instanceof JSONObject) {
            JSONObject jsonO = (JSONObject) jsonEntry;
            if (jsonO.getOrDefault("parameterClass", null) instanceof String) {
                setSelectedItem((String)jsonO.get("parameterClass"));
            } else setSelectedItem(null);
            if (currentParameter!=null && jsonO.containsKey("parameters")) {
                currentParameter.initFromJSONEntry(jsonO.get("parameters"));
            }
            if (jsonO.get("key")!=null) key.initFromJSONEntry(jsonO.get("key"));
        } else throw new IllegalArgumentException("Invalid JSON Entry");
    }

    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof CustomParameter) {
            bypassListeners=true;
            CustomParameter otherP = (CustomParameter) other;
            this.key.setContentFrom(otherP.key);
            if (otherP.selectedParameter != null) {
                setSelectedItem(otherP.selectedParameter);
                if (currentParameter!=null && otherP.currentParameter!=null) currentParameter.setContentFrom(otherP.currentParameter);
            } else setSelectedItem(null);
            bypassListeners=false;
        } else {
            //throw new IllegalArgumentException("wrong parameter type");
        }
        if (this instanceof Deactivable && other instanceof Deactivable) ((Deactivable)this).setActivated(((Deactivable)other).isActivated());
    }

}
