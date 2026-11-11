package bacmman.ui.gui.configurationIO;

import bacmman.core.RepositoryGateway;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.ui.gui.objects.CollapsiblePanel;
import bacmman.ui.gui.objects.DLModelGistTreeGenerator;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.IconUtils;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private JButton storeTrainedModelButton;
    JFrame displayingFrame;
    RepositoryGateway gateway;
    List<GistDLModel> gists;
    boolean loggedIn = false;
    final ModelFileAccountPanel modelFileAccount = new ModelFileAccountPanel("DL_LIBRARY_MODEL_FILE_USERNAME");
    DLModelMetadata pendingMetadata; // model to be stored, requested from the training tab
    File pendingModel;
    String connectedRepositoryKey; // key of the repository the library is connected to
    DLModelGistTreeGenerator tree;
    private static final Logger logger = LoggerFactory.getLogger(DLModelsLibrary.class);
    String workingDirectory;
    ProgressDisplay pcb;
    BiConsumer<String, DLModelMetadata> configureParameterCallback;
    JDialog dia;
    Runnable onClose;
    RepositoryCredentialPanel repositoryCredentialPanel;

    public DLModelsLibrary(RepositoryGateway gateway, String workingDirectory, Runnable onClose, ProgressDisplay pcb) {
        this.gateway = gateway;
        this.workingDirectory = workingDirectory;
        this.pcb = pcb;
        this.onClose = onClose;
        if (pcb instanceof JFrame) displayingFrame = (JFrame) pcb;
        this.repositoryCredentialPanel = new RepositoryCredentialPanel(gateway, () -> {
            modelFileAccount.updateVisibility(repositoryCredentialPanel.getRepositoryType());
            fetchGists();
            updateGistDisplay();
            updateEnableButtons();
        }, "Models", pcb);
        modelFileAccount.updateVisibility(repositoryCredentialPanel.getRepositoryType());
        $$$setupUI$$$();
        updateEnableButtons();
        storeTrainedModelButton.addActionListener(e -> storePendingModel());

        newButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            // check if a folder is selected
            String currentFolder = tree.getSelectedFolder();
            SaveDLModelGist form = new SaveDLModelGist(gateway);
            if (currentFolder != null) form.setFolder(currentFolder);
            GistRepository modelFileRepository = getModelFileRepository();
            if (modelFileRepository == null) return;
            form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb).setRepository(modelFileRepository);

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
                toSave.createNewGist(getRepository());
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
            GistRepository modelFileRepository = getModelFileRepository();
            if (modelFileRepository == null) return;
            form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb).setRepository(modelFileRepository);
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
            GistRepository modelFileRepository = getModelFileRepository();
            if (modelFileRepository == null) return;
            form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb).setRepository(modelFileRepository);
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
                toSave.createNewGist(getRepository());
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
                            GistRepository other = OtherRepositoryPrompt.prompt(getRepository(), gateway, "Account to duplicate to", displayingFrame, pcb);
                            if (other != null) {
                                boolean sameAccount = OtherRepositoryPrompt.same(other, getRepository());
                                try {
                                    GistDLModel gist = tree.getSelectedGist();
                                    if (gist == null) return;
                                    SaveDLModelGist form = new SaveDLModelGist(gateway);
                                    form.setFolder(gist.folder)
                                            .setName(gist.name)
                                            .setDescription(gist.getDescription())
                                            .setURL(gist.getModelURL())
                                            .setMetadata(gist.getMetadata())
                                            .setVisible(gist.isVisible());
                                    form.setAuthAndDefaultDirectory(other.getAuth(), workingDirectory, pcb).setRepository(other);
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
                                    if (sameAccount) {
                                        boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
                                        if (exists) {
                                            pcb.setMessage("Model already exists.");
                                            return;
                                        }
                                    } else { // check on other repository
                                        List<GistDLModel> otherModels = GistDLModel.get(other, pcb);
                                        boolean exists = otherModels.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
                                        if (exists) {
                                            if (pcb != null)
                                                pcb.setMessage("Model already exists on other account.");
                                            return;
                                        }
                                    }
                                    GistDLModel toSave = new GistDLModel(form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
                                    List<BufferedImage> otherThumb = gist.getThumbnail();
                                    if (otherThumb != null)
                                        for (BufferedImage b : otherThumb) toSave.appendThumbnail(b);
                                    toSave.createNewGist(other);
                                    if (sameAccount) {
                                        gists.add(toSave);
                                        updateGistDisplay();
                                        tree.setSelectedGist(toSave);
                                    }
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
        if (repositoryCredentialPanel.loadAllowed(getAuth())) { // local repository or credentials of this session. public models of remote repositories are loaded only on user request
            fetchGists();
            updateGistDisplay();
            updateEnableButtons();
        }
    }

    public boolean setSelectedGist(String id, boolean modelID) {
        if (tree==null) return false;
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

    public DLModelsLibrary setProgressLogger(ProgressDisplay progressDisplay) {
        this.pcb = progressDisplay;
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
            boolean allDeleted = true;
            for (GistDLModel g : gists.stream().filter(g -> folder.equals(g.folder)).collect(Collectors.toList())) {
                if (deleteModel(g, deleteFile)) gists.remove(g);
                else allDeleted = false;
            }
            if (allDeleted) tree.removeFolder(folder);
            else updateGistDisplay();
        } else if (deleteModel(gist, deleteFile)) {
            gists.remove(gist);
            tree.removeGist(gist);
        }
    }

    /**
     * Requests the upload of a model (e.g. from the training tab): enables the button that stores the model, so that the repository of the library and the (optional) model file account can be set before.
     * The model file is stored in the model file account if set, in the repository of the library otherwise.
     */
    public void requestModelUpload(DLModelMetadata metadata, File model) {
        pendingMetadata = metadata;
        pendingModel = model;
        updateEnableButtons();
        String msg = "To save the model: set the repository of the library (and optionally the model file account), then click \"" + storeTrainedModelButton.getText() + "\"";
        if (pcb != null) pcb.setMessage(msg);
        Utils.displayTemporaryMessage(msg, 8000);
    }

    /**
     * Connects to the repository of the library if not connected or connected to another account, and to the model file account, then stores the model requested from the training tab.
     * If a connection fails, the request is kept so that it can be retried. It is cleared once the form of the new model is displayed.
     */
    protected void storePendingModel() {
        if (pendingModel == null) return;
        GistRepository repo = getRepository();
        String key = repo == null ? null : repo.getKey();
        if (!loggedIn || key == null || !key.equals(connectedRepositoryKey)) connect();
        if (!loggedIn || tree == null) {
            error("Could not connect to the repository of the library: check username / password (or the folder of the local repository), then click again on \"" + storeTrainedModelButton.getText() + "\"");
            return;
        }
        GistRepository modelRepository = getModelFileRepository();
        if (modelRepository == null) {
            error("Model was not saved: check the model file account (or clear its username to store the model file in the repository of the library), then click again on \"" + storeTrainedModelButton.getText() + "\"");
            return;
        }
        DLModelMetadata metadata = pendingMetadata;
        File model = pendingModel;
        pendingMetadata = null;
        pendingModel = null;
        updateEnableButtons();
        if (pcb != null) pcb.setMessage("Model file will be stored in: " + modelRepository.getKey());
        uploadModel(modelRepository, metadata, model);
    }

    protected void error(String message) {
        logger.error(message);
        if (pcb != null) pcb.setMessage(message);
        Utils.displayTemporaryMessage(message, 8000);
    }

    /**
     * @return repository where model files are stored: the model file account if set, the repository of the library otherwise. null if the model file account is set but cannot be used: token cannot be loaded or connection fails (an error message is displayed)
     */
    protected GistRepository getModelFileRepository() {
        GistRepository repo;
        try {
            repo = modelFileAccount.getRepository(getRepository());
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return null;
        }
        if (repo == null) return getRepository();
        if (!repo.isWritable()) {
            error("Model file account " + repo.getKey() + " is not writable: set its password");
            return null;
        }
        try {
            repo.checkConnection();
        } catch (IOException e) {
            error("Could not connect to the model file account " + repo.getKey() + ": " + e.getMessage());
            return null;
        }
        return repo;
    }

    /**
     * @param modelRepository repository where the model file is stored (any type: GitHub, GitLab, local). null: repository of the library
     */
    public void uploadModel(GistRepository modelRepository, DLModelMetadata metadata, File model) {
        if (tree == null || !loggedIn) return;
        final GistRepository modelRepo = modelRepository == null ? getRepository() : modelRepository;
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
        form.setAuthAndDefaultDirectory(getAuth(), workingDirectory, pcb).setRepository(modelRepo);

        logger.debug("uploading new gist...");
        form.uploadFile(model, modelRepo, true);
        form.display(displayingFrame, update ? "Update model..." : "Upload model...");
        if (form.canceled) {
            deleteModelFile(form.id(), modelRepo);
            return;
        } else if (update && gist.getModelID() != null && !gist.getModelID().isEmpty()) { // delete previous model file
            if (!deleteModelFile(gist.getModelURL(), modelRepo)) error("Previous model file " + gist.getModelURL() + " could not be deleted");
        }
        if (!update) {
            boolean validName = false;
            while(!validName) {
                if (!Utils.isValid(form.name(), false)) {
                    if (pcb != null) pcb.setMessage("Invalid name (no special chars allowed except underscores)");
                    Utils.displayTemporaryMessage("Invalid name (no special chars allowed except underscores)", 6000);
                }
                if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                    if (pcb != null) pcb.setMessage("Invalid folder name (no special chars allowed)");
                    Utils.displayTemporaryMessage("Invalid folder name (no special chars allowed)", 6000);
                }
                boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
                if (exists) {
                    if (pcb != null) pcb.setMessage("Model already exists.");
                    Utils.displayTemporaryMessage("Model already exists.", 6000);
                } else validName = true;
                if (!validName) { // re-ask name
                    form.display(displayingFrame, "Upload model...");
                    if (form.canceled) {
                        deleteModelFile(form.id(), modelRepo);
                        return;
                    }
                }
            }
            gist = new GistDLModel(form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
            try {
                gist.createNewGist(getRepository());
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
        storeTrainedModelButton.setEnabled(pendingModel != null);
    }

    public void connect() {
        repositoryCredentialPanel.connect();
    }

    private void fetchGists() {
        String account = repositoryCredentialPanel.getUsername();
        connectedRepositoryKey = null;
        if (account.length() == 0 || !repositoryCredentialPanel.loadAllowed(getAuth())) { // public items of remote repositories are loaded only on user request
            gists = new ArrayList<>();
            loggedIn = false;
        } else if (!GistRepository.TYPE.GITHUB.equals(repositoryCredentialPanel.getRepositoryType())) { // GitLab or local
            GistRepository repo = getRepository();
            if (repo == null) {
                gists = new ArrayList<>();
                loggedIn = false;
            } else {
                try {
                    gists = GistDLModel.list(repo);
                    loggedIn = repo.isWritable();
                    if (loggedIn) connectedRepositoryKey = repo.getKey();
                } catch (IOException e) { // connection or authentication error
                    logger.error("Error getting DL models", e);
                    if (pcb != null) pcb.setMessage("Could not get DL models: " + e.getMessage());
                    gists = new ArrayList<>();
                    loggedIn = false;
                }
            }
            repositoryCredentialPanel.persistUsername();
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
                } else {
                    loggedIn = true;
                    connectedRepositoryKey = getRepository().getKey();
                }
            }
            repositoryCredentialPanel.persistUsername();
        }
        logger.debug("fetched gists: {}", gists.size());
    }

    private UserAuth getAuth() {
        return repositoryCredentialPanel.getAuth();
    }

    private GistRepository getRepository() {
        return repositoryCredentialPanel.getRepository();
    }

    /**
     * @return type of the repository selected in the library
     */
    public GistRepository.TYPE getRepositoryType() {
        return repositoryCredentialPanel.getRepositoryType();
    }

    /**
     * @return whether the library is connected to a repository where models can be stored
     */
    public boolean isWritable() {
        return loggedIn;
    }

    /**
     * Deletes a model from the library, and its model file if {@param deleteFile} is true. If the model file cannot be deleted, the model is kept (so that the file is not left without reference)
     * @return true if the model was deleted
     */
    protected boolean deleteModel(GistDLModel model, boolean deleteFile) {
        if (deleteFile && !deleteModelFile(model.getModelURL(), null)) {
            error("Model " + model.folder + "/" + model.name + " was not deleted: its model file " + model.getModelURL() + " could not be deleted. Set the model file account where it is stored (and its password), or delete the model and keep its file (right click on Delete)");
            return false;
        }
        if (!model.delete(getAuth(), false)) {
            error("Model " + model.folder + "/" + model.name + " could not be deleted");
            return false;
        }
        return true;
    }

    /**
     * Deletes a model file and its sub-files. The file may be stored in another account than the account of the library (model file account), and its url does not always identify the account (GitHub, GitLab):
     * deletion is attempted with each repository that may contain it: {@param repo}, model file account, repository of the library, repository inferred from the url
     * @param id id or url of the model file
     * @param repo preferred repository (may be null)
     * @return true if the file was deleted or if there is no file
     */
    private boolean deleteModelFile(String id, GistRepository repo) {
        if (id == null || id.isEmpty()) return true;
        List<GistRepository> candidates = new ArrayList<>();
        candidates.add(repo);
        try {
            candidates.add(modelFileAccount.getRepository(getRepository()));
        } catch (IllegalArgumentException e) {
            logger.debug("model file account cannot be used: {}", e.getMessage());
        }
        candidates.add(getRepository());
        candidates.add(gateway.getRepository(id));
        Set<String> tried = new HashSet<>();
        for (GistRepository r : candidates) {
            if (r == null || !r.isWritable() || !tried.add(r.getKey())) continue;
            if (id.contains("/") && r.idFromURL(id) == null) continue; // url of another server / folder
            String itemId = r.parseId(id);
            try {
                if (new LargeFileGist(r, itemId).delete(null)) { // also deletes sub-files
                    logger.debug("model file {} deleted from {}", id, r.getKey());
                    return true;
                }
            } catch (IOException e) { // not readable from this repository, or not a large file
                logger.debug("could not read model file {} from {}", id, r.getKey(), e);
            }
            try {
                r.delete(itemId);
                logger.debug("model file {} deleted from {}", id, r.getKey());
                return true;
            } catch (IOException e) {
                logger.debug("could not delete model file {} from {}", id, r.getKey(), e);
            }
        }
        return false;
    }

    public void display(JFrame parent) {
        dia = new Dial(parent, "Online DL Model Library");
        dia.setVisible(true);
    }

    public boolean close() {
        pendingMetadata = null;
        pendingModel = null;
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
        actionPanel.setLayout(new GridLayoutManager(3, 3, new Insets(0, 0, 0, 0), -1, -1));
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
        storeTrainedModelButton = new JButton();
        storeTrainedModelButton.setEnabled(false);
        storeTrainedModelButton.setText("Store Model From Training");
        storeTrainedModelButton.setToolTipText("Stores the model exported from the training tab (enabled after clicking \"Export To Library\" in the training tab). Connects to the repository of the library (if not already connected to this account) and to the model file account (if set). The model file is stored in the model file account if set, in the repository of the library otherwise");
        actionPanel.add(storeTrainedModelButton, new GridConstraints(2, 0, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

    private void createUIComponents() {
        JPanel repoPanel = new JPanel(new BorderLayout());
        repoPanel.add(repositoryCredentialPanel.getPanel(), BorderLayout.CENTER);
        repoPanel.add(modelFileAccount.getPanel(), BorderLayout.SOUTH);
        credentialPanel = new CollapsiblePanel("Repository", repoPanel);
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
