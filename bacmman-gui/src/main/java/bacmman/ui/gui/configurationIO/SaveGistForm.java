package bacmman.ui.gui.configurationIO;

import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import static bacmman.utils.Utils.SPECIAL_CHAR;

public class SaveGistForm {
    private JPanel namePanel;
    private JTextField name;
    private JTextField folder;
    private JPanel descriptionPanel;
    private JTextArea description;
    private JCheckBox publicJCB;
    private JButton cancel;
    private JButton OK;
    private JPanel panelMain;
    boolean canceled = false;
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(SaveGistForm.class);
    public SaveGistForm() {
        // unable special chars
        KeyAdapter ke = new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (SPECIAL_CHAR.matcher(String.valueOf(c)).find()) {
                    e.consume();  // ignore event
                }
            }
        };
        logger.debug("name field null: {}", name==null);
        name.addKeyListener(ke);
        folder.addKeyListener(ke);
    }
    public void display(JFrame parent, String title) {
        JDialog dia = new Dial(parent, title);
        dia.setVisible(true);
    }
    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, true);
            getContentPane().add(panelMain);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
            cancel.addActionListener(e->{
                canceled=true;
                setVisible(false);
                dispose();
            });
            OK.addActionListener(e->{
                canceled=false;
                setVisible(false);
                dispose();
            });
        }

    }


    public SaveGistForm setFolder(String folderName) {
        folder.setText(folderName);
        return this;
    }
    public SaveGistForm setName(String name) {
        this.name.setText(name);
        return this;
    }
    public SaveGistForm disableNameField() {
        this.name.setEnabled(false);
        return this;
    }
    public SaveGistForm disableFolderField() {
        this.folder.setEnabled(false);
        return this;
    }
    public SaveGistForm disableVisibleField() {
        this.publicJCB.setEnabled(false);
        return this;
    }
    public SaveGistForm setDescription(String description) {
        this.description.setText(description);
        return this;
    }
    public SaveGistForm setVisible(boolean visible) {
        this.publicJCB.setSelected(visible);
        return this;
    }
    public boolean visible() {
        return publicJCB.isSelected();
    }
    public String name() {
        return name.getText();
    }
    public String folder() {
        return folder.getText();
    }
    public String description() {
        return description.getText();
    }
}
