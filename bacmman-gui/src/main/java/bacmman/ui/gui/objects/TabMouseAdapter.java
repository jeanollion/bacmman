package bacmman.ui.gui.objects;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

public class TabMouseAdapter implements MouseListener {
    @Override
    public void mouseClicked(MouseEvent e) {
        redispatch(e);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        redispatch(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        redispatch(e);
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        redispatch(e);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        redispatch(e);
    }

    public void redispatch(MouseEvent e) {
        Component source = e.getComponent();
        Component target = source.getParent();
        while (true) {
            if (target == null) {
                break;
            }
            if (target instanceof JTabbedPane) {
                break;
            }
            target = target.getParent();
        }
        if (target != null) {
            MouseEvent targetEvent =  SwingUtilities.convertMouseEvent(source, e, target);
            target.dispatchEvent(targetEvent);
        }
    }
}
