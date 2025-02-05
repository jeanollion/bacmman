package bacmman.configuration.experiment;

import bacmman.configuration.parameters.Parameter;
import bacmman.github.gist.GistConfiguration;

public interface ConfigIDAware<P> {
    String getConfigID();
    P setConfigID(String configID);
    GistConfiguration.TYPE getType();
    default void setConfigItemIdx(int configItemIdx) {}
    default int getConfigItemIdx() {return -1;};
    String key = "config_id";
    String idxKey = "config_item_idx";
}
