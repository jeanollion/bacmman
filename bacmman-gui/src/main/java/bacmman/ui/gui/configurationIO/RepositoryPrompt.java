package bacmman.ui.gui.configurationIO;

import bacmman.core.RepositoryGateway;
import bacmman.github.gist.GistRepository;
import bacmman.ui.logger.ProgressDisplay;

import javax.swing.*;
import java.awt.*;

/**
 * Prompts for a repository of any type (GitHub, GitLab on any server, local folder), with its credentials
 */
public class RepositoryPrompt {

    /**
     * Prompts for a repository of any type (GitHub, GitLab on any server, local folder), with its credentials
     * @param writable if true, the repository must be writable (authentication with a token for remote repositories)
     * @return the selected repository, or null if canceled or not writable
     */
    public static GistRepository promptRepository(RepositoryGateway gateway, String title, boolean writable, Component parent, ProgressDisplay pcb) {
        RepositoryCredentialPanel panel = new RepositoryCredentialPanel(gateway, null, "Items", pcb, "_PROMPT");
        int res = JOptionPane.showConfirmDialog(parent, panel.getPanel(), title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return null;
        panel.persistUsername();
        GistRepository repo = panel.getRepository();
        if (repo == null) {
            if (pcb != null) pcb.setMessage("No repository selected");
            return null;
        }
        if (writable && !repo.isWritable()) {
            if (pcb != null) pcb.setMessage("Repository " + repo.getKey() + " is not writable: set a password to load a stored token (or generate one)");
            return null;
        }
        return repo;
    }

    /**
     * @return true if both repositories are the same (same account or same folder)
     */
    public static boolean same(GistRepository r1, GistRepository r2) {
        return r1 != null && r2 != null && r1.getKey().equals(r2.getKey());
    }
}
