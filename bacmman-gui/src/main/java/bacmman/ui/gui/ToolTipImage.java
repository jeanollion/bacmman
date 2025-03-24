package bacmman.ui.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import java.awt.*;

public class ToolTipImage extends JToolTip {
    private static final Logger logger = LoggerFactory.getLogger(ToolTipImage.class);
    private Icon image;
    JLabel text;
    JPanel ttPanel;
    public ToolTipImage(Image image) {
        this(image==null ? null : new ImageIcon(image));
    }
    public ToolTipImage(Icon image) {
        super();
        this.image = image;
        setLayout(new BorderLayout());
        setBorder(new BevelBorder(0));
        text = new JLabel("");
        text.setBackground(null);
        if (image!=null) {
            ttPanel = new JPanel(new FlowLayout(1, 0, 5));
            ttPanel.add(text);
            ttPanel.add(new JLabel(image));
            add(ttPanel);
        } else {
            ttPanel = new JPanel();
            ttPanel.add(text);
            add(ttPanel);
        }
    }

    public void stopAnimation() {
        if (image instanceof AnimatedIcon) {
            ((AnimatedIcon)image).stop();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        if (image==null) {
            Dimension dim = text.getPreferredSize();
            return new Dimension(dim.width+10, dim.height+15);
        } else return new Dimension(image.getIconWidth(), text.getPreferredSize().height+5+image.getIconHeight());
    }

    @Override
    public void setTipText(String tipText) {
        if (text!=null) text.setText(tipText);
        super.setTipText(tipText);
    }


}
