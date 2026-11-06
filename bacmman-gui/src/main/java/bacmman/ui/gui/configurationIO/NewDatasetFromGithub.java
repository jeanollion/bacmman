package bacmman.ui.gui.configurationIO;

import bacmman.core.RepositoryGateway;
import bacmman.github.gist.*;
import bacmman.ui.gui.objects.ConfigurationGistTreeGenerator;
import bacmman.ui.logger.ProgressDisplay;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.List;

public class NewDatasetFromGithub extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JPanel credentialPanel;
    private JScrollPane remoteSelectorJSP;
    private JPanel buttonPanel;
    private JPanel configPanel;
    private JPanel selectorPanel;
    final RepositoryCredentialPanel repositoryCredentialPanel;
    RepositoryGateway gateway;
    JSONObject selectedXP;
    List<GistConfiguration> gists;
    boolean loggedIn;
    ConfigurationGistTreeGenerator remoteSelector;
    private static final Logger logger = LoggerFactory.getLogger(NewDatasetFromGithub.class);
    ProgressDisplay bacmmanLogger;

    public NewDatasetFromGithub(RepositoryGateway gateway, ProgressDisplay bacmmanLogger) {
        this.bacmmanLogger = bacmmanLogger;
        this.gateway = gateway;
        this.repositoryCredentialPanel = new RepositoryCredentialPanel(gateway, () -> {
            fetchGists();
            updateRemoteSelector();
        }, "Configurations", bacmmanLogger);
        $$$setupUI$$$();
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

        this.gateway = gateway;

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
        buttonOK.setEnabled(false);
    }

    private void onOK() {
        dispose();
    }

    private void onCancel() {
        selectedXP = null;
        dispose();
    }

    public static JSONObject promptExperiment(RepositoryGateway gateway, ProgressDisplay logger) {
        NewDatasetFromGithub dialog = new NewDatasetFromGithub(gateway, logger);
        dialog.setTitle("Select a configuration file");
        dialog.pack();
        dialog.setVisible(true);
        return dialog.selectedXP;
    }

    private void updateRemoteSelector() {
        if (gists == null) fetchGists();
        GistConfiguration lastSel = remoteSelector == null ? null : remoteSelector.getSelectedGist();
        int selectedOC = remoteSelector == null ? -1 : remoteSelector.getSelectedGistOC();
        if (remoteSelector != null) remoteSelector.flush();
        remoteSelector = new ConfigurationGistTreeGenerator(gists, GistConfiguration.TYPE.WHOLE, (gist, ocIdx) -> {
            if (gist != null) {
                selectedXP = GistConfiguration.toExternalContent(gist); // links are urls for repositories other than GitHub
            }
            else selectedXP = null;
            buttonOK.setEnabled(selectedXP != null);
        });
        remoteSelectorJSP.setViewportView(remoteSelector.getTree());
        if (lastSel != null) {
            remoteSelector.setSelectedGist(lastSel, selectedOC);
            remoteSelector.displaySelectedConfiguration();
        }

    }

    private void fetchGists() {
        GistRepository repo = repositoryCredentialPanel.getRepository();
        if (repo == null || (!GistRepository.TYPE.LOCAL.equals(repo.getType()) && repositoryCredentialPanel.getUsername().isEmpty())) {
            gists = Collections.emptyList();
            loggedIn = false;
        } else {
            gists = GistConfiguration.getConfigurations(repo, bacmmanLogger);
            loggedIn = repo.isWritable();
            repositoryCredentialPanel.persistUsername();
        }
        logger.debug("fetched gists: {}", gists.size());
    }

    private void createUIComponents() {
        credentialPanel = new JPanel(new BorderLayout());
        credentialPanel.add(repositoryCredentialPanel.getPanel(), BorderLayout.NORTH);
    }


    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        contentPane = new JPanel();
        contentPane.setLayout(new GridLayoutManager(2, 1, new Insets(10, 10, 10, 10), -1, -1));
        buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(buttonPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        buttonPanel.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        buttonPanel.add(panel1, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        buttonOK = new JButton();
        buttonOK.setText("OK");
        panel1.add(buttonOK, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        buttonCancel = new JButton();
        buttonCancel.setText("Cancel");
        panel1.add(buttonCancel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        selectorPanel = new JPanel();
        selectorPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(selectorPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        selectorPanel.add(credentialPanel, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final Spacer spacer2 = new Spacer();
        selectorPanel.add(spacer2, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        configPanel = new JPanel();
        configPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        selectorPanel.add(configPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        remoteSelectorJSP = new JScrollPane();
        configPanel.add(remoteSelectorJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(200, 200), new Dimension(200, 200), null, 0, false));
        remoteSelectorJSP.setBorder(BorderFactory.createTitledBorder(null, "Configuration files", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }
}
