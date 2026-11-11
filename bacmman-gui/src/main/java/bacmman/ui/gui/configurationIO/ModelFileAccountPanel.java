package bacmman.ui.gui.configurationIO;

import bacmman.github.gist.GistRepository;
import bacmman.github.gist.GitLabRepository;
import bacmman.github.gist.TokenAuth;
import bacmman.ui.PropertyUtils;

import javax.swing.*;
import java.awt.*;
import java.security.GeneralSecurityException;

/**
 * Optional account where model files (weights of DL models) are stored, on the same server as the repository of the DL models.
 * If no username is set, model files are stored in the repository of the DL models. Not available for local repositories.
 */
public class ModelFileAccountPanel {
    final JPanel panel;
    final JTextField username;
    final JPasswordField password;

    /**
     * @param persistenceKey key used to store the username
     */
    public ModelFileAccountPanel(String persistenceKey) {
        panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Model file account (optional)"));
        panel.setToolTipText("<html>Optional: account of the same server as the repository of DL models, where model files (weights) are stored. DL models are stored in the repository, with links to the files stored in this account.<br />A token must have been stored for this account (password required).<br />If no username is set, model files are stored along with the models.</html>");
        username = new JTextField();
        username.setToolTipText("Username of the account where model files are stored. Empty: model files are stored with the models");
        password = new JPasswordField();
        password.setToolTipText("Password used to encrypt the token stored for this account");
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 2, 0, 2);
        c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("Username"), c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        username.setPreferredSize(new Dimension(150, username.getPreferredSize().height));
        panel.add(username, c);
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JLabel("Password"), c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        password.setPreferredSize(new Dimension(150, password.getPreferredSize().height));
        panel.add(password, c);
        PropertyUtils.setPersistent(username, persistenceKey, "", true);
    }

    public JPanel getPanel() {
        return panel;
    }

    /**
     * Model file accounts are not available for local repositories: the panel is hidden
     */
    public void updateVisibility(GistRepository.TYPE repositoryType) {
        panel.setVisible(!GistRepository.TYPE.LOCAL.equals(repositoryType));
    }

    /**
     * @param repository repository of the DL models
     * @return repository where model files are stored: same server as {@param repository}, account set in this panel. null if no account is set (model files stored with the models) or if {@param repository} is local
     * @throws IllegalArgumentException if the token of the account cannot be loaded
     */
    public GistRepository getRepository(GistRepository repository) {
        if (!panel.isVisible() || username.getText().trim().isEmpty() || repository == null || GistRepository.TYPE.LOCAL.equals(repository.getType())) return null;
        String user = username.getText().trim();
        if (password.getPassword().length == 0) throw new IllegalArgumentException("Set the password of the model file account " + user);
        TokenAuth auth;
        try {
            auth = new TokenAuth(user, password.getPassword(), repository.getType(), repository instanceof GitLabRepository ? ((GitLabRepository) repository).getServerURL() : null);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("Could not load the token of the model file account " + user + ": wrong password or no token stored (generate one from the library window)");
        }
        if (repository instanceof GitLabRepository) return GistRepository.gitlab(((GitLabRepository) repository).getServerURL(), auth);
        else return GistRepository.github(auth);
    }
}
