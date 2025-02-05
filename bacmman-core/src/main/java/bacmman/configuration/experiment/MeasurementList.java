package bacmman.configuration.experiment;

import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.SimpleListParameter;
import bacmman.github.gist.GistConfiguration;
import bacmman.measurement.MeasurementKey;
import bacmman.plugins.Measurement;
import com.google.common.collect.Sets;
import org.json.simple.JSONArray;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MeasurementList extends SimpleListParameter<PluginParameter<Measurement>> implements ConfigIDAware<MeasurementList> {
    String configID;
    
    public MeasurementList(String name) {
        super(name, new PluginParameter<>("", Measurement.class, false));
        addValidationFunctionToChildren(ppm -> {
            if (!ppm.isActivated()) return true;
            if (!ppm.isOnePluginSet()) return false;
            Measurement m = ppm.instantiatePlugin();
            if (m==null) return false;
            Map<Integer, List<String>> currentmkByStructure= m.getMeasurementKeys().stream().collect(Collectors.groupingBy(MeasurementKey::getStoreStructureIdx, Collectors.mapping(MeasurementKey::getKey, Collectors.toList())));
            if (currentmkByStructure.values().stream().anyMatch((l) -> ((new HashSet<>(l).size()!=l.size())))) return false; // first check if duplicated keys for the measurement
            MeasurementList ml= (MeasurementList) ppm.getParent();
            if (ml==null) return true; // in case the child was removed from parent
            Map<Integer, Set<String>> allmkByStructure= ml.getActivatedChildren().stream().filter(pp -> pp!=ppm).map(PluginParameter::instantiatePlugin).filter(mes->mes!=null).flatMap(mes -> mes.getMeasurementKeys().stream()).collect(Collectors.groupingBy(MeasurementKey::getStoreStructureIdx, Collectors.mapping(MeasurementKey::getKey, Collectors.toSet())));
            return currentmkByStructure.entrySet().stream().noneMatch(e -> {
                Set<String> otherKeys = allmkByStructure.get(e.getKey());
                if (otherKeys==null) return false;
                return !Sets.intersection(otherKeys, new HashSet<>(e.getValue())).isEmpty();
            });
        });
        setHint("Measurements to be performed after processing. Measurements will be extracted in several data tables, each one corresponding to a single object class (e.g. microchannels or bacteria or spots). For each measurement, the table in which it will be written and the name of the corresponding column are indicated in the Help window. If the user defines two measurements with the same name in the same data table, the measurements will not be performed and invalid measurements are displayed in red.");
    }

    public MeasurementList setConfigID(String configID) {
        this.configID = configID;
        return this;
    }

    public String getConfigID() {
        return configID;
    }

    @Override
    public GistConfiguration.TYPE getType() {
        return GistConfiguration.TYPE.MEASUREMENTS;
    }

    @Override
    public JSONAware toJSONEntry() {
        if (configID==null) return super.toJSONEntry();
        JSONObject res = new JSONObject();
        JSONArray list = (JSONArray)super.toJSONEntry();
        res.put(ConfigIDAware.key, configID);
        res.put("list", list);
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry instanceof JSONArray) super.initFromJSONEntry(jsonEntry);
        else {
            JSONObject jsonO = (JSONObject)jsonEntry;
            if (jsonO.containsKey(ConfigIDAware.key)) configID = (String)jsonO.get(ConfigIDAware.key);
            super.initFromJSONEntry(jsonO.get("list"));
        }
    }
}
