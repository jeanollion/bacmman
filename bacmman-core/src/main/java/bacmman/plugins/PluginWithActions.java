package bacmman.plugins;

import java.util.List;

/**
 * A plugin that proposes commands in the right-click menu of the parameter where it is selected (e.g. install or
 * update what it depends on).
 */
public interface PluginWithActions extends Plugin {
    class PluginAction {
        public final String name;
        public final String hint;
        public final Runnable action;
        /**
         * @param action run outside the event dispatch thread
         */
        public PluginAction(String name, String hint, Runnable action) {
            this.name = name;
            this.hint = hint;
            this.action = action;
        }
    }

    List<PluginAction> getActions();
}
