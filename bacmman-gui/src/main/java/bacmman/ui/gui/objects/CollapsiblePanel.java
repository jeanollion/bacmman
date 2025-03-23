package bacmman.ui.gui.objects;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class CollapsiblePanel extends JPanel {
    private JPanel contentPanel;
    private JLabel toggleLabel, titleLabel;
    private boolean isCollapsed;

    public CollapsiblePanel(String title, JPanel content) {
        setLayout(new BorderLayout());
        // Toggle label with arrow icon
        toggleLabel = new JLabel(createIcon(" ▼"));
        toggleLabel.setPreferredSize(new Dimension(20, 20));
        add(toggleLabel, BorderLayout.WEST);
        // Title label
        titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        add(titleLabel, BorderLayout.CENTER);

        // Content panel
        contentPanel = content;
        contentPanel.setBorder(BorderFactory.createTitledBorder(null, "", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        contentPanel.setVisible(true); // Initially shown
        isCollapsed = false;
        add(contentPanel, BorderLayout.SOUTH);

        // Mouse listener to toggle the panel
        toggleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle();
            }
        });
        titleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle();
            }
        });
    }

    public void toggle() {
        isCollapsed = !isCollapsed;
        contentPanel.setVisible(!isCollapsed);
        toggleLabel.setIcon(createIcon(isCollapsed ? " ▶" : " ▼"));
        revalidate();
        repaint();
    }

    // Helper method to create an icon from a string
    private Icon createIcon(String text) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(Color.BLACK);
                g.drawString(text, x, y + g.getFontMetrics().getAscent());
            }

            @Override
            public int getIconWidth() {
                return 20;
            }

            @Override
            public int getIconHeight() {
                return 20;
            }
        };
    }
}
