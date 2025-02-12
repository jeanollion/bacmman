package bacmman.configuration.experiment;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.github.gist.GistConfiguration;
import bacmman.plugins.ProcessingPipeline;
import org.json.simple.JSONObject;

import java.util.Objects;

public class ProcessingChain extends PluginParameter<ProcessingPipeline> implements ConfigIDAware<PluginParameter<ProcessingPipeline>>  {
    String configID;
    int configObjectClassIdx=-1;
    BooleanParameter autoUpdate = ConfigIDAware.getAutoUpdateParameter();

    public ProcessingChain(String name) {
        super(name, ProcessingPipeline.class, true);
        setEmphasized(true);
    }
    
    public ProcessingChain setConfigID(String configID) {
        this.configID = configID;
        return this;
    }

    public String getConfigID() {
        return configID;
    }

    @Override
    public boolean sameContent(Parameter other) {
        if (!super.sameContent(other)) return false;
        return Objects.equals(this.getConfigID(), ((ConfigIDAware)other).getConfigID())
                && Objects.equals(this.getConfigItemIdx(), ((ConfigIDAware)other).getConfigItemIdx());
    }

    @Override
    public BooleanParameter getAutoUpdate() {
        return autoUpdate;
    }

    public void setConfigItemIdx(int configItemIdx) {
        this.configObjectClassIdx = configItemIdx;
    }

    public int getConfigItemIdx() {
        return configObjectClassIdx;
    }

    @Override
    public GistConfiguration.TYPE getType() {
        return GistConfiguration.TYPE.PROCESSING;
    }

    @Override
    public JSONObject toJSONEntry() {
        JSONObject res= super.toJSONEntry();
        if (configID!=null) {
            res.put(ConfigIDAware.key, configID);
            res.put(ConfigIDAware.autoUpdateKey, autoUpdate.toJSONEntry());
            if (configObjectClassIdx>=0) res.put(ConfigIDAware.idxKey, configObjectClassIdx);
        }
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        super.initFromJSONEntry(jsonEntry);
        JSONObject jsonO = (JSONObject)jsonEntry;
        if (jsonO.containsKey(ConfigIDAware.key)) configID = (String)jsonO.get(ConfigIDAware.key);
        if (jsonO.containsKey(ConfigIDAware.idxKey)) configObjectClassIdx = ((Number)jsonO.get(ConfigIDAware.idxKey)).intValue();
        if (jsonO.containsKey(ConfigIDAware.autoUpdateKey)) autoUpdate.initFromJSONEntry(jsonO.get(ConfigIDAware.autoUpdateKey));
    }

}