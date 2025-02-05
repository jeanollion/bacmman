package bacmman.configuration.experiment;

import bacmman.configuration.parameters.PluginParameter;
import bacmman.github.gist.GistConfiguration;
import bacmman.plugins.ProcessingPipeline;
import org.json.simple.JSONObject;

public class ProcessingChain extends PluginParameter<ProcessingPipeline> implements ConfigIDAware<ProcessingChain>  {
    String configID;
    int configObjectClassIdx=-1;
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
    }

}