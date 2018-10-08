package bacmman.configuration.parameters.ui;

import bacmman.configuration.parameters.TextParameter;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import java.awt.*;

public class TextEditorUI implements ParameterUI { //modified from NameEditorUI

    TextParameter p;
    Object[] component;
    JTextField text;
    ConfigurationTreeModel model;

    public TextEditorUI(TextParameter p_, ConfigurationTreeModel model) {
        this.p = p_;
        this.model = model;
        text = new JTextField();
        text.setPreferredSize(new Dimension(100, 28));
        if (!p.isAllowSpecialCharacters()) {
            ((AbstractDocument) text.getDocument()).setDocumentFilter(new DocumentFilterIllegalCharacters());
        }
        this.component = new Component[]{text};
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
        // resize text area..
        text.setPreferredSize(new Dimension(p.getName().length() * 8 + 100, 28));
        return component;
    }

}
