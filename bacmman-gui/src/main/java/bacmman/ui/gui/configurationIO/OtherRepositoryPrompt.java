package bacmman.ui.gui.configurationIO;

import bacmman.core.RepositoryGateway;
import bacmman.github.gist.GistRepository;
import bacmman.github.gist.GitLabRepository;
import bacmman.github.gist.TokenAuth;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.Pair;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.security.GeneralSecurityException;

/**
 * Prompts for another repository of the same type as a given repository, used by "... to / from another account" actions:
 * another account of the same server for GitHub and GitLab, another folder for local repositories
 */
public class OtherRepositoryPrompt {

    /**
     * @param current repository of the library (defines the type and the server). null: GitHub
     * @return the other repository, with authentication, or null if canceled or if the token could not be loaded
     */
    public static GistRepository prompt(GistRepository current, RepositoryGateway gateway, String message, Component parent, ProgressDisplay pcb) {
        GistRepository.TYPE type = current == null ? GistRepository.TYPE.GITHUB : current.getType();
        switch (type) {
            case LOCAL: {
                JFileChooser fc = new JFileChooser(current instanceof bacmman.github.gist.LocalRepository ? ((bacmman.github.gist.LocalRepository) current).getFolder().getParentFile() : null);
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fc.setDialogTitle(message + " (folder of local repository)");
                if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
                File f = fc.getSelectedFile();
                return GistRepository.local(f);
            }
            case GITLAB:
            case GITHUB:
            default: {
                String server = current instanceof GitLabRepository ? ((GitLabRepository) current).getServerURL() : null;
                Pair<String, char[]> cred = PromptGithubCredentials.promptCredentials(gateway, message, type, server);
                if (cred == null) return null;
                try {
                    TokenAuth auth = new TokenAuth(cred.key, cred.value, type, server);
                    return GistRepository.TYPE.GITLAB.equals(type) ? GistRepository.gitlab(server, auth) : GistRepository.github(auth);
                } catch (GeneralSecurityException | RuntimeException e) {
                    if (pcb != null) pcb.setMessage("Could not load token for username: " + cred.key + " Wrong password ? Or no token was stored yet?");
                    return null;
                }
            }
        }
    }

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
