package bacmman.ui.gui.configurationIO;

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
    public SaveGistForm() {
        // unable special chars
        KeyAdapter ke = new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if ( !SPECIAL_CHAR.matcher(String.valueOf(c)).find()) {
                    e.consume();  // ignore event
                }
            }
        };
        name.addKeyListener(ke);
        folder.addKeyListener(ke);

    }
    public void display(JFrame parent, String title) {
        JDialog dia = new Dial(parent, title);
        dia.setVisible(true);
        dia.dispose();
        /*JFrame frame = new JFrame("SaveGistForm");
        frame.setContentPane(new SaveGistForm().panelMain);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);*/
    }
    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent,title);
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
