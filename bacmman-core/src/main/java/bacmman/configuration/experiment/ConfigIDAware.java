package bacmman.configuration.experiment;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.github.gist.GistConfiguration;

public interface ConfigIDAware<P extends Parameter<P>> extends Parameter<P> {
    String getConfigID();
    P setConfigID(String configID);
    GistConfiguration.TYPE getType();
    default void setConfigItemIdx(int configItemIdx) {}
    default int getConfigItemIdx() {return -1;};
    BooleanParameter getAutoUpdate();
    String key = "config_id";
    String idxKey = "config_item_idx";
    String autoUpdateKey = "config_auto_update";
    static void setAutoUpdate(ConfigIDAware source, ConfigIDAware target) {
        BooleanParameter sourceAU = source.getAutoUpdate();
        if (sourceAU == null) return;
        BooleanParameter targetAU = target.getAutoUpdate();
        if (targetAU == null) return;
        targetAU.setContentFrom(sourceAU);
    }
    static BooleanParameter getAutoUpdateParameter() {
        return new BooleanParameter("Auto Update Config", true)
                .setHint("If true, this configuration block will be automatically updated each time it is downloaded from configuration library");
    }

}
