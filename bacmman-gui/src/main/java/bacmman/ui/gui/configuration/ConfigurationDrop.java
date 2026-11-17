package bacmman.ui.gui.configuration;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.parameters.*;
import bacmman.github.gist.GistConfiguration;
import bacmman.github.gist.GistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;
import java.util.*;

/**
 * Drop of configuration parameters dragged from one configuration tree to another one (e.g. from the configuration of a repository to the current dataset).
 * Two operations:
 * <ul>
 *     <li>Insertion: items of a list are added to a list of the other tree whose items are of the same kind (e.g. object classes into the object class list, transformations into a list of transformations)</li>
 *     <li>Replacement: the content of a parameter is replaced by the content of a compatible parameter (e.g. an object class dropped on another object class, a list of transformations dropped on a list of transformations)</li>
 * </ul>
 * Compatibility is strict: same parameter class, same plugin type for plugin parameters, same kind of items for lists, same structure for containers and same name (except for items of lists), so that the configuration cannot be broken by a drop
 */
public class ConfigurationDrop {
    static final Logger logger = LoggerFactory.getLogger(ConfigurationDrop.class);
    public enum OPERATION {INSERT, REPLACE}

    public static class Plan {
        public final OPERATION operation;
        public final Parameter target; // list for insertions
        public final int index; // insertion index
        public final List<Parameter> sources;
        Plan(OPERATION operation, Parameter target, int index, List<Parameter> sources) {
            this.operation = operation;
            this.target = target;
            this.index = index;
            this.sources = sources;
        }
    }

    // templates of list items, computed once per list (canImport is called continuously during a drag)
    static final Map<ListParameter, Parameter> templates = new WeakHashMap<>();

    static Parameter template(ListParameter list) {
        return templates.computeIfAbsent(list, l -> {
            try {
                return l.createChildInstance();
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    static boolean isListItem(Parameter p) {
        return p.getParent() instanceof ListParameter;
    }

    /**
     * @param sources dragged parameters (same parent)
     * @param dest parameter of the drop location
     * @param childIndex insertion index within dest (drop between items of a list), -1 for a drop on dest
     * @return the operation to perform, or null if the drop is not allowed
     */
    public static Plan plan(List<Parameter> sources, Parameter dest, int childIndex) {
        if (sources == null || sources.isEmpty() || dest == null) return null;
        for (Parameter s : sources) if (s == dest || isAncestor(s, dest)) return null;
        if (dest instanceof ListParameter && canInsert((ListParameter) dest, sources, childIndex)) {
            return new Plan(OPERATION.INSERT, dest, childIndex < 0 ? dest.getChildCount() : childIndex, sources);
        }
        if (childIndex >= 0) return null; // drop between items of a list: only insertion
        if (sources.size() != 1) return null;
        Parameter s = sources.get(0);
        if (dest instanceof ListParameter && !((ListParameter) dest).allowModifications()) return null;
        boolean items = isListItem(s) && isListItem(dest);
        if (compatible(s, dest, !items)) return new Plan(OPERATION.REPLACE, dest, -1, sources);
        return null;
    }

    static boolean isAncestor(TreeNode ancestor, TreeNode node) {
        TreeNode p = node.getParent();
        while (p != null) {
            if (p == ancestor) return true;
            p = p.getParent();
        }
        return false;
    }

    static boolean canInsert(ListParameter list, List<Parameter> sources, int childIndex) {
        if (!list.allowModifications()) return false;
        if (list.getMaxChildCount() > 0 && list.getChildCount() + sources.size() > list.getMaxChildCount()) return false;
        if (childIndex >= 0 && childIndex <= list.getUnMutableIndex()) return false;
        Parameter template = template(list);
        if (template == null) return false;
        for (Parameter s : sources) {
            if (!isListItem(s)) return false; // only items of lists can be added to a list
            if (!compatible(s, template, false)) return false;
        }
        return true;
    }

    /**
     * @param checkName whether names must be equal (names of list items are free)
     * @return true if the content of target can be replaced by the content of source
     */
    public static boolean compatible(Parameter source, Parameter target, boolean checkName) {
        if (source == null || target == null) return false;
        if (!source.getClass().equals(target.getClass())) return false;
        if (checkName && !Objects.equals(source.getName(), target.getName())) return false;
        if (target instanceof PluginParameter) return Objects.equals(((PluginParameter) source).getPluginType(), ((PluginParameter) target).getPluginType());
        if (target instanceof ListParameter) {
            ListParameter sl = (ListParameter) source, tl = (ListParameter) target;
            if (!Objects.equals(sl.getChildClass(), tl.getChildClass())) return false;
            return compatible(template(sl), template(tl), false);
        }
        if (target instanceof ConditionalParameterAbstract) {
            Parameter sa = (Parameter) ((ConditionalParameterAbstract) source).getActionableParameter();
            Parameter ta = (Parameter) ((ConditionalParameterAbstract) target).getActionableParameter();
            return compatible(sa, ta, true);
        }
        if (target instanceof ContainerParameter) { // same structure
            if (source.getChildCount() != target.getChildCount()) return false;
            for (int i = 0; i < target.getChildCount(); ++i) {
                if (!compatible((Parameter) source.getChildAt(i), (Parameter) target.getChildAt(i), true)) return false;
            }
        }
        return true;
    }

    /**
     * Converts the links to configurations of the library of the configuration blocks of p (and its descendants), so that they remain valid in the target context
     * @param sourceRepository repository of the source configuration (null: dataset)
     * @param targetRepository repository of the target configuration (null: dataset)
     */
    static void convertLinks(TreeNode p, GistRepository sourceRepository, GistRepository targetRepository) {
        if (p instanceof ConfigIDAware) {
            ConfigIDAware cia = (ConfigIDAware) p;
            String id = cia.getConfigID();
            if (id != null) {
                org.json.simple.JSONObject o = new org.json.simple.JSONObject();
                o.put(ConfigIDAware.key, id);
                GistConfiguration.convertLinks(o, sourceRepository, targetRepository);
                if (!id.equals(o.get(ConfigIDAware.key))) cia.setConfigID((String) o.get(ConfigIDAware.key));
            }
        }
        for (int i = 0; i < p.getChildCount(); ++i) convertLinks(p.getChildAt(i), sourceRepository, targetRepository);
    }

    /**
     * Performs the drop
     * @return the inserted or modified parameters, empty list if the drop failed
     */
    public static List<Parameter> perform(Plan plan, ConfigurationTreeModel model, GistRepository sourceRepository, GistRepository targetRepository) {
        List<Parameter> res = new ArrayList<>();
        if (plan.operation == OPERATION.INSERT) {
            ListParameter list = (ListParameter) plan.target;
            int idx = Math.min(plan.index, list.getChildCount());
            for (Parameter s : plan.sources) {
                Parameter item = list.createChildInstance();
                try {
                    item.setContentFrom(s);
                    item.setName(s.getName());
                    convertLinks(item, sourceRepository, targetRepository);
                } catch (RuntimeException e) {
                    logger.error("Could not copy {} into {}", s, list, e);
                    continue;
                }
                model.insertNodeInto(item, list, idx++);
                res.add(item);
            }
            model.nodeStructureChanged(list);
        } else {
            Parameter target = plan.target;
            Object backup = target.toJSONEntry();
            // the link of the target block to the library is kept (the drop changes its content, not its origin)
            String configID = null;
            int itemIdx = -1;
            Boolean autoUpdate = null;
            if (target instanceof ConfigIDAware) {
                ConfigIDAware cia = (ConfigIDAware) target;
                configID = cia.getConfigID();
                itemIdx = cia.getConfigItemIdx();
                autoUpdate = cia.getAutoUpdate() == null ? null : cia.getAutoUpdate().getSelected();
            }
            try {
                target.setContentFrom(plan.sources.get(0));
                convertLinks(target, sourceRepository, targetRepository);
                if (target instanceof ConfigIDAware) {
                    ConfigIDAware cia = (ConfigIDAware) target;
                    cia.setConfigID(configID);
                    cia.setConfigItemIdx(itemIdx);
                    if (autoUpdate != null && cia.getAutoUpdate() != null) cia.getAutoUpdate().setSelected(autoUpdate);
                }
            } catch (RuntimeException e) {
                logger.error("Could not set content of {} from {}", target, plan.sources.get(0), e);
                try {
                    target.initFromJSONEntry(backup);
                } catch (RuntimeException e2) {
                    logger.error("Could not restore {}", target, e2);
                }
                model.nodeStructureChanged(target);
                return res;
            }
            model.nodeStructureChanged(target);
            res.add(target);
        }
        return res;
    }
}
