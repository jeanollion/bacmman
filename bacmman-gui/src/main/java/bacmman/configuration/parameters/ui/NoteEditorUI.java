package bacmman.configuration.parameters.ui;

import bacmman.configuration.parameters.NoteParameter;
import bacmman.configuration.parameters.TextParameter;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import java.awt.*;

public class NoteEditorUI implements ParameterUI { //modified from NameEditorUI

    TextParameter p;
    Object[] component;
    JTextArea text = new JTextArea();
    ConfigurationTreeModel model;

    public NoteEditorUI(NoteParameter p_, ConfigurationTreeModel model) {
        this.p = p_;
        this.model = model;
        text.setRows(35);
        text.setColumns(25);
        text.setWrapStyleWord(true);
        JScrollPane jsp = new JScrollPane(text);
        jsp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        JPanel panel = new JPanel();
        panel.add(jsp);
        this.component = new Component[]{panel};
        text.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                updateText();
            }

            public void removeUpdate(DocumentEvent e) {
                updateText();
            }

            public void changedUpdate(DocumentEvent e) {
                updateText();
            }

            private void updateText() {
                if (text.getText() == null) {
                    return;
                }
                p.setValue(text.getText());
                model.nodeChanged(p);
            }
        });
    }

    public Object[] getDisplayComponent() {
        text.setText(p.getValue());
        return component;
    }

}
