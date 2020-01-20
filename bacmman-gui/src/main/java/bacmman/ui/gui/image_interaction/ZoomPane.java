/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.ui.gui.image_interaction;

import bacmman.ui.ManualEdition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;

public class ZoomPane extends JPanel {
    public static final Logger logger = LoggerFactory.getLogger(ZoomPane.class);
    protected final int ZOOM_AREA;
    protected final float SCALE;
    Component parent;
    private final JWindow popup;

    private BufferedImage buffer;

    private final float zoomLevel;
    public ZoomPane() {
        this(3f, 25);
    }
    public ZoomPane(double zoomLevel, int zoomArea) {
        this(zoomLevel, zoomArea, Double.parseDouble(System.getProperty("sun.java2d.uiScale", "1")));
    }
    public ZoomPane(double zoomLevel, int zoomArea, double scale) {
        //scale = (int) scale; // TODO could work with double but it seams java only uses integral scale values....
        SCALE=(float)scale;
        logger.debug("Zoom pane Scale: {}", scale);
        ZOOM_AREA=zoomArea;
        this.zoomLevel = (float)zoomLevel;
        popup = new JWindow();
        popup.setLayout(new BorderLayout());
        popup.add(this);
        popup.setAlwaysOnTop(true);
        popup.pack();
    }
    
    public void setParent(Component comp) {
        if (parent!=null && parent.equals(comp)) return;
        detach();
        parent = comp;
    } 
    public void detach() {
        if (parent==null) return;
        popup.setVisible(false);
        parent.revalidate();
        parent.repaint();
    }
    
    public void updateLocation(MouseEvent e) {
        /*Point pos = e==null?MouseInfo.getPointerInfo().getLocation(): e.getLocationOnScreen();
        popup.setLocation(pos.x + 16, pos.y + 16);
        popup.setVisible(true);
        popup.repaint();*/
        
        Point p = e.getPoint();
        Point pos = e.getLocationOnScreen();
        updateBuffer(p);
        popup.setLocation(pos.x + 16, pos.y + 16);
        //repaint();
        if (!popup.isVisible()) popup.setVisible(true);

        repaint();
    }
    
    private static boolean ALLOW_OUTSIDE_AREA = true;
    protected void updateBuffer(Point p) {
        int size = Math.round(ZOOM_AREA * SCALE);
        buffer = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = buffer.createGraphics();
        AffineTransform at = new AffineTransform();
        int xPos = (ZOOM_AREA / 2) - p.x;
        int yPos = (ZOOM_AREA / 2) - p.y;
        if (!ALLOW_OUTSIDE_AREA) { // so that center of zoom window always corresponds to cursor location
            if (xPos > 0) xPos = 0;
            if (yPos > 0) yPos = 0;
            if ((xPos * -1) + ZOOM_AREA > parent.getWidth()) xPos = (parent.getWidth() - ZOOM_AREA) * -1;
            if ((yPos * -1) + ZOOM_AREA > parent.getHeight()) yPos = (parent.getHeight()- ZOOM_AREA) * -1;
        }
        at.translate(xPos, yPos);
        g2d.setTransform(at);
        parent.paint(g2d);
        g2d.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(Math.round(ZOOM_AREA * zoomLevel), Math.round(ZOOM_AREA * zoomLevel)); // scale is automatically applied
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        if (buffer != null) {
            AffineTransform at = g2d.getTransform();
            g2d.setTransform(AffineTransform.getScaleInstance(zoomLevel * SCALE, zoomLevel * SCALE));
            g2d.drawImage(buffer, 0, 0, this);
            g2d.setTransform(at);
        }
        g2d.setColor(Color.RED);
        g2d.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        int mid=getWidth()/2;
        int center = 5;
        g2d.drawLine(mid, 0, mid, mid-center);
        g2d.drawLine(mid, mid+center, mid, mid*2);
        g2d.drawLine(0, mid, mid-center, mid);
        g2d.drawLine(mid+center, mid, mid*2, mid);
        
        g2d.dispose();
    }

  }
