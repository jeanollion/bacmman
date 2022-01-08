package bacmman.ui.gui;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import java.awt.*;

public class ToolTipImage extends JToolTip {
    private Image image;
    JLabel text;
    JPanel ttPanel;
    public ToolTipImage(Image image) {
        super();
        this.image = image;
        setLayout(new BorderLayout());
        setBorder(new BevelBorder(0));
        text = new JLabel("");
        text.setBackground(null);
        if (image!=null) {
            ttPanel = new JPanel(new FlowLayout(1, 0, 5));
            ttPanel.add(text);
            ttPanel.add(new JLabel(new ImageIcon(image)));
            add(ttPanel);
        } else {
            ttPanel = new JPanel();
            ttPanel.add(text);
            add(ttPanel);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        if (image==null) return new Dimension(text.getWidth()+10, text.getHeight()+15);
        return new Dimension(image.getWidth(this), text.getHeight()+5+image.getHeight(this));
    }
    @Override
    public void setTipText(String tipText) {
        if (text!=null) text.setText(tipText);
        super.setTipText(tipText);
    }


}
