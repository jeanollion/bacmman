package bacmman.ui.gui.configurationIO;

import bacmman.core.GithubGateway;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.ui.gui.objects.CollapsiblePanel;
import bacmman.ui.gui.objects.DLModelGistTreeGenerator;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.IconUtils;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import ij.ImagePlus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.github.gist.GistDLModel.BASE_URL;
import static bacmman.ui.gui.Utils.getDisplayedImage;

public class DLModelsLibrary {
    private JPanel dlModelsPanel;
    private JButton newButton;
    private JScrollPane DLModelsJSP;
    private JPanel contentPane;
    private JPanel credentialPanel;
    private JButton deleteButton;
    private JButton updateButton;
    private JPanel actionPanel;
    private JButton duplicateButton;
    private JButton setThumbnailButton;
    private JButton configureParameterButton;
    JFrame displayingFrame;
    GithubGateway gateway;
    List<GistDLModel> gists;
    boolean loggedIn = false;
    DLModelGistTreeGenerator tree;
    private static final Logger logger = LoggerFactory.getLogger(DLModelsLibrary.class);
    String workingDirectory;
    ProgressLogger pcb;
    BiConsumer<String, DLModelMetadata> configureParameterCallback;
    JDialog dia;
    Runnable onClose;
    GitCredentialPanel gitCredentialPanel;

    public DLModelsLibrary(GithubGateway gateway, String workingDirectory, Runnable onClose, ProgressLogger pcb) {
        this.gateway = gateway;
        this.workingDirectory = workingDirectory;
        this.pcb = pcb;
        this.onClose = onClose;
        if (pcb instanceof JFrame) displayingFrame = (JFrame) pcb;
        this.gitCredentialPanel = new GitCredentialPanel(gateway, () -> {
            fetchGists();
            updateGistDisplay();
            updateEnableButtons();
        }, "Models", pcb);
        $$$setupUI$$$();
        updateEnableButtons();

        newButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            // check if a folder is selected
            String currentFolder = tree.getSelectedFolder();
            SaveDLModelGist form = new SaveDLModelGist(gateway);
            if (currentFolder != null) form.setFolder(currentFolder);
            form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb);

            form.display(displayingFrame, "Store dl model");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
            if (exists) {
                GUI.log("Gist already exists.");
                return;
            }
            if (!Utils.isValid(form.name(), false)) {
                GUI.log("Invalid name (no special chars allowed except underscores)");
                return;
            }
            if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                GUI.log("Invalid folder name (no special chars allowed)");
                return;
            }
            GistDLModel toSave = new GistDLModel(form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
            try {
                toSave.createNewGist(getAuth());
                gists.add(toSave);
                updateGistDisplay();
                tree.setSelectedGist(toSave);
            } catch (IOException ex) {
                if (pcb != null) pcb.setMessage("Error saving gist: " + ex.getMessage());
                logger.error("Error saving gist", ex);
            }

        });

        deleteButton.addActionListener(e -> {
            deleteSelectedGists(true);
        });
        deleteButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action del = new AbstractAction("Delete Selected Models and keep associated Files") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            deleteSelectedGists(false);
                        }
                    };
                    menu.add(del);
                    menu.show(deleteButton, evt.getX(), evt.getY());
                }
            }
        });
        updateButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            GistDLModel gist = tree.getSelectedGist();
            if (gist == null) return;
            SaveDLModelGist form = new SaveDLModelGist(gateway);
            form.setFolder(gist.folder).disableFolderField()
                    .setName(gist.name).disableNameField()
                    .setDescription(gist.getDescription())
                    .setURL(gist.getModelURL())
                    .setMetadata(gist.getMetadata())
                    .setVisible(gist.isVisible()).disableVisibleField();
            form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb);
            form.display(displayingFrame, "Update model...");
            if (form.canceled) return;
            gist.setDescription(form.description());
            gist.setContent(form.url(), form.metadata());
            gist.uploadIfNecessary(getAuth());
            tree.updateSelectedGistDisplay();
            //updateGistDisplay();
        });
        duplicateButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            GistDLModel gist = tree.getSelectedGist();
            if (gist == null) return;
            SaveDLModelGist form = new SaveDLModelGist(gateway);
            form.setFolder(gist.folder)
                    .setName(gist.name)
                    .setDescription(gist.getDescription())
                    .setURL(gist.getModelURL())
                    .setMetadata(gist.getMetadata())
                    .setVisible(gist.isVisible());
            form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb);
            form.display(displayingFrame, "Duplicate model...");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
            if (exists) {
                pcb.setMessage("Model already exists.");
                return;
            }
            if (!Utils.isValid(form.name(), false)) {
                pcb.setMessage("Invalid name");
                return;
            }
            if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                pcb.setMessage("Invalid folder name");
                return;
            }
            GistDLModel toSave = new GistDLModel(form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
            List<BufferedImage> otherThumb = gist.getThumbnail();
            if (otherThumb != null) for (BufferedImage b : otherThumb) toSave.appendThumbnail(b);
            try {
                toSave.createNewGist(getAuth());
                gists.add(toSave);
                updateGistDisplay();
                tree.setSelectedGist(toSave);
            } catch (IOException ex) {
                if (pcb != null) pcb.setMessage("Error saving gist: " + ex.getMessage());
                logger.error("Error saving gist", ex);
            }
        });
        duplicateButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action dupOther = new AbstractAction("Duplicate To another Account") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            Pair<String, char[]> cred = PromptGithubCredentials.promptCredentials(gateway, "Account to duplicate to");
                            if (cred != null) {
                                try {
                                    TokenAuth auth2 = new TokenAuth(cred.key, cred.value);
                                    GistDLModel gist = tree.getSelectedGist();
                                    if (gist == null) return;
                                    SaveDLModelGist form = new SaveDLModelGist(gateway);
                                    form.setFolder(gist.folder)
                                            .setName(gist.name)
                                            .setDescription(gist.getDescription())
                                            .setURL(gist.getModelURL())
                                            .setMetadata(gist.getMetadata())
                                            .setVisible(gist.isVisible());
                                    form.setAuthAndDefaultDirectory(auth2, workingDirectory, pcb);
                                    form.display(displayingFrame, "Duplicate model to another account...");
                                    if (form.canceled) return;
                                    if (!Utils.isValid(form.name(), false)) {
                                        pcb.setMessage("Invalid name");
                                        return;
                                    }
                                    if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                                        pcb.setMessage("Invalid folder name");
                                        return;
                                    }
                                    if (cred.key.equals(gitCredentialPanel.getUsername())) { // same account
                                        boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
                                        if (exists) {
                                            pcb.setMessage("Model already exists.");
                                            return;
                                        }
                                    } else { // check on remote
                                        List<GistConfiguration> otherConfigs = GistConfiguration.getConfigurations(auth2, pcb);
                                        boolean exists = otherConfigs.stream().anyMatch(g -> g.folder().equals(form.folder()) && g.name().equals(form.name()));
                                        if (exists) {
                                            if (pcb != null)
                                                pcb.setMessage("Configuration already exists on other account.");
                                            return;
                                        }
                                    }
                                    GistDLModel toSave = new GistDLModel(form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
                                    List<BufferedImage> otherThumb = gist.getThumbnail();
                                    if (otherThumb != null)
                                        for (BufferedImage b : otherThumb) toSave.appendThumbnail(b);
                                    toSave.createNewGist(auth2);
                                    if (cred.key.equals(gitCredentialPanel.getUsername())) { // same account
                                        gists.add(toSave);
                                        updateGistDisplay();
                                        tree.setSelectedGist(toSave);
                                    }
                                } catch (GeneralSecurityException ex) {
                                    pcb.setMessage("Could not load token for username: " + cred.key + " Wrong password ? Or no token was stored yet?");
                                } catch (IOException ex) {
                                    if (pcb != null) pcb.setMessage("Error saving gist" + ex.getMessage());
                                    logger.error("Error saving gist", ex);
                                }
                            }
                        }
                    };
                    dupOther.setEnabled(tree != null && tree.getSelectedGist() != null);
                    menu.add(dupOther);
                    menu.show(duplicateButton, evt.getX(), evt.getY());
                }
            }
        });
        setThumbnailButton.addActionListener(e -> {
            if (tree == null) return;
            Object image = ImageWindowManagerFactory.getImageManager().getDisplayer().getCurrentDisplayedImage();
            if (image != null) { // if null -> remove thumbnail ?
                if (image instanceof ImagePlus) {
                    ImagePlus ip = (ImagePlus) image;
                    BufferedImage bimage = getDisplayedImage(ip);
                    bimage = IconUtils.zoomToSize(bimage, 128);
                    tree.setIconToCurrentlySelectedGist(bimage);
                    tree.getSelectedGistNode().gist.uploadThumbnail(getAuth());
                }
            }
        });
        setThumbnailButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action append = new AbstractAction("Append Thumbnail") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            Object image = ImageWindowManagerFactory.getImageManager().getDisplayer().getCurrentDisplayedImage();
                            if (image != null) { // if null -> remove thumbnail ?
                                if (image instanceof ImagePlus) {
                                    ImagePlus ip = (ImagePlus) image;
                                    BufferedImage bimage = getDisplayedImage(ip);
                                    bimage = IconUtils.zoomToSize(bimage, 128);
                                    tree.appendIconToCurrentlySelectedGist(bimage);
                                    tree.getSelectedGist().uploadThumbnail(getAuth());
                                }
                            }
                        }
                    };
                    menu.add(append);
                    append.setEnabled(loggedIn && tree != null && tree.getTree().getSelectionCount() == 1);
                    menu.show(setThumbnailButton, evt.getX(), evt.getY());
                }
            }
        });
        configureParameterButton.addActionListener(e -> {
            if (configureParameterCallback != null) {
                GistDLModel gist = tree.getSelectedGist();
                if (gist != null) configureParameterCallback.accept(gist.getModelID(), gist.getMetadata());
            }
        });
        /*if (!gitCredentialPanel.getUsername().isEmpty()) {
            fetchGists();
            updateGistDisplay();
        }*/
    }

    public boolean setSelectedGist(String id, boolean modelID) {
        if (id == null) tree.setSelectedGist(null);
        else {
            GistDLModel gist = tree.getByID(id, modelID);
            if (gist != null) {
                tree.setSelectedGist(gist);
                return true;
            }
        }
        return false;
    }

    public DLModelsLibrary setProgressLogger(ProgressLogger progressLogger) {
        this.pcb = progressLogger;
        return this;
    }

    public DLModelsLibrary setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    public DLModelsLibrary setConfigureParameterCallback(BiConsumer<String, DLModelMetadata> callback) {
        this.configureParameterCallback = callback;
        updateEnableButtons();
        return this;
    }

    public void deleteSelectedGists(boolean deleteFile) {
        if (tree == null || !loggedIn) return;
        GistDLModel gist = tree.getSelectedGist();
        if (gist == null) {
            String folder = tree.getSelectedFolder();
            if (folder == null) return;
            if (!Utils.promptBoolean("Delete all model files from selected folder ? ", actionPanel)) return;
            gists.stream().filter(g -> folder.equals(g.folder)).collect(Collectors.toList()).forEach(g -> {
                gists.remove(g);
                g.delete(getAuth(), deleteFile);
            });
            tree.removeFolder(folder);
        } else {
            gist.delete(getAuth(), deleteFile);
            gists.remove(gist);
            tree.removeGist(gist);
        }
    }

    public void uploadModel(UserAuth modelAuth, DLModelMetadata metadata, File model) {
        if (tree == null || !loggedIn) return;
        GistDLModel gist = tree.getSelectedGist();
        boolean update = gist != null && Utils.promptBoolean("Update selected Model ?", this.displayingFrame);
        SaveDLModelGist form = new SaveDLModelGist(gateway);
        if (gist != null) {
            if (update) {
                form.setFolder(gist.folder).disableFolderField()
                        .setName(gist.name).disableNameField()
                        .setDescription(gist.getDescription())
                        .setVisible(gist.isVisible()).disableVisibleField();
            } else {
                form.setFolder(gist.folder)
                        .setDescription(gist.getDescription())
                        .setVisible(gist.isVisible());
            }
            if (metadata == null) form.setMetadata(gist.getMetadata());
        } else {
            String folder = tree.getSelectedFolder();
            if (folder != null) form.setFolder(folder);
        }
        if (metadata != null) form.setMetadata(metadata);
        form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb);

        logger.debug("uploading new gist...");
        form.uploadFile(model, modelAuth, true);
        form.display(displayingFrame, update ? "Update model..." : "Upload model...");
        if (form.canceled) {
            boolean del = JSONQuery.delete(BASE_URL + "/gists/" + form.id(), modelAuth);
            return;
        } else if (update && gist.getModelID() != null && !gist.getModelID().isEmpty()) { // delete current model file
            JSONQuery.delete(BASE_URL + "/gists/" + gist.getModelID(), modelAuth);
        }
        if (!update) {
            if (!Utils.isValid(form.name(), false)) {
                if (pcb != null) {
                    pcb.setMessage("Invalid name (no special chars allowed except underscores)");
                    pcb.setMessage("Uploaded Model URL : " + form.url());
                }
                return;
            }
            if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                if (pcb != null) {
                    pcb.setMessage("Invalid folder name (no special chars allowed)");
                    pcb.setMessage("Uploaded Model URL : " + form.url());
                }
                return;
            }
            boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
            if (exists) {
                if (pcb != null) {
                    pcb.setMessage("Model already exists.");
                    pcb.setMessage("Uploaded Model URL : " + form.url());
                }
                return;
            }
            gist = new GistDLModel(form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
            try {
                gist.createNewGist(getAuth());
                gists.add(gist);
                updateGistDisplay();
                tree.setSelectedGist(gist);
            } catch (IOException e) {
                if (pcb != null) pcb.setMessage("Error saving gist: " + e.getMessage());
                logger.error("Error saving gist", e);
            }

        } else {
            gist.setDescription(form.description());
            gist.setContent(form.url(), form.metadata());
            gist.uploadIfNecessary(getAuth());
        }
    }

    private void updateGistDisplay() {
        if (gists == null) fetchGists();
        GistDLModel lastSel = tree == null ? null : tree.getSelectedGist();
        Stream expState = tree == null ? null : tree.getExpandedState();
        if (tree != null) tree.flush();
        tree = new DLModelGistTreeGenerator(gists, this::updateEnableButtons, workingDirectory, this::getAuth, pcb);
        DLModelsJSP.setViewportView(tree.getTree());
        if (lastSel != null) tree.setSelectedGist(lastSel);
        tree.setExpandedState(expState);
    }

    private void updateEnableButtons() {
        boolean gistSel = tree != null && tree.getSelectedGist() != null;
        boolean folderSel = tree != null && tree.getSelectedFolder() != null;
        newButton.setEnabled(loggedIn);
        duplicateButton.setEnabled(loggedIn && gistSel);
        deleteButton.setEnabled(loggedIn && (gistSel || folderSel));
        updateButton.setEnabled(loggedIn && gistSel);
        setThumbnailButton.setEnabled(loggedIn && gistSel);
        configureParameterButton.setEnabled(gistSel && configureParameterCallback != null);
    }


    private void fetchGists() {
        String account = gitCredentialPanel.getUsername();
        if (account.length() == 0) {
            gists = Collections.emptyList();
            loggedIn = false;
        } else {
            UserAuth auth = getAuth();
            if (auth instanceof NoAuth) {
                gists = GistDLModel.getPublic(account, pcb);
                loggedIn = false;
            } else {
                gists = GistDLModel.get(auth, pcb);
                if (gists == null) {
                    gists = GistDLModel.getPublic(account, pcb);
                    loggedIn = false;
                    GUI.log("Could authenticate. Wrong username / password / token ?");
                } else loggedIn = true;
            }
            PropertyUtils.set("GITHUB_USERNAME", gitCredentialPanel.getUsername());
            PropertyUtils.addFirstStringToList("GITHUB_USERNAME", gitCredentialPanel.getUsername());
        }
        logger.debug("fetched gists: {}", gists.size());
    }

    private UserAuth getAuth() {
        return gitCredentialPanel.getAuth();
    }

    public void display(JFrame parent) {
        dia = new Dial(parent, "Online DL Model Library");
        dia.setVisible(true);
    }

    public boolean close() {
        if (dia != null) dia.dispose();
        if (tree != null) tree.flush();
        if (onClose != null) onClose.run();
        return true;
    }

    public JPanel getMainPanel() {
        return contentPane;
    }

    public void focusGained() {

    }

    public void focusLost() {

    }

    public void toFront() {
        dia.toFront();
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
        contentPane.setLayout(new GridLayoutManager(3, 1, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(credentialPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        dlModelsPanel = new JPanel();
        dlModelsPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(dlModelsPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        dlModelsPanel.setBorder(BorderFactory.createTitledBorder(null, "DL Models", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        DLModelsJSP = new JScrollPane();
        dlModelsPanel.add(DLModelsJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(600, 200), null, null, 0, false));
        actionPanel = new JPanel();
        actionPanel.setLayout(new GridLayoutManager(2, 3, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(actionPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        newButton = new JButton();
        newButton.setText("New");
        newButton.setToolTipText("Store a new model");
        actionPanel.add(newButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        deleteButton = new JButton();
        deleteButton.setText("Delete");
        deleteButton.setToolTipText("Delete selected model");
        actionPanel.add(deleteButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        duplicateButton = new JButton();
        duplicateButton.setText("Duplicate");
        duplicateButton.setToolTipText("Duplicate selected model. Right click: duplicate to another account");
        actionPanel.add(duplicateButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        setThumbnailButton = new JButton();
        setThumbnailButton.setText("Set Thumbnail");
        setThumbnailButton.setToolTipText("Set the active image as thumbnail for the selected model.  Click update to upload the thumbnail.");
        actionPanel.add(setThumbnailButton, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        updateButton = new JButton();
        updateButton.setText("Update");
        updateButton.setToolTipText("Modify and update selected model");
        actionPanel.add(updateButton, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        configureParameterButton = new JButton();
        configureParameterButton.setEnabled(false);
        configureParameterButton.setText("Configure Parameter");
        configureParameterButton.setToolTipText("Configure the current parameter using the metadata of the selected model. ");
        actionPanel.add(configureParameterButton, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

    private void createUIComponents() {
        credentialPanel = new CollapsiblePanel("Git Credentials", gitCredentialPanel.getPanel());
    }

    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, false);
            getContentPane().add(contentPane);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent evt) {
                    dia = null;
                    close();
                    logger.debug("DL Model Library closed successfully");
                }
            });
        }
    }


}
