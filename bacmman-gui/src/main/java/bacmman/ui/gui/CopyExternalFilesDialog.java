package bacmman.ui.gui;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.DatasetFiles;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.ui.GUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Copies the files used by the configuration of a dataset that are located outside of the dataset folder (files of parameters, optionally input images) into the dataset folder, and modifies the configuration to use the copies: the dataset folder becomes autonomous
 */
public class CopyExternalFilesDialog {
    static final Logger logger = LoggerFactory.getLogger(CopyExternalFilesDialog.class);

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            ++u;
        }
        return String.format("%.1f %s", v, units[u]);
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void show(GUI gui, MasterDAO db) {
        if (db == null) return;
        if (db.isConfigurationReadOnly()) {
            gui.setMessage("The configuration is read-only: files cannot be copied");
            return;
        }
        Experiment xp = db.getExperiment();
        List<DatasetFiles.ExternalFile> files = DatasetFiles.findExternalFiles(xp);
        List<DatasetFiles.ImageFolder> folders = DatasetFiles.findExternalImageFolders(xp);
        List<DatasetFiles.ModelToDownload> models = DatasetFiles.findModelsToDownload(xp);
        if (files.isEmpty() && folders.isEmpty() && models.isEmpty()) {
            JOptionPane.showMessageDialog(gui, "All files used by the configuration, and the input images, are in the dataset folder.", "Copy external files", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<JCheckBox> fileBoxes = new ArrayList<>();
        JCheckBox[] imagesBox = new JCheckBox[1];
        List<JCheckBox> modelBoxes = new ArrayList<>();
        JPanel panel = buildPanel(xp, files, folders, fileBoxes, imagesBox, models, modelBoxes);
        JCheckBox images = imagesBox[0];
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        Dimension pref = panel.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.min(pref.width + 30, 1100), Math.min(pref.height + 10, 600)));
        int choice = JOptionPane.showConfirmDialog(gui, scroll, "Copy external files into the dataset folder", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        List<DatasetFiles.ExternalFile> selectedFiles = new ArrayList<>();
        for (int i = 0; i < files.size(); ++i) if (fileBoxes.get(i).isSelected()) selectedFiles.add(files.get(i));
        List<DatasetFiles.ImageFolder> selectedFolders = images != null && images.isSelected() ? folders : new ArrayList<>();
        List<DatasetFiles.ModelToDownload> selectedModels = new ArrayList<>();
        for (int i = 0; i < models.size(); ++i) if (modelBoxes.get(i).isSelected()) selectedModels.add(models.get(i));
        if (selectedFiles.isEmpty() && selectedFolders.isEmpty() && selectedModels.isEmpty()) return;
        gui.setRunning(true);
        new Thread(() -> {
            String message;
            try {
                int n = DatasetFiles.copyIntoDataset(xp, selectedFiles, selectedFolders, selectedModels, gui);
                db.storeExperiment();
                message = n + " file(s) or folder(s) copied into the dataset folder. The configuration was saved.";
            } catch (Exception e) {
                logger.error("Error copying external files", e);
                message = "Error copying files into the dataset folder: " + e.getMessage() + ". The configuration was not saved: check the files and the configuration.";
            }
            String m = message;
            SwingUtilities.invokeLater(() -> {
                gui.setRunning(false);
                gui.setProgress(0);
                gui.setMessage(m);
                gui.updateConfigurationTree();
            });
        }, "copy external files").start();
    }

    /**
     * @param fileBoxes receives the check boxes of the files (same order as {@param files})
     * @param imagesBox receives the check box of the input images (null if there is no external image folder)
     */
    static JPanel buildPanel(Experiment xp, List<DatasetFiles.ExternalFile> files, List<DatasetFiles.ImageFolder> folders, List<JCheckBox> fileBoxes, JCheckBox[] imagesBox) {
        return buildPanel(xp, files, folders, fileBoxes, imagesBox, new ArrayList<>(), new ArrayList<>());
    }

    /**
     * @param modelBoxes receives the check boxes of the models to download (same order as {@param models})
     */
    static JPanel buildPanel(Experiment xp, List<DatasetFiles.ExternalFile> files, List<DatasetFiles.ImageFolder> folders, List<JCheckBox> fileBoxes, JCheckBox[] imagesBox, List<DatasetFiles.ModelToDownload> models, List<JCheckBox> modelBoxes) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3;
        panel.add(new JLabel("<html>These files are used by the configuration but are located outside of the dataset folder (or not downloaded):<br/>" + escape(xp.getPath().toString())
                + "<br/>They are copied into the dataset folder (originals are kept), and the configuration is modified to use the copies,<br/>so that the dataset folder can be moved or copied to another computer.</html>"), c);
        c.gridwidth = 1;
        if (!files.isEmpty()) {
            c.gridy++;
            panel.add(new JLabel("<html><b>Files of parameters</b> (copied into the folder " + DatasetFiles.RESOURCE_FOLDER + ", model files into " + DatasetFiles.MODEL_FOLDER + ")</html>"), c);
            for (DatasetFiles.ExternalFile f : files) {
                c.gridy++;
                c.gridx = 0;
                JCheckBox box = new JCheckBox(f.location, true);
                box.setToolTipText("Uncheck to keep this parameter unchanged (e.g. a folder where results are written)");
                fileBoxes.add(box);
                panel.add(box, c);
                c.gridx = 1;
                panel.add(new JLabel(escape(String.join(", ", f.paths))), c);
                c.gridx = 2;
                panel.add(new JLabel(formatSize(f.size)), c);
            }
        }
        if (!models.isEmpty()) {
            c.gridy++;
            c.gridx = 0;
            c.gridwidth = 3;
            panel.add(new JLabel("<html><b>DL models to download</b> (linked but not on this computer: downloaded into the folder " + DatasetFiles.MODEL_FOLDER + ")</html>"), c);
            c.gridwidth = 1;
            for (DatasetFiles.ModelToDownload m : models) {
                c.gridy++;
                c.gridx = 0;
                JCheckBox box = new JCheckBox(m.location, true);
                modelBoxes.add(box);
                panel.add(box, c);
                c.gridx = 1;
                c.gridwidth = 2;
                panel.add(new JLabel(escape(m.parameter.getID())), c);
                c.gridwidth = 1;
            }
        }
        JCheckBox images = null;
        if (!folders.isEmpty()) {
            c.gridy++;
            c.gridx = 0;
            c.gridwidth = 3;
            long size = folders.stream().mapToLong(f -> f.size).sum();
            int nPos = folders.stream().mapToInt(f -> f.positions.size()).sum();
            images = new JCheckBox("<html><b>Also copy the input images</b> (" + folders.size() + " folder" + (folders.size() > 1 ? "s" : "") + ", " + nPos + " position" + (nPos > 1 ? "s" : "") + ", " + formatSize(size) + ", copied into the folder " + DatasetFiles.IMAGE_FOLDER + ")</html>", false);
            images.setToolTipText("<html>The files of the folders that contain the input images are copied (not their sub-folders):<br/>" + folders.stream().map(f -> escape(f.folder) + " (" + formatSize(f.size) + ")").collect(Collectors.joining("<br/>")) + "</html>");
            panel.add(images, c);
            for (DatasetFiles.ImageFolder f : folders) {
                c.gridy++;
                JLabel l = new JLabel("      " + f.folder + " (" + formatSize(f.size) + ")");
                panel.add(l, c);
            }
            c.gridwidth = 1;
        }
        imagesBox[0] = images;
        return panel;
    }
}
