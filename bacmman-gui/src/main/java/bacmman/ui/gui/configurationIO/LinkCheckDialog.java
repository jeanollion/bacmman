package bacmman.ui.gui.configurationIO;

import bacmman.github.gist.GistConfiguration;
import bacmman.github.gist.GistDLModel;
import bacmman.github.gist.GistRepository;
import bacmman.github.gist.LinkCheck;
import bacmman.ui.logger.ProgressDisplay;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Before a configuration is stored in a repository: links that the users of this repository cannot read (configurations and DL model files of a local repository, for a configuration stored on GitHub or GitLab) can be replaced by a copy in the repository, by an existing item of the repository, or removed (configuration blocks only)
 */
public class LinkCheckDialog {
    static final Logger logger = LoggerFactory.getLogger(LinkCheckDialog.class);

    public enum ACTION {COPY, EXISTING, UNLINK, KEEP}

    static String label(ACTION a, LinkCheck.KIND kind) {
        return label(a, kind, false);
    }

    static String label(ACTION a, LinkCheck.KIND kind, boolean relative) {
        return label(a, kind, relative ? "dataset" : null);
    }

    static String label(ACTION a, LinkCheck.KIND kind, String relativeTo) {
        boolean relative = relativeTo != null;
        boolean model = LinkCheck.KIND.DL_MODEL.equals(kind);
        switch (a) {
            case COPY: return model ? "Copy the model (uploads the weights)" : "Copy to the repository";
            case EXISTING: return model ? "Use an existing model" : "Use an existing configuration";
            case UNLINK: return "Unlink (keep the content)";
            case KEEP:
            default: return relative ? "Keep the link (relative to the " + relativeTo + ")" : "Keep the link";
        }
    }

    public static class Result {
        public final boolean cancelled;
        public final int copied; // number of configurations or models copied to the repository
        Result(boolean cancelled, int copied) {
            this.cancelled = cancelled;
            this.copied = copied;
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * An item of the target repository the blocks can be linked to
     */
    static class Candidate {
        final String label, link, folder, name, itemId;
        Candidate(String folder, String name, String link, String itemId) {
            this.folder = folder;
            this.name = name;
            this.label = folder + "/" + name;
            this.link = link;
            this.itemId = itemId;
        }
        @Override public String toString() {return label;}
    }

    static class Option {
        final ACTION action;
        final String label;
        Option(ACTION action, LinkCheck.KIND kind) {
            this(action, kind, false);
        }
        Option(ACTION action, LinkCheck.KIND kind, boolean relative) {
            this(action, kind, relative ? "dataset" : null);
        }
        Option(ACTION action, LinkCheck.KIND kind, String relativeTo) {
            this.action = action;
            this.label = label(action, kind, relativeTo);
        }
        @Override public String toString() {return label;}
    }

    static class Row {
        final LinkCheck.Link link;
        final JComboBox<Option> action;
        final JComboBox<Candidate> existing;
        final String relativeLink; // link relative to the dataset folder, if the link is kept, or null
        Row(LinkCheck.Link link, List<Candidate> candidates, GistRepository target, java.nio.file.Path relativeBase, String baseName) {
            this.link = link;
            String rel = relativeBase == null ? null : ("local library".equals(baseName) ? bacmman.github.gist.LocalLinks.relativizeToLibrary(link.url, relativeBase) : bacmman.github.gist.LocalLinks.relativize(link.url, relativeBase));
            this.relativeLink = rel != null && bacmman.github.gist.LocalLinks.isRelative(rel) ? rel : null;
            existing = new JComboBox<>(candidates.toArray(new Candidate[0]));
            String previousId = link.getPreviousCopyId();
            Candidate previous = previousId == null ? null : candidates.stream().filter(c -> previousId.equals(c.itemId)).findFirst().orElse(null);
            String folder = null, name = null;
            if (link.getLinkedConfiguration() != null) {
                folder = link.getLinkedConfiguration().folder();
                name = link.getLinkedConfiguration().name();
            } else if (link.getModel() != null) {
                folder = link.getModel().folder;
                name = link.getModel().name;
            }
            String f = folder, n = name;
            Candidate sameName = f == null ? null : candidates.stream().filter(c -> f.equals(c.folder) && n.equals(c.name)).findFirst().orElse(null);
            if (previous != null) existing.setSelectedItem(previous);
            else if (sameName != null) existing.setSelectedItem(sameName);
            boolean model = LinkCheck.KIND.DL_MODEL.equals(link.kind);
            List<Option> options = new ArrayList<>();
            if (link.canCopy()) options.add(new Option(ACTION.COPY, link.kind));
            if (!candidates.isEmpty()) options.add(new Option(ACTION.EXISTING, link.kind));
            if (!model) options.add(new Option(ACTION.UNLINK, link.kind)); // a DL model parameter cannot be unlinked: the model could not be downloaded
            options.add(new Option(ACTION.KEEP, link.kind, relativeLink != null ? baseName : null));
            action = new JComboBox<>(options.toArray(new Option[0]));
            // default: links relative to the dataset are kept (users with the same folder layout can use them). Otherwise: the copy made previously or an item with the same name; configurations are copied, models are not (the weights are large): the link is kept
            if (relativeLink != null) setAction(ACTION.KEEP);
            else if (previous != null || (model && sameName != null)) setAction(ACTION.EXISTING);
            else if (!model && link.canCopy()) setAction(ACTION.COPY);
            else setAction(model ? ACTION.KEEP : ACTION.UNLINK);
            action.addActionListener(e -> updateEnabled());
            updateEnabled();
        }
        ACTION getAction() {
            Option o = (Option) action.getSelectedItem();
            return o == null ? null : o.action;
        }
        void updateEnabled() {
            existing.setEnabled(ACTION.EXISTING.equals(getAction()));
        }
        void setAction(ACTION a) {
            for (int i = 0; i < action.getItemCount(); ++i) if (action.getItemAt(i).action.equals(a)) {
                action.setSelectedIndex(i);
                return;
            }
        }
    }

    /**
     * Checks the links of a configuration before it is stored in {@param target}, and lets the user choose how to replace the links that the users of {@param target} cannot read. {@param content} is modified accordingly
     * @param type type of the configuration
     * @return the result: cancelled if the configuration must not be stored
     */
    public static Result check(JSONObject content, GistConfiguration.TYPE type, GistRepository target, Component parent, ProgressDisplay pcb) {
        return check(content, type, target, parent, pcb, null);
    }

    /**
     * @param relativeBase dataset folder the configuration comes from, or null. Links that are kept are stored relative to it (when a relative path exists): users who keep the same folder layout around their datasets can use them
     */
    public static Result check(JSONObject content, GistConfiguration.TYPE type, GistRepository target, Component parent, ProgressDisplay pcb, java.nio.file.Path relativeBase) {
        return check(content, type, target, parent, pcb, relativeBase, "dataset");
    }

    /**
     * @param baseName what {@param relativeBase} is, displayed to the user: "dataset" or "local library"
     */
    public static Result check(JSONObject content, GistConfiguration.TYPE type, GistRepository target, Component parent, ProgressDisplay pcb, java.nio.file.Path relativeBase, String baseName) {
        if (baseName == null) baseName = "dataset";
        final String bn = baseName;
        if (content == null || target == null || GistRepository.TYPE.LOCAL.equals(target.getType())) return new Result(false, 0);
        List<LinkCheck.Link> links = LinkCheck.unreachableLinks(content, type, target);
        if (links.isEmpty()) return new Result(false, 0);
        // items of the target repository
        List<GistConfiguration> configurations = links.stream().anyMatch(l -> LinkCheck.KIND.CONFIGURATION.equals(l.kind)) ? GistConfiguration.getConfigurations(target, pcb) : Collections.emptyList();
        List<GistDLModel> models = Collections.emptyList();
        if (links.stream().anyMatch(l -> LinkCheck.KIND.DL_MODEL.equals(l.kind))) {
            try {
                models = GistDLModel.list(target);
            } catch (IOException e) {
                logger.debug("Could not list DL models of {}", target, e);
            }
        }
        List<Candidate> modelCandidates = models.stream().filter(m -> m.getModelURL() != null)
                .map(m -> new Candidate(m.folder, m.name, m.getModelLink(), modelFileId(m, target))).collect(Collectors.toList());
        List<Row> rows = new ArrayList<>();
        for (LinkCheck.Link l : links) {
            List<Candidate> candidates = LinkCheck.KIND.DL_MODEL.equals(l.kind) ? modelCandidates : configurations.stream()
                    .filter(g -> l.getType() == null || Objects.equals(g.getType(), l.getType()))
                    .map(g -> new Candidate(g.folder(), g.name(), GistConfiguration.storedLink(target.toURL(g.getID()), target, false), g.getID())).collect(Collectors.toList());
            rows.add(new Row(l, candidates, target, relativeBase, bn));
        }

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 4;
        String folders = links.stream().map(LinkCheck.Link::getLocation).distinct().map(LinkCheckDialog::escape).collect(Collectors.joining("<br/>"));
        boolean hasModels = links.stream().anyMatch(l -> LinkCheck.KIND.DL_MODEL.equals(l.kind));
        panel.add(new JLabel("<html>This configuration links " + (hasModels ? "configurations or DL models" : "configurations") + " stored in a folder of this computer (local repository):<br/>" + folders + "<br/>"
                + "Users of " + escape(String.valueOf(target)) + " cannot read them: " + (hasModels ? "DL models could not be downloaded, " : "") + "linked blocks would not be updated, and <em>Update from configuration library</em> would fail.<br/>"
                + (rows.stream().anyMatch(r -> r.relativeLink != null) ? "Links that are kept are stored relative to the folder of the " + bn + ": they work where the folders have the same relative location.<br/>" : "")
                + "Choose how to replace each link:</html>"), c);
        c.gridwidth = 1;
        c.gridy++;
        for (String h : new String[]{"Block", "Linked item", "Action", "Item of the repository"}) {
            panel.add(new JLabel("<html><b>" + h + "</b></html>"), c);
            c.gridx++;
        }
        for (Row r : rows) {
            c.gridy++;
            c.gridx = 0;
            panel.add(new JLabel(String.join(", ", r.link.getBlockDescriptions())), c);
            c.gridx++;
            JLabel linked = new JLabel(r.link.getName());
            linked.setToolTipText(r.link.canCopy() ? r.link.getLocation() + (r.relativeLink != null ? " (kept as " + r.relativeLink + ")" : "") : "The linked configuration could not be read: it cannot be copied (" + r.link.url + ")");
            panel.add(linked, c);
            c.gridx++;
            panel.add(r.action, c);
            c.gridx++;
            panel.add(r.existing, c);
        }
        if (rows.size() > 1) { // same action for all links (when available)
            c.gridy++;
            c.gridx = 1;
            c.anchor = GridBagConstraints.EAST;
            c.fill = GridBagConstraints.NONE;
            panel.add(new JLabel("All links:"), c);
            c.gridx++;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            JComboBox<Option> all = new JComboBox<>(new Option[]{new Option(ACTION.COPY, LinkCheck.KIND.CONFIGURATION), new Option(ACTION.EXISTING, LinkCheck.KIND.CONFIGURATION), new Option(ACTION.UNLINK, LinkCheck.KIND.CONFIGURATION), new Option(ACTION.KEEP, LinkCheck.KIND.CONFIGURATION)});
            all.setSelectedItem(null);
            all.addActionListener(e -> {
                if (all.getSelectedItem() != null) for (Row r : rows) r.setAction(((Option) all.getSelectedItem()).action);
            });
            panel.add(all, c);
        }
        String[] options = new String[]{"OK", "Cancel"}; // links can be kept with the action of each row
        int choice = JOptionPane.showOptionDialog(parent, panel, "Links to items of this computer", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice != 0) return new Result(true, 0);
        int copied = 0;
        Cursor cursor = parent == null ? null : parent.getCursor();
        if (parent != null) parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            for (Row r : rows) {
                ACTION a = r.getAction();
                if (ACTION.COPY.equals(a) && LinkCheck.KIND.DL_MODEL.equals(r.link.kind)) { // the user stores the model with the DL model library: folder, name, account of the model file
                    String newLink = storeModelWithLibrary(r.link, target, parent, pcb);
                    if (newLink != null) {
                        r.link.relink(newLink);
                        ++copied;
                    } else if (pcb != null) pcb.setMessage("Model " + r.link.getName() + " was not stored: the link is kept");
                } else if (ACTION.COPY.equals(a)) {
                    try {
                        if (pcb != null) pcb.setMessage("Copying " + r.link.describe() + " to " + target);
                        LinkCheck.copy(r.link, target, pcb);
                        ++copied;
                    } catch (IOException | RuntimeException e) {
                        logger.error("Error copying linked item", e);
                        boolean model = LinkCheck.KIND.DL_MODEL.equals(r.link.kind);
                        if (!bacmman.utils.Utils.promptBoolean("Could not copy " + r.link.describe() + ": " + e.getMessage() + "\n" + (model ? "Keep the link" : "Unlink the block(s)") + " and store the configuration ?", parent)) return new Result(true, copied);
                        if (!model) r.link.unlink();
                    }
                } else if (ACTION.EXISTING.equals(a) && r.existing.getSelectedItem() != null) {
                    r.link.relink(((Candidate) r.existing.getSelectedItem()).link);
                } else if (ACTION.UNLINK.equals(a)) {
                    r.link.unlink();
                }
            }
        } finally {
            if (parent != null) parent.setCursor(cursor);
        }
        GistConfiguration.relativizeLocalLinks(content, relativeBase, "local library".equals(bn)); // links that are kept
        if (pcb != null && copied > 0) pcb.setMessage(copied + " linked item(s) copied to " + target);
        return new Result(false, copied);
    }

    /**
     * Opens the DL model library in a popup, on the repository {@param target}, ready to store the model of {@param link}: the user chooses the folder, the name and the account of the model file (weights), and stores the model
     * @return link to the model file of the stored model, or null if the model was not stored
     */
    static String storeModelWithLibrary(LinkCheck.Link link, GistRepository target, Component parent, ProgressDisplay pcb) {
        java.io.File tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("bacmman_model").toFile();
            if (pcb != null) pcb.setMessage("Reading model " + link.getName() + " from " + link.getLocation());
            java.io.File weights = new bacmman.github.gist.LargeFileGist(link.repository, link.itemId).retrieveFile(tmp, false, true, null, null, pcb);
            bacmman.core.RepositoryGateway gateway = bacmman.core.Core.getCore().getGithubGateway();
            DLModelsLibrary library = new DLModelsLibrary(gateway, tmp.getAbsolutePath(), () -> {}, pcb);
            library.setRepository(target);
            JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Store the model in " + target, Dialog.ModalityType.APPLICATION_MODAL);
            String[] result = new String[1];
            library.setModelStoredCallback(m -> {
                result[0] = m.getModelLink();
                dialog.dispose();
            });
            GistDLModel model = link.getModel();
            bacmman.github.gist.DLModelMetadata metadata = model != null ? model.getMetadata() : new bacmman.github.gist.DLModelMetadata();
            library.requestModelUpload(metadata, weights, model == null ? null : model.folder, model == null ? null : model.name, "Store Model");
            JPanel content = new JPanel(new BorderLayout());
            content.add(new JLabel("<html>Check the repository (and the model file account to store the weights in another account), then click <b>Store Model</b>: choose the folder and the name of the model.<br/>Close this window to keep the link to the model of this computer.</html>"), BorderLayout.NORTH);
            content.add(library.getMainPanel(), BorderLayout.CENTER);
            dialog.setContentPane(content);
            dialog.setSize(1000, 650);
            dialog.setLocationRelativeTo(parent);
            dialog.setVisible(true); // blocks until the model is stored or the window is closed
            if (result[0] != null && bacmman.ui.GUI.hasInstance() && bacmman.ui.GUI.getInstance().getDLModelLibraryIfOpen() != null) bacmman.ui.GUI.getInstance().getDLModelLibraryIfOpen().refreshIfShowing(target); // the new model is listed in the library of the main window
            return result[0];
        } catch (IOException | RuntimeException e) {
            logger.error("Error storing model", e);
            if (pcb != null) pcb.setMessage("Could not store model " + link.getName() + ": " + e.getMessage());
            return null;
        } finally {
            if (tmp != null) bacmman.utils.Utils.deleteDirectory(tmp);
        }
    }

    /**
     * @return id of the model file of {@param m} in {@param repository}, or null
     */
    static String modelFileId(GistDLModel m, GistRepository repository) {
        String u = m.getModelURL();
        if (u == null) return null;
        if (!u.contains("/")) return u;
        return repository.idFromURL(u);
    }
}
