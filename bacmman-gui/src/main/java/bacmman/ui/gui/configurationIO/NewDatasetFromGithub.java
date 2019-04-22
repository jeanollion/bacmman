package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.github.gist.BasicAuth;
import bacmman.github.gist.GistConfiguration;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.UserAuth;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import org.json.simple.JSONObject;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class NewDatasetFromGithub extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JPasswordField password;
    private JTextField username;
    private JScrollPane remoteSelectorJSP;
    private JPanel buttonPanel;
    private JPanel configPanel;
    private JPanel selectorPanel;
    Map<String, char[]> savedPassword;
    JSONObject selectedXP;
    List<GistConfiguration> gists;
    boolean loggedIn;
    ConfigurationGistTreeGenerator remoteSelector;
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(NewDatasetFromGithub.class);
    public NewDatasetFromGithub(Map<String, char[]> savedPassword) {

        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(buttonOK);

        buttonOK.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onOK();
            }
        });

        buttonCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        });

        this.savedPassword = savedPassword;

        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        username.addActionListener(e -> {
            if (password.getPassword().length==0 && savedPassword.containsKey(username.getText())) password.setText(String.valueOf(savedPassword.get(username.getText())));
            fetchGists();
            updateRemoteSelector();
        });
        password.addActionListener(e-> {
            savedPassword.put(username.getText(), password.getPassword());
            fetchGists();
            updateRemoteSelector();
        });
        // persistence of username account:
        PropertyUtils.setPersistant(username, "GITHUB_USERNAME", "jeanollion", true);
        buttonOK.setEnabled(false);
    }

    private void onOK() {
        // add your code here
        dispose();
    }

    private void onCancel() {
        selectedXP = null;
        dispose();
    }

    public static JSONObject promptExperiment(Map<String, char[]> savedPassword) {
        NewDatasetFromGithub dialog = new NewDatasetFromGithub(savedPassword);
        dialog.setTitle("Select a configuration file");
        dialog.pack();
        dialog.setVisible(true);
        //System.exit(0);
        return dialog.selectedXP;
    }
    private void updateRemoteSelector() {
        if (gists==null) fetchGists();
        GistConfiguration lastSel = remoteSelector==null ? null : remoteSelector.getSelectedGist();
        if (remoteSelector!=null) remoteSelector.flush();
        remoteSelector = new ConfigurationGistTreeGenerator(gists, GistConfiguration.TYPE.WHOLE, gist->{
            if (gist!=null) selectedXP = gist.gist.getContent();
            else  selectedXP=null;
            buttonOK.setEnabled(selectedXP!=null);
        });
        remoteSelectorJSP.setViewportView(remoteSelector.getTree());
        if (lastSel!=null) {
            remoteSelector.setSelectedGist(lastSel);
            remoteSelector.displaySelectedConfiguration();
        }

    }
    private void fetchGists() {
        String account = username.getText();
        if (account.length()==0) {
            gists = Collections.emptyList();
            loggedIn = false;
        }
        else {
            UserAuth auth = getAuth();
            if (auth instanceof NoAuth) {
                gists = GistConfiguration.getPublicConfigurations(account);
                loggedIn = false;
            } else {
                gists = GistConfiguration.getConfigurations(auth);
                loggedIn = true;
            }
        }
        logger.debug("fetched gists: {}", gists.size());
    }

    private UserAuth getAuth() {
        if (password.getPassword().length==0) return new NoAuth();
        else return new BasicAuth(username.getText(), String.valueOf(password.getPassword()));
    }
}
