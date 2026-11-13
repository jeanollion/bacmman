package bacmman.configuration.experiment;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.github.gist.GistConfiguration;
import bacmman.utils.JSONSerializable;

public interface ConfigIDAware<P extends Parameter<P>> extends Parameter<P>, JSONSerializable.PartialInit {
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
    /**
     * @return folder relative to which links to local repositories are stored: folder of the dataset that contains {@param p}, or null
     */
    static java.nio.file.Path getLinkRefPath(Parameter p) {
        Experiment xp = bacmman.configuration.parameters.ParameterUtils.getExperiment(p);
        return xp == null ? null : xp.getPath();
    }

    /**
     * @return {@param link} resolved (absolute) if it is a link to a local repository stored relative to the dataset folder
     */
    static String resolveLink(Parameter p, String link) {
        return bacmman.github.gist.LocalLinks.isRelative(link) ? bacmman.github.gist.LocalLinks.resolve(link, getLinkRefPath(p)) : link;
    }

    /**
     * @return {@param link} as stored in the configuration: relative to the dataset folder if it is a link to a local repository
     */
    static String storedLink(Parameter p, String link) {
        return bacmman.github.gist.LocalLinks.isAbsolute(link) ? bacmman.github.gist.LocalLinks.relativize(link, getLinkRefPath(p)) : link;
    }

    static BooleanParameter getAutoUpdateParameter() {
        return new BooleanParameter("Auto Update Config", true)
                .setHint("If true, this configuration block will be automatically updated each time it is downloaded from configuration library");
    }

}
